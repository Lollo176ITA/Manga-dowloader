package com.lorenzo.mangadownloader

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica pura del feed "Aggiornamenti": append/de-dup/cap, conteggio non visti, mark-all-seen
 * e raggruppamento per giorno. Tutto testabile su JVM (niente Android/rete).
 */
class FavoriteUpdatesFeedLogicTest {

    private fun event(
        key: String = "k1",
        chapter: String = "10",
        ts: Long = 1_000L,
        seen: Boolean = false,
        title: String = "Manga",
    ) = FavoriteUpdateEvent(
        identityKey = key,
        title = title,
        sourceId = "mangapill",
        mangaUrl = "https://mangapill.com/manga/$key",
        chapterLabel = "Capitolo $chapter",
        chapterNumber = chapter,
        timestampMillis = ts,
        seen = seen,
    )

    @Test
    fun appendUpdateEvent_prependsNewestAndKeepsPrior() {
        val first = event(chapter = "10", ts = 100L)
        val second = event(chapter = "11", ts = 200L)
        val result = appendUpdateEvent(listOf(first), second)
        assertEquals(listOf(second, first), result)
    }

    @Test
    fun appendUpdateEvent_dedupesOnKeyAndChapterNumber() {
        val original = event(key = "k1", chapter = "10", ts = 100L)
        val redetected = event(key = "k1", chapter = "10", ts = 500L)
        val result = appendUpdateEvent(listOf(original), redetected)
        assertEquals(1, result.size)
        // Tiene la nuova rilevazione (timestamp aggiornato), in testa.
        assertEquals(500L, result.first().timestampMillis)
    }

    @Test
    fun appendUpdateEvent_dedupeIsPerManga() {
        val a = event(key = "k1", chapter = "10", ts = 100L)
        val b = event(key = "k2", chapter = "10", ts = 200L)
        val result = appendUpdateEvent(listOf(a), b)
        assertEquals(2, result.size)
    }

    @Test
    fun appendUpdateEvent_trimsToCapDroppingOldest() {
        // Feed già ordinato dal più recente (k5) al più vecchio (k1).
        val existing = listOf(
            event(key = "k5", chapter = "5", ts = 5L),
            event(key = "k4", chapter = "4", ts = 4L),
            event(key = "k3", chapter = "3", ts = 3L),
            event(key = "k2", chapter = "2", ts = 2L),
            event(key = "k1", chapter = "1", ts = 1L),
        )
        val newest = event(key = "k6", chapter = "6", ts = 6L)
        val result = appendUpdateEvent(existing, newest, maxEvents = 3)
        assertEquals(listOf("k6", "k5", "k4"), result.map { it.identityKey })
        // I più vecchi sono caduti fuori.
        assertFalse(result.any { it.identityKey == "k1" })
    }

    @Test
    fun unseenCount_countsOnlyUnseen() {
        assertEquals(0, unseenCount(emptyList()))
        val events = listOf(
            event(key = "k1", seen = false),
            event(key = "k2", seen = true),
            event(key = "k3", seen = false),
        )
        assertEquals(2, unseenCount(events))
        assertEquals(0, unseenCount(markAllSeen(events)))
    }

    @Test
    fun markAllSeen_flipsEveryEventAndIsIdempotent() {
        val events = listOf(event(key = "k1", seen = false), event(key = "k2", seen = true))
        val once = markAllSeen(events)
        assertTrue(once.all { it.seen })
        assertEquals(once, markAllSeen(once))
    }

    @Test
    fun groupEventsByDay_labelsTodayYesterdayAndDate() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-05-31T12:00:00Z").toEpochMilli()
        val today1 = event(key = "t1", chapter = "1", ts = Instant.parse("2026-05-31T09:00:00Z").toEpochMilli())
        val today2 = event(key = "t2", chapter = "2", ts = Instant.parse("2026-05-31T11:00:00Z").toEpochMilli())
        val yesterday = event(key = "y1", chapter = "3", ts = Instant.parse("2026-05-30T20:00:00Z").toEpochMilli())
        val older = event(key = "o1", chapter = "4", ts = Instant.parse("2026-05-20T10:00:00Z").toEpochMilli())

        val days = groupEventsByDay(listOf(today1, yesterday, older, today2), zone, now)

        assertEquals(listOf("Oggi", "Ieri", "20 maggio 2026"), days.map { it.dayLabel })
        // Dentro "Oggi", ordine decrescente: il delle 11:00 prima del delle 09:00.
        assertEquals(listOf("t2", "t1"), days.first().events.map { it.identityKey })
    }

    @Test
    fun groupEventsByDay_emptyForNoEvents() {
        assertTrue(groupEventsByDay(emptyList()).isEmpty())
    }
}
