package com.lorenzo.mangadownloader.data.anilist

import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.sources.SearchScope
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * [AniListFavoritesSynchronizer] collegato ad AniList e alle fonti vere: la stessa
 * configurazione per il ViewModel (sync all'avvio e al tocco di una stella) e per il worker
 * (sync periodica). [excludedSourceIds] si rilegge a ogni ricerca: fonti disattivate
 * dall'utente più quelle che l'interruttore automatico sta saltando, perché un import costa
 * una ricerca su tutte le fonti e aspettarne una morta le rallenta tutte.
 */
fun realAniListFavoritesSynchronizer(
    client: AniListClient,
    token: String,
    viewerId: Int,
    registry: MangaSourceRegistry,
    syncStore: AniListFavoritesSyncStore,
    seriesLinksStore: SeriesLinksStore,
    disabledSourceIds: () -> Set<String>,
    unavailableSourceIds: () -> Set<String>,
): AniListFavoritesSynchronizer = AniListFavoritesSynchronizer(
    syncStore = syncStore,
    seriesLinksStore = seriesLinksStore,
    fetchFavourites = {
        withContext(Dispatchers.IO) { client.fetchFavouriteManga(token, viewerId) }
    },
    toggleFavourite = { mediaId ->
        withContext(Dispatchers.IO) { client.toggleFavouriteManga(token, mediaId) }
    },
    searchSources = { query ->
        searchAllEnabledSources(registry, disabledSourceIds() + unavailableSourceIds(), query)
    },
    sourcesSignature = {
        aniListImportSourcesSignature(disabledSourceIds(), unavailableSourceIds())
    },
)

/**
 * Ricerca su **tutte** le fonti attive, ignorando l'ambito di lingua della tab Cerca: un
 * preferito importato da AniList può stare su qualsiasi fonte. Il fallimento di una fonte
 * vale "nessun risultato" lì, non fa cadere la ricerca.
 *
 * I risultati vengono **alternati** fra le fonti come nella ricerca normale, non accodati a
 * blocchi: chi importa prende il primo che combacia, e concatenando vincerebbe sempre la fonte
 * in cima al catalogo.
 */
suspend fun searchAllEnabledSources(
    registry: MangaSourceRegistry,
    excludedSourceIds: Set<String>,
    query: String,
): List<MangaSearchResult> = withContext(Dispatchers.IO) {
    coroutineScope {
        val perSource = MangaSourceCatalog
            .descriptorsForScope(SearchScope.ALL, excludedSourceIds)
            .map { descriptor ->
                async {
                    try {
                        registry.requireById(descriptor.id).searchManga(query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            .awaitAll()
        MangaSourceCatalog.interleaveBySource(perSource)
    }
}
