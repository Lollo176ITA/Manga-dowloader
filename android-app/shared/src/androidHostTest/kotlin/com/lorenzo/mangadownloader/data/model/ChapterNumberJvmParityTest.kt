package com.lorenzo.mangadownloader.data.model

import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Confronto differenziale con java.math.BigDecimal, il tipo che [ChapterNumber] sostituisce:
 * parsing, formattazione, normalizzazione, ordinamento e uguaglianza devono coincidere, perché
 * da questi valori derivano nomi file e chiavi già salvati sui dispositivi degli utenti.
 */
class ChapterNumberJvmParityTest {

    private val fixed = listOf(
        "0", "00", "-0", "+0", "0.0", "0.000", "1", "01", "010", "1.0", "10.50", "007.10", ".5", "2.", "-1", "+2",
        "1e3", "1E-2", "1e+2", "-1.50e1", "0e5", "0.00e-3", "12.345", "120.0", "1000", "99999999999999999999.5",
        "3.14159265358979323846", "-0.0001", "5e-10", "123456789012345678901234567890",
        "", " 3 ", "Extra", "1,5", "NaN", "Infinity", "0x10", "1.5f", "1.5d", "12a", ".", "e3", "1e", "--1", "1..2",
    )

    private val generated: List<String> = Random(42).let { rnd ->
        List(4000) {
            buildString {
                when (rnd.nextInt(6)) { 0 -> append('-'); 1 -> append('+') }
                repeat(rnd.nextInt(0, 6)) { append(rnd.nextInt(10)) }
                if (rnd.nextBoolean()) {
                    append('.')
                    repeat(rnd.nextInt(0, 5)) { append(if (rnd.nextInt(3) == 0) 0 else rnd.nextInt(10)) }
                }
                if (rnd.nextInt(5) == 0) {
                    append(if (rnd.nextBoolean()) 'e' else 'E')
                    when (rnd.nextInt(3)) { 0 -> append('-'); 1 -> append('+') }
                    append(rnd.nextInt(0, 12))
                }
            }
        }
    }

    private val inputs = fixed + generated

    @Test
    fun parsingAndFormattingMatchBigDecimal() {
        for (raw in inputs) {
            val expected = raw.toBigDecimalOrNull()
            val actual = raw.toChapterNumberOrNull()
            assertEquals(expected?.toPlainString(), actual?.toPlainString(), "toPlainString of '$raw'")
            assertEquals(
                expected?.stripTrailingZeros()?.toPlainString(),
                actual?.stripTrailingZeros()?.toPlainString(),
                "stripTrailingZeros of '$raw'",
            )
        }
    }

    @Test
    fun orderingAndEqualityMatchBigDecimal() {
        val parsed = inputs.mapNotNull { raw -> raw.toBigDecimalOrNull()?.let { it to raw.toChapterNumberOrNull()!! } }
        val rnd = Random(7)
        repeat(20_000) {
            val (bigA, a) = parsed[rnd.nextInt(parsed.size)]
            val (bigB, b) = parsed[rnd.nextInt(parsed.size)]
            assertEquals(bigA.compareTo(bigB).coerceIn(-1, 1), a.compareTo(b).coerceIn(-1, 1), "compare $bigA vs $bigB")
            assertEquals(bigA == bigB, a == b, "equals $bigA vs $bigB")
            if (a == b) assertEquals(a.hashCode(), b.hashCode())
        }
    }

    @Test
    fun toIntTruncatesLikeBigDecimal() {
        for (raw in inputs + listOf("2147483648", "-2147483649", "99999999999999999999.5", "12.99", "-12.99")) {
            val expected = raw.toBigDecimalOrNull() ?: continue
            assertEquals(expected.toInt(), raw.toChapterNumberOrNull()!!.toInt(), "toInt of '$raw'")
        }
    }

    @Test
    fun longConstructorMatchesBigDecimal() {
        listOf(Long.MIN_VALUE, -1L, 0L, 7L, Long.MAX_VALUE).forEach {
            assertEquals(BigDecimal(it).toPlainString(), ChapterNumber.of(it).toPlainString())
        }
    }
}
