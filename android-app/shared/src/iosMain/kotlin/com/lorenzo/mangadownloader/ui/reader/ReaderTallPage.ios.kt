package com.lorenzo.mangadownloader.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.lorenzo.mangadownloader.platform.systemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

// Su iOS Skia decodifica l'immagine intera (niente decoder a regioni): i blocchi si ritagliano
// poi a piena risoluzione. La qualità ridotta "legacy" di VyManga qui non serve.
internal actual suspend fun decodeTallPageChunks(
    file: Path,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int?,
): List<ImageBitmap>? = withContext(Dispatchers.IO) {
    runCatching {
        val image = Image.makeFromEncoded(systemFileSystem.read(file) { readByteArray() })
        val width = image.width
        val height = image.height
        if (width <= 0 || height < TallPageNormalizationMinHeightPx) return@runCatching null
        val ranges = tallPageNormalizationRanges(height)
        val selected = if (segmentIndex == null) {
            ranges
        } else {
            listOf(ranges.getOrNull(segmentIndex) ?: return@runCatching null)
        }
        selected.map { rows ->
            val chunkHeight = rows.last + 1 - rows.first
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(width, chunkHeight)
            Canvas(bitmap).drawImageRect(
                image,
                Rect.makeLTRB(0f, rows.first.toFloat(), width.toFloat(), (rows.last + 1).toFloat()),
                Rect.makeWH(width.toFloat(), chunkHeight.toFloat()),
            )
            bitmap.setImmutable()
            bitmap.asComposeImageBitmap()
        }
    }.getOrNull()
}

// La memoria Skia la libera il garbage collector.
internal actual fun ImageBitmap.releaseNativeMemory() = Unit
