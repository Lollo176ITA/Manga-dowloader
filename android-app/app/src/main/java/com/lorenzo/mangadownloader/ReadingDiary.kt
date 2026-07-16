package com.lorenzo.mangadownloader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Diario di lettura **persistente**: quanto si è letto giorno per giorno (capitoli finiti e
 * pagine viste), chiave = data locale ISO (`2026-07-16`). Complementare alla memoria di
 * lettura ([ReadChapterMemory], che sa *cosa*): il diario sa *quando* e alimenta andamento
 * settimanale, streak, heatmap e record della pagina Statistiche. Qui il core puro
 * (niente Android/IO); la persistenza è in [ReadingDiaryStore].
 *
 * Conta solo la lettura reale nel reader (come la cronologia): il "segna come letto" a mano
 * non entra nel diario. I numeri per giorno non regrediscono mai.
 */
data class ReadingDayStats(
    val chaptersRead: Int = 0,
    val pagesRead: Int = 0,
) {
    val hasActivity: Boolean get() = chaptersRead > 0 || pagesRead > 0
}

/** Giorni tenuti nel diario: abbondanti per heatmap/record senza far crescere le prefs. */
const val READING_DIARY_RETENTION_DAYS = 400

/** Chiave-giorno del diario per un istante, nella zona oraria dell'utente. */
fun diaryDayKey(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().toString()

/** Data di una chiave-giorno; `null` per chiavi corrotte (che vanno scartate, non crashare). */
fun diaryDayOf(dayKey: String): LocalDate? =
    runCatching { LocalDate.parse(dayKey) }.getOrNull()

/** Somma i delta (solo positivi) sul giorno indicato. Stessa istanza se non cambia nulla. */
fun Map<String, ReadingDayStats>.withReadingActivity(
    dayKey: String,
    chaptersDelta: Int,
    pagesDelta: Int,
): Map<String, ReadingDayStats> {
    val chapters = chaptersDelta.coerceAtLeast(0)
    val pages = pagesDelta.coerceAtLeast(0)
    if (chapters == 0 && pages == 0) return this
    val current = this[dayKey] ?: ReadingDayStats()
    return this + (dayKey to current.copy(
        chaptersRead = current.chaptersRead + chapters,
        pagesRead = current.pagesRead + pages,
    ))
}

/** Scarta i giorni più vecchi della finestra di retention e le chiavi illeggibili. */
fun pruneReadingDiary(
    diary: Map<String, ReadingDayStats>,
    today: LocalDate,
    keepDays: Int = READING_DIARY_RETENTION_DAYS,
): Map<String, ReadingDayStats> {
    val cutoff = today.minusDays(keepDays.toLong())
    val pruned = diary.filterKeys { key ->
        val day = diaryDayOf(key) ?: return@filterKeys false
        !day.isBefore(cutoff)
    }
    return if (pruned.size == diary.size) diary else pruned
}

/** Totali nel periodo [from]..[to] (estremi inclusi). */
fun diaryTotalsBetween(
    diary: Map<String, ReadingDayStats>,
    from: LocalDate,
    to: LocalDate,
): ReadingDayStats {
    var chapters = 0
    var pages = 0
    for ((key, stats) in diary) {
        val day = diaryDayOf(key) ?: continue
        if (!day.isBefore(from) && !day.isAfter(to)) {
            chapters += stats.chaptersRead
            pages += stats.pagesRead
        }
    }
    return ReadingDayStats(chaptersRead = chapters, pagesRead = pages)
}

/**
 * Streak corrente: giorni consecutivi con attività che terminano oggi o ieri (un giorno
 * "in corso" senza ancora letture non azzera la serie a metà giornata).
 */
fun currentReadingStreak(diary: Map<String, ReadingDayStats>, today: LocalDate): Int {
    fun hasActivity(day: LocalDate) = diary[day.toString()]?.hasActivity == true
    var day = if (hasActivity(today)) today else today.minusDays(1)
    var streak = 0
    while (hasActivity(day)) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/** Streak più lungo mai registrato nella finestra del diario. */
fun longestReadingStreak(diary: Map<String, ReadingDayStats>): Int {
    val days = diary
        .filterValues { it.hasActivity }
        .keys
        .mapNotNull(::diaryDayOf)
        .sorted()
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in days) {
        run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
        if (run > longest) longest = run
        previous = day
    }
    return longest
}

/** Gli ultimi [days] giorni fino a [today] incluso, in ordine cronologico (buchi = zero). */
fun lastDiaryDays(
    diary: Map<String, ReadingDayStats>,
    days: Int,
    today: LocalDate,
): List<Pair<LocalDate, ReadingDayStats>> {
    return (days - 1 downTo 0).map { offset ->
        val day = today.minusDays(offset.toLong())
        day to (diary[day.toString()] ?: ReadingDayStats())
    }
}

/** La giornata migliore (più capitoli, a parità più pagine); `null` con diario vuoto. */
fun bestReadingDay(diary: Map<String, ReadingDayStats>): Pair<LocalDate, ReadingDayStats>? {
    return diary
        .mapNotNull { (key, stats) -> diaryDayOf(key)?.takeIf { stats.hasActivity }?.to(stats) }
        .maxWithOrNull(
            compareBy<Pair<LocalDate, ReadingDayStats>> { it.second.chaptersRead }
                .thenBy { it.second.pagesRead }
                .thenBy { it.first },
        )
}
