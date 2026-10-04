package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.platform.secureRandomBytes
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

fun generateParentalPinSalt(): String = secureRandomBytes(16).toByteString().base64()

/** SHA-256 di "salt:pin" in Base64 standard: formato invariato rispetto agli hash già salvati. */
fun hashParentalPin(pin: String, salt: String): String = "$salt:$pin".encodeUtf8().sha256().base64()

fun sanitizeParentalPin(input: String): String {
    return input.filter(Char::isDigit).take(6)
}

/** Tentativi sbagliati concessi prima che il PIN si blocchi per un po'. */
const val PARENTAL_FREE_PIN_ATTEMPTS = 5
private const val PARENTAL_FIRST_LOCKOUT_MILLIS = 30_000L
private const val PARENTAL_MAX_LOCKOUT_MILLIS = 15 * 60_000L

/**
 * Per quanto il PIN resta bloccato dopo [failedAttempts] errori di fila: niente fino a
 * [PARENTAL_FREE_PIN_ATTEMPTS], poi 30 secondi che raddoppiano a ogni errore, fino a 15 minuti.
 * Un PIN di 6 cifre si indovina in fretta se nessuno rallenta i tentativi. Pura.
 */
fun parentalPinLockoutMillis(failedAttempts: Int): Long {
    if (failedAttempts < PARENTAL_FREE_PIN_ATTEMPTS) return 0L
    val doublings = (failedAttempts - PARENTAL_FREE_PIN_ATTEMPTS).coerceAtMost(10)
    return (PARENTAL_FIRST_LOCKOUT_MILLIS shl doublings).coerceAtMost(PARENTAL_MAX_LOCKOUT_MILLIS)
}

/** "30 secondi", "2 minuti": quanto manca allo sblocco, arrotondato per eccesso. Pura. */
fun parentalLockoutLabel(remainingMillis: Long): String {
    val seconds = ((remainingMillis + 999) / 1000).coerceAtLeast(1)
    if (seconds < 60) return if (seconds == 1L) "1 secondo" else "$seconds secondi"
    val minutes = (seconds + 59) / 60
    return if (minutes == 1L) "1 minuto" else "$minutes minuti"
}
