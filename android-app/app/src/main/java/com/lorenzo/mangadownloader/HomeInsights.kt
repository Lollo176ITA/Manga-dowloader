package com.lorenzo.mangadownloader

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calcoli puri dei blocchi Home "Statistiche", "Letti di recente" e "Da finire" (pattern
 * [computeContinueReading]): niente Android/rete/IO.
 *
 * Statistiche e cronologia sono derivate dalla **memoria di lettura persistente**
 * ([ReadChapterMemory]): eliminare una serie scaricata non fa più calare i numeri né sparire
 * le letture passate. La libreria corrente serve solo a risolvere i capitoli ancora apribili.
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

fun computeHomeStats(
    library: List<DownloadedSeries>,
    favoritesCount: Int,
    memory: Map<String, ReadChapterMemory> = emptyMap(),
): HomeStats {
    // Il seed rende il calcolo corretto anche se il chiamante passa una memoria non ancora
    // riallineata alla libreria (idempotente quando lo è già).
    val effective = seedReadingMemory(memory, library)
    return HomeStats(
        seriesCount = library.size,
        chaptersRead = effective.values.count { it.isRead },
        pagesRead = effective.values.sumOf { record ->
            // Con pageCount noto il conteggio è vincolato al capitolo reale: completato =
            // tutte le pagine, in corso = mai oltre il totale (record stantii inclusi).
            record.pageCount?.let { count ->
                if (record.isRead) count else minOf(record.pagesRead, count)
            } ?: record.pagesRead
        },
        favoritesCount = favoritesCount,
    )
}

/**
 * Una riga di cronologia: il record persistito più, se il capitolo è ancora scaricato,
 * serie e capitolo risolti (per copertina e "riapri nel reader").
 */
data class ReadingHistoryItem(
    val relativePath: String,
    val memory: ReadChapterMemory,
    val series: DownloadedSeries?,
    val chapter: DownloadedChapter?,
)

/**
 * Capitoli con un timestamp di lettura, dal più recente, dalla memoria persistente: le letture
 * restano anche dopo aver eliminato i file. Include sia i completati sia quelli in corso
 * (a differenza di [computeContinueReading] che tiene solo gli "in corso").
 */
fun computeReadingHistory(
    memory: Map<String, ReadChapterMemory>,
    library: List<DownloadedSeries>,
    limit: Int = Int.MAX_VALUE,
): List<ReadingHistoryItem> {
    val chaptersByPath = buildMap {
        for (series in library) {
            for (chapter in series.chapters) {
                put(chapter.relativePath, series to chapter)
            }
        }
    }
    // Il seed assorbe anche i progressi vivi della libreria, quindi il record che ne esce
    // è già il merge tra memoria e stato su disco: niente ri-merge per riga.
    return seedReadingMemory(memory, library)
        .asSequence()
        .filter { (_, record) -> record.lastReadAtMillis > 0L }
        .map { (relativePath, record) ->
            val resolved = chaptersByPath[relativePath]
            ReadingHistoryItem(
                relativePath = relativePath,
                memory = record,
                series = resolved?.first,
                chapter = resolved?.second,
            )
        }
        .sortedWith(
            compareByDescending<ReadingHistoryItem> { it.memory.lastReadAtMillis }
                .thenBy { it.memory.seriesTitle.lowercase() }
                .thenBy { it.chapter?.numberValue ?: BigDecimal.ZERO }
                .thenBy { it.memory.chapterLabel },
        )
        .take(limit.coerceAtLeast(0))
        .toList()
}

/** Etichetta di avanzamento di un record: "Completato", "pagina X di Y" o "In corso". */
fun ReadChapterMemory.progressLabel(): String = when {
    isRead -> "Completato"
    pageCount != null && pageCount > 0 && pagesRead > 0 ->
        "pagina ${pagesRead.coerceAtMost(pageCount)} di $pageCount"
    else -> "In corso"
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
