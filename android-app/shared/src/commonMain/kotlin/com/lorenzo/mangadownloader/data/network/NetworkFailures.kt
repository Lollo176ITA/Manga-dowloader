package com.lorenzo.mangadownloader.data.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import okio.IOException

/**
 * Guasti di trasporto che meritano un messaggio e una diagnosi propri: le eccezioni concrete
 * cambiano per piattaforma (`java.net` su Android, `NSError` su iOS), quindi chi deve
 * distinguerle passa da [networkFailureKind] invece di controllare le classi a mano.
 */
enum class NetworkFailureKind {
    /** Host sconosciuto o rete assente. */
    UNREACHABLE,

    /** Nessuna risposta entro il tempo concesso. */
    TIMEOUT,

    /** Handshake TLS fallito. */
    TLS,
}

/** Timeout deciso dall'app (budget di una ricerca), non dal client HTTP. */
class SourceTimeoutException(message: String) : IOException(message)

fun Throwable.networkFailureKind(): NetworkFailureKind? = when (this) {
    is SourceTimeoutException,
    is SocketTimeoutException,
    is ConnectTimeoutException,
    is HttpRequestTimeoutException,
    -> NetworkFailureKind.TIMEOUT
    else -> platformNetworkFailureKind(this)
}

/** Le eccezioni native della piattaforma; `null` se [error] non è un guasto riconosciuto. */
internal expect fun platformNetworkFailureKind(error: Throwable): NetworkFailureKind?
