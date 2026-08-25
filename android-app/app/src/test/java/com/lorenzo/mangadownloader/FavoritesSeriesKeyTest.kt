package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferiti deduplicati per SeriesKey: la stessa serie da due fonti = una voce sola. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoritesSeriesKeyTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun stessaSerieDaDueFontiEUnSoloPreferito() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        // Stessa serie da un'altra fonte (titolo che normalizza uguale): toggle OFF, non doppione.
        viewModel.toggleFavorite(
            FavoriteManga("vymanga", "One Piece!", "https://vymanga.com/manga/9", null),
        )
        assertEquals(0, viewModel.state.value.favorites.size)
    }

    @Test
    fun favoriteSeriesKeysContieneLaChiaveSerie() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        assertTrue("title:one piece" in viewModel.state.value.favoriteSeriesKeys)
    }

    @Test
    fun serieDiverseRestanoPreferitiDistinti() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "Naruto", "https://mangapill.com/manga/3", null),
        )
        assertEquals(2, viewModel.state.value.favorites.size)
    }

    /**
     * Il caso che rendeva antipatico il sistema: la stella della scheda guardava la coppia
     * fonte+URL mentre il toggle ragionava per serie, quindi aprendo un preferito da un'altra
     * fonte la stella risultava vuota e premerla **cancellava** il preferito esistente.
     */
    @Test
    fun laSerieRestaRiconosciutaAncheAperturaDaUnAltraFonte() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        val keys = viewModel.state.value.favoriteSeriesKeys

        // È quello che chiede la stella in AppBars quando apri la stessa serie da VyManga.
        assertTrue(
            "La stella deve risultare piena anche da un'altra fonte",
            keys.containsAny(SeriesIdentity.keyForTitle("One Piece!")),
        )
    }

    @Test
    fun ogniPreferitoNasceConIlSuoLinkPerIMirror() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        val link = SeriesLinksStore(
            application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        ).linkFor("title:one piece")
        assertEquals("mangapill", link?.sources?.single()?.sourceId)
    }

    @Test
    fun laChiaveSeriePersisteTraUnAvvioELAltro() {
        createViewModel().toggleFavorite(
            FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null),
        )
        val riavviato = createViewModel()
        assertEquals("title:one piece", riavviato.state.value.favorites.single().seriesKey)
    }
}
