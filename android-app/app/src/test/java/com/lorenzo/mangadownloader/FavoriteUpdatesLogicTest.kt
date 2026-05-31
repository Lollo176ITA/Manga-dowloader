package com.lorenzo.mangadownloader

import java.math.BigDecimal
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica pura delle notifiche sui preferiti: mappatura stato, decisione "nuovo capitolo",
 * skip dei conclusi ed estrazione dello stato dall'HTML. Tutto testabile su JVM (niente Android/rete).
 */
class FavoriteUpdatesLogicTest {

    @Test
    fun mangaStatus_mapsItalianAndEnglish() {
        assertEquals(MangaPublicationStatus.ONGOING, mangaStatusFromText("In corso"))
        assertEquals(MangaPublicationStatus.ONGOING, mangaStatusFromText("Ongoing"))
        assertEquals(MangaPublicationStatus.ONGOING, mangaStatusFromText("Publishing"))
        assertEquals(MangaPublicationStatus.ONGOING, mangaStatusFromText("On Hiatus"))
        assertEquals(MangaPublicationStatus.ONGOING, mangaStatusFromText("In pausa"))
        assertEquals(MangaPublicationStatus.COMPLETED, mangaStatusFromText("Completato"))
        assertEquals(MangaPublicationStatus.COMPLETED, mangaStatusFromText("Concluso"))
        assertEquals(MangaPublicationStatus.COMPLETED, mangaStatusFromText("Finished"))
        assertEquals(MangaPublicationStatus.COMPLETED, mangaStatusFromText("Terminato"))
    }

    @Test
    fun mangaStatus_mapsDroppedAndAbandoned() {
        assertEquals(MangaPublicationStatus.DROPPED, mangaStatusFromText("Droppato"))
        assertEquals(MangaPublicationStatus.DROPPED, mangaStatusFromText("Abbandonato"))
        assertEquals(MangaPublicationStatus.DROPPED, mangaStatusFromText("Cancelled"))
        assertEquals(MangaPublicationStatus.DROPPED, mangaStatusFromText("Discontinued"))
    }

    @Test
    fun statusDisplayLabel_italianLabelsOrNull() {
        assertEquals("In corso", MangaPublicationStatus.ONGOING.displayLabel())
        assertEquals("Terminato", MangaPublicationStatus.COMPLETED.displayLabel())
        assertEquals("Abbandonato", MangaPublicationStatus.DROPPED.displayLabel())
        assertNull(MangaPublicationStatus.UNKNOWN.displayLabel())
    }

    @Test
    fun mangaStatus_unknownForBlankOrUnrecognized() {
        assertEquals(MangaPublicationStatus.UNKNOWN, mangaStatusFromText(null))
        assertEquals(MangaPublicationStatus.UNKNOWN, mangaStatusFromText("   "))
        assertEquals(MangaPublicationStatus.UNKNOWN, mangaStatusFromText("boh"))
    }

    @Test
    fun computeUpdate_firstTimeIsBaselineWithoutNotification() {
        val result = computeFavoriteUpdate(
            seen = null,
            latestNumber = BigDecimal("10"),
            latestLabel = "Capitolo 10",
            status = MangaPublicationStatus.ONGOING,
        )
        assertNull(result.newChapterLabel)
        assertEquals("10", result.newState.latestChapterNumber)
        assertEquals(MangaPublicationStatus.ONGOING.name, result.newState.status)
    }

    @Test
    fun computeUpdate_notifiesWhenNewerChapter() {
        val result = computeFavoriteUpdate(
            seen = FavoriteSeenState("10", MangaPublicationStatus.ONGOING.name),
            latestNumber = BigDecimal("11"),
            latestLabel = "Capitolo 11",
            status = MangaPublicationStatus.ONGOING,
        )
        assertEquals("Capitolo 11", result.newChapterLabel)
        assertEquals("11", result.newState.latestChapterNumber)
    }

    @Test
    fun computeUpdate_noNotificationWhenSameOrOlder() {
        val same = computeFavoriteUpdate(
            seen = FavoriteSeenState("11", MangaPublicationStatus.ONGOING.name),
            latestNumber = BigDecimal("11"),
            latestLabel = "Capitolo 11",
            status = MangaPublicationStatus.ONGOING,
        )
        assertNull(same.newChapterLabel)

        val older = computeFavoriteUpdate(
            seen = FavoriteSeenState("11", MangaPublicationStatus.ONGOING.name),
            latestNumber = BigDecimal("10"),
            latestLabel = "Capitolo 10",
            status = MangaPublicationStatus.ONGOING,
        )
        assertNull(older.newChapterLabel)
    }

    @Test
    fun computeUpdate_recordsStatusEvenWithoutNewChapter() {
        val result = computeFavoriteUpdate(
            seen = FavoriteSeenState("11", MangaPublicationStatus.ONGOING.name),
            latestNumber = BigDecimal("11"),
            latestLabel = "Capitolo 11",
            status = MangaPublicationStatus.COMPLETED,
        )
        assertNull(result.newChapterLabel)
        assertEquals(MangaPublicationStatus.COMPLETED.name, result.newState.status)
    }

    @Test
    fun shouldPoll_trueUnlessConcludedOrDropped() {
        assertTrue(shouldPollFavorite(null))
        assertTrue(shouldPollFavorite(FavoriteSeenState("5", MangaPublicationStatus.ONGOING.name)))
        assertTrue(shouldPollFavorite(FavoriteSeenState("5", MangaPublicationStatus.UNKNOWN.name)))
        assertFalse(shouldPollFavorite(FavoriteSeenState("5", MangaPublicationStatus.COMPLETED.name)))
        assertFalse(shouldPollFavorite(FavoriteSeenState("5", MangaPublicationStatus.DROPPED.name)))
    }

    @Test
    fun statusTextNearLabel_readsSiblingValue() {
        val doc = Jsoup.parse(
            """<div class="meta-data-item"><span class="title">Stato</span><a href="#">In corso</a></div>""",
        )
        assertEquals("In corso", statusTextNearLabel(doc, "Stato", "Status"))
    }

    @Test
    fun statusTextNearLabel_readsInlineLabelColonValue() {
        val doc = Jsoup.parse("""<li><b>Stato:</b> Completato</li>""")
        assertEquals("Completato", statusTextNearLabel(doc, "Stato"))
    }

    @Test
    fun statusTextNearLabel_readsValueInsideChildLink() {
        // Pattern "Etichetta: <a>valore</a>" sulla stessa riga — prima non lo prendeva.
        val doc = Jsoup.parse("""<p class="mb-1">Status: <a href="/status/2">Completed</a></p>""")
        assertEquals("Completed", statusTextNearLabel(doc, "Status", "Stato"))
    }

    @Test
    fun statusTextNearLabel_skipsSeparatorSpanBetweenLabelAndValue() {
        // Markup reale VyManga: <span>Status</span><span>:</span><span>Ongoing</span>.
        // Il separatore ":" va saltato, altrimenti si leggeva ":" invece del valore.
        val doc = Jsoup.parse(
            """<p><span class="pre-title">Status</span><span class="space">:</span><span class="text-ongoing">Ongoing</span></p>""",
        )
        assertEquals("Ongoing", statusTextNearLabel(doc, "Status", "Stato"))
    }

    @Test
    fun statusTextNearLabel_nullWhenNoLabel() {
        val doc = Jsoup.parse("""<div><span>Genere</span><a>Azione</a></div>""")
        assertNull(statusTextNearLabel(doc, "Stato", "Status"))
    }
}
