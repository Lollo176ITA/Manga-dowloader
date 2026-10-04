package com.lorenzo.mangadownloader.data.library

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Compatibilità con il formato dei .cbz già sui dispositivi (java.util.zip, Deflate livello 0) e
 * con i lettori esterni (java.util.zip, che rifiuta STORED senza dimensioni nell'header locale).
 */
class ChapterArchiveJvmCompatTest {

    private val dir: File = Files.createTempDirectory("chapter-archive").toFile()
    private val fs = FileSystem.SYSTEM
    private val pages = List(4) { i -> "%03d.jpg".format(i + 1) to Random(i).nextBytes(50_000 * (i + 1)) }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun legacyArchivesWrittenWithJavaZipAreStillReadable() {
        val legacy = File(dir, "legacy.cbz")
        ZipOutputStream(legacy.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            pages.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        val extracted = ChapterArchive.extractPages(fs, legacy.toOkioPath(), File(dir, "out").toOkioPath())

        assertEquals(pages.map { it.first }, extracted.map { it.name })
        pages.zip(extracted).forEach { (page, path) ->
            assertContentEquals(page.second, fs.read(path) { readByteArray() })
        }
    }

    @Test
    fun newArchivesAreReadableByJavaZipStreamsAndZipFile() {
        val sources = pages.map { (name, bytes) -> File(dir, name).apply { writeBytes(bytes) }.toOkioPath() }
        val target = File(dir, "new.cbz")

        ChapterArchive.write(fs, target.toOkioPath(), sources)

        ZipInputStream(target.inputStream().buffered()).use { zip ->
            pages.forEach { (name, bytes) ->
                val entry = zip.nextEntry!!
                assertEquals(name, entry.name)
                assertContentEquals(bytes, zip.readBytes())
            }
            assertEquals(null, zip.nextEntry)
        }
        ZipFile(target).use { zip ->
            assertEquals(pages.map { it.first }, zip.entries().toList().map { it.name })
            pages.forEach { (name, bytes) ->
                val entry = zip.getEntry(name)
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(CRC32().apply { update(bytes) }.value, entry.crc)
            }
        }
    }

    @Test
    fun crc32MatchesJavaUtilZip() {
        repeat(50) { seed ->
            val bytes = Random(seed).nextBytes(Random(seed).nextInt(0, 5_000))
            assertEquals(CRC32().apply { update(bytes) }.value, Crc32.of(bytes))
        }
    }
}
