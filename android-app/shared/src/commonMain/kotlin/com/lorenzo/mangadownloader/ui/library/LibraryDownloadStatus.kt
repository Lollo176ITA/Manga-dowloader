package com.lorenzo.mangadownloader.ui.library

import com.lorenzo.mangadownloader.app.DownloadJob
import com.lorenzo.mangadownloader.app.DownloadJobState
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog

data class SeriesDownloadStatus(
    val sourceId: String,
    val seriesTitle: String?,
    val mangaUrl: String?,
    val coverUrl: String?,
    val message: String?,
    val doneChapters: Int,
    val totalChapters: Int,
    val state: DownloadJobState,
    val requestCount: Int,
    /** Tutte le richieste della serie ancora attive: lo stop della card ferma queste. */
    val workIds: List<String> = emptyList(),
)

data class LibraryRowItem(
    val key: String,
    val title: String,
    val series: DownloadedSeries?,
    val downloadStatus: SeriesDownloadStatus?,
)

fun buildSeriesDownloadStatuses(jobs: List<DownloadJob>): Map<String, SeriesDownloadStatus> {
    val sorted = jobs.sortedBy { statePriority(it.displayState()) }
    val grouped = linkedMapOf<String, MutableList<DownloadJob>>()

    for (job in sorted) {
        val key = downloadSeriesKey(
            sourceId = job.sourceId?.trim()?.takeIf(String::isNotBlank),
            mangaUrl = job.mangaUrl?.trim()?.takeIf(String::isNotBlank),
            title = job.seriesTitle?.trim()?.takeIf(String::isNotBlank),
        ) ?: continue
        grouped.getOrPut(key) { mutableListOf() } += job
    }

    return grouped.mapValues { (_, entries) ->
        val job = entries.first()
        SeriesDownloadStatus(
            sourceId = job.sourceId ?: MangaSourceCatalog.resolveSourceId(null, job.mangaUrl),
            seriesTitle = job.seriesTitle,
            mangaUrl = job.mangaUrl,
            coverUrl = job.coverUrl,
            message = job.message,
            doneChapters = job.doneChapters,
            totalChapters = job.totalChapters,
            state = job.displayState(),
            requestCount = entries.size,
            workIds = entries.map(DownloadJob::id),
        )
    }
}

fun buildLibraryRowItems(
    library: List<DownloadedSeries>,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    query: String,
    sort: LibrarySort = LibrarySort.TITLE_ASC,
): List<LibraryRowItem> {
    val rows = mutableListOf<LibraryRowItem>()
    val usedStatusKeys = linkedSetOf<String>()

    library.forEach { series ->
        val status = downloadStatusForSeries(downloadStatuses, series)
        downloadSeriesKey(series.sourceId, series.mangaUrl, series.title)
            ?.takeIf { status != null }
            ?.let(usedStatusKeys::add)
        if (query.isBlank() || series.title.contains(query, ignoreCase = true)) {
            rows += LibraryRowItem(
                key = "series:${series.directory}",
                title = series.title,
                series = series,
                downloadStatus = status,
            )
        }
    }

    downloadStatuses.forEach { (key, status) ->
        if (key in usedStatusKeys) {
            return@forEach
        }
        val title = status.seriesTitle?.takeIf(String::isNotBlank) ?: return@forEach
        if (query.isNotBlank() && !title.contains(query, ignoreCase = true)) {
            return@forEach
        }
        rows += LibraryRowItem(
            key = "pending:$key",
            title = title,
            series = null,
            downloadStatus = status,
        )
    }

    return when (sort) {
        LibrarySort.TITLE_ASC -> rows.sortedWith(
            compareBy { it.title.lowercase() },
        )
        // Serie mai aperte (nessun timestamp) in fondo, in ordine alfabetico tra loro.
        LibrarySort.LAST_READ -> rows.sortedWith(
            compareByDescending<LibraryRowItem> { it.lastReadAtMillis() }
                .thenBy { it.title.lowercase() },
        )
        // Le serie con capitoli ancora da leggere prima; i download in arrivo (senza serie)
        // contano come "da leggere".
        LibrarySort.UNREAD_FIRST -> rows.sortedWith(
            compareBy<LibraryRowItem> { it.series?.isFullyRead() ?: false }
                .thenBy { it.title.lowercase() },
        )
    }
}

private fun LibraryRowItem.lastReadAtMillis(): Long =
    series?.chapters?.maxOfOrNull { it.lastReadAtMillis ?: 0L } ?: 0L

private fun downloadStatusForSeries(
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    series: DownloadedSeries,
): SeriesDownloadStatus? {
    val primaryKey = downloadSeriesKey(series.sourceId, series.mangaUrl, series.title)
    if (primaryKey != null) {
        downloadStatuses[primaryKey]?.let { return it }
    }
    return downloadSeriesKey(series.sourceId, null, series.title)?.let(downloadStatuses::get)
}

private fun downloadSeriesKey(
    sourceId: String?,
    mangaUrl: String?,
    title: String?,
): String? {
    return MangaSourceCatalog.identityKeyOrNull(sourceId, mangaUrl, title)
}

private fun statePriority(state: DownloadJobState): Int {
    return when (state) {
        DownloadJobState.RUNNING -> 0
        DownloadJobState.ENQUEUED -> 1
        DownloadJobState.BLOCKED -> 2
        else -> 3
    }
}

/**
 * Lo stato da mostrare. Un download che aspetta il turno di un'altra serie è in esecuzione per
 * la piattaforma, ma per chi guarda la Libreria è in coda.
 */
private fun DownloadJob.displayState(): DownloadJobState =
    if (state == DownloadJobState.RUNNING && waitingForTurn) DownloadJobState.ENQUEUED else state
