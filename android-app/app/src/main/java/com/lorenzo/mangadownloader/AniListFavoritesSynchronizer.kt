package com.lorenzo.mangadownloader

import kotlinx.coroutines.CancellationException

/**
 * Esegue un giro di riconciliazione dei preferiti (vedi [planAniListFavoritesSync] per le
 * regole). Le dipendenze arrivano come lambda, nello stesso stile di [FavoriteIdentityResolver]:
 * così il ViewModel e il worker condividono la stessa logica, e i test la esercitano tutta
 * senza rete.
 *
 * Un fallimento di rete su una singola voce non fa cadere il giro né viene archiviato come
 * fatto: quella voce resta fuori dagli id riconciliati e torna in gioco al prossimo giro. Le
 * eccezioni di autenticazione, invece, risalgono al chiamante: significano che il token non
 * vale più e vanno gestite là dove si gestisce l'account.
 */
class AniListFavoritesSynchronizer(
    private val syncStore: AniListFavoritesSyncStore,
    private val seriesLinksStore: SeriesLinksStore,
    private val fetchFavourites: suspend () -> List<AniListManga>,
    private val toggleFavourite: suspend (mediaId: Int) -> Unit,
    private val searchSources: suspend (query: String) -> List<MangaSearchResult>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Riconcilia [favorites] con i favourites dell'account.
     *
     * @return i preferiti **nuovi** arrivati da AniList, da aggiungere a quelli dell'app; lista
     *   vuota se in app non cambia nulla. Deliberatamente non ritorna la lista completa: il giro
     *   dura quanto una ricerca su tutte le fonti, e nel frattempo l'utente può aver toccato
     *   altre stelle. Sta al chiamante fondere questi nuovi con i preferiti correnti, invece di
     *   sovrascriverli con un elenco già vecchio.
     */
    suspend fun sync(favorites: List<FavoriteManga>): List<FavoriteManga> {
        val aniListFavourites = fetchFavourites()
        val aniListById = aniListFavourites.associateBy { it.id }
        // Solo i preferiti già agganciati ad AniList: quelli ancora su chiave `title:` non
        // hanno un id da confrontare e li aggancerà FavoriteIdentityResolver, non questo giro.
        val appIds = favorites
            .mapNotNull { SeriesIdentity.aniListIdFromKey(it.canonicalKey()) }
            .toSet()
        val reconciled = syncStore.readReconciledIds()
        val failedImports = syncStore.readFailedImports()
        val plan = planAniListFavoritesSync(
            appMediaIds = appIds,
            aniListMediaIds = aniListById.keys,
            alreadyReconciled = reconciled,
            failedImports = failedImports,
        )

        val succeeded = mutableSetOf<Int>()
        for (mediaId in plan.toPushToAniList) {
            try {
                toggleFavourite(mediaId)
                succeeded += mediaId
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                throw e
            } catch (_: Exception) {
                // Transitorio: l'id resta fuori dai riconciliati e si riprova al giro dopo.
            }
        }

        val imported = mutableListOf<FavoriteManga>()
        val newFailedImports = failedImports.toMutableSet()
        for (mediaId in plan.toImportInApp) {
            val media = aniListById[mediaId] ?: continue
            val match = try {
                findOnSources(media)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ricerca non riuscita: è un problema di rete, non un titolo introvabile.
                continue
            }
            if (match == null) {
                // Nessuna fonte attiva espone questo titolo: inutile ricercarlo ogni giro.
                newFailedImports += mediaId
                continue
            }
            imported += toFavorite(media, match)
            succeeded += mediaId
        }

        val importedIds = imported.mapNotNull { SeriesIdentity.aniListIdFromKey(it.canonicalKey()) }
        syncStore.writeReconciledIds(
            reconciledAniListFavoriteIds(
                alreadyReconciled = reconciled,
                appMediaIds = appIds + importedIds,
                aniListMediaIds = aniListById.keys,
                succeeded = succeeded,
            ),
        )
        if (newFailedImports != failedImports) {
            syncStore.writeFailedImports(newFailedImports)
        }

        return imported
    }

    /** Cerca [media] sulle fonti, provando i titoli in ordine di probabilità di match. */
    private suspend fun findOnSources(media: AniListManga): MangaSearchResult? {
        for (query in aniListImportSearchQueries(media)) {
            val match = matchSourceResultForAniList(media, searchSources(query))
            if (match != null) return match
        }
        return null
    }

    /**
     * Il preferito da salvare in app. La chiave è quella canonica AniList, quindi l'import non
     * può creare un doppione di una serie già presente sotto un titolo diverso. Il link viene
     * creato subito, come per un preferito aggiunto a mano: è il contenitore in cui il fallback
     * accumulerà i mirror alternativi.
     */
    private fun toFavorite(media: AniListManga, match: MangaSearchResult): FavoriteManga {
        val now = nowMillis()
        val seriesKey = SeriesIdentity.keyForAniList(media.id)
        val favorite = FavoriteManga(
            sourceId = match.sourceId,
            title = match.title,
            mangaUrl = match.mangaUrl,
            coverUrl = match.coverUrl ?: media.coverUrl,
            addedAt = now,
            seriesKey = seriesKey,
        )
        seriesLinksStore.ensureLink(
            seriesKey = seriesKey,
            title = favorite.title,
            coverUrl = favorite.coverUrl,
            binding = SeriesSourceBinding(favorite.sourceId, favorite.mangaUrl, now),
        )
        return favorite
    }
}
