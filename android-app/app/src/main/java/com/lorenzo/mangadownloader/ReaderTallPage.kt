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
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File
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
 * I blocchi sono alti al massimo [TallReaderPageChunkHeightPx] (ben sotto ogni limite
 * texture) e decodificati in RGB_565: per una striscia 800×18000 sono ~29 MB invece
 * dei ~58 MB di ARGB_8888 — le scan non hanno alpha e la differenza non si vede.
 */
internal const val TallReaderPageMinHeightPx = 4096
internal const val TallReaderPageChunkHeightPx = 2048
internal const val TallReaderPageMaxWidthPx = 2048

/**
 * Fasce orizzontali (range di righe, estremi inclusi) in cui spezzare un'immagine alta
 * [imageHeight] px: tutte alte [chunkHeight] tranne l'ultima, che copre il resto.
 */
internal fun tallReaderPageChunkRanges(
    imageHeight: Int,
    chunkHeight: Int = TallReaderPageChunkHeightPx,
): List<IntRange> {
    if (imageHeight <= 0 || chunkHeight <= 0) return emptyList()
    return (0 until imageHeight step chunkHeight).map { top ->
        top until minOf(top + chunkHeight, imageHeight)
    }
}

/**
 * Fattore di sottocampionamento (potenza di 2) perché la larghezza decodificata non
 * superi [maxWidth]: le strisce webtoon tipiche (≤1200 px) restano a piena risoluzione.
 */
internal fun tallReaderPageSampleSize(
    imageWidth: Int,
    maxWidth: Int = TallReaderPageMaxWidthPx,
): Int {
    var sampleSize = 1
    if (imageWidth <= 0 || maxWidth <= 0) return sampleSize
    while (imageWidth / sampleSize > maxWidth) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Prova a decodificare a blocchi la pagina fallita. Torna `null` se la pagina non è
 * una striscia alta (il fallimento ha un'altra causa: la card di retry resta la
 * risposta giusta) o se non abbiamo i byte dell'immagine da nessuna parte.
 * - [ReaderPage.Local]: legge direttamente il file.
 * - [ReaderPage.Remote]: legge la copia nella disk cache di Coil, che scrive i byte
 *   scaricati su disco prima della decodifica — quindi dopo un fallimento da "troppo
 *   alta" l'immagine è già lì, senza un secondo giro di rete.
 */
internal suspend fun decodeTallReaderPageChunks(
    context: Context,
    page: ReaderPage,
): List<ImageBitmap>? {
    return when (page) {
        is ReaderPage.Local -> decodeTallPageChunks(page.file)
        is ReaderPage.Remote -> decodeTallPageChunksFromCoilCache(context, page.url)
    }
}

@OptIn(ExperimentalCoilApi::class)
private suspend fun decodeTallPageChunksFromCoilCache(
    context: Context,
    url: String,
): List<ImageBitmap>? = withContext(Dispatchers.IO) {
    val diskCache = context.imageLoader.diskCache ?: return@withContext null
    val snapshot = try {
        diskCache.openSnapshot(url)
    } catch (_: Exception) {
        null
    } ?: return@withContext null
    snapshot.use { decodeTallPageChunks(it.data.toFile()) }
}

private suspend fun decodeTallPageChunks(file: File): List<ImageBitmap>? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            // Sotto la soglia non è una striscia: il fallimento ha un'altra causa.
            if (width <= 0 || height < TallReaderPageMinHeightPx) {
                return@withContext null
            }

            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
                ?: return@withContext null
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = tallReaderPageSampleSize(width)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                tallReaderPageChunkRanges(height).map { rows ->
                    val region = Rect(0, rows.first, width, rows.last + 1)
                    decoder.decodeRegion(region, options)?.asImageBitmap()
                        ?: return@withContext null
                }
            } finally {
                decoder.recycle()
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

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
