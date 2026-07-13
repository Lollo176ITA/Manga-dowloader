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
    val pageUrls: List<String>,
    val referer: String,
)

@Serializable
data class StreamingReaderCacheMetadata(
    val sourceId: String? = null,
    val mangaUrl: String? = null,
    val chapterUrl: String? = null,
    val title: String = "",
    val pageUrls: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
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
    )

    fun getCachedChapter(key: StreamingReaderCacheKey): StreamingReaderCachedChapter? {
        val directory = directoryFor(key)
        val metadata = StreamingReaderCacheMetadata.read(directory) ?: return null
        val pages = metadata.pages.map { File(directory, it) }
        // Un file vuoto (scrittura troncata, spazio esaurito) è una pagina persa quanto un
        // file mancante: la cache si butta e il capitolo si riscarica da capo.
        val complete = metadata.pageUrls.isNotEmpty() &&
            metadata.pageUrls.size == pages.size &&
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
            pageUrls = updated.pageUrls,
            referer = updated.referer,
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
            // awaitAll conserva l'ordine della lista, quindi pageNames resta in ordine di pagina.
            val pageNames = coroutineScope {
                pageUrls.mapIndexed { index, url ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val extension = DownloadStorage.imageExtension(url)
                            val finalName = "${(index + 1).toString().padStart(3, '0')}.$extension"
                            val finalFile = File(directory, finalName)
                            val tempFile = File(directory, "$finalName.part")
                            fetchPageToFile(url, referer, tempFile)
                            if (!tempFile.isFile || tempFile.length() == 0L) {
                                tempFile.delete()
                                throw IOException("Pagina vuota o mancante: $finalName")
                            }
                            if (!tempFile.renameTo(finalFile)) {
                                tempFile.delete()
                                throw IOException("Impossibile finalizzare la pagina $finalName")
                            }
                            finalName
                        }
                    }
                }.awaitAll()
            }

            StreamingReaderCacheMetadata.write(
                directory = directory,
                metadata = StreamingReaderCacheMetadata(
                    sourceId = key.sourceId,
                    mangaUrl = key.mangaUrl,
                    chapterUrl = key.chapterUrl,
                    title = title,
                    pageUrls = pageUrls,
                    pages = pageNames,
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
