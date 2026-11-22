package com.lorenzo.mangadownloader.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val secureRandom = SecureRandom()

fun generateParentalPinSalt(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

/**
 * Hash del PIN per i PIN nuovi: PBKDF2, lento di proposito, perché un PIN di 6 cifre ha solo un
 * milione di combinazioni e con un hash veloce chi legge le preferenze le prova tutte all'istante.
 * Il prefisso distingue questi hash da quelli SHA-256 dei PIN impostati prima.
 */
fun hashParentalPin(pin: String, salt: String): String {
    val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256)
    val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    return PBKDF2_PREFIX + Base64.getEncoder().encodeToString(hash)
}

/** Verifica [pin] contro l'hash salvato, sia PBKDF2 sia SHA-256 (PIN impostati prima). */
fun verifyParentalPin(pin: String, salt: String, expectedHash: String): Boolean {
    val provided = if (expectedHash.startsWith(PBKDF2_PREFIX)) {
        hashParentalPin(pin, salt)
    } else {
        legacyParentalPinHash(pin, salt)
    }
    return MessageDigest.isEqual(
        provided.toByteArray(Charsets.UTF_8),
        expectedHash.toByteArray(Charsets.UTF_8),
    )
}

internal fun legacyParentalPinHash(pin: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(hash)
}

private const val PBKDF2_PREFIX = "pbkdf2:"
private const val PBKDF2_ITERATIONS = 100_000

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
