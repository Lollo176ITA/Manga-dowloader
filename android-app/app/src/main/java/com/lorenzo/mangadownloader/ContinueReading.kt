package com.lorenzo.mangadownloader

import java.math.BigDecimal

/**
 * Una riga "Continua a leggere": il capitolo da riprendere con la serie a cui appartiene
 * (serve sia per cover/titolo nella card sia per riaprire il reader, che vuole un [DownloadedChapter]).
 */
data class ContinueReadingItem(
    val series: DownloadedSeries,
    val chapter: DownloadedChapter,
)

/**
 * Capitoli "in corso" (iniziati ma non finiti) di tutta la libreria, dal più recente al più
 * vecchio. Funzione **pura** (niente Android/rete): consuma la libreria già scansionata.
 *
 * "In corso" = [DownloadedChapter.hasReaderProgress] && ![DownloadedChapter.isReaderCompleted]
 * (le stesse condizioni di [DownloadedSeries.resumeChapter]). L'ordine è per
 * [DownloadedChapter.lastReadAtMillis] decrescente con i `null` in fondo (capitoli letti prima
 * dell'introduzione del timestamp), poi un tie-break deterministico per titolo/numero così la
 * card resta stabile tra una recomposition e l'altra.
 */
fun computeContinueReading(
    library: List<DownloadedSeries>,
    limit: Int = 1,
): List<ContinueReadingItem> {
    return library
        .flatMap { series -> series.chapters.map { ContinueReadingItem(series, it) } }
        .filter { it.chapter.hasReaderProgress() && !it.chapter.isReaderCompleted() }
        .sortedWith(
            compareByDescending<ContinueReadingItem> { it.chapter.lastReadAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.series.title.lowercase() }
                .thenBy { it.chapter.numberValue ?: BigDecimal.ZERO }
                .thenBy { it.chapter.numberText },
        )
        .take(limit.coerceAtLeast(0))
}

/** Il singolo capitolo in corso più recente, o null se non ce ne sono. */
fun List<DownloadedSeries>.mostRecentInProgressChapter(): ContinueReadingItem? =
    computeContinueReading(this, limit = 1).firstOrNull()
