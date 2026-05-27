package com.lorenzo.mangadownloader

/**
 * Applicazione del progresso di lettura (posizione pagina / "letto") allo stato in memoria.
 * Estratta da `MangaViewModel.withReaderProgress` per essere pura e testabile, e per evitare
 * di **ricostruire l'intera libreria a ogni pagina sfogliata**: le entry non toccate dal
 * capitolo corrente vengono restituite come la stessa istanza (meno allocazioni in lettura).
 */

fun DownloadedChapter.withReaderProgressApplied(
    relativePath: String,
    pageIndex: Int?,
    pageCount: Int?,
    markRead: Boolean,
): DownloadedChapter {
    if (this.relativePath != relativePath) return this
    return copy(
        readerPageIndex = pageIndex ?: readerPageIndex,
        readerPageCount = pageCount ?: readerPageCount,
        isRead = isRead || markRead,
    )
}

fun DownloadedSeries.withReaderProgressApplied(
    relativePath: String,
    pageIndex: Int?,
    pageCount: Int?,
    markRead: Boolean,
    readChapterId: String?,
): DownloadedSeries {
    // La serie che non contiene il capitolo non cambia: stessa istanza, niente copy/map.
    if (chapters.none { it.relativePath == relativePath }) {
        return this
    }
    val updatedChapters = chapters.map {
        it.withReaderProgressApplied(relativePath, pageIndex, pageCount, markRead)
    }
    return copy(
        chapters = updatedChapters,
        readChapterIds = if (markRead && readChapterId != null) {
            readChapterIds + readChapterId
        } else {
            readChapterIds
        },
    )
}
