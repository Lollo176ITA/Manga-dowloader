package com.lorenzo.mangadownloader.data.download

import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.DownloadPlan
import com.lorenzo.mangadownloader.data.model.DownloadResult
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.toChapterNumberOrNull
import com.lorenzo.mangadownloader.data.sources.MangaSource
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath

class ChapterDownloaderTest {

    private fun chapter(n: Int) = ChapterEntry(
        numberText = "$n",
        numberValue = "$n".toChapterNumberOrNull()!!,
        url = "https://example.test/c/$n",
        slug = "c$n",
    )

    private class FakeSource(
        private val chapters: List<ChapterEntry>,
        private val existing: Set<String> = emptySet(),
    ) : MangaSource {
        var prepared = false
        val downloaded = mutableListOf<String>()

        override val descriptor: MangaSourceDescriptor = MangaSourceCatalog.descriptors.first()

        override fun canHandleUrl(url: String) = true

        override suspend fun searchManga(query: String): List<MangaSearchResult> = error("non usato")

        override suspend fun fetchMangaDetails(mangaUrl: String): MangaDetails = error("non usato")

        override suspend fun fetchChapterPageImageUrls(chapterUrl: String): List<String> = error("non usato")

        override suspend fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String?) = DownloadPlan(
            sourceId = descriptor.id,
            seriesTitle = "Serie",
            mangaUrl = "https://example.test/serie",
            coverUrl = null,
            outputDir = "/dl/Serie".toPath(),
            chapters = chapters,
            totalChapterCount = chapters.size,
            startChapterLabel = chapters.first().displayNumber(),
            endChapterLabel = chapters.last().displayNumber(),
        )

        override suspend fun prepareSeriesStorage(plan: DownloadPlan) {
            prepared = true
        }

        override suspend fun downloadChapterAsCbz(
            chapter: ChapterEntry,
            outputDir: Path,
            pageConcurrency: Int,
            onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
            onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
        ): DownloadResult {
            if (chapter.url in existing) return DownloadResult.SKIPPED_EXISTING
            onPageProgress(1, 2)
            onPageProgress(2, 2)
            downloaded += chapter.url
            return DownloadResult.DOWNLOADED
        }
    }

    @Test
    fun reportsPlanEveryChapterAndSummary() = runTest {
        val source = FakeSource(
            chapters = listOf(chapter(1), chapter(2), chapter(3)),
            existing = setOf("https://example.test/c/2"),
        )
        val statuses = mutableListOf<SeriesDownloadProgress>()

        val summary = ChapterDownloader { _, _ -> source }.download(
            ChapterDownloadRequest(firstUrl = "https://example.test/c/1", lastUrl = "https://example.test/c/3"),
            isStopped = { false },
        ) { statuses += it }

        assertTrue(source.prepared)
        assertEquals(listOf("https://example.test/c/1", "https://example.test/c/3"), source.downloaded.sorted())
        assertEquals("Trovati 3 capitoli da 1 a 3", statuses.first().message)
        assertTrue(statuses.any { it.message == "Capitolo 2 già presente" })
        assertTrue(statuses.any { it.message == "Capitolo 1 completato" })
        assertEquals("Download completato: 3 capitoli", statuses.last().message)
        assertEquals(3, statuses.last().doneChapters)
        assertEquals(3, summary.totalChapters)
        assertEquals("capitoli", summary.unitPlural)
        assertEquals("Serie", summary.seriesTitle)
    }

    @Test
    fun singleChapterPlanSaysFoundOne() = runTest {
        val source = FakeSource(chapters = listOf(chapter(7)))
        val statuses = mutableListOf<String>()

        ChapterDownloader { _, _ -> source }.download(
            ChapterDownloadRequest(firstUrl = "https://example.test/c/7"),
            isStopped = { false },
        ) { statuses += it.message }

        assertEquals("Trovato 1 capitolo: 7", statuses.first())
    }

    @Test
    fun stoppedDownloadStartsNoChapter() = runTest {
        val source = FakeSource(chapters = listOf(chapter(1), chapter(2)))

        assertFailsWith<DownloadStoppedException> {
            ChapterDownloader { _, _ -> source }.download(
                ChapterDownloadRequest(firstUrl = "https://example.test/c/1"),
                isStopped = { true },
            ) { }
        }
        assertEquals(emptyList(), source.downloaded)
    }
}
