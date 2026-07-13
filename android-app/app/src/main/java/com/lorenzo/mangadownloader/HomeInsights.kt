package com.lorenzo.mangadownloader

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calcoli puri dei blocchi Home "Statistiche", "Letti di recente" e "Da finire" (pattern
 * [computeContinueReading]): consumano la libreria già scansionata, niente Android/rete/IO.
 *
 * Limite noto e accettato: tutto è derivato dalla libreria CORRENTE, quindi eliminare capitoli
 * fa calare i numeri (il contatore storico persistito è rinviato: vedi MIGLIORIE.md).
 */
data class HomeStats(
    val seriesCount: Int,
    val chaptersRead: Int,
    val pagesRead: Int,
    val favoritesCount: Int,
) {
    fun isEmpty(): Boolean =
        seriesCount == 0 && chaptersRead == 0 && pagesRead == 0 && favoritesCount == 0
}

fun computeHomeStats(library: List<DownloadedSeries>, favoritesCount: Int): HomeStats {
    var chaptersRead = 0
    var pagesRead = 0
    for (series in library) {
        for (chapter in series.chapters) {
            if (chapter.isRead) chaptersRead++
            val count = chapter.readerPageCount
            pagesRead += when {
                chapter.isRead -> count ?: 0
                chapter.readerPageIndex != null ->
                    (chapter.readerPageIndex + 1).coerceAtMost(count ?: Int.MAX_VALUE)
                else -> 0
            }
        }
    }
    return HomeStats(
        seriesCount = library.size,
        chaptersRead = chaptersRead,
        pagesRead = pagesRead,
        favoritesCount = favoritesCount,
    )
}

/** Una riga di cronologia: capitolo letto (o in corso) con la serie di appartenenza. */
data class ReadingHistoryItem(
    val series: DownloadedSeries,
    val chapter: DownloadedChapter,
)

/**
 * Capitoli con un timestamp di lettura, dal più recente. Include sia i completati sia quelli
 * in corso (a differenza di [computeContinueReading] che tiene solo gli "in corso").
 */
fun computeReadingHistory(
    library: List<DownloadedSeries>,
    limit: Int = Int.MAX_VALUE,
): List<ReadingHistoryItem> {
    return library
        .flatMap { series -> series.chapters.map { ReadingHistoryItem(series, it) } }
        .filter { it.chapter.lastReadAtMillis != null }
        .sortedWith(
            compareByDescending<ReadingHistoryItem> { it.chapter.lastReadAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.series.title.lowercase() }
                .thenBy { it.chapter.numberValue ?: BigDecimal.ZERO }
                .thenBy { it.chapter.numberText },
        )
        .take(limit.coerceAtLeast(0))
}

/** Una serie scaricata con capitoli ancora da leggere. */
data class SeriesToFinish(
    val series: DownloadedSeries,
    val unreadCount: Int,
)

/**
 * Serie con almeno un capitolo scaricato non letto: prima quelle lette di recente (le più
 * "calde"), le mai aperte in fondo, tie-break per titolo così l'ordine è stabile.
 */
fun computeSeriesToFinish(library: List<DownloadedSeries>): List<SeriesToFinish> {
    return library
        .mapNotNull { series ->
            val unread = series.chapters.count { !it.isRead }
            if (unread == 0) null else SeriesToFinish(series, unread)
        }
        .sortedWith(
            compareByDescending<SeriesToFinish> { item ->
                item.series.chapters.maxOfOrNull { it.lastReadAtMillis ?: 0L } ?: 0L
            }.thenBy { it.series.title.lowercase() },
        )
}

/** Etichetta del gruppo-giorno nella pagina Cronologia: "Oggi", "Ieri" o data estesa. */
fun historyDayLabel(
    lastReadAtMillis: Long,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val day = Instant.ofEpochMilli(lastReadAtMillis).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    return when (day) {
        today -> "Oggi"
        today.minusDays(1) -> "Ieri"
        else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN))
    }
}

/** Numeri delle tile Statistiche con separatore migliaia italiano (4.820). */
fun formatStatNumber(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.ITALIAN).format(value.toLong())
