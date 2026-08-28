package com.lorenzo.mangadownloader

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interruttore automatico delle fonti: dopo quanti fallimenti una fonte viene saltata, per
 * quanto, e come torna dentro da sola. Tutto puro, nessuna rete e nessun SharedPreferences.
 */
class SourceHealthTest {

    private val minute = 60_000L

    @Test
    fun unSoloFallimentoNonSpegneLaFonte() {
        val health = recordSourceProbeFailure(null, now = 1_000L)

        assertEquals(1, health.consecutiveFailures)
        assertFalse(isSourceSkipped(health, now = 1_000L))
        assertNull(sourceUnreachableSince(health, now = 1_000L))
    }

    @Test
    fun treFallimentiConsecutiviLaFannoSaltare() {
        var health: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { attempt ->
            health = recordSourceProbeFailure(health, now = attempt * 1_000L)
        }

        assertEquals(SOURCE_UNREACHABLE_THRESHOLD, health!!.consecutiveFailures)
        assertTrue(isSourceSkipped(health, now = 3_000L))
    }

    @Test
    fun scadutoIlCooldownLaFonteVieneRiprovata() {
        var health: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { health = recordSourceProbeFailure(health, now = 0L) }

        assertTrue(isSourceSkipped(health, now = SOURCE_COOLDOWN_MILLIS - 1))
        // Scaduta la finestra si concede una sonda: la fonte torna nel giro di ricerca.
        assertFalse(isSourceSkipped(health, now = SOURCE_COOLDOWN_MILLIS))
    }

    @Test
    fun unSuccessoAzzeraTuttoESiRicomincia() {
        var health: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { health = recordSourceProbeFailure(health, now = 0L) }

        val healed = recordSourceProbeSuccess(now = 5 * minute)

        assertEquals(0, healed.consecutiveFailures)
        assertEquals(5 * minute, healed.lastSuccessAtMillis)
        assertFalse(isSourceSkipped(healed, now = 5 * minute))
        assertNull(sourceUnreachableSince(healed, now = 5 * minute))
    }

    @Test
    fun ilSuccessoConservaLUltimaVoltaCheHaFunzionato() {
        val ok = recordSourceProbeSuccess(now = 10 * minute)
        val broken = recordSourceProbeFailure(ok, now = 20 * minute)

        assertEquals(10 * minute, broken.lastSuccessAtMillis)
    }

    @Test
    fun laFonteSiDiceIrraggiungibileSoloOltreLaSoglia() {
        var health = recordSourceProbeFailure(null, now = 0L)
        health = recordSourceProbeFailure(health, now = minute)

        assertNull(sourceUnreachableSince(health, now = 2 * minute))

        health = recordSourceProbeFailure(health, now = 2 * minute)

        // Oltre soglia scatta l'avviso, e la data è quella del **primo** fallimento della
        // serie: è da lì che il sito ha smesso di rispondere, non da quando ce ne siamo
        // convinti. L'avviso resta anche dopo il cooldown, finché non risponde di nuovo.
        assertEquals(0L, sourceUnreachableSince(health, now = 2 * minute))
        assertEquals(0L, sourceUnreachableSince(health, now = 10 * SOURCE_COOLDOWN_MILLIS))
    }

    @Test
    fun laRicercaSaltaSoloLeFontiInCooldown() {
        var giu: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { giu = recordSourceProbeFailure(giu, now = 0L) }

        val queried = sourcesToQuery(
            descriptors = MangaSourceCatalog.descriptors,
            health = mapOf(MangaSourceIds.VYMANGA to giu!!),
            now = minute,
        )

        assertEquals(MangaSourceCatalog.descriptors.size - 1, queried.size)
        assertFalse(queried.any { it.id == MangaSourceIds.VYMANGA })
    }

    @Test
    fun seSonoTutteGiuSiInterroganoComunqueTutte() {
        var giu: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { giu = recordSourceProbeFailure(giu, now = 0L) }
        val tutteGiu = MangaSourceCatalog.descriptors.associate { it.id to giu!! }

        val queried = sourcesToQuery(MangaSourceCatalog.descriptors, tutteGiu, now = minute)

        assertEquals(
            "col telefono senza rete sembrano tutte giù: una Cerca che non cerca è peggio",
            MangaSourceCatalog.descriptors,
            queried,
        )
    }

    @Test
    fun soloIGuastiDiFonteContano() {
        // Sito giù, timeout, DNS: sono guasti della fonte.
        assertTrue(isSourceOutage(IOException("HTTP 522 su https://vymanga.com/search")))
        assertTrue(isSourceOutage(IOException("HTTP 503 su https://vymanga.com/search")))
        assertTrue(isSourceOutage(SocketTimeoutException("timeout")))
        assertTrue(isSourceOutage(UnknownHostException("vymanga.com")))
        assertTrue(isSourceOutage(IOException("connection reset")))

        // Un contenuto mancante o un parsing andato storto NON sono la fonte che è giù:
        // spegnerla per questo toglierebbe all'utente una fonte perfettamente viva.
        assertFalse(isSourceOutage(IOException("HTTP 404 su https://vymanga.com/manga/x")))
        assertFalse(isSourceOutage(IllegalStateException("Capitolo iniziale non trovato")))
        assertFalse(isSourceOutage(IllegalArgumentException("URL manga non valido")))
    }

    @Test
    fun lEtichettaDiceDaQuandoNonRisponde() {
        var health: SourceReachability? = recordSourceProbeSuccess(now = 2 * minute)
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { health = recordSourceProbeFailure(health, now = 60 * minute) }

        val label = sourceUnreachableLabel(health, now = 62 * minute)

        assertEquals("Non raggiungibile: ultima risposta 1 h fa", label)
        assertNull(sourceUnreachableLabel(recordSourceProbeSuccess(now = 63 * minute), now = 63 * minute))
    }

    @Test
    fun seSonoTutteIrraggiungibiliNonSiAccusaNessuna() {
        var giu: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { giu = recordSourceProbeFailure(giu, now = 0L) }
        val ids = MangaSourceCatalog.descriptors.map { it.id }

        val tutteGiu = sourceUnreachableLabels(ids.associateWith { giu!! }, now = minute)
        val unaSola = sourceUnreachableLabels(mapOf(ids.first() to giu!!), now = minute)

        assertTrue(
            "otto siti caduti insieme è il telefono senza rete, non otto siti caduti",
            tutteGiu.isEmpty(),
        )
        assertEquals(setOf(ids.first()), unaSola.keys)
    }

    @Test
    fun senzaUnaRispostaMaiRicevutaLEtichettaRestaAsciutta() {
        var health: SourceReachability? = null
        repeat(SOURCE_UNREACHABLE_THRESHOLD) { health = recordSourceProbeFailure(health, now = 0L) }

        assertEquals("Non raggiungibile", sourceUnreachableLabel(health, now = minute))
    }
}
