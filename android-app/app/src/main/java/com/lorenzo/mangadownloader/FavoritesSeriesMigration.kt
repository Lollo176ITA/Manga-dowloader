package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Passaggio dei preferiti dalla chiave **per-fonte** (`identityKey` = fonte + URL) a quella
 * **per-serie** ([SeriesIdentity]).
 *
 * Serve perché baseline delle notifiche, trame e feed erano indicizzati sulla fonte con cui
 * il preferito era stato aggiunto: appena il fallback cambia mirror, quelle voci non si
 * ritroverebbero più e l'app ri-notificherebbe capitoli già visti. La migrazione è **one-shot**
 * (flag in [SharedPreferences]) e **idempotente**, così ViewModel e worker possono invocarla
 * entrambi senza coordinarsi.
 */
class FavoritesSeriesMigration(
    private val prefs: SharedPreferences,
    private val favoritesStore: FavoritesStore,
    private val favoriteUpdatesStore: FavoriteUpdatesStore,
    private val favoriteDescriptionsStore: FavoriteDescriptionsStore,
    private val favoriteUpdatesFeedStore: FavoriteUpdatesFeedStore,
    private val seriesLinksStore: SeriesLinksStore,
) {

    /**
     * Esegue la migrazione se non è già stata fatta e restituisce i preferiti aggiornati
     * (già letti da disco in ogni caso, così il chiamante non rilegge).
     */
    fun migrateIfNeeded(nowMillis: Long = System.currentTimeMillis()): List<FavoriteManga> {
        val favorites = favoritesStore.read()
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return favorites
        }
        if (favorites.isEmpty()) {
            prefs.edit { putBoolean(KEY_MIGRATED, true) }
            return favorites
        }

        // La chiave definitiva viene dal link quando esiste (un preferito già raggruppato in
        // ricerca può avere una chiave `anilist:`), altrimenti da titolo/URL.
        val migrated = favorites.map { favorite ->
            favorite.copy(
                seriesKey = seriesLinksStore.seriesKeyFor(
                    favorite.sourceId,
                    favorite.mangaUrl,
                    favorite.title,
                ),
            )
        }
        // Il mapping copre TUTTI i preferiti di partenza, non solo i superstiti: la baseline
        // di quello assorbito dalla fusione deve confluire nella serie, non sparire.
        val merged = mergeFavoritesBySeries(migrated)
        val deduplicated = merged.favorites
        val mapping = merged.canonicalKeyByIdentityKey

        deduplicated.forEach { favorite ->
            seriesLinksStore.ensureLink(
                seriesKey = favorite.canonicalKey(),
                title = favorite.title,
                coverUrl = favorite.coverUrl,
                binding = SeriesSourceBinding(
                    sourceId = favorite.sourceId,
                    mangaUrl = favorite.mangaUrl,
                    addedAt = favorite.addedAt.takeIf { it > 0L } ?: nowMillis,
                ),
            )
        }

        favoritesStore.persist(deduplicated)
        favoriteUpdatesStore.write(
            rekeyBySeries(favoriteUpdatesStore.read(), mapping, ::mostAdvancedSeenState),
        )
        favoriteDescriptionsStore.write(
            rekeyBySeries(favoriteDescriptionsStore.read(), mapping) { a, b -> if (b.length > a.length) b else a },
        )
        favoriteUpdatesFeedStore.update { events ->
            events.map { event ->
                if (event.seriesKey.isNotBlank()) {
                    event
                } else {
                    event.copy(seriesKey = mapping[event.identityKey] ?: event.identityKey)
                }
            }
        }
        prefs.edit { putBoolean(KEY_MIGRATED, true) }
        return deduplicated
    }

    private companion object {
        const val KEY_MIGRATED = "favorites_series_key_migrated_v1"
    }
}

/**
 * Ri-indicizza una mappa da `identityKey` a `seriesKey`. Le voci senza corrispondenza in
 * [mapping] vengono **scartate**: appartengono a preferiti non più presenti. Quando due
 * chiavi vecchie finiscono sulla stessa serie (stessa serie aggiunta da due fonti prima
 * della dedup) [merge] decide quale valore sopravvive. Pura.
 */
fun <T> rekeyBySeries(
    source: Map<String, T>,
    mapping: Map<String, String>,
    merge: (T, T) -> T,
): Map<String, T> {
    val result = LinkedHashMap<String, T>()
    source.forEach { (oldKey, value) ->
        val newKey = mapping[oldKey] ?: return@forEach
        val existing = result[newKey]
        result[newKey] = if (existing == null) value else merge(existing, value)
    }
    return result
}

/**
 * Fusione di due baseline che collidono sulla stessa serie: vince il capitolo più avanti, così
 * la migrazione non può far ri-notificare qualcosa di già visto. Pura.
 */
fun mostAdvancedSeenState(a: FavoriteSeenState, b: FavoriteSeenState): FavoriteSeenState {
    val left = a.latestChapterNumber.toBigDecimalOrNull()
    val right = b.latestChapterNumber.toBigDecimalOrNull()
    return when {
        left == null -> b
        right == null -> a
        right > left -> b
        else -> a
    }
}

/** Esito della fusione: i preferiti superstiti e dove è finito ciascun originale. */
data class FavoritesMergeResult(
    val favorites: List<FavoriteManga>,
    /** `identityKey` di ogni preferito di partenza → chiave canonica del superstite. */
    val canonicalKeyByIdentityKey: Map<String, String>,
)

/**
 * Collassa i preferiti che sono la stessa serie (chiave canonica o alias titolo in comune),
 * tenendo il primo come vincitore ma la data di aggiunta più remota. Pura: è la stessa regola
 * usata dal toggle, applicata in blocco a una lista già su disco.
 */
fun mergeFavoritesBySeries(favorites: List<FavoriteManga>): FavoritesMergeResult {
    val result = mutableListOf<FavoriteManga>()
    val claimed = mutableMapOf<String, Int>()
    val winnerIndexByIdentity = LinkedHashMap<String, Int>()
    favorites.forEach { favorite ->
        val existingIndex = favorite.matchKeys().firstNotNullOfOrNull { claimed[it] }
        val index = if (existingIndex == null) {
            val added = result.size
            result += favorite
            added
        } else {
            val winner = result[existingIndex]
            val addedAt = listOf(winner.addedAt, favorite.addedAt).filter { it > 0L }.minOrNull()
                ?: winner.addedAt
            result[existingIndex] = winner.copy(
                addedAt = addedAt,
                coverUrl = winner.coverUrl ?: favorite.coverUrl,
            )
            existingIndex
        }
        favorite.matchKeys().forEach { claimed.putIfAbsent(it, index) }
        winnerIndexByIdentity[favorite.identityKey()] = index
    }
    return FavoritesMergeResult(
        favorites = result,
        canonicalKeyByIdentityKey = winnerIndexByIdentity.mapValues { (_, index) ->
            result[index].canonicalKey()
        },
    )
}
