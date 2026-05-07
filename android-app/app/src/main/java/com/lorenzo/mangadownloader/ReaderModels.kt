package com.lorenzo.mangadownloader

import java.io.File

data class ReaderChapter(
    val title: String,
    val relativePath: String,
    val isRead: Boolean = false,
    val readerPageIndex: Int? = null,
    val readerPageCount: Int? = null,
    val downloadedChapter: DownloadedChapter? = null,
    val streamingChapter: StreamingReaderChapter? = null,
)

data class StreamingReaderChapter(
    val sourceId: String,
    val mangaTitle: String,
    val mangaUrl: String,
    val chapter: ChapterEntry,
    val chapters: List<ChapterEntry>,
)

sealed class ReaderPage {
    abstract val stableKey: String

    data class Local(
        val file: File,
    ) : ReaderPage() {
        override val stableKey: String = file.absolutePath
    }

    data class Remote(
        val url: String,
        val referer: String,
    ) : ReaderPage() {
        override val stableKey: String = url
    }
}

fun DownloadedChapter.toReaderChapter(): ReaderChapter {
    return ReaderChapter(
        title = title,
        relativePath = relativePath,
        isRead = isRead,
        readerPageIndex = readerPageIndex,
        readerPageCount = readerPageCount,
        downloadedChapter = this,
    )
}

fun StreamingReaderChapter.toReaderChapter(isRead: Boolean = false): ReaderChapter {
    val key = StreamingReaderCacheKey(
        sourceId = sourceId,
        mangaUrl = mangaUrl,
        chapterUrl = chapter.url,
    )
    return ReaderChapter(
        title = chapter.displayLabel(),
        relativePath = "streaming:${key.directoryName()}",
        isRead = isRead,
        streamingChapter = this,
    )
}
