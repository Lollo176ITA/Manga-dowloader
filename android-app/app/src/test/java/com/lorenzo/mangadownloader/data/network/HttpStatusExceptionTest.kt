package com.lorenzo.mangadownloader.data.network

import com.lorenzo.mangadownloader.app.userFacingErrorMessage
import com.lorenzo.mangadownloader.data.sources.isSourceOutage
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpStatusExceptionTest {

    @Test
    fun clientErrors_areNotWorthRetrying() {
        assertFalse(isWorthRetrying(http(404)))
        assertFalse(isWorthRetrying(http(403)))
        assertFalse(isWorthRetrying(http(410)))
    }

    @Test
    fun timeoutsRateLimitsAndServerErrors_areWorthRetrying() {
        assertTrue(isWorthRetrying(http(408)))
        assertTrue(isWorthRetrying(http(429)))
        assertTrue(isWorthRetrying(http(503)))
        assertTrue(isWorthRetrying(http(522)))
    }

    @Test
    fun transportErrors_areWorthRetrying() {
        assertTrue(isWorthRetrying(SocketTimeoutException("timeout")))
        assertTrue(isWorthRetrying(IOException("connessione chiusa")))
    }

    @Test
    fun statusIsFoundEvenWhenTheErrorWasWrapped() {
        assertFalse(isWorthRetrying(IOException("Capitolo non scaricato", http(404))))
    }

    @Test
    fun messageKeepsTheShapeTheRestOfTheAppParses() {
        val outage = HttpStatusException(522, "HTTP 522 su https://vymanga.com/search")

        // Chi leggeva lo stato dal testo (messaggi all'utente, salute delle fonti) non cambia.
        assertTrue(isSourceOutage(outage))
        assertFalse(isSourceOutage(http(404)))
        assertEquals(
            userFacingErrorMessage(IOException("HTTP 522 su https://vymanga.com/search"), "boh"),
            userFacingErrorMessage(outage, "boh"),
        )
    }

    private fun http(code: Int) = HttpStatusException(code, "HTTP $code su https://vymanga.com/manga/x")
}
