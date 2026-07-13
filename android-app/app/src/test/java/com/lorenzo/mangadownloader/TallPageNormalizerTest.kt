package com.lorenzo.mangadownloader

import android.graphics.BitmapFactory
import android.graphics.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TallPageNormalizerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun ranges_coverEveryRowExactlyOnce() {
        val ranges = tallPageNormalizationRanges(imageHeight = 10_001, chunkHeight = 2_048)

        assertEquals(0, ranges.first().first)
        assertEquals(10_000, ranges.last().last)
        assertTrue(ranges.all { it.count() <= 2_048 })
        ranges.zipWithNext { current, next ->
            assertEquals(current.last + 1, next.first)
        }
    }

    @Test
    fun ranges_mergeTinyTrailingSliverIntoPreviousChunk() {
        // 4097 = 2×2048 + 1: lo sliver da 1 px non diventa una fascia autonoma.
        val ranges = tallPageNormalizationRanges(imageHeight = 4_097, chunkHeight = 2_048)

        assertEquals(listOf(0..2_047, 2_048..4_096), ranges)
    }

    @Test
    fun ranges_keepTrailingChunkAtMergeThreshold() {
        // Resto pari a chunkHeight / 8 (256): abbastanza alto da restare una fascia propria.
        val ranges = tallPageNormalizationRanges(imageHeight = 4_352, chunkHeight = 2_048)

        assertEquals(listOf(0..2_047, 2_048..4_095, 4_096..4_351), ranges)
    }

    @Test
    fun partNames_areDeterministicAndLexicallyOrdered() {
        val names = (0 until 12).map { index ->
            tallPageNormalizationPartFileName("007", index, partCount = 12, extension = "png")
        }

        assertEquals("007__part_0001.png", names.first())
        assertEquals("007__part_0012.png", names.last())
        assertEquals(names, names.sorted())
    }

    @Test
    fun imageBelowThreshold_isReturnedWithoutCopyOrReencoding() {
        val input = createStripImage("short.png", width = 8, height = 12)
        val output = File(temporaryFolder.root, "parts")

        val result = TallPageNormalizer.normalize(
            source = input,
            outputDirectory = output,
            outputBaseName = "001",
            minHeightPx = 13,
            chunkHeightPx = 4,
        )

        assertFalse(result.wasSplit)
        assertEquals(8, result.originalWidth)
        assertEquals(12, result.originalHeight)
        assertEquals(1, result.files.size)
        assertSame(input, result.files.single())
        assertFalse(output.exists())
    }

    @Test
    fun tallImage_isWrittenAsFullResolutionArgbLosslessParts() {
        val input = createStripImage("tall.png", width = 8, height = 10)
        val output = temporaryFolder.newFolder("parts")

        val result = TallPageNormalizer.normalize(
            source = input,
            outputDirectory = output,
            outputBaseName = "003",
            minHeightPx = 5,
            chunkHeightPx = 4,
        )

        assertTrue(result.wasSplit)
        assertEquals(8, result.originalWidth)
        assertEquals(10, result.originalHeight)
        assertEquals(
            listOf(
                "003__part_0001.png",
                "003__part_0002.png",
                "003__part_0003.png",
            ),
            result.files.map(File::getName),
        )
        assertEquals(listOf(4, 4, 2), result.files.map(::decodeHeight))
        assertTrue(result.files.all { decodeWidth(it) == 8 })
        assertTrue(output.listFiles().orEmpty().none { it.name.startsWith(".003-") })
    }

    @Test
    fun tallImageWithTinySliver_mergesSliverIntoLastPart() {
        // 33 = 2×16 + 1: lo sliver (sotto 16/8 = 2 px) finisce nell'ultima fascia.
        val input = createStripImage("sliver.png", width = 4, height = 33)
        val output = temporaryFolder.newFolder("sliver-parts")

        val result = TallPageNormalizer.normalize(
            source = input,
            outputDirectory = output,
            outputBaseName = "004",
            minHeightPx = 5,
            chunkHeightPx = 16,
        )

        assertTrue(result.wasSplit)
        assertEquals(listOf(16, 17), result.files.map(::decodeHeight))
    }

    @Test
    fun imageAboveMaxNormalizationHeight_isReturnedUntouched() {
        // Un pixel oltre il tetto di sicurezza (64 fasce da 2048): niente split,
        // il file resta l'originale come per le pagine sotto soglia.
        val input = createStripImage(
            name = "bomb.png",
            width = 2,
            height = TallPageNormalizationMaxHeightPx + 1,
        )
        val output = File(temporaryFolder.root, "bomb-parts")

        val result = TallPageNormalizer.normalize(
            source = input,
            outputDirectory = output,
            outputBaseName = "008",
        )

        assertFalse(result.wasSplit)
        assertSame(input, result.files.single())
        assertEquals(TallPageNormalizationMaxHeightPx + 1, result.originalHeight)
        assertFalse(output.exists())
    }

    @Test
    fun successfulRetry_replacesAllPreviousPartsAndRemovesStaleOnes() {
        val input = createStripImage("retry.png", width = 8, height = 10)
        val output = temporaryFolder.newFolder("retry-parts")
        File(output, "005__part_0001.png").writeText("old")
        File(output, "005__part_0004.png").writeText("stale")

        val result = TallPageNormalizer.normalize(
            source = input,
            outputDirectory = output,
            outputBaseName = "005",
            minHeightPx = 5,
            chunkHeightPx = 4,
        )

        assertEquals(3, result.files.size)
        assertFalse(File(output, "005__part_0004.png").exists())
        assertEquals(4, decodeHeight(File(output, "005__part_0001.png")))
    }

    @Test(expected = IOException::class)
    fun corruptInput_isRejectedWithoutLeavingTemporaryFiles() {
        val input = temporaryFolder.newFile("broken.png").apply { writeText("not an image") }
        val output = temporaryFolder.newFolder("broken-parts")

        try {
            TallPageNormalizer.normalize(input, output, "009")
        } finally {
            assertTrue(output.listFiles().orEmpty().isEmpty())
        }
    }

    private fun createStripImage(name: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        repeat(height) { y ->
            val color = when {
                y < 4 -> Color.RED
                y < 8 -> Color.GREEN
                else -> Color.BLUE
            }
            repeat(width) { x -> image.setRGB(x, y, color) }
        }
        val file = File(temporaryFolder.root, name)
        assertTrue(ImageIO.write(image, "png", file))
        return file
    }

    private fun decodeHeight(file: File): Int =
        BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            outHeight
        }

    private fun decodeWidth(file: File): Int =
        BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            outWidth
        }
}
