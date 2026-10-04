package com.lorenzo.mangadownloader.data.library

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ChapterArchiveTest {

    private val fs = FakeFileSystem()
    private val pages = "/pages".toPath()
    private val archive = "/series/chapter_001.cbz".toPath()

    init {
        fs.createDirectories(pages)
        fs.createDirectories(archive.parent!!)
    }

    private fun page(name: String, bytes: ByteArray) = (pages / name).also { path ->
        fs.write(path) { write(bytes) }
    }

    @Test
    fun writtenArchiveExtractsSamePagesInOrderByteForByte() {
        val first = page("001.jpg", ByteArray(70_000) { (it % 251).toByte() })
        val second = page("002__part_0001.webp", byteArrayOf(1, 2, 3))
        val empty = page("003.png", ByteArray(0))

        ChapterArchive.write(fs, archive, listOf(first, second, empty))
        val out = "/cache/ch1".toPath()
        val extracted = ChapterArchive.extractPages(fs, archive, out)

        assertEquals(listOf("001.jpg", "002__part_0001.webp", "003.png"), extracted.map { it.name })
        assertContentEquals(fs.read(first) { readByteArray() }, fs.read(extracted[0]) { readByteArray() })
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read(extracted[1]) { readByteArray() })
        assertEquals(0L, fs.metadata(extracted[2]).size)
    }

    @Test
    fun pagesWithoutOrderedNamesAreRenumberedInArchiveOrder() {
        val b = page("zeta.JPG", byteArrayOf(9))
        val a = page("alpha.png", byteArrayOf(8))

        ChapterArchive.write(fs, archive, listOf(b, a))
        val extracted = ChapterArchive.extractPages(fs, archive, "/cache/x".toPath())

        assertEquals(listOf("001.jpg", "002.png"), extracted.map { it.name })
        assertContentEquals(byteArrayOf(9), fs.read(extracted[0]) { readByteArray() })
    }

    @Test
    fun corruptArchiveFailsWithoutLeavingPages() {
        fs.write(archive) { writeUtf8("non sono uno zip") }
        val out = "/cache/bad".toPath()

        assertFailsWith<IOException> { ChapterArchive.extractPages(fs, archive, out) }
        assertEquals(false, fs.exists(out))
    }

    @Test
    fun crc32MatchesKnownVectors() {
        assertEquals(0L, Crc32.of(ByteArray(0)))
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0x414FA339L, Crc32.of("The quick brown fox jumps over the lazy dog".encodeToByteArray()))
    }
}
