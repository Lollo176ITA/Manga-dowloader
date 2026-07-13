package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spezzettamento delle pagine webtoon a striscia: le fasce devono coprire l'intera
 * immagine senza buchi né sovrapposizioni e senza mai superare l'altezza massima,
 * altrimenti il render a blocchi perderebbe o duplicherebbe righe della pagina.
 */
class ReaderTallPageTest {

    @Test
    fun chunkRanges_coverWholeImageWithoutGapsOrOverlaps() {
        val height = 18000
        val ranges = tallReaderPageChunkRanges(height)

        assertEquals(0, ranges.first().first)
        assertEquals(height - 1, ranges.last().last)
        ranges.zipWithNext { current, next ->
            assertEquals(current.last + 1, next.first)
        }
        assertTrue(ranges.all { it.last - it.first + 1 <= TallReaderPageChunkHeightPx })
    }

    @Test
    fun chunkRanges_exactMultipleOfChunkHeight() {
        val ranges = tallReaderPageChunkRanges(4096, chunkHeight = 2048)

        assertEquals(2, ranges.size)
        assertEquals(0..2047, ranges[0])
        assertEquals(2048..4095, ranges[1])
    }

    @Test
    fun chunkRanges_shorterThanOneChunk_returnsSingleRange() {
        assertEquals(listOf(0..999), tallReaderPageChunkRanges(1000, chunkHeight = 2048))
    }

    @Test
    fun chunkRanges_invalidInput_returnsEmpty() {
        assertEquals(emptyList<IntRange>(), tallReaderPageChunkRanges(0))
        assertEquals(emptyList<IntRange>(), tallReaderPageChunkRanges(-5))
        assertEquals(emptyList<IntRange>(), tallReaderPageChunkRanges(100, chunkHeight = 0))
    }

    @Test
    fun sampleSize_typicalWebtoonWidth_staysFullResolution() {
        assertEquals(1, tallReaderPageSampleSize(800))
        assertEquals(1, tallReaderPageSampleSize(1200))
        assertEquals(1, tallReaderPageSampleSize(2048))
    }

    @Test
    fun sampleSize_wideImages_halveUntilWithinLimit() {
        assertEquals(2, tallReaderPageSampleSize(3000))
        assertEquals(4, tallReaderPageSampleSize(8192))
        assertEquals(1, tallReaderPageSampleSize(0))
    }
}
