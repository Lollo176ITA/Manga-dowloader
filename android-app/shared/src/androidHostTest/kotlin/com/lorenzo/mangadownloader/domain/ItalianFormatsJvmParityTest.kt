package com.lorenzo.mangadownloader.domain

import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

/** Le date e i numeri in italiano devono restare identici a quelli prodotti con java.time/java.text. */
class ItalianFormatsJvmParityTest {

    @Test
    fun longDateMatchesDateTimeFormatter() {
        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
        var day = java.time.LocalDate.of(1999, 12, 25)
        repeat(800) {
            val expected = day.format(formatter)
            val actual = formatItalianLongDate(LocalDate(day.year, day.monthValue, day.dayOfMonth))
            assertEquals(expected, actual)
            day = day.plusDays(3)
        }
    }

    @Test
    fun narrowDayNamesMatchTextStyleNarrow() {
        java.time.DayOfWeek.entries.forEach { day ->
            assertEquals(
                day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ITALIAN),
                italianNarrowDayName(kotlinx.datetime.DayOfWeek(day.value)),
            )
        }
    }

    @Test
    fun integersMatchNumberFormat() {
        val format = NumberFormat.getIntegerInstance(Locale.ITALIAN)
        val rnd = Random(3)
        val values = listOf(0L, 7L, -7L, 999L, 1000L, -1000L, 1234567L, Long.MAX_VALUE, Long.MIN_VALUE + 1) +
            List(2000) { rnd.nextLong(-10_000_000_000L, 10_000_000_000L) }
        values.forEach { assertEquals(format.format(it), formatItalianInteger(it), "value $it") }
    }
}
