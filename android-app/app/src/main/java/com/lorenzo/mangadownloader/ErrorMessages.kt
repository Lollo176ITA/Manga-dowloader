package com.lorenzo.mangadownloader

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Traduce le eccezioni di rete/parsing in messaggi in italiano comprensibili da chi usa
 * l'app: prima in snackbar arrivavano testi grezzi tipo "HTTP 503 su https://…" (vedi
 * [MangaNetworkClient]). Il dettaglio tecnico resta nell'eccezione per i log; qui si
 * decide solo cosa mostrare. [fallback] copre le eccezioni senza messaggio.
 */
fun userFacingErrorMessage(exc: Throwable, fallback: String): String {
    val httpStatus = exc.message
        ?.let { HTTP_STATUS_REGEX.find(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return when {
        exc is UnknownHostException -> "Sito non raggiungibile: controlla la connessione."
        exc is SocketTimeoutException -> "Il sito non risponde: riprova tra poco."
        exc is SSLException -> "Connessione al sito non riuscita: riprova."
        httpStatus != null && httpStatus >= 500 -> "Il sito ha un problema: riprova tra poco."
        httpStatus == 404 -> "Contenuto non più disponibile sul sito."
        httpStatus == 403 || httpStatus == 429 -> "Il sito sta bloccando le richieste: riprova tra poco."
        httpStatus != null -> "Errore del sito (HTTP $httpStatus): riprova."
        exc is IOException -> "Problema di rete: controlla la connessione e riprova."
        else -> exc.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private val HTTP_STATUS_REGEX = Regex("""HTTP (\d{3})""")
