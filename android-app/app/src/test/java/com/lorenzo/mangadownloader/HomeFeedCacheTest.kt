package com.lorenzo.mangadownloader

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scadenza della cache delle vetrine AniList della Home. La regola non è "24 ore dall'ultimo
 * scaricamento" ma **il rollover fisso delle 09:00 locali**: quello che hai visto stamattina
 * resta identico per tutta la giornata, e la prima apertura dopo le 9 lo rinnova.
 *
 * Logica pura: niente Android, niente rete, niente orologio di sistema — il "adesso" è sempre
 * un parametro, così i confini si testano davvero invece di sperare nel momento giusto.
 */
class HomeFeedCacheTest {

    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    @Test
    fun `data fetched after this morning's rollover is fresh`() {
        assertTrue(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-11T09:30:00"),
                nowMillis = at("2026-01-11T20:00:00"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `data fetched exactly at the rollover is fresh`() {
        assertTrue(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-11T09:00:00"),
                nowMillis = at("2026-01-11T09:00:00"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `data fetched last night is stale once 9 has passed`() {
        assertFalse(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T22:00:00"),
                nowMillis = at("2026-01-11T09:00:01"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `data fetched yesterday morning survives the night until 9`() {
        // Apri l'app alle 8:59: il rollover di stamattina non è ancora scoccato, quindi quello
        // che hai scaricato ieri dopo le 9 vale ancora. Nessuna richiesta.
        assertTrue(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T10:00:00"),
                nowMillis = at("2026-01-11T08:59:00"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `data fetched before yesterday's rollover is stale even before 9`() {
        assertFalse(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T08:59:00"),
                nowMillis = at("2026-01-11T08:59:00"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `data stamped in the future is treated as stale`() {
        // Orologio del telefono spostato avanti e poi rimesso a posto: senza questo controllo
        // la cache resterebbe "fresca" finché il futuro non viene raggiunto.
        assertFalse(
            isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-12T10:00:00"),
                nowMillis = at("2026-01-11T10:00:00"),
                zone = rome,
            ),
        )
    }

    @Test
    fun `never fetched is stale`() {
        assertFalse(
            isHomeFeedFresh(
                fetchedAtMillis = 0L,
                nowMillis = at("2026-01-11T10:00:00"),
                zone = rome,
            ),
        )
    }

    // --- Impronta dei semi dei Consigliati ---

    @Test
    fun `seed signature ignores order and duplicates`() {
        assertEquals(
            recommendationSeedSignature(listOf("Berserk", "Vinland Saga")),
            recommendationSeedSignature(listOf("Vinland Saga", "Berserk", "Berserk")),
        )
    }

    @Test
    fun `seed signature changes when a series is added`() {
        assertNotEquals(
            recommendationSeedSignature(listOf("Berserk")),
            recommendationSeedSignature(listOf("Berserk", "Vinland Saga")),
        )
    }

    @Test
    fun `seed signature of no seeds is stable`() {
        assertEquals(recommendationSeedSignature(emptyList()), recommendationSeedSignature(emptyList()))
    }
}
