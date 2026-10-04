package com.lorenzo.mangadownloader.ui.reader

import com.lorenzo.mangadownloader.platform.ImageOps
import com.lorenzo.mangadownloader.platform.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class TallPageNormalizerCommonTest {

    private val fs = FakeFileSystem()
    private val dir = "/chapter".toPath().also { fs.createDirectories(it) }
    private val imageOps = FakeImageOps(fs)
    private val normalizer = TallPageNormalizer(imageOps, fs)

    private fun image(name: String, width: Int, height: Int): Path = (dir / name).also {
        fs.write(it) { writeUtf8("img") }
        imageOps.sizes[it] = ImageSize(width, height)
    }

    @Test
    fun shortPagesAreReturnedUntouched() {
        val source = image("001.jpg", 800, 1200)

        val result = normalizer.normalize(source, dir, "001")

        assertEquals(listOf(source), result.files)
        assertFalse(result.wasSplit)
        assertEquals(0, imageOps.writes)
    }

    @Test
    fun tallPagesAreSplitIntoOrderedLosslessStrips() {
        val source = image("002.jpg", 720, 5000)

        val result = normalizer.normalize(source, dir, "002", chunkHeightPx = 2048)

        assertTrue(result.wasSplit)
        assertEquals(720, result.originalWidth)
        assertEquals(5000, result.originalHeight)
        assertEquals(
            listOf("002__part_0001.webp", "002__part_0002.webp", "002__part_0003.webp"),
            result.files.map { it.name },
        )
        assertEquals("0..2047", fs.read(result.files[0]) { readUtf8() })
        assertEquals("4096..4999", fs.read(result.files[2]) { readUtf8() })
        assertEquals(dir, result.files[0].parent)
        assertTrue(fs.list(dir).none { it.name.startsWith(".") })
    }

    @Test
    fun previousPartsAreReplacedAndRestoredWhenEncodingFails() {
        val old = (dir / "003__part_0001.webp").also { fs.write(it) { writeUtf8("old") } }
        val source = image("003.jpg", 720, 5000)
        imageOps.failAtStrip = 1

        assertFailsWith<IOException> { normalizer.normalize(source, dir, "003", chunkHeightPx = 2048) }

        assertEquals("old", fs.read(old) { readUtf8() })
        assertEquals(listOf("003.jpg", "003__part_0001.webp"), fs.list(dir).map { it.name }.sorted())
    }

    @Test
    fun unsafeBaseNamesAndMissingSourcesAreRejected() {
        val source = image("004.jpg", 720, 5000)
        assertFailsWith<IllegalArgumentException> { normalizer.normalize(source, dir, "../x") }
        assertFailsWith<IOException> { normalizer.normalize(dir / "missing.jpg", dir, "005") }
    }

    private class FakeImageOps(private val fs: FakeFileSystem) : ImageOps {
        val sizes = mutableMapOf<Path, ImageSize>()
        var writes = 0
        var failAtStrip = -1

        override val losslessExtension = "webp"

        override fun readSize(path: Path): ImageSize? = sizes[path]

        override fun writeStrips(source: Path, width: Int, rows: List<IntRange>, destinations: List<Path>) {
            rows.zip(destinations).forEachIndexed { index, (range, destination) ->
                if (index == failAtStrip) throw IOException("encoder rotto")
                fs.write(destination) { writeUtf8(range.toString()) }
                writes++
            }
        }
    }
}
