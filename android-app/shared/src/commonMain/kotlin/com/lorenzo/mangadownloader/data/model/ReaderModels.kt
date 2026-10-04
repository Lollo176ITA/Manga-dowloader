package com.lorenzo.mangadownloader.data.model

import com.lorenzo.mangadownloader.data.library.DownloadedChapter
import com.lorenzo.mangadownloader.data.library.ReaderPagePosition
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheKey
import com.lorenzo.mangadownloader.data.library.StreamingReaderCachedChapter
import com.lorenzo.mangadownloader.domain.reading.displayLabel
import com.lorenzo.mangadownloader.ui.reader.PageHalf
import com.lorenzo.mangadownloader.ui.reader.SpreadRotation
import com.lorenzo.mangadownloader.platform.length
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.systemFileSystem
import okio.Path

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
    abstract val sourceId: String?

    data class Local(
        val file: Path,
        /**
         * Origine remota della pagina, quando nota (capitoli streaming in cache): permette
         * di riscaricarla se il file locale è sparito o corrotto, invece di rileggere
         * all'infinito lo stesso file rotto.
         */
        val remote: Remote? = null,
        override val sourceId: String? = null,
        /** Indice del frammento da recuperare dall'immagine remota completa. */
        val remoteSegmentIndex: Int? = null,
        /**
         * Metà da mostrare quando il file è una pagina doppia divisa (vedi [SpreadPages]).
         * `null` per le pagine normali, che si mostrano intere.
         */
        val half: PageHalf? = null,
    ) : ReaderPage() {
        // Le due metà della stessa pagina sono due voci distinte del pager e della lista:
        // senza il suffisso condividerebbero la chiave, che deve essere unica.
        override val stableKey: String =
            file.toString() + (half?.let { "#${it.name}" } ?: "")

        /** Vero se il file locale non è utilizzabile (sparito o vuoto). */
        val isFileBroken: Boolean get() = !systemFileSystem.isFile(file) || systemFileSystem.length(file) == 0L
    }

    data class Remote(
        val url: String,
        val referer: String,
        override val sourceId: String? = null,
    ) : ReaderPage() {
        override val stableKey: String = url
    }
}

private val persistedTallPagePartPattern = Regex(
    pattern = """^(.+)__part_(\d{4,})\.(?:png|webp)$""",
    option = RegexOption.IGNORE_CASE,
)

/**
 * Identità della pagina alta da cui deriva un frammento persistente. `null` per le
 * pagine normali e remote. Il reader verticale usa questa informazione per applicare
 * la spaziatura soltanto tra pagine originali, mai in mezzo a una striscia.
 */
fun ReaderPage.persistedTallPageGroupKey(): String? {
    val local = this as? ReaderPage.Local ?: return null
    val match = persistedTallPagePartPattern.matchEntire(local.file.name) ?: return null
    return "${local.file.parent?.toString().orEmpty()}::${match.groupValues[1]}"
}

/**
 * Pagine del reader per un capitolo streaming servito dalla cache su disco: locali, ma con
 * l'URL d'origine (allineato per indice, garantito da [StreamingReaderCacheRepository]) come
 * ripiego per recuperare dalla rete una pagina il cui file non si carica più.
 */
fun StreamingReaderCachedChapter.toReaderPages(): List<ReaderPage> {
    return pages.mapIndexed { index, file ->
        ReaderPage.Local(
            file = file,
            remote = pageUrls.getOrNull(index)?.let { url ->
                ReaderPage.Remote(url = url, referer = referer, sourceId = sourceId)
            },
            sourceId = sourceId,
            remoteSegmentIndex = segmentIndexes.getOrNull(index)
                ?.takeIf { segmentCounts.getOrNull(index)?.let { count -> count > 1 } == true },
        )
    }
}

/**
 * Distingue un progresso già salvato sulla lista espansa da uno storico sulla lista
 * remota 1:1. Nel secondo caso converte l'indice della pagina originale nel primo
 * frammento corrispondente.
 */
fun StreamingReaderCachedChapter.restoreReaderPageIndex(
    savedPosition: ReaderPagePosition?,
): Int {
    val savedIndex = savedPosition?.pageIndex ?: 0
    val candidate = if (savedPosition?.pageCount == pages.size) {
        savedIndex
    } else {
        readerPageIndexForOriginalPage(savedIndex) ?: savedIndex
    }
    return candidate.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
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
