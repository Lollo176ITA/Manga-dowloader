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

/** Dove riprendere una lettura: un file in libreria, oppure un capitolo letto in streaming. */
sealed interface ResumeTarget {
    data class Downloaded(val chapter: DownloadedChapter) : ResumeTarget
    data class Streaming(val memory: ReadChapterMemory) : ResumeTarget
}

/**
 * Una lettura da riprendere, già pronta per la card: non importa da dove venga il capitolo.
 * [coverModel] è un `File` per gli scaricati e un URL per lo streaming — [CoverImage] li
 * accetta entrambi.
 */
data class ResumeReadingItem(
    val seriesTitle: String,
    val chapterLabel: String,
    val coverModel: Any?,
    val pageIndex: Int?,
    val pageCount: Int?,
    val lastReadAtMillis: Long,
    val target: ResumeTarget,
)

/**
 * La lettura in corso più recente **di tutta l'app**, non solo della libreria scaricata.
 *
 * Il blocco "Riprendi" della Home nasceva dalla sola libreria: chi legge in streaming non lo
 * vedeva mai comparire, pur avendo un progresso registrato (la memoria di lettura traccia
 * anche l'online) — e per lo stesso motivo si vedeva l'empty state "la Home si riempie
 * mentre leggi" dopo settimane di letture.
 *
 * Funzione **pura**: consuma libreria e memoria già caricate.
 */
fun computeHomeResume(
    library: List<DownloadedSeries>,
    memory: Map<String, ReadChapterMemory>,
): ResumeReadingItem? {
    val downloaded = computeContinueReading(library, limit = 1).firstOrNull()?.let { item ->
        ResumeReadingItem(
            seriesTitle = item.series.title,
            chapterLabel = item.chapter.displayLabel(),
            coverModel = item.series.coverFile,
            pageIndex = item.chapter.readerPageIndex,
            pageCount = item.chapter.readerPageCount,
            lastReadAtMillis = item.chapter.lastReadAtMillis ?: 0L,
            target = ResumeTarget.Downloaded(item.chapter),
        )
    }

    val streaming = memory
        .asSequence()
        .filter { (relativePath, record) ->
            isStreamingMemoryPath(relativePath) &&
                !record.isRead &&
                record.pagesRead > 0 &&
                record.lastReadAtMillis > 0L &&
                // Senza le coordinate la card sarebbe un pulsante che non porta da nessuna
                // parte: meglio non offrirla affatto.
                record.canReopenStreaming()
        }
        .maxByOrNull { (_, record) -> record.lastReadAtMillis }
        ?.value
        ?.let { record ->
            ResumeReadingItem(
                seriesTitle = record.seriesTitle,
                chapterLabel = record.chapterLabel,
                coverModel = record.coverUrl.takeIf(String::isNotBlank),
                // pagesRead è un conteggio, la card ragiona per indice.
                pageIndex = (record.pagesRead - 1).coerceAtLeast(0),
                pageCount = record.pageCount,
                lastReadAtMillis = record.lastReadAtMillis,
                target = ResumeTarget.Streaming(record),
            )
        }

    // A parità di sorgente vince la lettura più recente: è quella che l'utente si aspetta di
    // ritrovare in cima.
    return listOfNotNull(downloaded, streaming).maxByOrNull { it.lastReadAtMillis }
}
