package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTallPageTest {

    @Test
    fun memoryBudget_acceptsOnlyArgbPagesThatFit() {
        val exactBudget = 800L * 16_000L * 4L

        assertTrue(tallReaderPageFitsMemoryBudget(800, 16_000, exactBudget))
        assertFalse(tallReaderPageFitsMemoryBudget(801, 16_000, exactBudget))
        assertFalse(tallReaderPageFitsMemoryBudget(0, 16_000, exactBudget))
    }

    @Test
    fun vyMangaLegacySampling_preservesPreviousWidthPolicy() {
        assertEquals(1, legacyVyMangaTallPageSampleSize(2_048))
        assertEquals(2, legacyVyMangaTallPageSampleSize(3_000))
        assertEquals(4, legacyVyMangaTallPageSampleSize(8_192))
    }

    @Test
    fun memoryFallback_reducesOnlyAsMuchAsNeeded() {
        val fortyMegabytes = 40L * 1024L * 1024L
        val twentyMegabytes = 20L * 1024L * 1024L

        assertEquals(1, memoryConstrainedTallPageSampleSize(1_200, 16_000, fortyMegabytes))
        assertEquals(2, memoryConstrainedTallPageSampleSize(1_200, 16_000, twentyMegabytes))
    }
}
