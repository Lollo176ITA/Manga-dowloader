package com.lorenzo.mangadownloader.domain.reading

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Parsing e formattazione della data di pubblicazione dei capitoli. Logica pura: niente
 * Android, niente rete, niente dipendenza dal locale di sistema (i mesi italiani sono nostri).
 */
class ChapterDatesTest {

    private val rome: TimeZone = TimeZone.of("Europe/Rome")

    private fun millisAt(text: String): Long =
        LocalDateTime.parse(text).toInstant(rome).toEpochMilliseconds()

    // --- Parsing ISO (Asura `published_at`, Hasta Team `published_on`, DemonicScans) ---

    @Test
    fun parses_iso_instant_with_milliseconds() {
        val parsed = chapterDateFromIso("2026-04-19T17:07:50.694Z", rome)

        assertEquals(1776618470694L, parsed)
    }

    @Test
    fun parses_iso_instant_with_microseconds() {
        // Hasta Team manda 6 cifre di frazione: "2018-04-10T16:00:04.000000Z".
        val parsed = chapterDateFromIso("2018-04-10T16:00:04.000000Z", rome)

        assertEquals(1523376004000L, parsed)
    }

    @Test
    fun parses_plain_iso_date_as_start_of_day_in_the_given_zone() {
        val parsed = chapterDateFromIso("2024-07-13", rome)

        assertEquals(millisAt("2024-07-13T00:00:00"), parsed)
    }

    @Test
    fun returns_null_for_blank_or_unparsable_iso_text() {
        assertNull(chapterDateFromIso(null, rome))
        assertNull(chapterDateFromIso("   ", rome))
        assertNull(chapterDateFromIso("ieri", rome))
    }

    // --- Parsing italiano (MangaWorld `<i class="chap-date">03 Maggio 2022</i>`) ---

    @Test
    fun parses_italian_long_date() {
        val parsed = chapterDateFromItalianDate("03 Maggio 2022", rome)

        assertEquals(millisAt("2022-05-03T00:00:00"), parsed)
    }

    @Test
    fun parses_italian_long_date_ignoring_case_and_extra_spaces() {
        val parsed = chapterDateFromItalianDate("  9  gennaio   2026 ", rome)

        assertEquals(millisAt("2026-01-09T00:00:00"), parsed)
    }

    @Test
    fun returns_null_for_an_unknown_italian_month() {
        assertNull(chapterDateFromItalianDate("03 Smaggio 2022", rome))
        assertNull(chapterDateFromItalianDate(null, rome))
    }

    // --- Formattazione mostrata accanto al capitolo ---

    @Test
    fun formats_today_as_Oggi() {
        val now = millisAt("2026-01-11T09:00:00")

        assertEquals("Oggi", formatChapterDate(millisAt("2026-01-11T02:00:00"), now, rome))
    }

    @Test
    fun formats_yesterday_as_Ieri() {
        val now = millisAt("2026-01-11T09:00:00")

        assertEquals("Ieri", formatChapterDate(millisAt("2026-01-10T23:30:00"), now, rome))
    }

    @Test
    fun formats_the_last_week_in_days() {
        val now = millisAt("2026-01-11T09:00:00")

        assertEquals("2 giorni fa", formatChapterDate(millisAt("2026-01-09T20:00:00"), now, rome))
        assertEquals("6 giorni fa", formatChapterDate(millisAt("2026-01-05T00:10:00"), now, rome))
    }

    @Test
    fun formats_a_week_or_more_as_an_absolute_short_date() {
        val now = millisAt("2026-01-11T09:00:00")

        assertEquals("4 gen 2026", formatChapterDate(millisAt("2026-01-04T18:00:00"), now, rome))
        assertEquals("3 mag 2022", formatChapterDate(millisAt("2022-05-03T00:00:00"), now, rome))
    }

    @Test
    fun formats_a_future_date_as_Oggi() {
        // Orologi delle fonti sbilanciati o uscite programmate: mai "-1 giorni fa".
        val now = millisAt("2026-01-11T09:00:00")

        assertEquals("Oggi", formatChapterDate(millisAt("2026-01-12T09:00:00"), now, rome))
    }
}
