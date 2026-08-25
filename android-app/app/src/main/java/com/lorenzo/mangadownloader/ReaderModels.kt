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

    /**
     * In quale ordine mostrare le due metà di una pagina doppia divisa: `true` per l'ordine
     * di lettura dei manga, prima la metà destra.
     *
     * Vale ovunque tranne che in [PAGED], l'unica modalità in cui l'utente ha dichiarato di
     * leggere da sinistra. Anche nello scroll verticale, quindi: le pagine doppie arrivano
     * dai volumi manga, mentre le strisce webtoon — l'altro contenuto tipico di quella
     * modalità — non ne producono mai.
     */
    val splitsSpreadRightFirst: Boolean get() = this != PAGED
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
    abstract val sourceId: String?

    data class Local(
        val file: File,
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
            file.absolutePath + (half?.let { "#${it.name}" } ?: "")

        /** Vero se il file locale non è utilizzabile (sparito o vuoto). */
        val isFileBroken: Boolean get() = !file.isFile || file.length() == 0L
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
internal fun ReaderPage.persistedTallPageGroupKey(): String? {
    val local = this as? ReaderPage.Local ?: return null
    val match = persistedTallPagePartPattern.matchEntire(local.file.name) ?: return null
    return "${local.file.parentFile?.absolutePath.orEmpty()}::${match.groupValues[1]}"
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
internal fun StreamingReaderCachedChapter.restoreReaderPageIndex(
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
