package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.data.model.toChapterNumberOrNull

/**
 * [value] con esattamente [decimals] cifre decimali e il punto come separatore: lo stesso output
 * di `String.format(Locale.US, "%.Nf", value)` della JVM, che arrotonda HALF_UP partendo dalla
 * rappresentazione decimale più corta del double (0.15 → "0.2", non "0.1").
 */
fun formatFixed(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    val parsed = kotlin.math.abs(value).toString().toChapterNumberOrNull()
        ?: return value.toString()
    val plain = parsed.toPlainString()
    val integerPart = plain.substringBefore('.')
    val fractionPart = plain.substringAfter('.', "")
    var digits = integerPart + fractionPart.take(decimals).padEnd(decimals, '0')
    val firstDropped = fractionPart.getOrNull(decimals)
    if (firstDropped != null && firstDropped >= '5') digits = incrementDigits(digits)
    val wholeLength = digits.length - decimals
    val whole = digits.substring(0, wholeLength).trimStart('0').ifEmpty { "0" }
    val result = if (decimals == 0) whole else whole + "." + digits.substring(wholeLength)
    return if (negative) "-$result" else result
}

private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    var index = chars.lastIndex
    while (index >= 0) {
        if (chars[index] == '9') {
            chars[index] = '0'
            index--
        } else {
            chars[index] = chars[index] + 1
            return chars.concatToString()
        }
    }
    return "1" + chars.concatToString()
}
