package com.lorenzo.mangadownloader.ui.library

import com.lorenzo.mangadownloader.data.library.DownloadedChapter
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.platform.length
import com.lorenzo.mangadownloader.platform.systemFileSystem

fun DownloadedSeries.isFullyRead(): Boolean {
    return totalChapterCount > 0 && readChapterCount() >= totalChapterCount
}

fun DownloadedSeries.resumeChapter(): DownloadedChapter? {
    return chapters.lastOrNull { it.hasReaderProgress() && !it.isReaderCompleted() }
        ?: chapters.firstOrNull { !it.isRead }
        ?: chapters.lastOrNull()
}

fun DownloadedSeries.readChapterCount(): Int {
    return readChapterIds.size.coerceAtMost(totalChapterCount.coerceAtLeast(0))
}

/** I capitoli **scaricati e già letti**: ciò che si può eliminare per liberare spazio. */
fun DownloadedSeries.readDownloadedChapters(): List<DownloadedChapter> =
    chapters.filter { it.isRead }

/** Byte occupati dai capitoli letti (somma le dimensioni dei file). IO leggero (pochi file). */
fun DownloadedSeries.readChaptersSizeBytes(): Long =
    readDownloadedChapters().sumOf { systemFileSystem.length(it.file) }

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

fun DownloadedChapter.hasReaderProgress(): Boolean {
    return readerPageIndex != null
}

fun DownloadedChapter.readerProgressDescription(): String {
    val pageIndex = readerPageIndex ?: return "Lettura in corso"
    val pageCount = readerPageCount
    return if (pageCount != null && pageCount > 0) {
        "Riprendi da pagina ${pageIndex + 1} di $pageCount"
    } else {
        "Lettura in corso"
    }
}

fun DownloadedChapter.isReaderCompleted(): Boolean {
    if (!isRead) return false
    val pageIndex = readerPageIndex ?: return true
    val pageCount = readerPageCount ?: return false
    return pageCount > 0 && pageIndex >= pageCount - 1
}
