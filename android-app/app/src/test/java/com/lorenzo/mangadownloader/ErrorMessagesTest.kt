package com.lorenzo.mangadownloader

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I messaggi mostrati in snackbar: con e senza il nome della fonte. Le frasi devono reggere
 * entrambi i soggetti ("Il sito" / "VyManga") senza diventare sgrammaticate.
 */
class ErrorMessagesTest {

    @Test
    fun senzaNomeFonteRestaIlSoggettoGenerico() {
        assertEquals(
            "Il sito ha un problema: riprova tra poco.",
            userFacingErrorMessage(IOException("HTTP 522 su https://vymanga.com/"), "boh"),
        )
    }

    @Test
    fun conIlNomeFonteLUtenteSaChiNonRisponde() {
        val cases = mapOf(
            IOException("HTTP 522 su https://vymanga.com/") to
                "VyManga ha un problema: riprova tra poco.",
            IOException("HTTP 404 su https://vymanga.com/manga/x") to
                "VyManga non ha più questo contenuto.",
            IOException("HTTP 429 su https://vymanga.com/") to
                "VyManga sta bloccando le richieste: riprova tra poco.",
            IOException("HTTP 418 su https://vymanga.com/") to
                "VyManga ha risposto con un errore (HTTP 418): riprova.",
            SocketTimeoutException("timeout") to "VyManga non risponde: riprova tra poco.",
            UnknownHostException("vymanga.com") to
                "VyManga non è raggiungibile: controlla la connessione.",
        )

        cases.forEach { (exc, expected) ->
            assertEquals(expected, userFacingErrorMessage(exc, "boh", sourceName = "VyManga"))
        }
    }

    @Test
    fun ilProblemaDiReteNonAccusaLaFonte() {
        // Senza status e senza timeout non sappiamo di chi sia la colpa: può essere il wifi
        // dell'utente, e dare la colpa alla fonte sbagliata gli farebbe spegnere quella buona.
        assertEquals(
            "Problema di rete: controlla la connessione e riprova.",
            userFacingErrorMessage(IOException(), "boh", sourceName = "VyManga"),
        )
    }
}
