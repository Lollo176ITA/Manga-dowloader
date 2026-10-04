package com.lorenzo.mangadownloader.platform

import okio.IOException
import okio.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/**
 * [ImageOps] su Skia. Skia non ha un decoder a regioni: l'immagine si decodifica una volta e le
 * fasce si ritagliano dalla copia in memoria. Le fasce si salvano in PNG (lossless ovunque).
 */
object IosImageOps : ImageOps {

    override fun readSize(path: Path): ImageSize? = runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(systemFileSystem.read(path) { readByteArray() }))
        ImageSize(codec.width, codec.height)
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }

    override val losslessExtension: String get() = "png"

    override fun writeStrips(source: Path, width: Int, rows: List<IntRange>, destinations: List<Path>) {
        require(rows.size == destinations.size) { "rows and destinations must match" }
        val image = Image.makeFromEncoded(systemFileSystem.read(source) { readByteArray() })
        rows.zip(destinations).forEach { (range, destination) ->
            val height = range.last + 1 - range.first
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(width, height)
            Canvas(bitmap).drawImageRect(
                image,
                Rect.makeLTRB(0f, range.first.toFloat(), width.toFloat(), (range.last + 1).toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
            )
            val encoded = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                ?: throw IOException("Codifica PNG fallita per $destination")
            systemFileSystem.write(destination) { write(encoded.bytes) }
        }
    }
}

actual val platformImageOps: ImageOps get() = IosImageOps
