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
 *
 * [sourceName] è il nome della fonte che ha fallito, quando il chiamante lo conosce. Con otto
 * fonti registrate, "il sito ha un problema" non dice all'utente *quale* sito né perché la
 * stessa serie si apra da un'altra parte: "VyManga ha un problema" sì. Le frasi sono scritte
 * per reggere entrambi i soggetti senza storpiature.
 */
fun userFacingErrorMessage(
    exc: Throwable,
    fallback: String,
    sourceName: String? = null,
): String {
    val site = sourceName ?: "Il sito"
    val httpStatus = exc.message
        ?.let { HTTP_STATUS_REGEX.find(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return when {
        exc is UnknownHostException -> "$site non è raggiungibile: controlla la connessione."
        exc is SocketTimeoutException -> "$site non risponde: riprova tra poco."
        exc is SSLException -> "$site ha rifiutato la connessione: riprova."
        httpStatus != null && httpStatus >= 500 -> "$site ha un problema: riprova tra poco."
        httpStatus == 404 -> "$site non ha più questo contenuto."
        httpStatus == 403 || httpStatus == 429 -> "$site sta bloccando le richieste: riprova tra poco."
        httpStatus != null -> "$site ha risposto con un errore (HTTP $httpStatus): riprova."
        exc is IOException -> "Problema di rete: controlla la connessione e riprova."
        else -> exc.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private val HTTP_STATUS_REGEX = Regex("""HTTP (\d{3})""")
