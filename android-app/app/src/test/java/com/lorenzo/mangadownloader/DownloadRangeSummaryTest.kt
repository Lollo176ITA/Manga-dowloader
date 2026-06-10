package com.lorenzo.mangadownloader

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/** Conteggio del riepilogo del dialog di range: selezionati e già scaricati (da saltare). */
class DownloadRangeSummaryTest {

    private fun chapter(n: Int): ChapterEntry = ChapterEntry(
        numberText = n.toString(),
        numberValue = BigDecimal(n),
        url = "https://mangapill.com/chapters/$n/test-$n",
        slug = "test-$n",
    )

    private val chapters = (1..5).map(::chapter)

    @Test
    fun countsSelectedAndAlreadyDownloadedInRange() {
        // Capitoli 1 e 3 già scaricati (per stableChapterId).
        val downloaded = setOf(
            DownloadStorage.stableChapterId(chapters[0]),
            DownloadStorage.stableChapterId(chapters[2]),
        )

        val summary = downloadRangeSummary(
            chapters = chapters,
            startUrl = chapters[0].url,
            endUrl = chapters[3].url, // range 1..4
            downloadedChapterKeys = downloaded,
        )

        assertEquals(4, summary.selectedCount)
        assertEquals(2, summary.alreadyDownloadedCount)
    }

    @Test
    fun singleChapterRange() {
        val summary = downloadRangeSummary(
            chapters = chapters,
            startUrl = chapters[2].url,
            endUrl = chapters[2].url,
            downloadedChapterKeys = emptySet(),
        )
        assertEquals(1, summary.selectedCount)
        assertEquals(0, summary.alreadyDownloadedCount)
    }

    @Test
    fun allAlreadyDownloaded_leavesNothingToDownload() {
        val downloaded = chapters.map { DownloadStorage.stableChapterId(it) }.toSet()
        val summary = downloadRangeSummary(chapters, chapters.first().url, chapters.last().url, downloaded)
        assertEquals(5, summary.selectedCount)
        assertEquals(5, summary.alreadyDownloadedCount)
    }

    @Test
    fun invertedOrUnknownRange_isEmpty() {
        // end prima di start
        assertEquals(
            DownloadRangeSummary(0, 0),
            downloadRangeSummary(chapters, chapters[3].url, chapters[1].url, emptySet()),
        )
        // url inesistente
        assertEquals(
            DownloadRangeSummary(0, 0),
            downloadRangeSummary(chapters, "https://x/none", chapters[1].url, emptySet()),
        )
    }
}
