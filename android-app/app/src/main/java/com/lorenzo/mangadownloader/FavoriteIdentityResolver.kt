package com.lorenzo.mangadownloader

import kotlinx.coroutines.CancellationException

/**
 * Consolida l'identità dei preferiti agganciandoli ad AniList quando possibile.
 *
 * Un preferito nato senza aggancio vive sotto una chiave `title:`, che dipende da come la
 * fonte scrive il titolo: la stessa opera indicizzata come "Attack on Titan" da una parte e
 * "Shingeki no Kyojin" dall'altra resterebbe due preferiti distinti, con doppie notifiche.
 * Promuovendo la chiave ad `anilist:` le due metà convergono e si fondono.
 *
 * Gira nel [FavoriteUpdatesWorker], a poche serie per volta. [searchAniList] è iniettata così
 * i test coprono promozione e fusione senza rete.
 */
class FavoriteIdentityResolver(
    private val favoritesStore: FavoritesStore,
    private val favoriteUpdatesStore: FavoriteUpdatesStore,
    private val favoriteDescriptionsStore: FavoriteDescriptionsStore,
    private val favoriteSourceHealthStore: FavoriteSourceHealthStore,
    private val seriesLinksStore: SeriesLinksStore,
    private val attemptsStore: AniListResolutionAttemptsStore,
    private val searchAniList: suspend (String) -> List<AniListManga>,
) {

    /** Restituisce i preferiti dopo le eventuali promozioni (già persistiti se cambiati). */
    suspend fun resolve(favorites: List<FavoriteManga>): List<FavoriteManga> {
        val attempted = attemptsStore.read().toMutableSet()
        val candidates = favoritesNeedingAniListResolution(favorites, attempted)
        if (candidates.isEmpty()) return favorites

        var current = favorites
        var promotedAny = false
        for (candidate in candidates) {
            val oldKey = candidate.canonicalKey()
            val match = try {
                matchAniListCandidate(candidate.title, searchAniList(candidate.title))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Rete/API giù: non si consuma il tentativo, si riprova al giro dopo.
                continue
            }
            // Il tentativo si segna anche quando AniList risponde "non lo conosco": è una
            // risposta valida, e senza questo la ricerca si ripeterebbe a ogni giro.
            attempted += oldKey
            val aniListId = match?.id ?: continue
            val newKey = SeriesIdentity.keyForAniList(aniListId)
            if (newKey == oldKey) continue

            promoteLink(candidate, oldKey, aniListId)
            rekeyStores(oldKey, newKey)
            current = current.map { favorite ->
                if (favorite.canonicalKey() == oldKey) favorite.copy(seriesKey = newKey) else favorite
            }
            promotedAny = true
        }

        attemptsStore.write(attempted)
        if (!promotedAny) return current

        // Due preferiti finiti sulla stessa chiave AniList sono la stessa serie: si fondono.
        val merged = mergeFavoritesBySeries(current).favorites
        favoritesStore.persist(merged)
        return merged
    }

    private fun promoteLink(favorite: FavoriteManga, oldKey: String, aniListId: Int) {
        val promoted = seriesLinksStore.promoteToAniList(oldKey, aniListId)
        if (promoted != null) return
        // Preferito senza link (percorsi legacy): si crea direttamente sotto la chiave nuova.
        seriesLinksStore.ensureLink(
            seriesKey = SeriesIdentity.keyForAniList(aniListId),
            title = favorite.title,
            coverUrl = favorite.coverUrl,
            binding = SeriesSourceBinding(
                favorite.sourceId,
                favorite.mangaUrl,
                favorite.addedAt,
            ),
        )
    }

    /**
     * Sposta sulla chiave nuova tutto ciò che è indicizzato per serie, fondendo se la serie
     * ne aveva già una copia. Dimenticarne uno significa perderlo: la baseline farebbe
     * ri-notificare capitoli già visti, e l'avviso "nessuna fonte raggiungibile" sparirebbe
     * dalla card proprio mentre il problema è ancora in corso.
     */
    private fun rekeyStores(oldKey: String, newKey: String) {
        favoriteUpdatesStore.read()[oldKey]?.let { seen ->
            val updated = favoriteUpdatesStore.read().toMutableMap()
            updated.remove(oldKey)
            updated[newKey] = updated[newKey]?.let { mostAdvancedSeenState(it, seen) } ?: seen
            favoriteUpdatesStore.write(updated)
        }
        favoriteDescriptionsStore.read()[oldKey]?.let { description ->
            val updated = favoriteDescriptionsStore.read().toMutableMap()
            updated.remove(oldKey)
            updated[newKey] = updated[newKey]?.takeIf { it.length >= description.length } ?: description
            favoriteDescriptionsStore.write(updated)
        }
        favoriteSourceHealthStore.read()[oldKey]?.let { health ->
            val updated = favoriteSourceHealthStore.read().toMutableMap()
            updated.remove(oldKey)
            // La serie è una sola: tra le due salute vince quella che sta peggio, per non
            // azzerare un avviso ancora valido.
            updated[newKey] = updated[newKey]?.let { existing ->
                if (existing.consecutiveFailures >= health.consecutiveFailures) existing else health
            } ?: health
            favoriteSourceHealthStore.write(updated)
        }
    }
}
