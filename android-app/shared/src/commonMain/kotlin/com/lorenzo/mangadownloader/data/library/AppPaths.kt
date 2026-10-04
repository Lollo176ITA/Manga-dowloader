package com.lorenzo.mangadownloader.data.library

import okio.Path

/**
 * Cartelle dell'app fornite dalla piattaforma. [downloadsRoot] è calcolata a ogni uso: su Android
 * la memoria esterna può non essere disponibile all'avvio e tornare dopo.
 */
class AppPaths(
    private val downloadsRootProvider: () -> Path,
    val cacheRoot: Path,
) {
    val downloadsRoot: Path get() = downloadsRootProvider()

    /** Cartella della libreria: una sottocartella per serie con i .cbz e `series.json`. */
    val libraryRoot: Path get() = downloadsRoot / LIBRARY_FOLDER_NAME

    /** Pagine estratte dai .cbz per il reader (cache LRU). */
    val readerPagesCache: Path get() = cacheRoot / "reader-pages"

    companion object {
        const val LIBRARY_FOLDER_NAME = "MangaDownloader"
    }
}
