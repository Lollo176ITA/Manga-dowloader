package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selettore puro di "Continua a leggere": filtro dei capitoli in corso, ordine per recency con
 * i null in fondo e tie-break deterministico. Niente Android/rete.
 */
class ContinueReadingTest {

    private fun chapter(
        number: String,
        isRead: Boolean = false,
        readerPageIndex: Int? = null,
        readerPageCount: Int? = null,
        lastReadAtMillis: Long? = null,
    ) = DownloadedChapter(
        title = "Capitolo $number",
        numberText = number,
        numberValue = number.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File("$number.cbz"),
        relativePath = "s/$number.cbz",
        chapterId = "id-$number",
        isRead = isRead,
        readerPageIndex = readerPageIndex,
        readerPageCount = readerPageCount,
        lastReadAtMillis = lastReadAtMillis,
    )

    private fun series(
        title: String,
        chapters: List<DownloadedChapter>,
    ) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/${title.lowercase()}",
        coverFile = null,
        directory = File(title),
        chapters = chapters,
        totalChapterCount = chapters.size,
        readChapterIds = emptySet(),
    )

    @Test
    fun emptyLibrary_returnsEmpty() {
        assertTrue(computeContinueReading(emptyList()).isEmpty())
    }

    @Test
    fun onlyFullyReadChapters_returnsEmpty() {
        val lib = listOf(
            series("A", listOf(chapter("1", isRead = true, readerPageIndex = 9, readerPageCount = 10))),
        )
        assertTrue(computeContinueReading(lib).isEmpty())
    }

    @Test
    fun onlyNeverOpenedChapters_returnsEmpty() {
        val lib = listOf(
            series("A", listOf(chapter("1"), chapter("2", isRead = true))),
        )
        // Nessun readerPageIndex → nessun progresso di lettura → niente da riprendere.
        assertTrue(computeContinueReading(lib).isEmpty())
    }

    @Test
    fun singleInProgressChapter_isReturnedWithItsSeries() {
        val ch = chapter("3", readerPageIndex = 4, readerPageCount = 20)
        val lib = listOf(series("Berserk", listOf(ch)))
        val result = computeContinueReading(lib)
        assertEquals(1, result.size)
        assertEquals("Berserk", result.first().series.title)
        assertEquals("3", result.first().chapter.numberText)
    }

    @Test
    fun multipleInProgress_orderedByLastReadDesc() {
        val older = series("A", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 100L)))
        val newer = series("B", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 500L)))
        val result = computeContinueReading(listOf(older, newer), limit = 5)
        assertEquals(listOf("B", "A"), result.map { it.series.title })
    }

    @Test
    fun nullLastRead_sortsLastAndIsDeterministic() {
        val withTs = series("Zeta", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 200L)))
        val noTsB = series("Beta", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10)))
        val noTsA = series("Alfa", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10)))
        val result = computeContinueReading(listOf(noTsB, withTs, noTsA), limit = 5)
        // Quello con timestamp per primo; i null in fondo, ordinati per titolo (Alfa < Beta).
        assertEquals(listOf("Zeta", "Alfa", "Beta"), result.map { it.series.title })
    }

    @Test
    fun chapterMarkedReadButStoppedMidway_isIncluded_whileCompletedIsExcluded() {
        val midway = chapter("1", isRead = true, readerPageIndex = 3, readerPageCount = 10)
        val completed = chapter("2", isRead = true, readerPageIndex = 9, readerPageCount = 10)
        val lib = listOf(series("A", listOf(midway, completed)))
        val result = computeContinueReading(lib, limit = 5)
        assertEquals(listOf("1"), result.map { it.chapter.numberText })
    }

    @Test
    fun limitIsRespected() {
        val lib = listOf(
            series("A", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 300L))),
            series("B", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 200L))),
            series("C", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 100L))),
        )
        assertEquals(2, computeContinueReading(lib, limit = 2).size)
    }

    @Test
    fun mostRecentInProgressChapter_matchesLimitOne() {
        val lib = listOf(
            series("A", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 100L))),
            series("B", listOf(chapter("1", readerPageIndex = 1, readerPageCount = 10, lastReadAtMillis = 500L))),
        )
        assertEquals(
            computeContinueReading(lib, limit = 1).firstOrNull(),
            lib.mostRecentInProgressChapter(),
        )
        assertEquals("B", lib.mostRecentInProgressChapter()?.series?.title)
    }

    @Test
    fun noInProgress_mostRecentIsNull() {
        assertNull(emptyList<DownloadedSeries>().mostRecentInProgressChapter())
    }
}
