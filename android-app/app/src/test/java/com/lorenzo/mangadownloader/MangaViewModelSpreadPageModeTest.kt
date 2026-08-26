package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pagine doppie: default globale + override ricordato per serie, come per la modalità di
 * lettura (vedi [MangaViewModelReadingModeTest], che spiega perché lo stato è leggibile
 * subito dopo `openStreamingReader`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelSpreadPageModeTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultSpreadPageMode_isSplit() {
        assertEquals(SpreadPageMode.SPLIT, createViewModel().state.value.settings.spreadPageMode)
    }

    @Test
    fun spreadRotation_followsReadingOrder() {
        // Occidentale: si comincia da sinistra, quindi la facciata sinistra va in alto.
        assertEquals(SpreadRotation.CLOCKWISE, ReadingMode.PAGED.spreadRotation)
        // Manga (e scroll verticale): si comincia da destra.
        assertEquals(SpreadRotation.COUNTER_CLOCKWISE, ReadingMode.PAGED_RTL.spreadRotation)
        assertEquals(SpreadRotation.COUNTER_CLOCKWISE, ReadingMode.VERTICAL.spreadRotation)
    }

    @Test
    fun setSpreadPageMode_updatesGlobalDefaultAndPersists() {
        createViewModel().setSpreadPageMode(SpreadPageMode.ROTATE)

        assertEquals(
            SpreadPageMode.ROTATE,
            createViewModel().state.value.settings.spreadPageMode,
        )
    }

    @Test
    fun openReader_usesGlobalDefaultWhenNoOverride() {
        val viewModel = createViewModel()
        viewModel.setSpreadPageMode(SpreadPageMode.FIT)

        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())

        assertEquals(SpreadPageMode.FIT, viewModel.state.value.readerSpreadPageMode)
    }

    @Test
    fun setReaderSpreadPageMode_overridesPerSeriesAndIsRemembered() {
        val viewModel = createViewModel()
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())

        viewModel.setReaderSpreadPageMode(SpreadPageMode.ROTATE)
        assertEquals(SpreadPageMode.ROTATE, viewModel.state.value.readerSpreadPageMode)

        // Riapro la stessa serie: l'override viene ricordato.
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())
        assertEquals(SpreadPageMode.ROTATE, viewModel.state.value.readerSpreadPageMode)

        // Un'altra serie senza override usa il default globale.
        viewModel.openStreamingReader(seriesB(), seriesB().chapters.first())
        assertEquals(SpreadPageMode.SPLIT, viewModel.state.value.readerSpreadPageMode)
    }

    @Test
    fun perSeriesOverride_survivesNewViewModelAndWinsOverGlobal() {
        createViewModel().also { viewModel ->
            viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())
            viewModel.setReaderSpreadPageMode(SpreadPageMode.FIT)
        }

        val recreated = createViewModel()
        // Il nuovo default globale non deve calpestare l'override della serie A.
        recreated.setSpreadPageMode(SpreadPageMode.ROTATE)
        recreated.openStreamingReader(seriesA(), seriesA().chapters.first())

        assertEquals(SpreadPageMode.FIT, recreated.state.value.readerSpreadPageMode)
    }

    @Test
    fun setSpreadPageMode_doesNotTouchTheSeriesBeingReadWhenItHasAnOverride() {
        val viewModel = createViewModel()
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())
        viewModel.setReaderSpreadPageMode(SpreadPageMode.FIT)

        // Cambiare il default globale a lettore aperto non deve scavalcare l'override.
        viewModel.setSpreadPageMode(SpreadPageMode.ROTATE)

        assertEquals(SpreadPageMode.FIT, viewModel.state.value.readerSpreadPageMode)
        assertEquals(SpreadPageMode.ROTATE, viewModel.state.value.settings.spreadPageMode)
    }

    @Test
    fun setSpreadPageMode_appliesLiveToTheSeriesBeingReadWithoutOverride() {
        val viewModel = createViewModel()
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())

        viewModel.setSpreadPageMode(SpreadPageMode.ROTATE)

        assertEquals(SpreadPageMode.ROTATE, viewModel.state.value.readerSpreadPageMode)
    }

    private fun seriesA(): MangaDetails = sampleDetails(
        title = "Series A",
        mangaUrl = "https://mangapill.com/manga/1/series-a",
        chapterUrl = "https://mangapill.com/chapters/1-1/series-a-1",
        slug = "series-a-1",
    )

    private fun seriesB(): MangaDetails = sampleDetails(
        title = "Series B",
        mangaUrl = "https://mangapill.com/manga/2/series-b",
        chapterUrl = "https://mangapill.com/chapters/2-1/series-b-1",
        slug = "series-b-1",
    )

    private fun sampleDetails(
        title: String,
        mangaUrl: String,
        chapterUrl: String,
        slug: String,
    ): MangaDetails {
        val chapter = ChapterEntry(
            numberText = "1",
            numberValue = BigDecimal("1"),
            url = chapterUrl,
            slug = slug,
        )
        return MangaDetails(
            sourceId = MangaSourceIds.MANGAPILL,
            title = title,
            coverUrl = null,
            mangaUrl = mangaUrl,
            chapters = listOf(chapter),
        )
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    private companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
