package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regressione: aprire il reader dal "Riprendi" della libreria (nessuna serie selezionata)
 * deve comunque calcolare capitolo precedente/successivo cercando la serie nella libreria,
 * altrimenti la barra di navigazione capitoli non compare mai.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelReaderAdjacencyTest {

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
        File(seriesDir, "chapter_002.cbz").writeText("c2")
        File(seriesDir, "chapter_003.cbz").writeText("c3")
    }

    @Test
    fun openReaderFromLibrary_withoutSelectedSeries_resolvesAdjacentChapters() {
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)

        val series = viewModel.state.value.library.single()
        assertNull(
            "Precondizione: nessuna serie selezionata (flusso Riprendi dalla libreria)",
            viewModel.state.value.selectedDownloadedSeries,
        )

        viewModel.openReader(series.chapters[1])

        val state = viewModel.state.value
        assertEquals(series.chapters[0].relativePath, state.readerPreviousChapter?.relativePath)
        assertEquals(series.chapters[2].relativePath, state.readerNextChapter?.relativePath)
    }

    @Test
    fun openReaderFromLibrary_firstChapter_hasOnlyNext() {
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)

        val series = viewModel.state.value.library.single()
        viewModel.openReader(series.chapters.first())

        val state = viewModel.state.value
        assertNull(state.readerPreviousChapter)
        assertEquals(series.chapters[1].relativePath, state.readerNextChapter?.relativePath)
    }

    /**
     * Lo scan gira su Dispatchers.IO e riconsegna lo stato sul main looper (in pausa
     * sotto Robolectric): pompa la coda finché la libreria non è popolata.
     */
    private fun waitForLibrary(viewModel: MangaViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.library.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Libreria non caricata entro il timeout")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
