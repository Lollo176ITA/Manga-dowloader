package com.lorenzo.mangadownloader

/**
 * Memoria di lettura **persistente**: cosa è stato letto (capitoli, pagine, quando), sganciata
 * dai file `.cbz` scaricati. Prima le statistiche e la cronologia erano derivate dalla sola
 * libreria corrente: eliminare una serie azzerava tutto. Qui vive il core puro (niente
 * Android/IO): modello, merge monotono e derivazioni; la persistenza è in [ReadingMemoryStore].
 *
 * Chiave dei record: il `relativePath` del capitolo — la stessa chiave usata per il progresso
 * del reader, stabile tra un download e l'altro (scaricati) e univoca per capitolo (streaming).
 */
data class ReadChapterMemory(
    /** Identità della serie: cartella per gli scaricati, chiave streaming per l'online. */
    val seriesKey: String,
    val seriesTitle: String,
    /** Etichetta del capitolo così com'era mostrata ("Capitolo 12", titolo...). */
    val chapterLabel: String,
    /** Pagine viste (conteggio, non indice): non cala mai. */
    val pagesRead: Int,
    val pageCount: Int?,
    val isRead: Boolean,
    /** 0 = mai letto nel reader (es. segnato letto a mano): resta fuori dalla cronologia. */
    val lastReadAtMillis: Long,
    /** Fonte del capitolo ([MangaSourceIds]); "" per i record storici che non la conoscono. */
    val sourceId: String = "",
)

/**
 * Merge monotono di due record dello stesso capitolo: i numeri non regrediscono mai
 * (pagine/timestamp al massimo, letto in OR). Usato dal seed dalla libreria e dal restore
 * di un backup; le correzioni esplicite dell'utente (segna come NON letto) non passano di qui.
 */
fun ReadChapterMemory.mergedWith(other: ReadChapterMemory): ReadChapterMemory = ReadChapterMemory(
    seriesKey = other.seriesKey.ifBlank { seriesKey },
    seriesTitle = other.seriesTitle.ifBlank { seriesTitle },
    chapterLabel = other.chapterLabel.ifBlank { chapterLabel },
    pagesRead = maxOf(pagesRead, other.pagesRead),
    pageCount = other.pageCount ?: pageCount,
    isRead = isRead || other.isRead,
    lastReadAtMillis = maxOf(lastReadAtMillis, other.lastReadAtMillis),
    sourceId = other.sourceId.ifBlank { sourceId },
)

/** Etichetta visuale di un capitolo scaricato, con lo stesso ripiego usato nelle liste. */
fun DownloadedChapter.displayLabel(): String =
    title.ifBlank { "$labelPrefix $numberText".trim() }

/** Record derivato da un capitolo scaricato; `null` se il capitolo non ha alcun progresso. */
fun readingMemoryOf(series: DownloadedSeries, chapter: DownloadedChapter): ReadChapterMemory? {
    val pagesSeen = when {
        chapter.isRead -> chapter.readerPageCount ?: (chapter.readerPageIndex?.plus(1)) ?: 0
        chapter.readerPageIndex != null -> chapter.readerPageIndex + 1
        else -> 0
    }
    if (!chapter.isRead && pagesSeen == 0 && chapter.lastReadAtMillis == null) return null
    return ReadChapterMemory(
        seriesKey = seriesKeyOf(chapter.relativePath),
        seriesTitle = series.title,
        chapterLabel = chapter.displayLabel(),
        // Cap al numero di pagine reale: una posizione stantia (capitolo riscaricato in
        // un'edizione più corta) non deve gonfiare il contatore in modo irreversibile.
        pagesRead = chapter.readerPageCount?.let { minOf(pagesSeen, it) } ?: pagesSeen,
        pageCount = chapter.readerPageCount,
        isRead = chapter.isRead,
        lastReadAtMillis = chapter.lastReadAtMillis ?: 0L,
        sourceId = series.sourceId,
    )
}

/** Identità-serie di un capitolo scaricato: la cartella nel `relativePath`. */
fun seriesKeyOf(relativePath: String): String = relativePath.substringBefore('/')

/** Vero per i record dei capitoli letti in streaming (chiave `streaming:<dir>` del reader). */
fun isStreamingMemoryPath(relativePath: String): Boolean = relativePath.startsWith("streaming:")

/**
 * Seed/riallineamento dalla libreria corrente: assorbe nella memoria i progressi presenti su
 * disco (migrazione automatica per chi aveva già letture, e rete di sicurezza per ogni percorso
 * non instrumentato). Merge monotono: non cancella mai ciò che la libreria non ha più.
 */
fun seedReadingMemory(
    memory: Map<String, ReadChapterMemory>,
    library: List<DownloadedSeries>,
): Map<String, ReadChapterMemory> {
    var changed = false
    val merged = memory.toMutableMap()
    for (series in library) {
        for (chapter in series.chapters) {
            val record = readingMemoryOf(series, chapter) ?: continue
            val existing = merged[chapter.relativePath]
            val next = existing?.mergedWith(record) ?: record
            if (next != existing) {
                merged[chapter.relativePath] = next
                changed = true
            }
        }
    }
    return if (changed) merged else memory
}

/**
 * Reidrata lo stato "letto" dalla memoria su una serie appena scansionata: una serie eliminata
 * e riscaricata ritrova i capitoli già letti (metadati su disco persi, memoria no). Non tocca
 * la posizione di pagina: riaprire il capitolo riparte comunque dal punto salvato nelle prefs.
 */
fun DownloadedSeries.withReadingMemoryApplied(
    memory: Map<String, ReadChapterMemory>,
): DownloadedSeries {
    if (chapters.none { !it.isRead && memory[it.relativePath]?.isRead == true }) return this
    val updatedChapters = chapters.map { chapter ->
        if (!chapter.isRead && memory[chapter.relativePath]?.isRead == true) {
            chapter.copy(isRead = true)
        } else {
            chapter
        }
    }
    return copy(
        chapters = updatedChapters,
        readChapterIds = readChapterIds + updatedChapters.filter { it.isRead }.map { it.chapterId },
    )
}
