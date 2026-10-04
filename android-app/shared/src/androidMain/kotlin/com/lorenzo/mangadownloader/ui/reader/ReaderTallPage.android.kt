package com.lorenzo.mangadownloader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path

internal actual suspend fun decodeTallPageChunks(
    file: Path,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int?,
): List<ImageBitmap>? =
    withContext(Dispatchers.IO) {
        val decoded = mutableListOf<Bitmap>()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.toString(), bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            // Sotto la soglia non è una striscia: il fallimento ha un'altra causa.
            if (width <= 0 || height < TallPageNormalizationMinHeightPx) {
                return@withContext null
            }

            val ranges = tallPageNormalizationRanges(height)
            val selectedRanges = if (segmentIndex == null) {
                ranges
            } else {
                listOf(ranges.getOrNull(segmentIndex) ?: return@withContext null)
            }
            val selectedHeight = selectedRanges.sumOf { it.count() }
            val memoryBudget = runtimeTallPageMemoryBudgetBytes()
            val useReducedMemoryFallback = !useLegacyVyMangaQuality &&
                !tallReaderPageFitsMemoryBudget(width, selectedHeight, memoryBudget)
            val sampleSize = when {
                useLegacyVyMangaQuality -> legacyVyMangaTallPageSampleSize(width)
                useReducedMemoryFallback -> memoryConstrainedTallPageSampleSize(
                    width = width,
                    height = selectedHeight,
                    maxBytes = memoryBudget,
                )
                else -> 1
            }

            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(file.toString(), false)
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = if (useLegacyVyMangaQuality || useReducedMemoryFallback) {
                        Bitmap.Config.RGB_565
                    } else {
                        Bitmap.Config.ARGB_8888
                    }
                }
                selectedRanges.forEach { rows ->
                    val region = Rect(0, rows.first, width, rows.last + 1)
                    decoded += decoder.decodeRegion(region, options)
                        ?: throw IOException("Impossibile decodificare un blocco della pagina")
                }
            } finally {
                decoder.recycle()
            }
            decoded.map(Bitmap::asImageBitmap)
        } catch (_: Exception) {
            decoded.forEach(Bitmap::recycle)
            null
        } catch (_: OutOfMemoryError) {
            decoded.forEach(Bitmap::recycle)
            null
        }
    }

internal actual fun ImageBitmap.releaseNativeMemory() {
    val bitmap = asAndroidBitmap()
    if (!bitmap.isRecycled) bitmap.recycle()
}

private fun runtimeTallPageMemoryBudgetBytes(): Long =
    minOf(Runtime.getRuntime().maxMemory() / 5L, MaxRuntimeTallPageMemoryBytes)
