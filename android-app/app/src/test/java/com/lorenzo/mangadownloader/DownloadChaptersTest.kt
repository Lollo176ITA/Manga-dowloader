package com.lorenzo.mangadownloader

import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.network.HttpStatusException
import com.lorenzo.mangadownloader.data.sources.InsufficientStorageException
import java.io.IOException
import java.math.BigDecimal
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un capitolo che fallisce non deve impedire agli altri della serie di scaricarsi. */
class DownloadChaptersTest {

    private fun chapter(number: Int) = ChapterEntry(
        numberText = number.toString(),
        numberValue = BigDecimal(number),
        url = "https://example.com/chapter-$number",
        slug = "chapter-$number",
    )

    private val chapters = (1..5).map(::chapter)

    @Test
    fun removedChapter_doesNotStopTheOthers() = runBlocking {
        val downloaded = Collections.synchronizedList(mutableListOf<String>())

        val failures = downloadChaptersCollectingFailures(chapters, concurrency = 2) { chapter ->
            if (chapter.numberText == "2") throw HttpStatusException(404, "HTTP 404")
            downloaded += chapter.numberText
        }

        assertEquals(listOf("1", "3", "4", "5"), downloaded.sorted())
        assertEquals(listOf("2"), failures.map { it.chapter.numberText })
        assertTrue(failures.single().error is HttpStatusException)
    }

    @Test
    fun parsingError_isCollectedLikeAnHttpError() = runBlocking {
        val failures = downloadChaptersCollectingFailures(chapters, concurrency = 2) { chapter ->
            if (chapter.numberText == "4") throw IllegalStateException("Nessuna pagina trovata")
        }

        assertEquals(listOf("4"), failures.map { it.chapter.numberText })
    }

    @Test
    fun fullDisk_stopsEverything() {
        assertThrows(InsufficientStorageException::class.java) {
            runBlocking {
                downloadChaptersCollectingFailures(chapters, concurrency = 2) {
                    throw InsufficientStorageException("Spazio insufficiente")
                }
            }
        }
    }

    @Test
    fun errorTheCallerWantsToAbortOn_stopsEverything() {
        // Rete caduta con tentativi ancora disponibili: meglio fermarsi e ritentare la serie
        // che far scadere il timeout su ogni capitolo.
        assertThrows(IOException::class.java) {
            runBlocking {
                downloadChaptersCollectingFailures(
                    chapters = chapters,
                    concurrency = 2,
                    abortOn = { it is IOException && it !is HttpStatusException },
                ) { chapter ->
                    if (chapter.numberText == "3") throw IOException("timeout")
                }
            }
        }
    }

    @Test
    fun failureSummary_namesTheMissingChapters() {
        val failures = listOf(2, 4).map { ChapterFailure(chapter(it), IOException("HTTP 404")) }

        assertEquals(
            "2 capitoli su 5 non scaricati: 2, 4",
            chapterFailuresMessage(failures, chapters),
        )
    }

    @Test
    fun failureSummary_shortensLongLists() {
        val failures = chapters.dropLast(1).map { ChapterFailure(it, IOException("HTTP 404")) }

        assertEquals(
            "4 capitoli su 5 non scaricati: 1, 2, 3…",
            chapterFailuresMessage(failures, chapters),
        )
    }

    @Test
    fun failureSummary_whenNothingWasDownloaded_isTheOriginalError() {
        val failures = chapters.map { ChapterFailure(it, IOException("HTTP 403 su example.com")) }

        assertEquals("HTTP 403 su example.com", chapterFailuresMessage(failures, chapters))
    }
}
