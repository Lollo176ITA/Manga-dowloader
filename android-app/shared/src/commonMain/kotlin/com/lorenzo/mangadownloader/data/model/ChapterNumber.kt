package com.lorenzo.mangadownloader.data.model

/**
 * Numero decimale esatto per i capitoli ("10", "10.5", "007.10"), multipiattaforma.
 *
 * Sostituisce `java.math.BigDecimal` replicandone **solo** il comportamento che l'app usa, perché
 * da questi valori derivano nomi file `.cbz` e chiavi letto/scaricato già salvati sui dispositivi:
 * - parsing con la grammatica dei letterali decimali Java (segno, frazione, esponente), niente
 *   spazi, `NaN`, esadecimali o suffissi `f`/`d` (come `String.toBigDecimalOrNull()`);
 * - [equals] **sensibile alla scala** come BigDecimal (`9.5 != 9.50`), mentre [compareTo] è
 *   numerico (`9.5` e `9.50` valgono 0);
 * - [toPlainString] e [stripTrailingZeros] con lo stesso output.
 *
 * Le cifre sono tenute come stringa: nessun limite di grandezza e nessun overflow.
 */
class ChapterNumber private constructor(
    private val negative: Boolean,
    /** Valore assoluto non scalato, senza zeri iniziali ("0" per lo zero). */
    private val digits: String,
    /** Cifre dopo la virgola, come `BigDecimal.scale()`; negativa per gli esponenti positivi. */
    val scale: Int,
) : Comparable<ChapterNumber> {

    private val isZero: Boolean get() = digits == "0"

    private val signum: Int get() = when {
        isZero -> 0
        negative -> -1
        else -> 1
    }

    fun toPlainString(): String {
        if (isZero && scale <= 0) return "0"
        val sign = if (negative) "-" else ""
        val body = when {
            scale <= 0 -> digits + "0".repeat(-scale)
            digits.length > scale -> digits.substring(0, digits.length - scale) + "." +
                digits.substring(digits.length - scale)
            else -> "0." + "0".repeat(scale - digits.length) + digits
        }
        return sign + body
    }

    fun stripTrailingZeros(): ChapterNumber {
        if (isZero) return ZERO
        val trailing = digits.length - digits.trimEnd('0').length
        if (trailing == 0) return this
        return ChapterNumber(negative, digits.dropLast(trailing), scale - trailing)
    }

    /**
     * Parte intera troncata verso lo zero, come `BigDecimal.toInt()`: oltre il range di Int
     * restituisce gli stessi 32 bit bassi (l'aritmetica Int va in overflow allo stesso modo).
     */
    fun toInt(): Int {
        val integerDigits = when {
            scale <= 0 -> digits + "0".repeat(-scale)
            digits.length > scale -> digits.substring(0, digits.length - scale)
            else -> "0"
        }
        val magnitude = integerDigits.fold(0) { acc, digit -> acc * 10 + (digit - '0') }
        return if (negative) -magnitude else magnitude
    }

    override fun compareTo(other: ChapterNumber): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        if (signum == 0) return 0
        val magnitude = compareMagnitude(other)
        return if (negative) -magnitude else magnitude
    }

    private fun compareMagnitude(other: ChapterNumber): Int {
        // Posizione della cifra più significativa: con cifre senza zeri iniziali decide da sola.
        val leading = digits.length - scale
        val otherLeading = other.digits.length - other.scale
        if (leading != otherLeading) return leading.compareTo(otherLeading)
        val length = maxOf(digits.length, other.digits.length)
        return digits.padEnd(length, '0').compareTo(other.digits.padEnd(length, '0'))
    }

    override fun equals(other: Any?): Boolean =
        other is ChapterNumber && negative == other.negative && digits == other.digits && scale == other.scale

    override fun hashCode(): Int = (digits.hashCode() * 31 + scale) * 31 + negative.hashCode()

    override fun toString(): String = toPlainString()

    companion object {
        val ZERO = ChapterNumber(negative = false, digits = "0", scale = 0)

        /** Oltre questa scala il valore non è un numero di capitolo: evita stringhe enormi. */
        private const val MAX_ABS_SCALE = 1_000

        private val literal = Regex("""([+-])?(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d{1,9}))?""")

        fun of(value: Long): ChapterNumber =
            ChapterNumber(negative = value < 0, digits = value.toString().removePrefix("-"), scale = 0)

        /** Come il costruttore `BigDecimal(String)`: lancia [NumberFormatException] se non valido. */
        fun parse(text: String): ChapterNumber =
            parseOrNull(text) ?: throw NumberFormatException("Numero capitolo non valido: $text")

        internal fun parseOrNull(text: String): ChapterNumber? {
            val match = literal.matchEntire(text) ?: return null
            val integerPart = match.groupValues[2]
            val fractionPart = match.groupValues[3]
            if (integerPart.isEmpty() && fractionPart.isEmpty()) return null
            val exponent = match.groupValues[4].takeIf(String::isNotEmpty)?.toInt() ?: 0
            val scale = fractionPart.length.toLong() - exponent
            if (scale > MAX_ABS_SCALE || scale < -MAX_ABS_SCALE) return null
            val digits = (integerPart + fractionPart).trimStart('0').ifEmpty { "0" }
            val negative = match.groupValues[1] == "-" && digits != "0"
            return ChapterNumber(negative, digits, scale.toInt())
        }
    }
}

/** Equivalente di `String.toBigDecimalOrNull()` per i numeri di capitolo. */
fun String.toChapterNumberOrNull(): ChapterNumber? = ChapterNumber.parseOrNull(this)
