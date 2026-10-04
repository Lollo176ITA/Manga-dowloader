package com.lorenzo.mangadownloader.data.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut

internal actual fun platformNetworkFailureKind(error: Throwable): NetworkFailureKind? {
    val origin = (error as? DarwinHttpRequestException)?.origin ?: return null
    if (origin.domain != NSURLErrorDomain) return null
    return when (origin.code) {
        NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed, NSURLErrorNotConnectedToInternet ->
            NetworkFailureKind.UNREACHABLE
        NSURLErrorTimedOut -> NetworkFailureKind.TIMEOUT
        NSURLErrorSecureConnectionFailed,
        NSURLErrorServerCertificateHasBadDate,
        NSURLErrorServerCertificateUntrusted,
        NSURLErrorServerCertificateHasUnknownRoot,
        NSURLErrorServerCertificateNotYetValid,
        -> NetworkFailureKind.TLS
        else -> null
    }
}
