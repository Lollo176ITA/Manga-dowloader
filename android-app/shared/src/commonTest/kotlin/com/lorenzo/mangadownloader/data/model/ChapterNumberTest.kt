package com.lorenzo.mangadownloader.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterNumberTest {

    @Test
    fun parsesPlainDecimalsKeepingTheirScale() {
        assertEquals("10.50", "10.50".toChapterNumberOrNull()?.toPlainString())
        assertEquals("7.10", "007.10".toChapterNumberOrNull()?.toPlainString())
        assertEquals("0.5", ".5".toChapterNumberOrNull()?.toPlainString())
        assertEquals("2", "2.".toChapterNumberOrNull()?.toPlainString())
        assertEquals("-1", "-1".toChapterNumberOrNull()?.toPlainString())
        assertEquals("0", "-0".toChapterNumberOrNull()?.toPlainString())
    }

    @Test
    fun parsesExponents() {
        assertEquals("1000", "1e3".toChapterNumberOrNull()?.toPlainString())
        assertEquals("0.01", "1E-2".toChapterNumberOrNull()?.toPlainString())
    }

    @Test
    fun rejectsAnythingThatIsNotADecimalLiteral() {
        listOf("", " 3 ", "Extra", "1,5", "NaN", "Infinity", "0x10", "1.5f", "12a", ".", "e3", "1e").forEach {
            assertNull(it.toChapterNumberOrNull(), "'$it'")
        }
        assertFailsWith<NumberFormatException> { ChapterNumber.parse("Extra") }
    }

    @Test
    fun stripTrailingZerosNormalizesTheScale() {
        assertEquals("10.5", "10.50".toChapterNumberOrNull()!!.stripTrailingZeros().toPlainString())
        assertEquals("120", "120.0".toChapterNumberOrNull()!!.stripTrailingZeros().toPlainString())
        assertEquals("0", "0.000".toChapterNumberOrNull()!!.stripTrailingZeros().toPlainString())
    }

    @Test
    fun comparesNumericallyButEqualsKeepsTheScale() {
        val a = "9.5".toChapterNumberOrNull()!!
        val b = "9.50".toChapterNumberOrNull()!!
        assertEquals(0, a.compareTo(b))
        assertNotEquals(a, b)
        assertEquals(a, b.stripTrailingZeros())
        assertEquals(a.hashCode(), b.stripTrailingZeros().hashCode())
        assertTrue("-2".toChapterNumberOrNull()!! < ChapterNumber.ZERO)
        assertTrue("10".toChapterNumberOrNull()!! > "9.99".toChapterNumberOrNull()!!)
        assertTrue(ChapterNumber.of(Long.MIN_VALUE) < ChapterNumber.ZERO)
    }

    @Test
    fun handlesNumbersLongerThanALong() {
        val big = "99999999999999999999.5".toChapterNumberOrNull()!!
        assertEquals("99999999999999999999.5", big.toPlainString())
        assertTrue(big > "99999999999999999999".toChapterNumberOrNull()!!)
    }
}
