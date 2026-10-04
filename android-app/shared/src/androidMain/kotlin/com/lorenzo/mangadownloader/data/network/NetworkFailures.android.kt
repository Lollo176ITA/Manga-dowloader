package com.lorenzo.mangadownloader.data.network

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal actual fun platformNetworkFailureKind(error: Throwable): NetworkFailureKind? = when (error) {
    is UnknownHostException -> NetworkFailureKind.UNREACHABLE
    is SocketTimeoutException -> NetworkFailureKind.TIMEOUT
    is SSLException -> NetworkFailureKind.TLS
    else -> null
}
