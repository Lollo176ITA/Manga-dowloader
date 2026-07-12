package com.lorenzo.mangadownloader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderPageExtractionTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        File(application.cacheDir, "reader-pages").deleteRecursively()
    }

    @Test
    fun validCbz_extractsPagesInOrder() {
        val repo = LibraryRepository(application)
        val chapter = chapterWithCbz(zipBytes("a.jpg" to byteArrayOf(1, 2, 3), "b.jpg" to byteArrayOf(4, 5)))

        val pages = runBlocking { repo.extractReaderPages(chapter) }

        assertEquals(2, pages.size)
        assertEquals("001.jpg", pages[0].name)
        assertEquals("002.jpg", pages[1].name)
    }

    @Test
    fun corruptCbz_throwsAndLeavesNoPartialCache() {
        val repo = LibraryRepository(application)
        // Dati casuali (incomprimibili) così lo zip è grande; troncato dentro al primo
        // entry ⇒ la lettura fallisce a estrazione iniziata.
        val full = zipBytes("a.jpg" to randomBytes(3000), "b.jpg" to randomBytes(3000))
        val truncated = full.copyOfRange(0, full.size / 3)
        val chapter = chapterWithCbz(truncated)

        var thrown = false
        try {
            runBlocking { repo.extractReaderPages(chapter) }
        } catch (_: IOException) {
            thrown = true
        }

        assertEquals(true, thrown)
        val cacheDir = File(
            File(application.cacheDir, "reader-pages"),
            DownloadStorage.readerCacheDirectoryName(chapter.relativePath),
        )
        // Nessuna cache parziale: alla riapertura non verrebbe scambiata per completa.
        assertFalse(cacheDir.exists())
    }

    @Test
    fun emptyCachedPage_isReextractedFromCbz() {
        val repo = LibraryRepository(application)
        val chapter = chapterWithCbz(zipBytes("a.jpg" to byteArrayOf(1, 2, 3), "b.jpg" to byteArrayOf(4, 5)))
        val firstPages = runBlocking { repo.extractReaderPages(chapter) }
        // Simula una pagina persa (scrittura troncata, cache ripulita a metà).
        firstPages[0].writeBytes(ByteArray(0))

        val pages = runBlocking { repo.extractReaderPages(chapter) }

        assertEquals(2, pages.size)
        assertTrue(pages.all { it.length() > 0L })
        assertEquals(3L, pages[0].length())
    }

    @Test
    fun extraction_evictsLeastRecentlyUsedChaptersBeyondLimit() {
        val repo = LibraryRepository(application)
        val cacheRoot = File(application.cacheDir, "reader-pages")
        // Deve combaciare con MAX_EXTRACTED_READER_CHAPTERS di LibraryRepository.
        val limit = 10

        // Riempie la cache fino al limite, con timestamp crescenti espliciti: lastModified
        // può avere granularità di 1 secondo e senza l'ordine LRU sarebbe ambiguo.
        val chapters = (1..limit).map { index ->
            chapterWithCbz(zipBytes("p.jpg" to byteArrayOf(1)), index = index)
        }
        chapters.forEachIndexed { position, chapter ->
            runBlocking { repo.extractReaderPages(chapter) }
            cacheDirFor(chapter).setLastModified(1_000L * (position + 1))
        }

        // Il capitolo oltre il limite fa cadere il meno usato di recente (il primo).
        val newest = chapterWithCbz(zipBytes("p.jpg" to byteArrayOf(1)), index = limit + 1)
        runBlocking { repo.extractReaderPages(newest) }

        assertFalse(cacheDirFor(chapters.first()).exists())
        assertTrue(cacheDirFor(chapters.last()).exists())
        assertTrue(cacheDirFor(newest).exists())
        assertEquals(limit, cacheRoot.listFiles()?.count { it.isDirectory })
    }

    private fun cacheDirFor(chapter: DownloadedChapter): File = File(
        File(application.cacheDir, "reader-pages"),
        DownloadStorage.readerCacheDirectoryName(chapter.relativePath),
    )

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { kotlin.random.Random(1).nextBytes(it) }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun chapterWithCbz(cbzBytes: ByteArray, index: Int = 1): DownloadedChapter {
        val root = DownloadStorage.libraryRoot(application)
        val seriesDir = File(root, "TestSeries").apply { mkdirs() }
        val cbz = File(seriesDir, "chapter_${index.toString().padStart(3, '0')}.cbz")
        cbz.writeBytes(cbzBytes)
        return DownloadedChapter(
            title = "Capitolo $index",
            numberText = "$index",
            numberValue = null,
            volumeText = null,
            labelPrefix = "Capitolo",
            file = cbz,
            relativePath = DownloadStorage.relativePath(root, cbz),
            chapterId = "number:$index",
            isRead = false,
            readerPageIndex = null,
            readerPageCount = null,
        )
    }
}
