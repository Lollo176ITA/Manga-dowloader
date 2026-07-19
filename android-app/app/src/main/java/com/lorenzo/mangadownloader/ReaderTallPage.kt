package com.lorenzo.mangadownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recupero delle pagine webtoon "a striscia" che Coil non riesce a mostrare.
 *
 * Alcuni capitoli (es. webtoon su MangaWorld) hanno pagine altissime, tipo 800×18000 px:
 * decodificate intere superano il limite massimo di texture della GPU (di solito
 * 4096–16384 px per lato) e Coil fallisce, lasciando la card "Pagina non caricata"
 * anche se il file è perfettamente valido — e il retry non può risolvere, perché non
 * è un problema di rete. Il rimedio è quello dei reader manga classici: decodificare
 * la striscia a blocchi orizzontali con [BitmapRegionDecoder] e impilarli in colonna.
 *
 * I blocchi sono alti al massimo [TallPageNormalizationChunkHeightPx] (ben sotto ogni limite
 * texture) e restano a piena risoluzione e profondità colore. Questo percorso è solo
 * il fallback per capitoli vecchi e streaming non ancora normalizzato: i nuovi download
 * salvano già blocchi persistenti e non arrivano qui.
 */
/**
 * Prova a decodificare a blocchi la pagina fallita. Torna `null` se la pagina non è
 * una striscia alta (il fallimento ha un'altra causa: la card di retry resta la
 * risposta giusta) o se non abbiamo i byte dell'immagine da nessuna parte.
 * - [ReaderPage.Local]: legge direttamente il file; se il file è rotto ma l'origine
 *   remota è nota, ripiega sulla copia in disk cache di Coil (il retry remoto di
 *   readerImageRequest l'ha appena scaricata lì).
 * - [ReaderPage.Remote]: legge la copia nella disk cache di Coil, che scrive i byte
 *   scaricati su disco prima della decodifica — quindi dopo un fallimento da "troppo
 *   alta" l'immagine è già lì, senza un secondo giro di rete.
 */
internal suspend fun decodeTallReaderPageChunks(
    context: Context,
    page: ReaderPage,
): List<ImageBitmap>? {
    return when (page) {
        is ReaderPage.Local -> {
            val localChunks = if (!page.isFileBroken) {
                decodeTallPageChunks(
                    file = page.file,
                    useLegacyVyMangaQuality = page.sourceId == MangaSourceIds.VYMANGA,
                )
            } else {
                null
            }
            localChunks ?: page.remote?.let { remote ->
                decodeRemoteFallbackChunks(
                    context = context,
                    remote = remote,
                    segmentIndex = page.remoteSegmentIndex,
                )
            }
        }
        is ReaderPage.Remote -> decodeTallPageChunksFromCoilCache(
            context = context,
            url = page.url,
            useLegacyVyMangaQuality = page.sourceId == MangaSourceIds.VYMANGA,
        )
    }
}

private suspend fun decodeRemoteFallbackChunks(
    context: Context,
    remote: ReaderPage.Remote,
    segmentIndex: Int?,
): List<ImageBitmap>? {
    val legacyQuality = remote.sourceId == MangaSourceIds.VYMANGA
    return decodeTallPageChunksFromCoilCache(
        context = context,
        url = remote.url,
        useLegacyVyMangaQuality = legacyQuality,
        segmentIndex = segmentIndex,
    ) ?: run {
        refreshRemotePageDiskCache(context, remote)
        decodeTallPageChunksFromCoilCache(
            context = context,
            url = remote.url,
            useLegacyVyMangaQuality = legacyQuality,
            segmentIndex = segmentIndex,
        )
    }
}

@OptIn(ExperimentalCoilApi::class)
private suspend fun refreshRemotePageDiskCache(
    context: Context,
    remote: ReaderPage.Remote,
) {
    try {
        context.imageLoader.diskCache?.remove(remote.url)
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(remote.url)
                .httpHeaders(NetworkHeaders.Builder().set("Referer", remote.referer).build())
                // Basta una miniatura: la disk cache conserva comunque i byte originali.
                .size(1, 1)
                .build(),
        )
    } catch (_: Exception) {
        // Best effort: il chiamante mostrerà la normale card di retry.
    }
}

@OptIn(ExperimentalCoilApi::class)
private suspend fun decodeTallPageChunksFromCoilCache(
    context: Context,
    url: String,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int? = null,
): List<ImageBitmap>? = withContext(Dispatchers.IO) {
    val diskCache = context.imageLoader.diskCache ?: return@withContext null
    val snapshot = try {
        diskCache.openSnapshot(url)
    } catch (_: Exception) {
        null
    } ?: return@withContext null
    snapshot.use {
        decodeTallPageChunks(
            file = it.data.toFile(),
            useLegacyVyMangaQuality = useLegacyVyMangaQuality,
            segmentIndex = segmentIndex,
        )
    }
}

private suspend fun decodeTallPageChunks(
    file: File,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int? = null,
): List<ImageBitmap>? =
    withContext(Dispatchers.IO) {
        val decoded = mutableListOf<Bitmap>()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
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
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
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

internal fun legacyVyMangaTallPageSampleSize(imageWidth: Int): Int {
    var sampleSize = 1
    while (imageWidth / sampleSize > LegacyVyMangaTallPageMaxWidthPx) sampleSize *= 2
    return sampleSize
}

internal fun tallReaderPageFitsMemoryBudget(
    width: Int,
    height: Int,
    maxBytes: Long,
    bytesPerPixel: Long = ArgbBytesPerPixel,
): Boolean {
    if (width <= 0 || height <= 0 || maxBytes <= 0L || bytesPerPixel <= 0L) return false
    return width.toLong() * height.toLong() <= maxBytes / bytesPerPixel
}

internal fun memoryConstrainedTallPageSampleSize(
    width: Int,
    height: Int,
    maxBytes: Long,
): Int {
    if (width <= 0 || height <= 0 || maxBytes <= 0L) return 1
    var sampleSize = 1
    while (sampleSize <= Int.MAX_VALUE / 2) {
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        val fitsWidth = sampledWidth <= MemoryFallbackMaxWidthPx
        val fitsMemory = sampledWidth * sampledHeight <= maxBytes / Rgb565BytesPerPixel
        if (fitsWidth && fitsMemory) return sampleSize
        sampleSize *= 2
    }
    return sampleSize
}

private fun runtimeTallPageMemoryBudgetBytes(): Long =
    minOf(Runtime.getRuntime().maxMemory() / 5L, MaxRuntimeTallPageMemoryBytes)

private const val LegacyVyMangaTallPageMaxWidthPx = 2048
private const val MemoryFallbackMaxWidthPx = 2048L
private const val ArgbBytesPerPixel = 4L
private const val Rgb565BytesPerPixel = 2L
private const val MaxRuntimeTallPageMemoryBytes = 64L * 1024L * 1024L

/**
 * Striscia webtoon renderizzata come colonna di blocchi: ognuno riempie la larghezza
 * e mantiene le proporzioni, quindi la colonna è identica all'immagine originale ma
 * senza mai creare un bitmap oltre i limiti della GPU.
 */
@Composable
internal fun TallReaderPageStrip(
    chunks: List<ImageBitmap>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        chunks.forEachIndexed { index, chunk ->
            Image(
                bitmap = chunk,
                contentDescription = contentDescription.takeIf { index == 0 },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(chunk.width.toFloat() / chunk.height.toFloat()),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
