package com.lorenzo.mangadownloader

import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.app.withoutRegisteredAdult
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.canonicalKey
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.store.FavoriteDescriptionsStore
import com.lorenzo.mangadownloader.data.store.FavoriteSourceHealthStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesStore
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import com.lorenzo.mangadownloader.data.store.SeriesSourceBinding
import com.lorenzo.mangadownloader.data.store.appendUpdateEvent
import com.lorenzo.mangadownloader.data.store.computeFavoriteUpdate
import com.lorenzo.mangadownloader.data.store.recordSourceFailure
import com.lorenzo.mangadownloader.data.store.recordSourceSuccess
import com.lorenzo.mangadownloader.data.store.shouldPollFavorite
import com.lorenzo.mangadownloader.domain.isRegisteredAdult
import com.lorenzo.mangadownloader.domain.series.favoriteSourceCandidates
import com.lorenzo.mangadownloader.domain.series.fetchFromFirstAvailable
import kotlinx.coroutines.CancellationException

/**
 * Il giro capitoli del [FavoriteUpdatesWorker]: per ogni preferito ancora "in corso" scarica i
 * dettagli dal primo mirror che risponde, aggiorna la baseline, scrive l'evento nel feed e
 * notifica. Best-effort: un errore su un preferito non blocca gli altri.
 *
 * Non conosce `Context`, rete né notifiche di sistema: [fetchDetails], [notifyNewChapter] e
 * [notifySummary] arrivano da fuori, così i test coprono il giro per intero (ripiego sui
 * mirror, interruzione a metà, serie per adulti) con le sole preferenze.
 *
 * @param disabledSourceIds fonti spente in impostazioni: non si interrogano.
 * @param adultKeys registro adulti quando il filtro è attivo, vuoto altrimenti: quelle serie
 *   si controllano lo stesso (spegnendo il filtro lo stato è in pari) ma non si notificano,
 *   finiscono solo nel feed, che le nasconde finché il filtro resta acceso.
 */
