package com.lorenzo.mangadownloader.domain

import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** Nomi dei mesi in italiano, minuscoli come li scrive `DateTimeFormatter` con `Locale.ITALIAN`. */
val ITALIAN_MONTH_NAMES = listOf(
    "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
    "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
)

/** "4 ottobre 2026": stesso output di `DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)`. */
fun formatItalianLongDate(date: LocalDate): String =
    "${date.day} ${ITALIAN_MONTH_NAMES[date.month.number - 1]} ${date.year}"

/** "1.234.567": stesso output di `NumberFormat.getIntegerInstance(Locale.ITALIAN)`. */
fun formatItalianInteger(value: Long): String {
    val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else value.absoluteValue.toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** Iniziale del giorno ("L", "M", "G"...): come `TextStyle.NARROW` con `Locale.ITALIAN`. */
fun italianNarrowDayName(day: DayOfWeek): String = "LMMGVSD"[day.isoDayNumber - 1].toString()

/** La data di oggi nel fuso del dispositivo. */
fun todayLocalDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = Clock.System.todayIn(zone)

/** L'ora corrente (0-23) nel fuso del dispositivo, come `Calendar.HOUR_OF_DAY`. */
fun currentLocalHour(zone: TimeZone = TimeZone.currentSystemDefault()): Int =
    Clock.System.now().toLocalDateTime(zone).hour
