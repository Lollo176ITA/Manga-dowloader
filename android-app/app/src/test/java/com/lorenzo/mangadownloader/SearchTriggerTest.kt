package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il ponte "consigliato → ricerca" deve rilanciare il fetch anche quando si ritocca lo stesso
 * titolo. Prima non lo faceva: query, ambito e fonti attive erano identici al giro precedente,
 * il `distinctUntilChanged` del ViewModel scartava l'aggiornamento e la tab Cerca restava con
 * lo spinner acceso e i risultati svuotati, per sempre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchTriggerTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `ritoccare lo stesso consigliato produce un trigger nuovo`() {
        val viewModel = createViewModel()
        val manga = sampleAniListManga()

        viewModel.onPickAniListManga(manga)
        val first = viewModel.state.value.searchTrigger()

        // Ritorno alla Home e nuovo tap sulla stessa card.
        viewModel.selectTab(AppTab.HOME)
        viewModel.onPickAniListManga(manga)
        val second = viewModel.state.value.searchTrigger()

        assertEquals("la query è la stessa: da sola non basta a far ripartire il fetch", first.query, second.query)
        assertNotEquals(first, second)
        assertTrue("la tab Cerca mostra lo spinner: il fetch deve partire", viewModel.state.value.isSearching)
    }

    @Test
    fun `un cambio di stato estraneo alla ricerca non produce un trigger nuovo`() {
        val viewModel = createViewModel()
        viewModel.onPickAniListManga(sampleAniListManga())
        val before = viewModel.state.value.searchTrigger()

        viewModel.setKeepScreenOnEnabled(true)

        assertEquals(before, viewModel.state.value.searchTrigger())
    }

    @Test
    fun `spegnere una fonte produce un trigger nuovo`() {
        val viewModel = createViewModel()
        viewModel.onPickAniListManga(sampleAniListManga())
        val before = viewModel.state.value.searchTrigger()

        viewModel.setSourceEnabled(MangaSourceIds.MANGAPILL, false)

        assertNotEquals(before, viewModel.state.value.searchTrigger())
    }

    private fun sampleAniListManga(): AniListManga = AniListManga(
        id = 30002,
        titleRomaji = "Berserk",
        titleEnglish = "Berserk",
        coverUrl = null,
        genres = listOf("Action"),
        averageScore = 93,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))
}
