package com.lorenzo.mangadownloader

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bersaglio della freccia di navigazione nella lista capitoli: deve portare all'ultimo
 * capitolo letto, non all'ultimo uscito.
 */
class ChapterJumpTargetTest {

    private fun chapter(n: Int, volume: String? = null): ChapterEntry = ChapterEntry(
        numberText = n.toString(),
        numberValue = BigDecimal(n),
        url = "https://mangapill.com/chapters/$n/test-$n",
        slug = "test-$n",
        volumeText = volume,
    )

    private val chapters = (1..5).map { chapter(it) }

    private fun readIdsOf(vararg entries: ChapterEntry): Set<String> =
        entries.map { DownloadStorage.stableChapterId(it) }.toSet()

    @Test
    fun pointsToLastReadChapter() {
        val items = buildChapterListItems(chapters)

        val target = chapterJumpTarget(items, readIdsOf(chapters[0], chapters[2]))

        assertEquals(2, target.index) // capitolo 3
        assertTrue(target.isLastRead)
    }

    @Test
    fun fallsBackToListBottomWhenNothingRead() {
        val items = buildChapterListItems(chapters)

        val target = chapterJumpTarget(items, emptySet())

        assertEquals(items.lastIndex, target.index)
        assertFalse(target.isLastRead)
    }

    @Test
    fun accountsForVolumeHeadersInTheIndex() {
        // Due volumi: ogni cambio volume inserisce una riga intestazione prima del capitolo.
        val volumed = listOf(
            chapter(1, "Volume 1"),
            chapter(2, "Volume 1"),
            chapter(3, "Volume 2"),
            chapter(4, "Volume 2"),
        )
        val items = buildChapterListItems(volumed)

        val target = chapterJumpTarget(items, readIdsOf(volumed[2]))

        // [0] header V1, [1] cap 1, [2] cap 2, [3] header V2, [4] cap 3, [5] cap 4
        assertEquals(4, target.index)
        assertTrue(target.isLastRead)
    }

    @Test
    fun emptyListStaysAtZero() {
        val target = chapterJumpTarget(emptyList(), emptySet())

        assertEquals(0, target.index)
        assertFalse(target.isLastRead)
    }

    @Test
    fun readChapterTrackedByNumberKeyCounts() {
        val items = buildChapterListItems(chapters)
        val byNumber = setOf(
            DownloadStorage.chapterNumberKey(chapters[3].displayNumber(), chapters[3].variantTag),
        )

        val target = chapterJumpTarget(items, byNumber)

        assertEquals(3, target.index) // capitolo 4
        assertTrue(target.isLastRead)
    }
}
