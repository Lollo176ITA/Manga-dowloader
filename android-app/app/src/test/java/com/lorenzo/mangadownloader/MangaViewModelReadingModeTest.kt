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
 * Modalità di lettura: default globale + override ricordato per serie.
 * openStreamingReader imposta lo stato in modo sincrono (il job di rete resta in
 * coda con il looper in pausa), quindi readerReadingMode/readerSeriesKey sono
 * leggibili subito dopo la chiamata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelReadingModeTest {

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
    fun defaultReadingMode_isVertical() {
        assertEquals(ReadingMode.VERTICAL, createViewModel().state.value.settings.readingMode)
    }

    @Test
    fun setReadingMode_updatesGlobalDefaultAndPersists() {
        createViewModel().setReadingMode(ReadingMode.PAGED_RTL)

        // Nuovo ViewModel: il default è ancora su disco.
        assertEquals(ReadingMode.PAGED_RTL, createViewModel().state.value.settings.readingMode)
    }

    @Test
    fun openReader_usesGlobalDefaultWhenNoOverride() {
        val viewModel = createViewModel()
        viewModel.setReadingMode(ReadingMode.PAGED_LTR)

        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())

        assertEquals(ReadingMode.PAGED_LTR, viewModel.state.value.readerReadingMode)
    }

    @Test
    fun setReaderReadingMode_overridesPerSeriesAndIsRemembered() {
        val viewModel = createViewModel()
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())

        viewModel.setReaderReadingMode(ReadingMode.PAGED_RTL)
        assertEquals(ReadingMode.PAGED_RTL, viewModel.state.value.readerReadingMode)

        // Riapro la stessa serie: l'override viene ricordato.
        viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())
        assertEquals(ReadingMode.PAGED_RTL, viewModel.state.value.readerReadingMode)

        // Un'altra serie senza override usa il default globale (verticale).
        viewModel.openStreamingReader(seriesB(), seriesB().chapters.first())
        assertEquals(ReadingMode.VERTICAL, viewModel.state.value.readerReadingMode)
    }

    @Test
    fun perSeriesOverride_survivesNewViewModelAndIgnoresGlobalChange() {
        createViewModel().also { viewModel ->
            viewModel.openStreamingReader(seriesA(), seriesA().chapters.first())
            viewModel.setReaderReadingMode(ReadingMode.PAGED_RTL)
        }

        val recreated = createViewModel()
        // Cambio il default globale: non deve sovrascrivere l'override esplicito della serie A.
        recreated.setReadingMode(ReadingMode.PAGED_LTR)
        recreated.openStreamingReader(seriesA(), seriesA().chapters.first())

        assertEquals(ReadingMode.PAGED_RTL, recreated.state.value.readerReadingMode)
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
