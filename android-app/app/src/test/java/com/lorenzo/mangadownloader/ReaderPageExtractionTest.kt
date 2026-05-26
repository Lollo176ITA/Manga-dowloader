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

    private fun chapterWithCbz(cbzBytes: ByteArray): DownloadedChapter {
        val root = DownloadStorage.libraryRoot(application)
        val seriesDir = File(root, "TestSeries").apply { mkdirs() }
        val cbz = File(seriesDir, "chapter_001.cbz")
        cbz.writeBytes(cbzBytes)
        return DownloadedChapter(
            title = "Capitolo 1",
            numberText = "1",
            numberValue = null,
            volumeText = null,
            labelPrefix = "Capitolo",
            file = cbz,
            relativePath = DownloadStorage.relativePath(root, cbz),
            chapterId = "number:1",
            isRead = false,
            readerPageIndex = null,
            readerPageCount = null,
        )
    }
}
