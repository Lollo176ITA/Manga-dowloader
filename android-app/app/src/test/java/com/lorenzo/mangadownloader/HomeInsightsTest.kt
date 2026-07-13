package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcoli puri dei blocchi Home nuovi: statistiche, cronologia, da finire. Niente Android/rete. */
class HomeInsightsTest {

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

    private fun series(title: String, chapters: List<DownloadedChapter>) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/${title.lowercase()}",
        coverFile = null,
        directory = File(title),
        chapters = chapters,
        totalChapterCount = chapters.size,
        readChapterIds = emptySet(),
    )

    // --- computeHomeStats ---

    @Test
    fun stats_emptyLibraryAndNoFavorites_isEmpty() {
        val stats = computeHomeStats(emptyList(), favoritesCount = 0)
        assertTrue(stats.isEmpty())
        assertEquals(HomeStats(0, 0, 0, 0), stats)
    }

    @Test
    fun stats_countsSeriesReadChaptersAndPages() {
        val lib = listOf(
            series("A", listOf(
                chapter("1", isRead = true, readerPageCount = 20),            // completato: 20 pagine
                chapter("2", readerPageIndex = 4, readerPageCount = 30),      // in corso: 5 pagine
                chapter("3"),                                                  // mai aperto: 0
            )),
            series("B", listOf(
                chapter("1", isRead = true),                                   // letto senza pageCount: 0 pagine
            )),
        )
        val stats = computeHomeStats(lib, favoritesCount = 7)
        assertEquals(2, stats.seriesCount)
        assertEquals(2, stats.chaptersRead)
        assertEquals(25, stats.pagesRead)
        assertEquals(7, stats.favoritesCount)
        assertTrue(!stats.isEmpty())
    }

    @Test
    fun stats_onlyFavorites_isNotEmpty() {
        assertTrue(!computeHomeStats(emptyList(), favoritesCount = 1).isEmpty())
    }

    @Test
    fun stats_pageIndexBeyondCount_isCappedToCount() {
        val lib = listOf(series("A", listOf(chapter("1", readerPageIndex = 99, readerPageCount = 10))))
        assertEquals(10, computeHomeStats(lib, 0).pagesRead)
    }

    // --- computeReadingHistory ---

    @Test
    fun history_ordersByLastReadDescAndSkipsNeverRead() {
        val lib = listOf(
            series("A", listOf(
                chapter("1", isRead = true, lastReadAtMillis = 1_000L),
                chapter("2", readerPageIndex = 3, readerPageCount = 10, lastReadAtMillis = 3_000L),
                chapter("3"), // mai letto: escluso
            )),
            series("B", listOf(chapter("5", isRead = true, lastReadAtMillis = 2_000L))),
        )
        val history = computeReadingHistory(lib)
        assertEquals(listOf("2", "5", "1"), history.map { it.chapter.numberText })
        assertEquals(listOf("A", "B", "A"), history.map { it.series.title })
    }

    @Test
    fun history_respectsLimit() {
        val lib = listOf(series("A", (1..15).map {
            chapter("$it", isRead = true, lastReadAtMillis = it * 100L)
        }))
        assertEquals(10, computeReadingHistory(lib, limit = 10).size)
    }

    // --- computeSeriesToFinish ---

    @Test
    fun toFinish_excludesFullyReadSeries_andCountsUnread() {
        val lib = listOf(
            series("Tutta letta", listOf(chapter("1", isRead = true), chapter("2", isRead = true))),
            series("Metà", listOf(chapter("1", isRead = true, lastReadAtMillis = 5_000L), chapter("2"), chapter("3"))),
            series("Mai aperta", listOf(chapter("1"))),
        )
        val result = computeSeriesToFinish(lib)
        assertEquals(listOf("Metà", "Mai aperta"), result.map { it.series.title })
        assertEquals(listOf(2, 1), result.map { it.unreadCount })
    }

    @Test
    fun toFinish_emptyLibrary_returnsEmpty() {
        assertTrue(computeSeriesToFinish(emptyList()).isEmpty())
    }

    // --- historyDayLabel / formatStatNumber ---

    @Test
    fun dayLabel_todayYesterdayAndDate() {
        val zone = ZoneId.of("Europe/Rome")
        val now = 1_770_000_000_000L // un istante fisso
        assertEquals("Oggi", historyDayLabel(now - 60_000L, now, zone))
        assertEquals("Ieri", historyDayLabel(now - 24L * 60 * 60 * 1000, now, zone))
        val old = historyDayLabel(now - 10L * 24 * 60 * 60 * 1000, now, zone)
        assertTrue(old != "Oggi" && old != "Ieri" && old.isNotBlank())
    }

    @Test
    fun formatStatNumber_usesItalianGrouping() {
        assertEquals("4.820", formatStatNumber(4820))
        assertEquals("12", formatStatNumber(12))
    }
}
