package com.lorenzo.mangadownloader

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
            fetchPageToFile = { _, _, target -> target.writeBytes("page-1".toByteArray()) },
            nowMillis = { now },
        )
        val key = chapterKey("1")
        now = 10L
        runBlocking {
            repository.cacheCompleteChapter(
                key = key,
                title = "Capitolo 1",
                pageUrls = listOf("https://example.test/1.jpg"),
                referer = "https://example.test/chapter-1",
            )
        }

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
            fetchPageToFile = { _, _, _ -> },
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
    fun getCachedChapter_deletesCacheWithEmptyPageFile() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            fetchPageToFile = { _, _, _ -> },
        )
        val key = chapterKey("empty-page")
        val directory = File(root, key.directoryName()).apply { mkdirs() }
        File(directory, "001.jpg").writeBytes(ByteArray(0))
        StreamingReaderCacheMetadata.write(
            directory = directory,
            metadata = StreamingReaderCacheMetadata(
                title = "Pagina vuota",
                pageUrls = listOf("https://example.test/1.jpg"),
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
            fetchPageToFile = { url, _, target -> target.writeBytes(url.toByteArray()) },
            nowMillis = { now },
        )

        (1..7).forEach { chapter ->
            now = chapter.toLong()
            runBlocking {
                repository.cacheCompleteChapter(
                    key = chapterKey(chapter.toString()),
                    title = "Capitolo $chapter",
                    pageUrls = listOf("https://example.test/$chapter.jpg"),
                    referer = "https://example.test/chapter-$chapter",
                )
            }
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

    @Test
    fun cacheCompleteChapter_writesEveryPageInOrder() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            // Il contenuto di ogni pagina è il suo URL: verifica che l'ordine sia preservato
            // anche con download in parallelo.
            fetchPageToFile = { url, _, target -> target.writeBytes(url.toByteArray()) },
        )
        val key = chapterKey("multi")
        val pageUrls = (1..5).map { "https://example.test/p$it.png" }

        val cached = runBlocking {
            repository.cacheCompleteChapter(
                key = key,
                title = "Capitolo multi",
                pageUrls = pageUrls,
                referer = "https://example.test/chapter",
            )
        }

        assertEquals(5, cached.pages.size)
        cached.pages.forEachIndexed { index, file ->
            assertEquals("${(index + 1).toString().padStart(3, '0')}.png", file.name)
            assertEquals(pageUrls[index], file.readText())
        }
    }

    @Test
    fun cacheCompleteChapter_failsAndRemovesDirectoryWhenAPageFails() {
        val root = createTempDirectory()
        val repository = StreamingReaderCacheRepository(
            cacheRoot = root,
            fetchPageToFile = { url, _, target ->
                if (url.endsWith("2.jpg")) throw java.io.IOException("boom")
                target.writeBytes(url.toByteArray())
            },
        )
        val key = chapterKey("partial")

        var thrown = false
        try {
            runBlocking {
                repository.cacheCompleteChapter(
                    key = key,
                    title = "Capitolo",
                    pageUrls = listOf("https://example.test/1.jpg", "https://example.test/2.jpg"),
                    referer = "https://example.test/chapter",
                )
            }
        } catch (_: Exception) {
            thrown = true
        }

        assertTrue(thrown)
        // Niente cache parziale: alla riapertura non verrebbe scambiata per completa.
        assertFalse(File(root, key.directoryName()).exists())
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
