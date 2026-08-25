package com.lorenzo.mangadownloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Divisione delle pagine doppie: riconoscimento, ordine delle due metà e — la parte che si
 * rompe in silenzio — la traduzione degli indici di progresso quando l'elenco pagine cambia
 * lunghezza perché la divisione è stata accesa o spenta.
 */
class SpreadPagesTest {

    @Test
    fun `riconosce una facciata doppia e lascia stare le altre`() {
        assertTrue("2000x1400 è una doppia", isSpreadPage(2000, 1400))
        assertFalse("1000x1400 è una facciata sola", isSpreadPage(1000, 1400))
        // Le illustrazioni quasi quadrate non vanno spezzate a metà.
        assertFalse("1400x1300 è quasi quadrata", isSpreadPage(1400, 1300))
        assertFalse(isSpreadPage(0, 0))
    }

    @Test
    fun `la pagina doppia diventa due meta, prima la destra nell ordine manga`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        assertEquals(4, expansion.pages.size)
        val halves = expansion.pages.mapNotNull { (it as? ReaderPage.Local)?.half }
        assertEquals(listOf(PageHalf.RIGHT, PageHalf.LEFT), halves)
    }

    @Test
    fun `nella modalita occidentale si legge prima la meta sinistra`() {
        val expansion = expand(rightFirst = false, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        val halves = expansion.pages.mapNotNull { (it as? ReaderPage.Local)?.half }
        assertEquals(listOf(PageHalf.LEFT, PageHalf.RIGHT), halves)
    }

    @Test
    fun `le due meta hanno chiavi distinte`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        val keys = expansion.pages.map { it.stableKey }
        assertEquals("chiavi duplicate romperebbero pager e lista", keys.size, keys.toSet().size)
    }

    @Test
    fun `senza pagine doppie l elenco resta identico`() {
        val expansion = expand(rightFirst = true, bounds = emptyMap())

        assertEquals(3, expansion.pages.size)
        assertEquals(listOf(0, 1, 2), expansion.originalIndexByPage)
    }

    @Test
    fun `le pagine remote non vengono divise`() {
        val pages = listOf<ReaderPage>(ReaderPage.Remote(url = "https://x/1.jpg", referer = "https://x"))

        val expansion = expandSpreadPages(pages, rightFirst = true) { PageBounds(2000, 1400) }

        assertEquals(pages, expansion.pages)
    }

    @Test
    fun `i frammenti di una striscia webtoon non vengono divisi`() {
        // Una fascia di striscia alta e' larga e bassa come una doppia, ma dividerla
        // spezzerebbe a meta' il flusso verticale.
        val pages = listOf<ReaderPage>(
            ReaderPage.Local(File("/tmp/pagina__part_0001.webp")),
        )

        val expansion = expandSpreadPages(pages, rightFirst = true) { PageBounds(3000, 2048) }

        assertEquals(pages, expansion.pages)
    }

    @Test
    fun `la mappa verso le pagine originali segue la divisione`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        assertEquals(listOf(0, 1, 1, 2), expansion.originalIndexByPage)
        assertEquals(3, expansion.originalCount)
        assertEquals(1, expansion.readerIndexForOriginalPage(1))
        assertEquals(3, expansion.readerIndexForOriginalPage(2))
    }

    @Test
    fun `un progresso salvato con lo stesso numero di pagine si usa cosi com e`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        assertEquals(3, expansion.restoredIndex(savedIndex = 3, savedCount = 4))
    }

    @Test
    fun `un progresso salvato senza divisione viene tradotto`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        // Con la divisione spenta la pagina 2 era l'indice 2; con la divisione è l'indice 3.
        assertEquals(3, expansion.restoredIndex(savedIndex = 2, savedCount = 3))
    }

    @Test
    fun `un progresso fuori scala resta dentro i limiti`() {
        val expansion = expand(rightFirst = true, bounds = mapOf("2.jpg" to PageBounds(2000, 1400)))

        assertEquals(3, expansion.restoredIndex(savedIndex = 99, savedCount = 999))
        assertEquals(0, expansion.restoredIndex(savedIndex = -5, savedCount = null))
    }

    @Test
    fun `l elenco non espanso mappa gli indici uno a uno`() {
        val pages = listOf<ReaderPage>(ReaderPage.Local(File("/tmp/1.jpg")), ReaderPage.Local(File("/tmp/2.jpg")))

        val expansion = unexpandedReaderPages(pages)

        assertEquals(pages, expansion.pages)
        assertEquals(listOf(0, 1), expansion.originalIndexByPage)
        assertEquals(1, expansion.restoredIndex(savedIndex = 1, savedCount = 2))
    }

    @Test
    fun `solo la modalita a pagine occidentale legge prima la meta sinistra`() {
        assertFalse(ReadingMode.PAGED.splitsSpreadRightFirst)
        assertTrue(ReadingMode.PAGED_RTL.splitsSpreadRightFirst)
        assertTrue(ReadingMode.VERTICAL.splitsSpreadRightFirst)
    }

    /** Tre pagine `1.jpg`, `2.jpg`, `3.jpg`; [bounds] assegna le dimensioni per nome file. */
    private fun expand(
        rightFirst: Boolean,
        bounds: Map<String, PageBounds>,
    ): ReaderPageExpansion {
        val pages = listOf("1.jpg", "2.jpg", "3.jpg").map { name ->
            ReaderPage.Local(File("/tmp/$name"))
        }
        return expandSpreadPages(pages, rightFirst = rightFirst) { local ->
            bounds[local.file.name] ?: PageBounds(1000, 1400)
        }
    }
}
