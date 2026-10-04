package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.platform.currentTimeMillis
import com.lorenzo.mangadownloader.platform.isDirectory
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.length
import com.lorenzo.mangadownloader.platform.listOrEmpty
import com.lorenzo.mangadownloader.platform.readText
import com.lorenzo.mangadownloader.platform.renameTo
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.platform.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

data class StreamingReaderCacheKey(
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
) {
    fun directoryName(): String {
        val raw = "${MangaSourceCatalog.resolveSourceId(sourceId, mangaUrl)}\n$mangaUrl\n$chapterUrl"
        return raw.encodeUtf8().sha256().hex()
    }
}

data class StreamingReaderCachedChapter(
    val title: String,
    val pages: List<Path>,
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
/** Un nome file semplice, senza separatori di cartella (come `File(it).name == it`). */
private fun String.isPlainFileName(): Boolean = substringAfterLast('/') == this

private fun StreamingReaderCacheMetadata.resolvedCachedPages(): List<StreamingReaderCachedPageMetadata>? {
    if (pageUrls.isEmpty()) return null
    if (cachedPages.isEmpty()) {
        if (pages.size != pageUrls.size) return null
        if (pages.any { !it.isPlainFileName() } || pages.distinct().size != pages.size) return null
        return pages.mapIndexed { index, fileName ->
            StreamingReaderCachedPageMetadata(
                fileName = fileName,
                sourceUrl = pageUrls[index],
                originalPageIndex = index,
            )
        }
    }

    if (cachedPages.any { !it.fileName.isPlainFileName() } ||
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

        fun read(directory: Path, fileSystem: FileSystem = systemFileSystem): StreamingReaderCacheMetadata? {
            val file = directory / FILE_NAME
            if (!fileSystem.isFile(file)) return null
            return try {
                json.decodeFromString<StreamingReaderCacheMetadata>(fileSystem.readText(file)).normalized()
            } catch (_: Exception) {
                null
            }
        }

        fun write(
            directory: Path,
            metadata: StreamingReaderCacheMetadata,
            fileSystem: FileSystem = systemFileSystem,
        ) {
            fileSystem.createDirectories(directory)
            fileSystem.writeText(directory / FILE_NAME, json.encodeToString(metadata))
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
    private val cacheRoot: Path,
    // Scarica la pagina [url] scrivendola direttamente su [target] (streaming, niente pagina
    // intera in RAM). Vedi il costruttore di comodo per l'implementazione reale.
    private val fetchPageToFile: suspend (url: String, referer: String, target: Path) -> Unit,
    private val nowMillis: () -> Long = { currentTimeMillis() },
    private val maxCachedChapters: Int = MAX_CACHED_CHAPTERS,
    private val downloadConcurrency: Int = DOWNLOAD_CONCURRENCY,
    // Il costruttore primario resta testabile su JVM senza dipendenze Android.
    private val normalizePage: (source: Path, outputDirectory: Path, outputBaseName: String) -> List<Path> =
        { source, _, _ -> listOf(source) },
    private val fileSystem: FileSystem = systemFileSystem,
) {
    fun getCachedChapter(key: StreamingReaderCacheKey): StreamingReaderCachedChapter? {
        val directory = directoryFor(key)
        val metadata = StreamingReaderCacheMetadata.read(directory, fileSystem) ?: return null
        val cachedPages = metadata.resolvedCachedPages()
        val pages = cachedPages?.map { directory / it.fileName }.orEmpty()
        // Un file vuoto (scrittura troncata, spazio esaurito) è una pagina persa quanto un
        // file mancante: la cache si butta e il capitolo si riscarica da capo.
        val complete = cachedPages != null &&
            pages.all { fileSystem.isFile(it) && fileSystem.length(it) > 0L }

        if (!complete) {
            fileSystem.deleteRecursively(directory, mustExist = false)
            return null
        }

        val updated = metadata.copy(lastAccessAtMs = nowMillis())
        StreamingReaderCacheMetadata.write(directory, updated, fileSystem)
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
        fileSystem.deleteRecursively(directory, mustExist = false)
        fileSystem.createDirectories(directory)

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
                            val tempFile = directory / ".$finalName.part"
                            fetchPageToFile(url, referer, tempFile)
                            if (!fileSystem.isFile(tempFile) || fileSystem.length(tempFile) == 0L) {
                                fileSystem.delete(tempFile, mustExist = false)
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
                fileSystem = fileSystem,
            )
            evictOldChapters()
            getCachedChapter(key) ?: throw IOException("Cache streaming non leggibile")
        } catch (exc: Exception) {
            fileSystem.deleteRecursively(directory, mustExist = false)
            throw exc
        }
    }

    private fun directoryFor(key: StreamingReaderCacheKey): Path {
        fileSystem.createDirectories(cacheRoot)
        return cacheRoot / key.directoryName()
    }

    private fun finalizeNormalizedPage(
        source: Path,
        normalizedFiles: List<Path>,
        unsplitFinalName: String,
    ): List<Path> {
        if (normalizedFiles.isEmpty()) {
            throw IOException("La normalizzazione non ha prodotto pagine")
        }
        val cacheDirectory = source.parent?.let(fileSystem::canonicalize)
            ?: throw IOException("La pagina sorgente non ha una directory")
        val canonicalSource = fileSystem.canonicalize(source)
        val distinctFiles = normalizedFiles.distinctBy { canonicalOrSelf(it) }
        if (distinctFiles.size != normalizedFiles.size || normalizedFiles.any { file ->
                file.parent?.let(::canonicalOrSelf) != cacheDirectory ||
                    !fileSystem.isFile(file) || fileSystem.length(file) == 0L
            }
        ) {
            throw IOException("Risultato della normalizzazione non valido")
        }

        if (normalizedFiles.size == 1 && canonicalOrSelf(normalizedFiles.single()) == canonicalSource) {
            val finalFile = cacheDirectory / unsplitFinalName
            if (!fileSystem.renameTo(source, finalFile)) {
                throw IOException("Impossibile finalizzare la pagina $unsplitFinalName")
            }
            return listOf(finalFile)
        }

        try {
            fileSystem.delete(source, mustExist = false)
        } catch (e: IOException) {
            throw IOException("Impossibile rimuovere la pagina sorgente normalizzata", e)
        }
        return normalizedFiles
    }

    /** Come `File.canonicalFile`: per un percorso che non esiste resta quello normalizzato. */
    private fun canonicalOrSelf(path: Path): Path =
        try {
            fileSystem.canonicalize(path)
        } catch (_: IOException) {
            path.normalized()
        }

    private fun evictOldChapters() {
        val cached = fileSystem.listOrEmpty(cacheRoot)
            .filter { fileSystem.isDirectory(it) }
            .mapNotNull { directory ->
                StreamingReaderCacheMetadata.read(directory, fileSystem)?.let { metadata -> directory to metadata }
            }
            .sortedBy { (_, metadata) -> metadata.lastAccessAtMs }

        cached
            .dropLast(maxCachedChapters.coerceAtLeast(0))
            .forEach { (directory, _) -> fileSystem.deleteRecursively(directory, mustExist = false) }
    }

    companion object {
        const val CACHE_DIR_NAME = "streaming-reader"
        private const val MAX_CACHED_CHAPTERS = 6

        // Pagine scaricate in parallelo per mettere in cache un capitolo, come il percorso
        // dei download normali. Le pagine già in disk-cache di Coil non contano (sola copia).
        private const val DOWNLOAD_CONCURRENCY = 4
    }
}

private data class DownloadedStreamingPage(
    val index: Int,
    val sourceUrl: String,
    val tempFile: Path,
    val finalName: String,
    val outputBaseName: String,
)
