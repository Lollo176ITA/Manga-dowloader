package com.lorenzo.mangadownloader

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()

fun generateParentalPinSalt(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

fun hashParentalPin(pin: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(hash)
}

fun sanitizeParentalPin(input: String): String {
    return input.filter(Char::isDigit).take(6)
}
