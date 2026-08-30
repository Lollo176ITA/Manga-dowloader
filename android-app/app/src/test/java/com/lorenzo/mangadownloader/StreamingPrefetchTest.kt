package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decisioni del prefetch del capitolo successivo in streaming: quando parte e a quale
 * capitolo può essere servito. Funzioni pure → test su JVM, senza rete né Robolectric.
 */
class StreamingPrefetchTest {

    private val pages = listOf("https://cdn/1.jpg", "https://cdn/2.jpg")
    private val prefetched = PrefetchedStreamingChapter(
        sourceId = MangaSourceIds.MANGAPILL,
        chapterUrl = "https://mangapill.com/chapters/1/berserk-chapter-1",
        pageUrls = pages,
    )

    @Test
    fun pagesFor_serveLePagineSoloAlCapitoloEsatto() {
        assertEquals(
            pages,
            prefetched.pagesFor(MangaSourceIds.MANGAPILL, "https://mangapill.com/chapters/1/berserk-chapter-1"),
        )
    }

    /**
     * Il caso che questo controllo esiste per impedire: stesso URL di capitolo su una fonte
     * diversa, o capitolo diverso sulla stessa fonte. Servire le pagine anticipate qui
     * significherebbe mostrare le tavole di un altro capitolo.
     */
    @Test
    fun pagesFor_nonServeLePagineAUnAltroCapitoloOFonte() {
        assertNull(
            "Fonte diversa",
            prefetched.pagesFor(MangaSourceIds.VYMANGA, "https://mangapill.com/chapters/1/berserk-chapter-1"),
        )
        assertNull(
            "Capitolo diverso",
            prefetched.pagesFor(MangaSourceIds.MANGAPILL, "https://mangapill.com/chapters/2/berserk-chapter-2"),
        )
        assertNull(
            "Nessun prefetch in corso",
            null.pagesFor(MangaSourceIds.MANGAPILL, "https://mangapill.com/chapters/1/berserk-chapter-1"),
        )
    }

    @Test
    fun isNearChapterEnd_scattaSoloVicinoAllaFine() {
        val pageCount = 20

        // A tre pagine dalla fine (indice 16 su 0..19) e oltre.
        assertTrue(isNearChapterEnd(pageIndex = 16, pageCount = pageCount, triggerPages = 3))
        assertTrue(isNearChapterEnd(pageIndex = 19, pageCount = pageCount, triggerPages = 3))
        // Prima, no: anticipare a inizio capitolo sprecherebbe dati di chi si ferma.
        assertFalse(isNearChapterEnd(pageIndex = 15, pageCount = pageCount, triggerPages = 3))
        assertFalse(isNearChapterEnd(pageIndex = 0, pageCount = pageCount, triggerPages = 3))
    }

    /** Capitolo cortissimo: la soglia non deve impedire del tutto il prefetch. */
    @Test
    fun isNearChapterEnd_capitoloPiuCortoDellaSoglia() {
        assertTrue(isNearChapterEnd(pageIndex = 0, pageCount = 2, triggerPages = 3))
        assertTrue(isNearChapterEnd(pageIndex = 0, pageCount = 1, triggerPages = 3))
    }
}
