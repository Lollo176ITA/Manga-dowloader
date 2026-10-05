package com.lorenzo.mangadownloader.data.download

import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.data.library.ChapterArchive
import com.lorenzo.mangadownloader.data.model.*
import com.lorenzo.mangadownloader.data.sources.*
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class BackgroundChapterDownloaderTest {
    private val fs = FakeFileSystem()
    private val root = "/library".toPath()
    private val staging = "/transfers/chapter".toPath()
    private val source = Source()
    private fun downloader() = BackgroundChapterDownloader(root, { _, _ -> source }, fs) { file, _, _ -> listOf(file) }

    @Test
    fun interruptedPlanningResumesFromCheckpointWithoutRefetchingPreparedChapters() = runTest {
        var checkpoint: BackgroundDownloadManifest? = null
        val request = ChapterDownloadRequest("https://example.test/c/1", "https://example.test/c/2")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            downloader().prepare(request, onCheckpoint = { manifest ->
                checkpoint = manifest
                if (manifest.chapters.count { it.prepared } == 1) throw kotlinx.coroutines.CancellationException("suspended")
            })
        }
        assertEquals(listOf("https://example.test/c/1"), source.pageRequests)
        val resumed = downloader().prepare(request, previous = checkpoint)
        assertEquals(1, source.plans)
        assertEquals(listOf("https://example.test/c/1", "https://example.test/c/2"), source.pageRequests)
        assertTrue(resumed.chapters.all { it.prepared })
    }

    @Test
    fun preparesEveryMissingChapterBeforeTransferringAndSkipsExistingArchives() = runTest {
        fs.createDirectories(root / "Serie")
        fs.write(root / "Serie/chapter_001.cbz") { writeUtf8("existing") }
        val manifest = downloader().prepare(ChapterDownloadRequest("https://example.test/c/1", "https://example.test/c/2"))
        assertEquals(listOf("https://example.test/c/2"), source.pageRequests)
        assertTrue(source.prepared)
        assertEquals("Serie", manifest.directory)
        assertTrue(manifest.chapters[0].alreadyPresent)
        assertEquals(listOf("001.jpg", "002.png"), manifest.chapters[1].pages.map { it.name })
        assertEquals(listOf("https://example.test/2/a.jpg", "https://example.test/2/b.png?x=1"), manifest.chapters[1].pages.map { it.url })
        assertEquals(manifest, BackgroundDownloadManifest.decode(manifest.encode()))
    }

    @Test
    fun finalizesInPageOrderAndRetryDoesNotOverwriteCompletedCbz() = runTest {
        val manifest = downloader().prepare(ChapterDownloadRequest("https://example.test/c/1"))
        fs.createDirectories(staging)
        fs.write(staging / "001.jpg") { writeUtf8("first page") }
        fs.write(staging / "002.png") { writeUtf8("second page") }
        assertEquals(DownloadResult.DOWNLOADED, downloader().finalizeChapter(manifest, 0, staging))
        val archive = root / "Serie/chapter_001.cbz"
        val extracted = ChapterArchive.extractPages(fs, archive, "/read".toPath())
        assertEquals(listOf("first page", "second page"), extracted.map { fs.read(it) { readUtf8() } })
        val bytes = fs.read(archive) { readByteString() }
        fs.deleteRecursively(staging)
        assertEquals(DownloadResult.SKIPPED_EXISTING, downloader().finalizeChapter(manifest, 0, staging))
        assertEquals(bytes, fs.read(archive) { readByteString() })
    }

    @Test
    fun missingOrEmptyPageNeverBecomesACompletedArchive() = runTest {
        val manifest = downloader().prepare(ChapterDownloadRequest("https://example.test/c/1"))
        fs.createDirectories(staging)
        fs.write(staging / "001.jpg") { writeUtf8("first") }
        assertFailsWith<okio.IOException> { downloader().finalizeChapter(manifest, 0, staging) }
        assertFalse(fs.exists(root / "Serie/chapter_001.cbz"))
        fs.write(staging / "002.png") {}
        assertFailsWith<okio.IOException> { downloader().finalizeChapter(manifest, 0, staging) }
        assertFalse(fs.exists(root / "Serie/chapter_001.cbz"))
        assertFalse(fs.exists(root / "Serie/chapter_001.cbz.part"))
    }

    @Test
    fun rejectsManifestPathsOutsideLibraryOrTransferDirectory() = runTest {
        val manifest = downloader().prepare(ChapterDownloadRequest("https://example.test/c/1"))
        assertFailsWith<IllegalArgumentException> { downloader().finalizeChapter(manifest.copy(directory = "../other"), 0, staging) }
        assertFailsWith<IllegalArgumentException> {
            downloader().finalizeChapter(manifest.copy(chapters = listOf(manifest.chapters[0].copy(fileName = "../other.cbz"))), 0, staging)
        }
    }

    private class Source : MangaSource {
        var prepared = false
        var plans = 0
        val pageRequests = mutableListOf<String>()
        override val descriptor = MangaSourceCatalog.descriptors.first()
        override fun canHandleUrl(url: String) = true
        override suspend fun searchManga(query: String): List<MangaSearchResult> = error("unused")
        override suspend fun fetchMangaDetails(mangaUrl: String): MangaDetails = error("unused")
        override suspend fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String?): DownloadPlan {
            plans++
            val chapters = (1..if(lastChapterUrl == null) 1 else 2).map {
                ChapterEntry("$it", "$it".toChapterNumberOrNull()!!, "https://example.test/c/$it", "c$it")
            }
            return DownloadPlan(descriptor.id, "Serie", "https://example.test/manga", null, "/library/Serie".toPath(), chapters, 2, "1", "2")
        }
        override suspend fun prepareSeriesStorage(plan: DownloadPlan) { prepared = true }
        override suspend fun fetchChapterPageImageUrls(chapterUrl: String): List<String> {
            pageRequests += chapterUrl
            val n = chapterUrl.substringAfterLast('/')
            return listOf("https://example.test/$n/a.jpg", "https://example.test/$n/b.png?x=1")
        }
        override suspend fun downloadChapterAsCbz(chapter: ChapterEntry, outputDir: Path, pageConcurrency: Int, onProcessingProgress: suspend (Int, Int) -> Unit, onPageProgress: suspend (Int, Int) -> Unit): DownloadResult = error("native transfers only")
    }
}
