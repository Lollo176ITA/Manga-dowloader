package com.lorenzo.mangadownloader.platform

import okio.Path

data class ImageSize(val width: Int, val height: Int)

/**
 * Operazioni sulle immagini che dipendono dalla piattaforma (decoder e encoder nativi). Il codice
 * comune decide cosa fare; qui c'è solo il come.
 */
interface ImageOps {
    /** Dimensioni lette dall'intestazione, senza decodificare i pixel; `null` se non è un'immagine leggibile. */
    fun readSize(path: Path): ImageSize?

    /** Estensione del formato lossless usato da [writeStrips] (per esempio "webp" o "png"). */
    val losslessExtension: String

    /**
     * Decodifica da [source] le fasce orizzontali [rows] (a tutta larghezza [width]) una alla volta,
     * senza mai tenere in memoria l'immagine intera, e scrive ognuna lossless nel [destinations]
     * corrispondente.
     */
    fun writeStrips(source: Path, width: Int, rows: List<IntRange>, destinations: List<Path>)
}

/** Le [ImageOps] della piattaforma corrente. */
expect val platformImageOps: ImageOps
