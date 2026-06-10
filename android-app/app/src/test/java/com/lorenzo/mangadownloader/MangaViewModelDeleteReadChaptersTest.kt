package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Elimina capitoli letti": rimuove dal disco solo i capitoli letti, tiene i non letti, e
 * preserva la baseline "letto" nei metadati. È il building block della gestione spazio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelDeleteReadChaptersTest {

    private lateinit var application: Application
    private lateinit var seriesDir: File

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val root = DownloadStorage.libraryRoot(application)
        root.deleteRecursively()
        seriesDir = File(root, "berserk").apply { mkdirs() }
        File(seriesDir, "chapter_001.cbz").writeText("c1")
        File(seriesDir, "chapter_002.cbz").writeText("c2")
        File(seriesDir, "chapter_003.cbz").writeText("c3")

        // I capitoli 1 e 2 sono già letti (readChapterIds), il 3 no.
        SeriesMetadataJson.write(
            File(seriesDir, DownloadStorage.SERIES_METADATA_FILE_NAME),
            SeriesMetadata(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/1/berserk",
                coverFileName = null,
                totalChapters = 3,
                readChapterIds = setOf("c1", "c2"),
                chapters = listOf(
                    metaChapter("1", "chapter_001.cbz", "c1"),
                    metaChapter("2", "chapter_002.cbz", "c2"),
                    metaChapter("3", "chapter_003.cbz", "c3"),
                ),
            ),
        )
    }

    @Test
    fun deleteReadChapters_removesOnlyReadFiles_andKeepsUnread() {
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)

        val series = viewModel.state.value.library.single()
        assertEquals(2, series.chapters.count { it.isRead })

        viewModel.deleteReadChapters(series)
        // Aspetta lo stato finale (rescan completato sul main thread), non il singolo file:
        // l'eliminazione gira su un thread IO reale e cancella i file uno alla volta.
        waitUntil { viewModel.state.value.library.sumOf { it.chapters.size } == 1 }

        assertFalse(File(seriesDir, "chapter_001.cbz").exists())
        assertFalse(File(seriesDir, "chapter_002.cbz").exists())
        assertTrue("Il capitolo non letto resta sul disco", File(seriesDir, "chapter_003.cbz").exists())
    }

    @Test
    fun deleteReadChapters_withNothingRead_isNoOp() {
        // Azzera la baseline: nessun capitolo letto.
        SeriesMetadataJson.write(
            File(seriesDir, DownloadStorage.SERIES_METADATA_FILE_NAME),
            SeriesMetadata(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/1/berserk",
                coverFileName = null,
                totalChapters = 3,
                readChapterIds = emptySet(),
                chapters = listOf(
                    metaChapter("1", "chapter_001.cbz", "c1"),
                    metaChapter("2", "chapter_002.cbz", "c2"),
                    metaChapter("3", "chapter_003.cbz", "c3"),
                ),
            ),
        )
        val viewModel = createViewModel()
        viewModel.refreshLibrary(forceRefresh = true)
        waitForLibrary(viewModel)

        val series = viewModel.state.value.library.single()
        viewModel.deleteReadChapters(series)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(File(seriesDir, "chapter_001.cbz").exists())
        assertTrue(File(seriesDir, "chapter_002.cbz").exists())
        assertTrue(File(seriesDir, "chapter_003.cbz").exists())
    }

    private fun metaChapter(numberText: String, fileName: String, id: String) =
        SeriesMetadataChapter(
            numberText = numberText,
            url = "https://mangapill.com/chapters/1/berserk-chapter-$numberText",
            slug = "berserk-chapter-$numberText",
            fileName = fileName,
            id = id,
        )

    private fun waitForLibrary(viewModel: MangaViewModel) {
        waitUntil { viewModel.state.value.library.isNotEmpty() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Condizione non raggiunta entro il timeout")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))
}
