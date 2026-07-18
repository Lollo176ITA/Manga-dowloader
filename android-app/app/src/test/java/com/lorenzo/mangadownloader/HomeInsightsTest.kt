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
        dir: String = "s",
    ) = DownloadedChapter(
        title = "Capitolo $number",
        numberText = number,
        numberValue = number.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File("$number.cbz"),
        relativePath = "$dir/$number.cbz",
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
        assertEquals(HomeStats(0, 0, 0, 0, 0), stats)
    }

    @Test
    fun stats_countsSeriesReadChaptersAndPages() {
        val lib = listOf(
            series("A", listOf(
                chapter("1", isRead = true, readerPageCount = 20, dir = "a"),       // completato: 20 pagine
                chapter("2", readerPageIndex = 4, readerPageCount = 30, dir = "a"), // in corso: 5 pagine
                chapter("3", dir = "a"),                                            // mai aperto: 0
            )),
            series("B", listOf(
                chapter("1", isRead = true, dir = "b"),                             // letto senza pageCount: 0 pagine
            )),
        )
        val stats = computeHomeStats(lib, favoritesCount = 7)
        assertEquals(2, stats.seriesCount)
        assertEquals(2, stats.seriesReadCount)
        assertEquals(2, stats.chaptersRead)
        assertEquals(25, stats.pagesRead)
        assertEquals(7, stats.favoritesCount)
        assertTrue(!stats.isEmpty())
    }

    @Test
    fun stats_survivesLibraryDeletionThroughMemory() {
        val lib = listOf(
            series("A", listOf(chapter("1", isRead = true, readerPageCount = 20, dir = "a"))),
        )
        val memory = seedReadingMemory(emptyMap(), lib)
        // Serie eliminata: capitoli e pagine letti restano, cala solo "Serie in libreria".
        val stats = computeHomeStats(emptyList(), favoritesCount = 0, memory = memory)
        assertEquals(0, stats.seriesCount)
        assertEquals(1, stats.seriesReadCount)
        assertEquals(1, stats.chaptersRead)
        assertEquals(20, stats.pagesRead)
    }

    // --- topReadSeries ---

    @Test
    fun topSeries_ranksByChaptersRead_andSurvivesDeletion() {
        // dir = nome della cartella serie (directory di `series("A")` = File("A")).
        val lib = listOf(
            series("A", listOf(
                chapter("1", isRead = true, dir = "A"),
                chapter("2", isRead = true, dir = "A"),
            )),
        )
        val memory = seedReadingMemory(
            seedReadingMemory(emptyMap(), lib),
            listOf(series("B", listOf(chapter("1", isRead = true, dir = "B")))),
        )
        // "B" è stata eliminata dalla libreria: resta in classifica, senza serie risolta.
        val top = topReadSeries(memory, lib)
        assertEquals(listOf("A", "B"), top.map { it.title })
        assertEquals(listOf(2, 1), top.map { it.chaptersRead })
        assertEquals("A", top[0].series?.title)
        assertEquals(null, top[1].series)
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
                chapter("1", isRead = true, lastReadAtMillis = 1_000L, dir = "a"),
                chapter("2", readerPageIndex = 3, readerPageCount = 10, lastReadAtMillis = 3_000L, dir = "a"),
                chapter("3", dir = "a"), // mai letto: escluso
            )),
            series("B", listOf(chapter("5", isRead = true, lastReadAtMillis = 2_000L, dir = "b"))),
        )
        val history = computeReadingHistory(emptyMap(), lib)
        assertEquals(listOf("2", "5", "1"), history.map { it.chapter?.numberText })
        assertEquals(listOf("A", "B", "A"), history.map { it.series?.title })
    }

    @Test
    fun history_respectsLimit() {
        val lib = listOf(series("A", (1..15).map {
            chapter("$it", isRead = true, lastReadAtMillis = it * 100L)
        }))
        assertEquals(10, computeReadingHistory(emptyMap(), lib, limit = 10).size)
    }

    @Test
    fun history_keepsDeletedChaptersFromMemory() {
        val lib = listOf(
            series("A", listOf(chapter("1", isRead = true, lastReadAtMillis = 1_000L, dir = "a"))),
        )
        val memory = seedReadingMemory(emptyMap(), lib)
        // Libreria svuotata: la lettura resta in cronologia, ma senza capitolo da riaprire.
        val history = computeReadingHistory(memory, emptyList())
        assertEquals(1, history.size)
        assertEquals("A", history.first().memory.seriesTitle)
        assertEquals("Capitolo 1", history.first().memory.chapterLabel)
        assertEquals(null, history.first().chapter)
        assertEquals("Completato", history.first().memory.progressLabel())
    }

    @Test
    fun history_mergesLiveLibraryProgressOverMemory() {
        val stale = mapOf(
            "a/1.cbz" to ReadChapterMemory("a", "A", "Capitolo 1", 2, 10, false, 1_000L),
        )
        val lib = listOf(
            series("A", listOf(
                chapter("1", readerPageIndex = 6, readerPageCount = 10, lastReadAtMillis = 2_000L, dir = "a"),
            )),
        )
        val history = computeReadingHistory(stale, lib)
        assertEquals("pagina 7 di 10", history.first().memory.progressLabel())
        assertEquals(2_000L, history.first().memory.lastReadAtMillis)
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
