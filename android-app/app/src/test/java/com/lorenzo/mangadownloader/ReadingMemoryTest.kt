package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core puro della memoria di lettura: merge monotono, seed dalla libreria, reidratazione. */
class ReadingMemoryTest {

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
        readChapterIds = chapters.filter { it.isRead }.map { it.chapterId }.toSet(),
    )

    private fun record(
        pagesRead: Int = 0,
        pageCount: Int? = null,
        isRead: Boolean = false,
        lastReadAtMillis: Long = 0L,
    ) = ReadChapterMemory(
        seriesKey = "s",
        seriesTitle = "S",
        chapterLabel = "Capitolo 1",
        pagesRead = pagesRead,
        pageCount = pageCount,
        isRead = isRead,
        lastReadAtMillis = lastReadAtMillis,
    )

    // --- mergedWith ---

    @Test
    fun merge_isMonotone_neverRegresses() {
        val newer = record(pagesRead = 3, pageCount = 10, isRead = false, lastReadAtMillis = 1_000L)
        val older = record(pagesRead = 10, pageCount = 10, isRead = true, lastReadAtMillis = 5_000L)
        val merged = older.mergedWith(newer)
        assertEquals(10, merged.pagesRead)
        assertTrue(merged.isRead)
        assertEquals(5_000L, merged.lastReadAtMillis)
    }

    @Test
    fun merge_advancesWithNewProgress() {
        val merged = record(pagesRead = 3, lastReadAtMillis = 1_000L)
            .mergedWith(record(pagesRead = 7, pageCount = 12, lastReadAtMillis = 2_000L))
        assertEquals(7, merged.pagesRead)
        assertEquals(12, merged.pageCount)
        assertEquals(2_000L, merged.lastReadAtMillis)
    }

    // --- seedReadingMemory ---

    @Test
    fun seed_importsProgressAndSkipsUntouchedChapters() {
        val lib = listOf(
            series("A", listOf(
                chapter("1", isRead = true, readerPageCount = 20, lastReadAtMillis = 1_000L, dir = "a"),
                chapter("2", readerPageIndex = 4, readerPageCount = 30, dir = "a"),
                chapter("3", dir = "a"), // mai aperto: non entra
            )),
        )
        val memory = seedReadingMemory(emptyMap(), lib)
        assertEquals(setOf("a/1.cbz", "a/2.cbz"), memory.keys)
        assertEquals(20, memory["a/1.cbz"]?.pagesRead)
        assertEquals("A", memory["a/1.cbz"]?.seriesTitle)
        assertEquals(5, memory["a/2.cbz"]?.pagesRead)
        assertFalse(memory["a/2.cbz"]?.isRead == true)
    }

    @Test
    fun seed_isIdempotent_returnsSameInstanceWhenNothingNew() {
        val lib = listOf(series("A", listOf(chapter("1", isRead = true, readerPageCount = 20, dir = "a"))))
        val seeded = seedReadingMemory(emptyMap(), lib)
        assertSame(seeded, seedReadingMemory(seeded, lib))
    }

    @Test
    fun seed_doesNotForgetDeletedChapters() {
        val fullLib = listOf(series("A", listOf(chapter("1", isRead = true, dir = "a"))))
        val memory = seedReadingMemory(emptyMap(), fullLib)
        // La serie sparisce dalla libreria: il seed successivo non cancella nulla.
        assertSame(memory, seedReadingMemory(memory, emptyList()))
    }

    @Test
    fun seed_doesNotResurrectManuallyUnreadChapters() {
        val unreadOverride = mapOf(
            "a/1.cbz" to record(pagesRead = 10, pageCount = 10, isRead = false, lastReadAtMillis = 1_000L),
        )
        val lib = listOf(series("A", listOf(chapter("1", readerPageIndex = 9, readerPageCount = 10, dir = "a"))))
        val seeded = seedReadingMemory(unreadOverride, lib)
        assertFalse(seeded["a/1.cbz"]?.isRead == true)
    }

    // --- withReadingMemoryApplied (reidratazione) ---

    @Test
    fun rehydrate_marksRedownloadedChaptersAsRead() {
        val memory = mapOf(
            "A/1.cbz" to record(pagesRead = 10, pageCount = 10, isRead = true, lastReadAtMillis = 1_000L),
        )
        val redownloaded = series("A", listOf(chapter("1", dir = "A"), chapter("2", dir = "A")))
        val rehydrated = redownloaded.withReadingMemoryApplied(memory)
        assertTrue(rehydrated.chapters.first { it.numberText == "1" }.isRead)
        assertFalse(rehydrated.chapters.first { it.numberText == "2" }.isRead)
        assertTrue("id-1" in rehydrated.readChapterIds)
    }

    @Test
    fun rehydrate_returnsSameInstanceWhenNothingToApply() {
        val s = series("A", listOf(chapter("1", isRead = true, dir = "A")))
        assertSame(s, s.withReadingMemoryApplied(emptyMap()))
    }
}
