package com.lorenzo.mangadownloader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lorenzo.mangadownloader.data.model.ReaderPage
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path

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
    context: PlatformContext,
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
    context: PlatformContext,
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
    context: PlatformContext,
    remote: ReaderPage.Remote,
) {
    try {
        SingletonImageLoader.get(context).diskCache?.remove(remote.url)
        SingletonImageLoader.get(context).execute(
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
    context: PlatformContext,
    url: String,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int? = null,
): List<ImageBitmap>? = withContext(Dispatchers.IO) {
    val diskCache = SingletonImageLoader.get(context).diskCache ?: return@withContext null
    val snapshot = try {
        diskCache.openSnapshot(url)
    } catch (_: Exception) {
        null
    } ?: return@withContext null
    snapshot.use {
        decodeTallPageChunks(
            file = it.data,
            useLegacyVyMangaQuality = useLegacyVyMangaQuality,
            segmentIndex = segmentIndex,
        )
    }
}

/**
 * Decodifica a blocchi orizzontali la striscia in [file] (tutti, o solo [segmentIndex]).
 * `null` se non è una striscia alta o se la decodifica fallisce.
 */
internal expect suspend fun decodeTallPageChunks(
    file: Path,
    useLegacyVyMangaQuality: Boolean,
    segmentIndex: Int? = null,
): List<ImageBitmap>?

/** Libera subito la memoria nativa di un blocco non più mostrato (dove la piattaforma lo consente). */
internal expect fun ImageBitmap.releaseNativeMemory()

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

internal const val LegacyVyMangaTallPageMaxWidthPx = 2048
internal const val MemoryFallbackMaxWidthPx = 2048L
internal const val ArgbBytesPerPixel = 4L
internal const val Rgb565BytesPerPixel = 2L
internal const val MaxRuntimeTallPageMemoryBytes = 64L * 1024L * 1024L

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
