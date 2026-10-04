package com.lorenzo.mangadownloader.data.sources

import com.lorenzo.mangadownloader.data.network.isTransportError
import com.lorenzo.mangadownloader.data.network.networkFailureKind


/**
 * Il tentativo è fallito perché **la fonte** non risponde? Solo questi guasti muovono
 * l'interruttore: un 404 (contenuto rimosso) o un parsing andato storto su una pagina strana
 * non dicono niente sulla salute del sito, e saltarlo per quello significherebbe togliere
 * all'utente una fonte perfettamente viva. Pura.
 */
fun isSourceOutage(exc: Throwable): Boolean {
    if (exc.networkFailureKind() != null) return true
    if (!exc.isTransportError()) return false
    val status = HTTP_STATUS_IN_MESSAGE.find(exc.message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    // Nessuno status nel messaggio = errore di trasporto (connessione rifiutata, reset).
    return status == null || status >= 500 || status == 429
}

private val HTTP_STATUS_IN_MESSAGE = Regex("""HTTP (\d{3})""")
