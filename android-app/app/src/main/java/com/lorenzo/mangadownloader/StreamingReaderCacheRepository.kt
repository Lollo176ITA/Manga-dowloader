package com.lorenzo.mangadownloader

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

data class StreamingReaderCacheMetadata(
    val sourceId: String? = null,
    val mangaUrl: String? = null,
    val chapterUrl: String? = null,
    val title: String,
    val pageUrls: List<String>,
    val pages: List<String>,
    val referer: String,
    val lastAccessAtMs: Long,
) {
    companion object {
        private const val FILE_NAME = "metadata.json"
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun read(directory: File): StreamingReaderCacheMetadata? {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return null
            return try {
                val root = json.parseToJsonElement(file.readText()).jsonObject
                StreamingReaderCacheMetadata(
                    sourceId = root["sourceId"]?.jsonPrimitive?.contentOrNull,
                    mangaUrl = root["mangaUrl"]?.jsonPrimitive?.contentOrNull,
                    chapterUrl = root["chapterUrl"]?.jsonPrimitive?.contentOrNull,
                    title = root["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pageUrls = root["pageUrls"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    }.orEmpty(),
                    pages = root["pages"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    }.orEmpty(),
                    referer = root["referer"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    lastAccessAtMs = root["lastAccessAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            } catch (_: Exception) {
                null
            }
        }

        fun write(
            directory: File,
            metadata: StreamingReaderCacheMetadata,
        ) {
            directory.mkdirs()
            val payload = buildJsonObject {
                put("title", JsonPrimitive(metadata.title))
                metadata.sourceId?.let { put("sourceId", JsonPrimitive(it)) }
                metadata.mangaUrl?.let { put("mangaUrl", JsonPrimitive(it)) }
                metadata.chapterUrl?.let { put("chapterUrl", JsonPrimitive(it)) }
                put("referer", JsonPrimitive(metadata.referer))
                put("lastAccessAtMs", JsonPrimitive(metadata.lastAccessAtMs))
                put(
                    "pageUrls",
                    buildJsonArray {
                        metadata.pageUrls.forEach { add(JsonPrimitive(it)) }
                    },
                )
                put(
                    "pages",
                    buildJsonArray {
                        metadata.pages.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
            File(directory, FILE_NAME).writeText(json.encodeToString(JsonObject.serializer(), payload))
        }
    }
}

class StreamingReaderCacheRepository(
    private val cacheRoot: File,
    private val pageDownloader: (url: String, referer: String) -> ByteArray,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val maxCachedChapters: Int = MAX_CACHED_CHAPTERS,
) {
    constructor(
        context: Context,
        networkClient: MangaNetworkClient,
    ) : this(
        cacheRoot = File(context.cacheDir, CACHE_DIR_NAME),
        pageDownloader = { url, referer -> networkClient.fetchBytes(url, referer = referer) },
    )

    fun getCachedChapter(key: StreamingReaderCacheKey): StreamingReaderCachedChapter? {
        val directory = directoryFor(key)
        val metadata = StreamingReaderCacheMetadata.read(directory) ?: return null
        val pages = metadata.pages.map { File(directory, it) }
        val complete = metadata.pageUrls.isNotEmpty() &&
            metadata.pageUrls.size == pages.size &&
            pages.all { it.isFile }

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

    fun cacheCompleteChapter(
        key: StreamingReaderCacheKey,
        title: String,
        pageUrls: List<String>,
        referer: String,
    ): StreamingReaderCachedChapter {
        val pageBytes = pageUrls.map { url -> pageDownloader(url, referer) }
        return saveCompleteChapter(
            key = key,
            title = title,
            pageUrls = pageUrls,
            referer = referer,
            pageBytes = pageBytes,
        )
    }

    fun saveCompleteChapter(
        key: StreamingReaderCacheKey,
        title: String,
        pageUrls: List<String>,
        referer: String,
        pageBytes: List<ByteArray>,
    ): StreamingReaderCachedChapter {
        require(pageUrls.isNotEmpty()) { "Nessuna pagina da salvare in cache" }
        require(pageUrls.size == pageBytes.size) { "Numero pagine e contenuti non coerente" }

        val directory = directoryFor(key)
        if (directory.exists()) {
            directory.deleteRecursively()
        }
        directory.mkdirs()

        return try {
            val pageNames = pageUrls.mapIndexed { index, url ->
                val extension = extractImageExtension(url)
                val finalName = "${(index + 1).toString().padStart(3, '0')}.$extension"
                val finalFile = File(directory, finalName)
                val tempFile = File(directory, "$finalName.part")
                tempFile.writeBytes(pageBytes[index])
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.delete()
                    throw IOException("Impossibile finalizzare la pagina $finalName")
                }
                finalName
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

    private fun extractImageExtension(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "jpg")
        val cleaned = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "jpg" else cleaned
    }

    companion object {
        private const val CACHE_DIR_NAME = "streaming-reader"
        private const val MAX_CACHED_CHAPTERS = 6
    }
}
