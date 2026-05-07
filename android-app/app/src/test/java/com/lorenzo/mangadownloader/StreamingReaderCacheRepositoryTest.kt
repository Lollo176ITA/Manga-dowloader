package com.lorenzo.mangadownloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingReaderCacheRepositoryTest {

    @Test
    fun getCachedChapter_returnsCompleteCacheAndUpdatesLastAccess() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            pageDownloader = { _, _ -> "unused".toByteArray() },
            nowMillis = { now },
        )
        val key = chapterKey("1")
        now = 10L
        repository.saveCompleteChapter(
            key = key,
            title = "Capitolo 1",
            pageUrls = listOf("https://example.test/1.jpg"),
            referer = "https://example.test/chapter-1",
            pageBytes = listOf("page-1".toByteArray()),
        )

        now = 25L
        val cached = repository.getCachedChapter(key)

        assertEquals("Capitolo 1", cached?.title)
        assertEquals(1, cached?.pages?.size)
        assertEquals(25L, StreamingReaderCacheMetadata.read(File(root, key.directoryName()))?.lastAccessAtMs)
    }

    @Test
    fun getCachedChapter_deletesIncompleteCache() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            pageDownloader = { _, _ -> "unused".toByteArray() },
        )
        val key = chapterKey("incomplete")
        val directory = File(root, key.directoryName()).apply { mkdirs() }
        StreamingReaderCacheMetadata.write(
            directory = directory,
            metadata = StreamingReaderCacheMetadata(
                title = "Incomplete",
                pageUrls = listOf("https://example.test/missing.jpg"),
                pages = listOf("001.jpg"),
                referer = "https://example.test/chapter",
                lastAccessAtMs = 1L,
            ),
        )

        val cached = repository.getCachedChapter(key)

        assertEquals(null, cached)
        assertFalse(directory.exists())
    }

    @Test
    fun cacheCompleteChapter_keepsOnlySixMostRecentlyAccessedChapters() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            pageDownloader = { url, _ -> url.toByteArray() },
            nowMillis = { now },
        )

        (1..7).forEach { chapter ->
            now = chapter.toLong()
            repository.cacheCompleteChapter(
                key = chapterKey(chapter.toString()),
                title = "Capitolo $chapter",
                pageUrls = listOf("https://example.test/$chapter.jpg"),
                referer = "https://example.test/chapter-$chapter",
            )
        }

        val cachedDirectories = root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()

        assertFalse(chapterKey("1").directoryName() in cachedDirectories)
        assertEquals(6, cachedDirectories.size)
        (2..7).forEach { chapter ->
            assertTrue(chapterKey(chapter.toString()).directoryName() in cachedDirectories)
        }
    }

    private var now: Long = 1L

    private fun chapterKey(chapter: String): StreamingReaderCacheKey {
        return StreamingReaderCacheKey(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = "https://example.test/manga",
            chapterUrl = "https://example.test/chapter-$chapter",
        )
    }

    private fun createTempDirectory(): File {
        return Files.createTempDirectory("streaming-reader-cache-test").toFile().apply {
            deleteOnExit()
        }
    }
}
