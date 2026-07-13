package com.lorenzo.mangadownloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recupero delle pagine locali rotte: ogni pagina servita dalla cache streaming porta
 * con sé l'URL d'origine ([ReaderPage.Local.remote]) così il reader può riscaricarla
 * se il file su disco è sparito o vuoto, invece di rileggere per sempre lo stesso file.
 */
class ReaderPageRecoveryTest {

    @Test
    fun toReaderPages_attachesRemoteOriginAlignedByIndex() {
        val directory = createTempDirectory()
        val cached = StreamingReaderCachedChapter(
            title = "Capitolo 1",
            pages = listOf(File(directory, "001.jpg"), File(directory, "002.jpg")),
            pageUrls = listOf("https://example.test/1.jpg", "https://example.test/2.jpg"),
            referer = "https://example.test/chapter-1",
        )

        val pages = cached.toReaderPages()

        assertEquals(2, pages.size)
        pages.forEachIndexed { index, page ->
            val local = page as ReaderPage.Local
            assertEquals(cached.pages[index], local.file)
            assertEquals(cached.pageUrls[index], local.remote?.url)
            assertEquals(cached.referer, local.remote?.referer)
        }
    }

    @Test
    fun toReaderPages_withoutUrlForAPage_leavesRemoteNull() {
        val directory = createTempDirectory()
        val cached = StreamingReaderCachedChapter(
            title = "Capitolo",
            pages = listOf(File(directory, "001.jpg"), File(directory, "002.jpg")),
            pageUrls = listOf("https://example.test/1.jpg"),
            referer = "https://example.test/chapter",
        )

        val pages = cached.toReaderPages()

        assertEquals("https://example.test/1.jpg", (pages[0] as ReaderPage.Local).remote?.url)
        assertNull((pages[1] as ReaderPage.Local).remote)
    }

    @Test
    fun isFileBroken_detectsMissingAndEmptyFiles() {
        val directory = createTempDirectory()
        val valid = File(directory, "ok.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val empty = File(directory, "empty.jpg").apply { writeBytes(ByteArray(0)) }
        val missing = File(directory, "missing.jpg")

        assertFalse(ReaderPage.Local(valid).isFileBroken)
        assertTrue(ReaderPage.Local(empty).isFileBroken)
        assertTrue(ReaderPage.Local(missing).isFileBroken)
    }

    private fun createTempDirectory(): File {
        return Files.createTempDirectory("reader-page-recovery-test").toFile().apply {
            deleteOnExit()
        }
    }
}
