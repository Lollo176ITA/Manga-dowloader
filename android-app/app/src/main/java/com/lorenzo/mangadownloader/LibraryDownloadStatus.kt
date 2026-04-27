package com.lorenzo.mangadownloader

import androidx.work.WorkInfo

data class SeriesDownloadStatus(
    val sourceId: String,
    val seriesTitle: String?,
    val mangaUrl: String?,
    val coverUrl: String?,
    val message: String?,
    val doneChapters: Int,
    val totalChapters: Int,
    val state: WorkInfo.State,
    val requestCount: Int,
)

data class LibraryRowItem(
    val key: String,
    val title: String,
    val series: DownloadedSeries?,
    val downloadStatus: SeriesDownloadStatus?,
)

fun buildSeriesDownloadStatuses(workInfos: List<WorkInfo>): Map<String, SeriesDownloadStatus> {
    val sorted = workInfos.sortedBy { statePriority(it.state) }
    val grouped = linkedMapOf<String, MutableList<WorkInfo>>()

    for (workInfo in sorted) {
        val sourceId = workInfo.progress.getString(DownloadWorker.PROGRESS_SOURCE_ID)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_SOURCE_ID_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val title = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_SERIES_TITLE_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val key = downloadSeriesKey(sourceId = sourceId, mangaUrl = mangaUrl, title = title) ?: continue
        grouped.getOrPut(key) { mutableListOf() } += workInfo
    }

    return grouped.mapValues { (_, entries) ->
        val workInfo = entries.first()
        SeriesDownloadStatus(
            sourceId = workInfo.progress.getString(DownloadWorker.PROGRESS_SOURCE_ID)
                ?: workInfo.tagValue(DownloadWorker.TAG_SOURCE_ID_PREFIX)
                ?: MangaSourceCatalog.resolveSourceId(
                    null,
                    workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
                        ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX),
                ),
            seriesTitle = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
                ?: workInfo.tagValue(DownloadWorker.TAG_SERIES_TITLE_PREFIX),
            mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
                ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX),
            coverUrl = workInfo.tagValue(DownloadWorker.TAG_COVER_URL_PREFIX),
            message = workInfo.progress.getString(DownloadWorker.PROGRESS_MESSAGE),
            doneChapters = workInfo.progress.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1),
            totalChapters = workInfo.progress.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1),
            state = workInfo.state,
            requestCount = entries.size,
        )
    }
}

fun buildLibraryRowItems(
    library: List<DownloadedSeries>,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    query: String,
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
                key = "series:${series.directory.absolutePath}",
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

    return rows.sortedWith(
        compareBy<LibraryRowItem> { it.title.lowercase() },
    )
}

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

private fun statePriority(state: WorkInfo.State): Int {
    return when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED -> 1
        WorkInfo.State.BLOCKED -> 2
        else -> 3
    }
}

private fun WorkInfo.tagValue(prefix: String): String? {
    return tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
}

fun DownloadedSeries.isFullyRead(): Boolean {
    return totalChapterCount > 0 && readChapterCount() >= totalChapterCount
}

fun DownloadedSeries.resumeChapter(): DownloadedChapter? {
    return chapters.lastOrNull { it.hasUnfinishedReaderPosition() }
        ?: chapters.firstOrNull { !it.isRead }
        ?: chapters.lastOrNull()
}

fun DownloadedSeries.readChapterCount(): Int {
    return readChapterIds.size.coerceAtMost(totalChapterCount.coerceAtLeast(0))
}

fun DownloadedSeries.readProgressPercent(): Int {
    if (totalChapterCount <= 0) return 0
    return ((readChapterCount() * 100f) / totalChapterCount.toFloat()).toInt()
}

fun DownloadedSeries.readProgressLabel(): String {
    val readCount = readChapterCount()
    return when {
        totalChapterCount <= 0 -> "$readCount letti"
        readCount >= totalChapterCount -> "Completato · $readCount / $totalChapterCount"
        else -> "${readProgressPercent()}% letto · $readCount / $totalChapterCount"
    }
}

private fun DownloadedChapter.hasUnfinishedReaderPosition(): Boolean {
    val pageIndex = readerPageIndex ?: return false
    val pageCount = readerPageCount ?: return true
    return pageCount <= 0 || pageIndex < pageCount - 1
}
