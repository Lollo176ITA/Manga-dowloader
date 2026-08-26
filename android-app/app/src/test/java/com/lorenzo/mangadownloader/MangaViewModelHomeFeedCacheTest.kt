package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La Home usa la cache invece della rete. Sotto Robolectric non c'è rete: se il ViewModel
 * provasse comunque a interrogare AniList, il blocco finirebbe in errore o vuoto — quindi
 * "lo stato contiene i titoli della cache e nessun errore" è la prova che la rete non è
 * stata toccata, senza bisogno di mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelHomeFeedCacheTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs(): SharedPreferences =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    private fun manga(id: Int, title: String) = AniListManga(
        id = id,
        titleRomaji = title,
        titleEnglish = title,
        coverUrl = null,
        genres = listOf("Action"),
        averageScore = 80,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    @Test
    fun loadDiscovery_servesAFreshCacheWithoutHittingTheNetwork() {
        HomeFeedCacheStore(prefs()).writeDiscover(
            HomeDiscoverCache(
                trending = listOf(manga(1, "Berserk")),
                topRated = listOf(manga(2, "Vinland Saga")),
                newest = listOf(manga(3, "Vagabond")),
                fetchedAtMillis = System.currentTimeMillis(),
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadDiscovery()

        val discovery = viewModel.state.value.discovery
        assertEquals(listOf("Berserk"), discovery.trending.map { it.displayTitle() })
        assertEquals(listOf("Vinland Saga"), discovery.topRated.map { it.displayTitle() })
        assertEquals(listOf("Vagabond"), discovery.newest.map { it.displayTitle() })
        assertTrue(discovery.loaded)
        assertNull(discovery.sectionsError)
    }

    @Test
    fun loadDiscovery_ignoresACacheFromBeforeYesterdaysRollover() {
        HomeFeedCacheStore(prefs()).writeDiscover(
            HomeDiscoverCache(
                trending = listOf(manga(1, "Berserk")),
                fetchedAtMillis = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000,
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadDiscovery()

        // Cache scaduta: non deve essere mostrata come se fosse buona.
        assertTrue(viewModel.state.value.discovery.trending.isEmpty())
    }

    @Test
    fun loadRecommendations_servesAFreshCacheBuiltFromTheSameSeeds() {
        val seeds = listOf("Berserk")
        HomeFeedCacheStore(prefs()).writeRecommendations(
            HomeRecommendationsCache(
                items = listOf(manga(9, "Kingdom")),
                fetchedAtMillis = System.currentTimeMillis(),
                seedSignature = recommendationSeedSignature(seeds),
            ),
        )
        FavoritesStore(prefs()).persist(
            listOf(
                FavoriteManga(
                    sourceId = MangaSourceIds.MANGAPILL,
                    title = "Berserk",
                    mangaUrl = "https://mangapill.com/manga/1/berserk",
                    coverUrl = null,
                ),
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadRecommendations()

        val recommendations = viewModel.state.value.recommendations
        assertEquals(listOf("Kingdom"), recommendations.items.map { it.displayTitle() })
        assertTrue(recommendations.loaded)
        assertNull(recommendations.error)
    }

    @Test
    fun loadRecommendations_ignoresACacheBuiltFromDifferentSeeds() {
        HomeFeedCacheStore(prefs()).writeRecommendations(
            HomeRecommendationsCache(
                items = listOf(manga(9, "Kingdom")),
                fetchedAtMillis = System.currentTimeMillis(),
                seedSignature = recommendationSeedSignature(listOf("Naruto")),
            ),
        )
        FavoritesStore(prefs()).persist(
            listOf(
                FavoriteManga(
                    sourceId = MangaSourceIds.MANGAPILL,
                    title = "Berserk",
                    mangaUrl = "https://mangapill.com/manga/1/berserk",
                    coverUrl = null,
                ),
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadRecommendations()

        // Hai aggiunto un preferito da quando i consigli erano stati calcolati: quelli vecchi
        // non valgono più, vanno rifatti anche prima delle 9.
        assertTrue(viewModel.state.value.recommendations.items.isEmpty())
    }

    @Test
    fun refreshHomeFeeds_bypassesAFreshCache() {
        HomeFeedCacheStore(prefs()).writeDiscover(
            HomeDiscoverCache(
                trending = listOf(manga(1, "Berserk")),
                fetchedAtMillis = System.currentTimeMillis(),
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadDiscovery()
        assertEquals(listOf("Berserk"), viewModel.state.value.discovery.trending.map { it.displayTitle() })

        viewModel.refreshHomeFeeds()

        // Servire la cache significa restare fermi (isLoadingSections = false); qui invece il
        // caricamento deve **ripartire**, ed è questa la prova che la cache è stata scavalcata.
        // Il fetch vero e proprio resta in coda: sotto Robolectric il main looper è in pausa.
        assertTrue(viewModel.state.value.discovery.isLoadingSections)
    }

    @Test
    fun refreshHomeFeeds_doesNothingUnderParentalControl() {
        HomeFeedCacheStore(prefs()).writeDiscover(
            HomeDiscoverCache(
                trending = listOf(manga(1, "Berserk")),
                fetchedAtMillis = System.currentTimeMillis(),
            ),
        )

        val viewModel = MangaViewModel(application)
        viewModel.loadDiscovery()
        // Attivare il controllo parentale passa dalla creazione del PIN: il solo
        // setParentalControlEnabled(true) apre il setup, non lo accende.
        viewModel.setParentalControlEnabled(true)
        viewModel.onParentalPinSetupChange(pin = "123456")
        viewModel.onParentalPinSetupChange(confirmPin = "123456")
        viewModel.confirmParentalPinSetup()
        assertTrue(viewModel.state.value.settings.parentalControlEnabled)

        viewModel.refreshHomeFeeds()

        // Sotto controllo parentale Scopri e Consigliati non esistono: niente da ricaricare.
        assertFalse(viewModel.state.value.discovery.isLoadingSections)
        assertEquals(listOf("Berserk"), viewModel.state.value.discovery.trending.map { it.displayTitle() })
    }
}
