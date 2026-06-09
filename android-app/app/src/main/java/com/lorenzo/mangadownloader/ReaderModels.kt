package com.lorenzo.mangadownloader

import java.io.File

/**
 * Come vengono sfogliate le pagine nel reader.
 * - [VERTICAL]: scroll verticale continuo (webtoon), modalità storica.
 * - [PAGED]: una pagina per volta, si sfoglia da sinistra a destra (occidentale).
 * - [PAGED_RTL]: come [PAGED] ma da destra a sinistra, il senso di lettura dei manga.
 */
enum class ReadingMode(val menuLabel: String, val shortLabel: String) {
    VERTICAL("Scroll verticale", "Verticale"),
    PAGED("A pagine", "Pagine"),
    PAGED_RTL("A pagine (da destra)", "Manga");

    /** Vero per entrambe le modalità a pagine (occidentale e manga). */
    val isPaged: Boolean get() = this == PAGED || this == PAGED_RTL

    /** Vero solo per la modalità manga: lo swipe e l'ordine pagine vanno da destra a sinistra. */
    val isRightToLeft: Boolean get() = this == PAGED_RTL
}

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
