package com.lorenzo.mangadownloader

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core puro del diario di lettura: incrementi, retention e derivazioni (streak, record). */
class ReadingDiaryTest {

    private val today = LocalDate.of(2026, 7, 16)

    private fun day(offset: Long): String = today.minusDays(offset).toString()

    // --- withReadingActivity / pruneReadingDiary ---

    @Test
    fun activity_accumulatesOnSameDay_andIgnoresZeroDeltas() {
        var diary = emptyMap<String, ReadingDayStats>()
        diary = diary.withReadingActivity(day(0), chaptersDelta = 1, pagesDelta = 20)
        diary = diary.withReadingActivity(day(0), chaptersDelta = 0, pagesDelta = 5)
        assertEquals(ReadingDayStats(chaptersRead = 1, pagesRead = 25), diary[day(0)])
        // Nessun delta: stessa istanza, niente scritture inutili.
        assertSame(diary, diary.withReadingActivity(day(0), 0, 0))
        assertSame(diary, diary.withReadingActivity(day(0), -3, -1))
    }

    @Test
    fun prune_dropsOldAndCorruptKeys_keepsRecent() {
        val diary = mapOf(
            day(0) to ReadingDayStats(1, 10),
            day(READING_DIARY_RETENTION_DAYS.toLong() + 1) to ReadingDayStats(9, 90),
            "non-una-data" to ReadingDayStats(5, 50),
        )
        val pruned = pruneReadingDiary(diary, today)
        assertEquals(setOf(day(0)), pruned.keys)
    }

    // --- totali / ultimi giorni ---

    @Test
    fun totals_sumOnlyDaysInRange() {
        val diary = mapOf(
            day(0) to ReadingDayStats(2, 30),
            day(6) to ReadingDayStats(1, 10),
            day(7) to ReadingDayStats(4, 40), // fuori dagli ultimi 7 giorni
        )
        assertEquals(
            ReadingDayStats(chaptersRead = 3, pagesRead = 40),
            diaryTotalsBetween(diary, today.minusDays(6), today),
        )
    }

    @Test
    fun lastDays_isChronological_withZeroGaps() {
        val diary = mapOf(day(1) to ReadingDayStats(2, 20))
        val last3 = lastDiaryDays(diary, days = 3, today = today)
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), last3.map { it.first })
        assertEquals(listOf(0, 2, 0), last3.map { it.second.chaptersRead })
    }

    // --- streak ---

    @Test
    fun streak_countsConsecutiveDaysEndingTodayOrYesterday() {
        val diary = mapOf(
            day(1) to ReadingDayStats(1, 10),
            day(2) to ReadingDayStats(0, 5), // anche solo pagine tengono vivo lo streak
            day(4) to ReadingDayStats(1, 10), // buco al giorno 3: non conta
        )
        // Oggi ancora senza letture: lo streak (ieri+altroieri) non si azzera a metà giornata.
        assertEquals(2, currentReadingStreak(diary, today))
        assertEquals(
            3,
            currentReadingStreak(diary + (day(0) to ReadingDayStats(1, 1)), today),
        )
    }

    @Test
    fun streak_zeroWhenLastActivityIsOlderThanYesterday() {
        assertEquals(0, currentReadingStreak(mapOf(day(2) to ReadingDayStats(1, 1)), today))
    }

    @Test
    fun longestStreak_findsBestRunEver() {
        val diary = mapOf(
            day(10) to ReadingDayStats(1, 1),
            day(9) to ReadingDayStats(1, 1),
            day(8) to ReadingDayStats(1, 1),
            day(1) to ReadingDayStats(1, 1),
        )
        assertEquals(3, longestReadingStreak(diary))
        assertEquals(0, longestReadingStreak(emptyMap()))
    }

    // --- record ---

    @Test
    fun bestDay_prefersChaptersThenPages() {
        val diary = mapOf(
            day(1) to ReadingDayStats(3, 10),
            day(2) to ReadingDayStats(3, 50),
            day(3) to ReadingDayStats(1, 200),
        )
        assertEquals(today.minusDays(2), bestReadingDay(diary)?.first)
        assertNull(bestReadingDay(emptyMap()))
    }

    @Test
    fun dayKey_roundTripsThroughParser() {
        val key = diaryDayKey(1_770_000_000_000L)
        assertTrue(diaryDayOf(key) != null)
        assertNull(diaryDayOf("garbage"))
    }
}