class FavoriteUpdatesRun(
    private val favoritesStore: FavoritesStore,
    private val updatesStore: FavoriteUpdatesStore,
    private val descriptionsStore: FavoriteDescriptionsStore,
    private val feedStore: FavoriteUpdatesFeedStore,
    private val seriesLinksStore: SeriesLinksStore,
    private val healthStore: FavoriteSourceHealthStore,
    private val disabledSourceIds: Set<String>,
    private val adultKeys: Set<String>,
    private val fetchDetails: suspend (SeriesSourceBinding) -> MangaDetails,
    private val notifyNewChapter: (favorite: FavoriteManga, seriesKey: String, chapterLabel: String) -> Unit,
    private val notifySummary: (unseenEvents: List<FavoriteUpdateEvent>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(favorites: List<FavoriteManga>) {
        val seenMap = updatesStore.read().toMutableMap()
        val descriptions = descriptionsStore.read().toMutableMap()
        val healthMap = healthStore.read().toMutableMap()
        var notifiedCount = 0
        // Cambi di mirror di questo giro, per chiave di serie: a fine giro si applicano SOLO
        // questi ai preferiti di quel momento, non la copia presa minuti fa (vedi [FavoritesStore.update]).
        val sourceSwitches = mutableMapOf<String, FavoriteManga>()

        try {
            for (favorite in favorites) {
                val key = favorite.canonicalKey()
                val seen = seenMap[key]
                if (!shouldPollFavorite(seen)) {
                    continue
                }
                try {
                    val candidates = favoriteSourceCandidates(
                        favorite = favorite,
                        link = seriesLinksStore.linkFor(key),
                        disabledSourceIds = disabledSourceIds,
                    )
                    val fetched = fetchFromFirstAvailable(candidates, fetch = fetchDetails)
                    if (fetched == null) {
                        // Nessun mirror raggiungibile: NON si cambia la fonte preferita (di
                        // solito è la rete del telefono, non il sito). Si conta e basta.
                        healthMap[key] = recordSourceFailure(healthMap[key])
                        continue
                    }
                    val details = fetched.details
                    val usedBinding = fetched.binding
                    healthMap[key] = recordSourceSuccess(
                        current = healthMap[key],
                        previousSourceId = favorite.sourceId,
                        usedSourceId = usedBinding.sourceId,
                        nowMillis = now(),
                    )
                    var current = favorite
                    if (usedBinding.sourceId != favorite.sourceId) {
                        // Ha risposto un mirror diverso: diventa quello da cui leggere la serie.
                        seriesLinksStore.setPreferredSource(key, usedBinding.sourceId)
                        current = favorite.copy(
                            sourceId = usedBinding.sourceId,
                            mangaUrl = usedBinding.mangaUrl,
                        )
                        sourceSwitches[key] = current
                    }
                    // La trama dei preferiti viene scaricata comunque qui: salvala così il
                    // pulsante info è pronto anche dopo il riavvio dell'app (è solo testo).
                    details.description?.trim()?.takeIf(String::isNotBlank)?.let { descriptions[key] = it }
                    val latest = details.chapters.maxByOrNull { it.numberValue } ?: continue
                    val result = computeFavoriteUpdate(
                        seen = seen,
                        latestNumber = latest.numberValue,
                        latestLabel = latest.displayLabel(),
                        status = details.status,
                    )
                    seenMap[key] = result.newState
                    result.newChapterLabel?.let { label ->
                        // Persisti baseline e feed PRIMA di notificare: se il processo muore
                        // subito dopo la notifica, al giro dopo non si rinotifica lo stesso
                        // capitolo e l'evento è comunque nel feed in-app. L'append è un
                        // read-merge-write atomico per evento: non si tiene in mano il feed
                        // durante il giro di rete, così un "segna come visto" fatto
                        // dall'utente nel frattempo non viene sovrascritto.
                        updatesStore.write(seenMap)
                        feedStore.update { events ->
                            appendUpdateEvent(
                                events,
                                FavoriteUpdateEvent(
                                    identityKey = MangaSourceCatalog.identityKey(
                                        usedBinding.sourceId,
                                        usedBinding.mangaUrl,
                                    ),
                                    title = favorite.title,
                                    // Fonte e URL dell'evento sono quelli del mirror che ha
                                    // risposto: è lì che il tap deve portare.
                                    sourceId = usedBinding.sourceId,
                                    mangaUrl = usedBinding.mangaUrl,
                                    chapterLabel = label,
                                    chapterNumber = latest.numberValue.stripTrailingZeros().toPlainString(),
                                    coverUrl = favorite.coverUrl,
                                    timestampMillis = now(),
                                    seen = false,
                                    seriesKey = key,
                                ),
                            )
                        }
                        if (!isRegisteredAdult(adultKeys, current.title, current.sourceId, current.mangaUrl, key)) {
                            notifyNewChapter(current, key, label)
                            notifiedCount++
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Best-effort: rete/parsing fallito su un preferito → si riprova al giro dopo.
                }
            }
            // Riepilogo del gruppo: con 2+ capitoli non visti il pannello resta compatto
            // (una voce sola che espande le singole) invece di N notifiche sparse.
            if (notifiedCount > 0) {
                notifySummary(feedStore.read().filter { !it.seen }.withoutRegisteredAdult(adultKeys))
            }
        } finally {
            // Persisti SEMPRE i progressi parziali: se WorkManager interrompe il worker a metà
            // (es. rete caduta), così non si rinotificano i capitoli già avvisati al giro dopo.
            // Il feed NON si riscrive qui: ogni evento è già stato persistito al momento
            // della notifica (vedi sopra).
            updatesStore.write(seenMap)
            descriptionsStore.write(descriptions)
            healthStore.write(healthMap)
            if (sourceSwitches.isNotEmpty()) {
                favoritesStore.update { stored ->
                    stored.map { favorite ->
                        sourceSwitches[favorite.canonicalKey()]?.let { switched ->
                            favorite.copy(sourceId = switched.sourceId, mangaUrl = switched.mangaUrl)
                        } ?: favorite
                    }
                }
            }
        }
    }
}
