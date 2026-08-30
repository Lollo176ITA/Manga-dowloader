package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Il tap su un capitolo nel dettaglio non dipende più da un'impostazione: apre la copia in
 * libreria se c'è, altrimenti lo streaming. Prima, con la lettura online attiva, anche un
 * capitolo già scaricato veniva riscaricato dalla rete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelOpenChapterTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val root = DownloadStorage.libraryRoot(application)
        root.deleteRecursively()
        val seriesDir = File(root, "test_series").apply { mkdirs() }
        File(seriesDir, "chapter_001.cbz").writeText("c1")
    }

    @Test
    fun openChapterFromDetail_apreLaCopiaInLibreriaSenzaPassareDallaRete() {
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)
        val series = viewModel.state.value.library.single()
        val downloaded = series.chapters.single()

        viewModel.openChapterFromDetail(
            details = detailsFor(series),
            chapter = chapterEntry(number = downloaded.numberText),
        )

        val opened = viewModel.state.value.readerChapter
        assertEquals(downloaded.relativePath, opened?.relativePath)
    }

    @Test
    fun openChapterFromDetail_vaInStreamingQuandoIlCapitoloNonEScaricato() {
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)
        val series = viewModel.state.value.library.single()

        // Capitolo che in libreria non c'è: resta solo la strada online.
        viewModel.openChapterFromDetail(
            details = detailsFor(series),
            chapter = chapterEntry(number = "99"),
        )

        val opened = viewModel.state.value.readerChapter
        assertTrue(
            "Atteso un capitolo streaming, trovato ${opened?.relativePath}",
            opened?.relativePath?.startsWith("streaming:") == true,
        )
    }

    private fun detailsFor(series: DownloadedSeries) = MangaDetails(
        sourceId = series.sourceId,
        title = series.title,
        coverUrl = null,
        mangaUrl = series.mangaUrl.orEmpty(),
        chapters = emptyList(),
    )

    private fun chapterEntry(number: String) = ChapterEntry(
        numberText = number,
        numberValue = BigDecimal(number),
        url = "https://mangapill.com/chapters/$number/test-$number",
        slug = "test-$number",
    )

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    /**
     * Lo scan gira su Dispatchers.IO e riconsegna lo stato sul main looper (in pausa sotto
     * Robolectric): pompa la coda finché la libreria non è popolata.
     */
    private fun waitForLibrary(viewModel: MangaViewModel) {
        val deadline = System.currentTimeMillis() + LIBRARY_TIMEOUT_MS
        while (viewModel.state.value.library.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Libreria non caricata entro il timeout")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val LIBRARY_TIMEOUT_MS = 5_000L
    }
}
