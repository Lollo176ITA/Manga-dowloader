package com.lorenzo.mangadownloader.data.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Scadenza della cache delle vetrine AniList della Home. La regola non è "24 ore dall'ultimo
 * scaricamento" ma **il rollover fisso delle 09:00 locali**: quello che hai visto stamattina
 * resta identico per tutta la giornata, e la prima apertura dopo le 9 lo rinnova.
 *
 * Logica pura: niente Android, niente rete, niente orologio di sistema — il "adesso" è sempre
 * un parametro, così i confini si testano davvero invece di sperare nel momento giusto.
 */
class HomeFeedCacheTest {

    private val rome: TimeZone = TimeZone.of("Europe/Rome")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).toInstant(rome).toEpochMilliseconds()

    @Test
    fun data_fetched_after_this_morning_s_rollover_is_fresh() {
        assertTrue(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-11T09:30:00"),
                nowMillis = at("2026-01-11T20:00:00"),
                zone = rome,
            ))
    }

    @Test
    fun data_fetched_exactly_at_the_rollover_is_fresh() {
        assertTrue(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-11T09:00:00"),
                nowMillis = at("2026-01-11T09:00:00"),
                zone = rome,
            ))
    }

    @Test
    fun data_fetched_last_night_is_stale_once_9_has_passed() {
        assertFalse(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T22:00:00"),
                nowMillis = at("2026-01-11T09:00:01"),
                zone = rome,
            ))
    }

    @Test
    fun data_fetched_yesterday_morning_survives_the_night_until_9() {
        // Apri l'app alle 8:59: il rollover di stamattina non è ancora scoccato, quindi quello
        // che hai scaricato ieri dopo le 9 vale ancora. Nessuna richiesta.
        assertTrue(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T10:00:00"),
                nowMillis = at("2026-01-11T08:59:00"),
                zone = rome,
            ))
    }

    @Test
    fun data_fetched_before_yesterday_s_rollover_is_stale_even_before_9() {
        assertFalse(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-10T08:59:00"),
                nowMillis = at("2026-01-11T08:59:00"),
                zone = rome,
            ))
    }

    @Test
    fun data_stamped_in_the_future_is_treated_as_stale() {
        // Orologio del telefono spostato avanti e poi rimesso a posto: senza questo controllo
        // la cache resterebbe "fresca" finché il futuro non viene raggiunto.
        assertFalse(isHomeFeedFresh(
                fetchedAtMillis = at("2026-01-12T10:00:00"),
                nowMillis = at("2026-01-11T10:00:00"),
                zone = rome,
            ))
    }

    @Test
    fun never_fetched_is_stale() {
        assertFalse(isHomeFeedFresh(
                fetchedAtMillis = 0L,
                nowMillis = at("2026-01-11T10:00:00"),
                zone = rome,
            ))
    }

    // --- Impronta dei semi dei Consigliati ---

    @Test
    fun seed_signature_ignores_order_and_duplicates() {
        assertEquals(recommendationSeedSignature(listOf("Berserk", "Vinland Saga")), recommendationSeedSignature(listOf("Vinland Saga", "Berserk", "Berserk")))
    }

    @Test
    fun seed_signature_changes_when_a_series_is_added() {
        assertNotEquals(recommendationSeedSignature(listOf("Berserk")), recommendationSeedSignature(listOf("Berserk", "Vinland Saga")))
    }

    @Test
    fun seed_signature_of_no_seeds_is_stable() {
        assertEquals(recommendationSeedSignature(emptyList()), recommendationSeedSignature(emptyList()))
    }
}
