package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.anilist.AniListSort
import com.lorenzo.mangadownloader.data.anilist.matchAniListCandidate
import com.lorenzo.mangadownloader.data.store.HomeDiscoverCache
import com.lorenzo.mangadownloader.data.store.HomeFeedCacheStore
import com.lorenzo.mangadownloader.data.store.HomeRecommendationsCache
import com.lorenzo.mangadownloader.data.store.isHomeFeedFresh
import com.lorenzo.mangadownloader.data.store.recommendationSeedSignature
import com.lorenzo.mangadownloader.domain.home.DiscoverGenre
import com.lorenzo.mangadownloader.domain.home.aggregateRecommendations
import com.lorenzo.mangadownloader.domain.home.normalizedRecommendationTitle
import com.lorenzo.mangadownloader.domain.home.selectRecommendationSeeds
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vetrine AniList della Home e della tab Scopri, estratte da [MangaViewModel]: caroselli
 * (tendenze, più votati, novità), "Consigliati per te", pagina del genere e scheda info.
 * Solo metadati: il ponte verso le fonti ([MangaViewModel.onPickAniListManga]) resta nel
 * ViewModel perché è una ricerca. Condivide il [MutableStateFlow] del ViewModel.
 */
class HomeFeedController(
    private val state: MutableStateFlow<MangaUiState>,
    private val scope: CoroutineScope,
    private val aniListClient: AniListClient,
    private val homeFeedCacheStore: HomeFeedCacheStore,
) {
    private var discoveryJob: Job? = null
    private var recommendationsJob: Job? = null
    private var genreJob: Job? = null

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    /**
     * Carica le tre sezioni a caroselli (tendenze, più votati, novità). Idempotente nella
     * sessione e, grazie a [homeFeedCacheStore], anche **tra un avvio e l'altro**: la vetrina
     * si rinnova una volta al giorno alle 9 (vedi [isHomeFeedFresh]), non a ogni apertura
     * dell'app. [forceRefresh] è il pull-to-refresh: scavalca la cache e la riscrive.
     */
    fun loadDiscovery(forceRefresh: Boolean = false) {
        val current = state.value.discovery
        if (!forceRefresh && (current.loaded || current.isLoadingSections)) {
            return
        }
        if (!forceRefresh && restoreCachedDiscovery()) {
            return
        }
        discoveryJob?.cancel()
        updateState { copy(discovery = discovery.copy(isLoadingSections = true, sectionsError = null)) }
        discoveryJob = scope.launch {
            try {
                val sections = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val trending = async { aniListClient.fetchMedia(AniListSort.TRENDING) }
                        val topRated = async { aniListClient.fetchMedia(AniListSort.TOP_RATED) }
                        val newest = async { aniListClient.fetchMedia(AniListSort.NEWEST) }
                        Triple(trending.await(), topRated.await(), newest.await())
                    }
                }
                homeFeedCacheStore.writeDiscover(
                    HomeDiscoverCache(
                        trending = sections.first,
                        topRated = sections.second,
                        newest = sections.third,
                        fetchedAtMillis = System.currentTimeMillis(),
                    ),
                )
                updateState {
                    copy(
                        discovery = discovery.copy(
                            trending = sections.first,
                            topRated = sections.second,
                            newest = sections.third,
                            isLoadingSections = false,
                            loaded = true,
                            sectionsError = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        discovery = discovery.copy(
                            isLoadingSections = false,
                            sectionsError = exc.message ?: "Errore caricamento Scopri",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Carica il blocco Home "Consigliati per te". Idempotente per sessione (come la Scopri):
     * semi = titoli di preferiti e serie lette; ogni seme viene risolto in un media AniList via
     * ricerca, poi un'unica query prende le raccomandazioni della community, aggregate e
     * ripulite da ciò che l'utente ha già. Nessun seme → blocco vuoto (la Home lo nasconde).
     */
    fun loadRecommendations(forceRefresh: Boolean = false) {
        val snapshot = state.value
        if (snapshot.settings.parentalControlEnabled) return
        val current = snapshot.recommendations
        if (!forceRefresh && (current.loaded || current.isLoading)) {
            return
        }
        val seeds = selectRecommendationSeeds(snapshot.favorites, snapshot.readingMemory)
        if (seeds.isEmpty()) {
            updateState {
                copy(recommendations = recommendations.copy(items = emptyList(), loaded = true))
            }
            return
        }
        val seedSignature = recommendationSeedSignature(seeds)
        if (!forceRefresh && restoreCachedRecommendations(seedSignature)) {
            return
        }
        val excludeTitles = buildSet {
            snapshot.favorites.forEach { add(normalizedRecommendationTitle(it.title)) }
            snapshot.library.forEach { add(normalizedRecommendationTitle(it.title)) }
            snapshot.readingMemory.values.forEach { add(normalizedRecommendationTitle(it.seriesTitle)) }
        }
        recommendationsJob?.cancel()
        updateState { copy(recommendations = recommendations.copy(isLoading = true, error = null)) }
        recommendationsJob = scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    // Un seme che non matcha (titolo oscuro, refuso) non deve far fallire il
                    // blocco; se però falliscono TUTTE le ricerche è un problema reale (rete)
                    // e va mostrato il "Riprova".
                    val lookups = coroutineScope {
                        seeds.map { title ->
                            async {
                                runCatching {
                                    aniListClient.searchManga(title, perPage = 5)
                                        .let { results ->
                                            // Il primo risultato di AniList non è il più
                                            // giusto: la ricerca pesca anche nei sinonimi, e
                                            // un titolo generico può portare in testa una
                                            // serie che non c'entra. Se qualcuno combacia
                                            // esattamente, vince lui.
                                            matchAniListCandidate(title, results)
                                                ?: results.firstOrNull { it.format != "NOVEL" }
                                                ?: results.firstOrNull()
                                        }?.id
                                }
                            }
                        }.map { it.await() }
                    }
                    if (lookups.isNotEmpty() && lookups.all { it.isFailure }) {
                        throw lookups.first().exceptionOrNull() ?: IOException("Ricerca AniList non riuscita")
                    }
                    val seedIds = lookups.mapNotNull { it.getOrNull() }.distinct()
                    if (seedIds.isEmpty()) {
                        emptyList()
                    } else {
                        aggregateRecommendations(
                            recommendations = aniListClient.fetchRecommendations(seedIds),
                            excludeIds = seedIds.toSet(),
                            excludeTitles = excludeTitles,
                        )
                    }
                }
                homeFeedCacheStore.writeRecommendations(
                    HomeRecommendationsCache(
                        items = items,
                        fetchedAtMillis = System.currentTimeMillis(),
                        seedSignature = seedSignature,
                    ),
                )
                updateState {
                    copy(
                        recommendations = recommendations.copy(
                            items = items,
                            isLoading = false,
                            loaded = true,
                            error = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        recommendations = recommendations.copy(
                            isLoading = false,
                            error = exc.message ?: "Errore caricamento consigli",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Pull-to-refresh della Home: rinnova le vetrine AniList **adesso**, senza aspettare il
     * rollover delle 9. Sotto controllo parentale i due blocchi non esistono, quindi non c'è
     * niente da rinfrescare e la rete non va toccata.
     */
    fun refreshHomeFeeds() {
        if (state.value.settings.parentalControlEnabled) return
        loadDiscovery(forceRefresh = true)
        loadRecommendations(forceRefresh = true)
    }

    /**
     * Ripubblica la Scopri salvata su disco se non ha ancora passato il rollover delle 9.
     * Ritorna `true` se lo stato è stato riempito, cioè se non serve andare in rete.
     */
    private fun restoreCachedDiscovery(): Boolean {
        val cache = homeFeedCacheStore.readDiscover()
        if (!isHomeFeedFresh(cache.fetchedAtMillis, System.currentTimeMillis())) {
            return false
        }
        updateState {
            copy(
                discovery = discovery.copy(
                    trending = cache.trending,
                    topRated = cache.topRated,
                    newest = cache.newest,
                    isLoadingSections = false,
                    loaded = true,
                    sectionsError = null,
                ),
            )
        }
        return true
    }

    /**
     * Come [restoreCachedDiscovery], ma i Consigliati dipendono anche da **cosa leggi**: una
     * cache costruita su semi diversi da quelli di adesso (hai aggiunto un preferito, hai
     * iniziato una serie) è da buttare anche se è di stamattina.
     */
    private fun restoreCachedRecommendations(seedSignature: String): Boolean {
        val cache = homeFeedCacheStore.readRecommendations()
        if (cache.seedSignature != seedSignature) {
            return false
        }
        if (!isHomeFeedFresh(cache.fetchedAtMillis, System.currentTimeMillis())) {
            return false
        }
        updateState {
            copy(
                recommendations = recommendations.copy(
                    items = cache.items,
                    isLoading = false,
                    loaded = true,
                    error = null,
                ),
            )
        }
        return true
    }

    /** Apre la pagina del genere e ne avvia il caricamento. */
    fun openDiscoverGenre(genre: DiscoverGenre) {
        updateState {
            copy(discovery = discovery.copy(selectedGenre = genre, genreResults = emptyList(), genreError = null))
        }
        loadDiscoverGenre(genre)
    }

    /** Fetch dei popolari del genere; pubblico per il "Riprova" della pagina. */
    fun loadDiscoverGenre(genre: DiscoverGenre) {
        genreJob?.cancel()
        updateState { copy(discovery = discovery.copy(isLoadingGenre = true, genreError = null)) }
        genreJob = scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    aniListClient.fetchMedia(AniListSort.POPULAR, genre = genre.apiGenre, perPage = 50)
                }
                updateState {
                    copy(discovery = discovery.copy(genreResults = results, isLoadingGenre = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        discovery = discovery.copy(
                            isLoadingGenre = false,
                            genreError = exc.message ?: "Errore di caricamento",
                        ),
                    )
                }
            }
        }
    }

    fun closeDiscoverGenre() {
        genreJob?.cancel()
        updateState {
            copy(
                discovery = discovery.copy(
                    selectedGenre = null,
                    genreResults = emptyList(),
                    isLoadingGenre = false,
                    genreError = null,
                ),
            )
        }
    }

    fun showDiscoveryInfo(manga: AniListManga) {
        updateState { copy(discovery = discovery.copy(info = manga)) }
    }

    fun dismissDiscoveryInfo() {
        updateState { copy(discovery = discovery.copy(info = null)) }
    }
}
