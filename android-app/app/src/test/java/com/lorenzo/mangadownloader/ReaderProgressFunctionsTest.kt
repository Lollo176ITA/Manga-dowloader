package com.lorenzo.mangadownloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applicazione pura del progresso di lettura. Verifica soprattutto l'ottimizzazione anti-rebuild:
 * capitoli/serie non toccati tornano come **la stessa istanza** (evita di ricostruire la libreria
 * a ogni pagina), oltre alla correttezza dell'aggiornamento.
 */
class ReaderProgressFunctionsTest {

    @Test
    fun chapter_unchangedWhenPathDoesNotMatch() {
        val chapter = chapter("s/1.cbz")

        val result = chapter.withReaderProgressApplied("s/altra.cbz", pageIndex = 5, pageCount = 10, markRead = true)

        assertSame(chapter, result)
    }

    @Test
    fun chapter_updatesPositionAndReadFlagWhenPathMatches() {
        val chapter = chapter("s/1.cbz")

        val result = chapter.withReaderProgressApplied("s/1.cbz", pageIndex = 5, pageCount = 10, markRead = true)

        assertEquals(5, result.readerPageIndex)
        assertEquals(10, result.readerPageCount)
        assertTrue(result.isRead)
    }

    @Test
    fun series_returnsSameInstanceWhenItDoesNotContainTheChapter() {
        val series = series("other-series", chapters = listOf(chapter("other/1.cbz")))

        val result = series.withReaderProgressApplied("s/1.cbz", pageIndex = 3, pageCount = 9, markRead = false, readChapterId = null)

        assertSame(series, result)
    }

    @Test
    fun series_updatesContainedChapterAndKeepsOthers() {
        val target = chapter("s/2.cbz")
        val other = chapter("s/1.cbz")
        val series = series("s", chapters = listOf(other, target))

        val result = series.withReaderProgressApplied("s/2.cbz", pageIndex = 4, pageCount = 12, markRead = false, readChapterId = null)

        assertEquals(4, result.chapters.first { it.relativePath == "s/2.cbz" }.readerPageIndex)
        // L'altro capitolo resta la stessa istanza.
        assertSame(other, result.chapters.first { it.relativePath == "s/1.cbz" })
    }

    @Test
    fun series_addsReadChapterIdOnlyWhenMarkRead() {
        val series = series("s", chapters = listOf(chapter("s/1.cbz")))

        val read = series.withReaderProgressApplied("s/1.cbz", pageIndex = 9, pageCount = 10, markRead = true, readChapterId = "cid")
        assertTrue("cid" in read.readChapterIds)
        assertTrue(read.chapters.first().isRead)

        val notRead = series.withReaderProgressApplied("s/1.cbz", pageIndex = 5, pageCount = 10, markRead = false, readChapterId = "cid")
        assertFalse("cid" in notRead.readChapterIds)
    }

    private fun chapter(relativePath: String) = DownloadedChapter(
        title = relativePath,
        numberText = "1",
        numberValue = null,
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File(relativePath),
        relativePath = relativePath,
        chapterId = "id-$relativePath",
        isRead = false,
        readerPageIndex = null,
        readerPageCount = null,
    )

    private fun series(name: String, chapters: List<DownloadedChapter>) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = name,
        mangaUrl = "https://mangapill.com/manga/$name",
        coverFile = null,
        directory = File(name),
        chapters = chapters,
        totalChapterCount = chapters.size,
        readChapterIds = emptySet(),
    )
}
