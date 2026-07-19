package com.lorenzo.mangadownloader

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class StreamingReaderCacheKey(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
) {
    fun directoryName(): String {
        val raw = "${MangaSourceCatalog.resolveSourceId(sourceId, mangaUrl)}\n$mangaUrl\n$chapterUrl"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class StreamingReaderCachedChapter(
    val title: String,
    val pages: List<File>,
    /** URL remoto d'origine di ogni file in [pages], ripetuto per i segmenti della stessa pagina. */
    val pageUrls: List<String>,
    val referer: String,
    /** Indice della pagina remota da cui deriva ogni file locale. */
    val originalPageIndexes: List<Int> = pages.indices.toList(),
    val segmentIndexes: List<Int> = List(pages.size) { 0 },
    val segmentCounts: List<Int> = List(pages.size) { 1 },
    val sourceId: String? = null,
) {
    fun readerPageIndexForOriginalPage(originalPageIndex: Int): Int? {
        return originalPageIndexes.indexOf(originalPageIndex).takeIf { it >= 0 }
    }
}

/**
 * Risolve il formato corrente oppure quello legacy 1:1. Nel formato corrente l'ordine e la
 * cardinalita' dei segmenti sono validati, cosi' una cache parziale non viene mai esposta.
 */
private fun StreamingReaderCacheMetadata.resolvedCachedPages(): List<StreamingReaderCachedPageMetadata>? {
    if (pageUrls.isEmpty()) return null
    if (cachedPages.isEmpty()) {
        if (pages.size != pageUrls.size) return null
        if (pages.any { File(it).name != it } || pages.distinct().size != pages.size) return null
        return pages.mapIndexed { index, fileName ->
            StreamingReaderCachedPageMetadata(
                fileName = fileName,
                sourceUrl = pageUrls[index],
                originalPageIndex = index,
            )
        }
    }

    if (cachedPages.any { File(it.fileName).name != it.fileName } ||
        cachedPages.map(StreamingReaderCachedPageMetadata::fileName).distinct().size != cachedPages.size
    ) {
        return null
    }
    val grouped = cachedPages.groupBy(StreamingReaderCachedPageMetadata::originalPageIndex)
    if (grouped.keys != pageUrls.indices.toSet()) return null
    pageUrls.indices.forEach { originalPageIndex ->
        val segments = grouped.getValue(originalPageIndex)
        if (segments.any { it.sourceUrl != pageUrls[originalPageIndex] } ||
            segments.map(StreamingReaderCachedPageMetadata::segmentIndex) != segments.indices.toList() ||
            segments.any { it.segmentCount != segments.size }
        ) {
            return null
        }
    }
    val expectedOrder = cachedPages.sortedWith(
        compareBy(
            StreamingReaderCachedPageMetadata::originalPageIndex,
            StreamingReaderCachedPageMetadata::segmentIndex,
        ),
    )
    return cachedPages.takeIf { it == expectedOrder }
}

@Serializable
data class StreamingReaderCachedPageMetadata(
    val fileName: String,
    val sourceUrl: String,
    val originalPageIndex: Int,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 1,
)

@Serializable
data class StreamingReaderCacheMetadata(
    val sourceId: String? = null,
    val mangaUrl: String? = null,
    val chapterUrl: String? = null,
    val title: String = "",
    val pageUrls: List<String> = emptyList(),
    /** Compatibilita' con i metadata storici; [cachedPages] e' autorevole nel nuovo formato. */
    val pages: List<String> = emptyList(),
    val cachedPages: List<StreamingReaderCachedPageMetadata> = emptyList(),
    val referer: String = "",
    val lastAccessAtMs: Long = 0L,
) {
    companion object {
        private const val FILE_NAME = "metadata.json"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun read(directory: File): StreamingReaderCacheMetadata? {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return null
            return try {
                json.decodeFromString<StreamingReaderCacheMetadata>(file.readText()).normalized()
            } catch (_: Exception) {
                null
            }
        }

        fun write(
            directory: File,
            metadata: StreamingReaderCacheMetadata,
        ) {
            directory.mkdirs()
            File(directory, FILE_NAME).writeText(json.encodeToString(metadata))
        }

        private fun StreamingReaderCacheMetadata.normalized(): StreamingReaderCacheMetadata {
            return copy(
                pageUrls = pageUrls.mapNotNull { it.trim().takeIf(String::isNotBlank) },
                pages = pages.mapNotNull { it.trim().takeIf(String::isNotBlank) },
                cachedPages = cachedPages.mapNotNull { page ->
                    val fileName = page.fileName.trim()
                    val sourceUrl = page.sourceUrl.trim()
                    if (fileName.isBlank() || sourceUrl.isBlank()) {
                        null
                    } else {
                        page.copy(fileName = fileName, sourceUrl = sourceUrl)
                    }
                },
            )
        }
    }
}

class StreamingReaderCacheRepository(
    private val cacheRoot: File,
    // Scarica la pagina [url] scrivendola direttamente su [target] (streaming, niente pagina
    // intera in RAM). Vedi il costruttore di comodo per l'implementazione reale.
    private val fetchPageToFile: suspend (url: String, referer: String, target: File) -> Unit,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maxCachedChapters: Int = MAX_CACHED_CHAPTERS,
    private val downloadConcurrency: Int = DOWNLOAD_CONCURRENCY,
    // Il costruttore primario resta testabile su JVM senza dipendenze Android.
    private val normalizePage: (source: File, outputDirectory: File, outputBaseName: String) -> List<File> =
        { source, _, _ -> listOf(source) },
) {
    /**
     * @param reusablePageCopier prova a copiare su `target` una pagina già scaricata altrove
     *   (tipicamente dalla disk-cache di Coil, che l'ha scaricata per mostrarla nel reader) e
     *   ritorna `true` se ci è riuscita. Così una pagina già vista non viaggia sulla rete una
     *   seconda volta solo per finire in cache. Default: nessun riuso (sempre rete).
     */
    constructor(
        context: Context,
        networkClient: MangaNetworkClient,
        reusablePageCopier: (url: String, target: File) -> Boolean = { _, _ -> false },
    ) : this(
        cacheRoot = File(context.cacheDir, CACHE_DIR_NAME),
        fetchPageToFile = { url, referer, target ->
            if (!reusablePageCopier(url, target)) {
                target.outputStream().buffered().use { output ->
                    networkClient.fetchToStream(url, sink = output, referer = referer)
                }
            }
        },
        normalizePage = { source, outputDirectory, outputBaseName ->
            TallPageNormalizer.normalize(source, outputDirectory, outputBaseName).files
        },
    )

    fun getCachedChapter(key: StreamingReaderCacheKey): StreamingReaderCachedChapter? {
        val directory = directoryFor(key)
        val metadata = StreamingReaderCacheMetadata.read(directory) ?: return null
        val cachedPages = metadata.resolvedCachedPages()
        val pages = cachedPages?.map { File(directory, it.fileName) }.orEmpty()
        // Un file vuoto (scrittura troncata, spazio esaurito) è una pagina persa quanto un
        // file mancante: la cache si butta e il capitolo si riscarica da capo.
        val complete = cachedPages != null &&
            pages.all { it.isFile && it.length() > 0L }

        if (!complete) {
            directory.deleteRecursively()
            return null
        }

        val updated = metadata.copy(lastAccessAtMs = nowMillis())
        StreamingReaderCacheMetadata.write(directory, updated)
        return StreamingReaderCachedChapter(
            title = updated.title,
            pages = pages,
            pageUrls = cachedPages.orEmpty().map(StreamingReaderCachedPageMetadata::sourceUrl),
            referer = updated.referer,
            originalPageIndexes = cachedPages.orEmpty()
                .map(StreamingReaderCachedPageMetadata::originalPageIndex),
            segmentIndexes = cachedPages.orEmpty()
                .map(StreamingReaderCachedPageMetadata::segmentIndex),
            segmentCounts = cachedPages.orEmpty()
                .map(StreamingReaderCachedPageMetadata::segmentCount),
            sourceId = MangaSourceCatalog.resolveSourceId(
                updated.sourceId ?: key.sourceId,
                updated.mangaUrl ?: key.mangaUrl,
            ),
        )
    }

    /**
     * Scarica in cache tutte le pagine del capitolo. Le pagine sono scaricate in parallelo
     * (fino a [downloadConcurrency]) e scritte una per una su file temporanei `.part` poi
     * rinominati: nessun capitolo intero tenuto in RAM (prima venivano accumulate tutte le
     * pagine come `ByteArray`, con picchi di decine di MB). Ogni pagina passa da
     * [fetchPageToFile], che riusa la copia già scaricata da Coil quando disponibile.
     * All-or-nothing: al primo errore la cartella viene cancellata e l'eccezione propagata.
     */
    suspend fun cacheCompleteChapter(
        key: StreamingReaderCacheKey,
        title: String,
        pageUrls: List<String>,
        referer: String,
    ): StreamingReaderCachedChapter {
        require(pageUrls.isNotEmpty()) { "Nessuna pagina da salvare in cache" }

        val directory = directoryFor(key)
        if (directory.exists()) {
            directory.deleteRecursively()
        }
        directory.mkdirs()

        return try {
            val semaphore = Semaphore(downloadConcurrency.coerceAtLeast(1))
            val shouldNormalize = MangaSourceCatalog.resolveSourceId(key.sourceId, key.mangaUrl) !=
                MangaSourceIds.VYMANGA
            // Prima completiamo la rete e rilasciamo tutti i permit. La normalizzazione avviene
            // dopo, cosi una pagina alta non mette in pausa gli altri trasferimenti.
            val downloadedPages = coroutineScope {
                pageUrls.mapIndexed { index, url ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val extension = DownloadStorage.imageExtension(url)
                            val finalName = "${(index + 1).toString().padStart(3, '0')}.$extension"
                            val outputBaseName = (index + 1).toString().padStart(3, '0')
                            val tempFile = File(directory, ".$finalName.part")
                            fetchPageToFile(url, referer, tempFile)
                            if (!tempFile.isFile || tempFile.length() == 0L) {
                                tempFile.delete()
                                throw IOException("Pagina vuota o mancante: $finalName")
                            }
                            DownloadedStreamingPage(
                                index = index,
                                sourceUrl = url,
                                tempFile = tempFile,
                                finalName = finalName,
                                outputBaseName = outputBaseName,
                            )
                        }
                    }
                }.awaitAll()
            }

            // awaitAll conserva l'ordine delle pagine; ogni risultato conserva quello dei segmenti.
            val cachedPages = downloadedPages.flatMap { page ->
                val normalizedFiles = if (!shouldNormalize) {
                    // VyManga mantiene intenzionalmente invariata la propria pipeline.
                    listOf(page.tempFile)
                } else {
                    withContext(Dispatchers.IO) {
                        normalizePage(page.tempFile, directory, page.outputBaseName)
                    }
                }
                val finalFiles = finalizeNormalizedPage(
                    source = page.tempFile,
                    normalizedFiles = normalizedFiles,
                    unsplitFinalName = page.finalName,
                )
                finalFiles.mapIndexed { segmentIndex, file ->
                    StreamingReaderCachedPageMetadata(
                        fileName = file.name,
                        sourceUrl = page.sourceUrl,
                        originalPageIndex = page.index,
                        segmentIndex = segmentIndex,
                        segmentCount = finalFiles.size,
                    )
                }
            }

            StreamingReaderCacheMetadata.write(
                directory = directory,
                metadata = StreamingReaderCacheMetadata(
                    sourceId = key.sourceId,
                    mangaUrl = key.mangaUrl,
                    chapterUrl = key.chapterUrl,
                    title = title,
                    pageUrls = pageUrls,
                    pages = cachedPages.map(StreamingReaderCachedPageMetadata::fileName),
                    cachedPages = cachedPages,
                    referer = referer,
                    lastAccessAtMs = nowMillis(),
                ),
            )
            evictOldChapters()
            getCachedChapter(key) ?: throw IOException("Cache streaming non leggibile")
        } catch (exc: Exception) {
            directory.deleteRecursively()
            throw exc
        }
    }

    private fun directoryFor(key: StreamingReaderCacheKey): File {
        return File(cacheRoot.apply { mkdirs() }, key.directoryName())
    }

    private fun finalizeNormalizedPage(
        source: File,
        normalizedFiles: List<File>,
        unsplitFinalName: String,
    ): List<File> {
        if (normalizedFiles.isEmpty()) {
            throw IOException("La normalizzazione non ha prodotto pagine")
        }
        val cacheDirectory = source.parentFile?.canonicalFile
            ?: throw IOException("La pagina sorgente non ha una directory")
        val distinctFiles = normalizedFiles.distinctBy { it.canonicalPath }
        if (distinctFiles.size != normalizedFiles.size || normalizedFiles.any { file ->
                file.parentFile?.canonicalFile != cacheDirectory || !file.isFile || file.length() == 0L
            }
        ) {
            throw IOException("Risultato della normalizzazione non valido")
        }

        if (normalizedFiles.size == 1 && normalizedFiles.single().canonicalFile == source.canonicalFile) {
            val finalFile = File(cacheDirectory, unsplitFinalName)
            if (!source.renameTo(finalFile)) {
                throw IOException("Impossibile finalizzare la pagina $unsplitFinalName")
            }
            return listOf(finalFile)
        }

        if (source.exists() && !source.delete()) {
            throw IOException("Impossibile rimuovere la pagina sorgente normalizzata")
        }
        return normalizedFiles
    }

    private fun evictOldChapters() {
        val cached = cacheRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { directory ->
                StreamingReaderCacheMetadata.read(directory)?.let { metadata -> directory to metadata }
            }
            ?.sortedBy { (_, metadata) -> metadata.lastAccessAtMs }
            .orEmpty()

        cached
            .dropLast(maxCachedChapters.coerceAtLeast(0))
            .forEach { (directory, _) -> directory.deleteRecursively() }
    }

    companion object {
        private const val CACHE_DIR_NAME = "streaming-reader"
        private const val MAX_CACHED_CHAPTERS = 6

        // Pagine scaricate in parallelo per mettere in cache un capitolo, come il percorso
        // dei download normali. Le pagine già in disk-cache di Coil non contano (sola copia).
        private const val DOWNLOAD_CONCURRENCY = 4
    }
}

private data class DownloadedStreamingPage(
    val index: Int,
    val sourceUrl: String,
    val tempFile: File,
    val finalName: String,
    val outputBaseName: String,
)
