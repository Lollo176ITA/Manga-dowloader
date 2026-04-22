package com.lorenzo.mangadownloader

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class DownloadedChapter(
    val title: String,
    val numberText: String,
    val numberValue: BigDecimal?,
    val file: File,
    val relativePath: String,
    val chapterId: String,
    val isRead: Boolean,
)

data class DownloadedSeries(
    val title: String,
    val mangaUrl: String?,
    val coverFile: File?,
    val directory: File,
    val chapters: List<DownloadedChapter>,
    val totalChapterCount: Int,
    val readChapterIds: Set<String>,
)

data class SeriesMetadata(
    val title: String,
    val mangaUrl: String?,
    val coverFileName: String?,
    val totalChapters: Int?,
    val readChapterIds: Set<String>,
    val chapters: List<SeriesMetadataChapter>,
)

data class SeriesMetadataChapter(
    val numberText: String,
    val url: String?,
    val slug: String?,
    val fileName: String,
    val id: String?,
)

object DownloadStorage {
    const val LIBRARY_FOLDER_NAME = "MangaDownloader"
    const val SERIES_METADATA_FILE_NAME = "series.json"

    private val chapterFileRegex = Regex("""^chapter_(.+)\.cbz$""", RegexOption.IGNORE_CASE)
    private val numericRegex = Regex("""\d+(?:\.\d+)?""")

    fun libraryRoot(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Cartella download dell'app non disponibile")
        return File(root, LIBRARY_FOLDER_NAME).apply { mkdirs() }
    }

    fun safeFilename(input: String): String {
        return input.replace(Regex("""[^A-Za-z0-9._-]+"""), "_").trim('_').ifBlank { "manga" }
    }

    fun buildChapterFileName(chapter: ChapterEntry): String {
        val label = normalizedChapterLabel(chapter.numberText)
        val padded = if (label.all(Char::isDigit)) label.padStart(3, '0') else label
        return "chapter_${safeFilename(padded)}.cbz"
    }

    fun normalizedChapterLabel(raw: String): String {
        return raw.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: raw.trim()
    }

    fun parseChapterLabelFromFileName(fileName: String): String? {
        val raw = chapterFileRegex.matchEntire(fileName)?.groupValues?.getOrNull(1) ?: return null
        return normalizedChapterLabel(raw)
    }

    fun parseChapterValueOrNull(raw: String): BigDecimal? {
        return numericRegex.find(raw)?.value?.toBigDecimalOrNull()
    }

    fun stableChapterId(
        numberText: String,
        url: String?,
        slug: String?,
    ): String {
        val normalizedUrl = url?.trim()?.takeIf(String::isNotBlank)
        if (normalizedUrl != null) {
            return "url:$normalizedUrl"
        }
        val normalizedSlug = slug?.trim()?.takeIf(String::isNotBlank)
        if (normalizedSlug != null) {
            return "slug:$normalizedSlug"
        }
        return "number:${normalizedChapterLabel(numberText)}"
    }

    fun relativePath(root: File, file: File): String {
        return file.relativeTo(root).invariantSeparatorsPath
    }

    fun readerCacheDirectoryName(relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(relativePath.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun chapterComparator(): Comparator<DownloadedChapter> {
        return compareBy<DownloadedChapter>(
            { it.numberValue == null },
            { it.numberValue ?: BigDecimal.ZERO },
            { it.numberText.lowercase(Locale.US) },
        )
    }
}

object SeriesMetadataJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun write(target: File, metadata: SeriesMetadata) {
        val payload = buildJsonObject {
            put("title", JsonPrimitive(metadata.title))
            metadata.mangaUrl?.let { put("mangaUrl", JsonPrimitive(it)) }
            metadata.coverFileName?.let { put("coverFileName", JsonPrimitive(it)) }
            metadata.totalChapters?.let { put("totalChapters", JsonPrimitive(it)) }
            put(
                "readChapterIds",
                buildJsonArray {
                    metadata.readChapterIds
                        .sorted()
                        .forEach { id -> add(JsonPrimitive(id)) }
                },
            )
            put(
                "chapters",
                buildJsonArray {
                    metadata.chapters.forEach { chapter ->
                        add(
                            buildJsonObject {
                                put("numberText", JsonPrimitive(chapter.numberText))
                                chapter.url?.let { put("url", JsonPrimitive(it)) }
                                chapter.slug?.let { put("slug", JsonPrimitive(it)) }
                                put("fileName", JsonPrimitive(chapter.fileName))
                                chapter.id?.let { put("id", JsonPrimitive(it)) }
                            },
                        )
                    }
                },
            )
        }
        target.writeText(json.encodeToString(JsonObject.serializer(), payload))
    }

    fun read(target: File): SeriesMetadata? {
        if (!target.isFile) return null
        return try {
            parse(target.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun parse(raw: String): SeriesMetadata? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) {
                return null
            }
            val chapters = root["chapters"]
                ?.jsonArray
                ?.mapNotNull { element -> parseChapter(element.jsonObject) }
                .orEmpty()
            SeriesMetadata(
                title = title,
                mangaUrl = root["mangaUrl"]?.jsonPrimitive?.contentOrNull,
                coverFileName = root["coverFileName"]?.jsonPrimitive?.contentOrNull,
                totalChapters = root["totalChapters"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                readChapterIds = root["readChapterIds"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                    ?.toSet()
                    .orEmpty(),
                chapters = chapters,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseChapter(jsonObject: JsonObject): SeriesMetadataChapter? {
        val numberText = jsonObject["numberText"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val fileName = jsonObject["fileName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (numberText.isBlank() || fileName.isBlank()) {
            return null
        }
        return SeriesMetadataChapter(
            numberText = numberText,
            url = jsonObject["url"]?.jsonPrimitive?.contentOrNull,
            slug = jsonObject["slug"]?.jsonPrimitive?.contentOrNull,
            fileName = fileName,
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

object LibraryScanner {
    fun scan(root: File, isRead: (String) -> Boolean): List<DownloadedSeries> {
        if (!root.exists()) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
            .mapNotNull { directory -> scanSeriesDirectory(root, directory, isRead) }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun scanSeriesDirectory(
        root: File,
        directory: File,
        isRead: (String) -> Boolean,
    ): DownloadedSeries? {
        if (!directory.isDirectory) return null

        val metadata = SeriesMetadataJson.read(File(directory, DownloadStorage.SERIES_METADATA_FILE_NAME))
        val metadataByFileName = metadata?.chapters?.associateBy { it.fileName }.orEmpty()
        val persistedReadIds = metadata?.readChapterIds.orEmpty()
        val coverFile = resolveCoverFile(directory, metadata)

        val chapters = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                val chapterMeta = metadataByFileName[file.name]
                val numberText = chapterMeta?.numberText
                    ?: DownloadStorage.parseChapterLabelFromFileName(file.name)
                    ?: return@mapNotNull null
                val normalized = DownloadStorage.normalizedChapterLabel(numberText)
                val relativePath = DownloadStorage.relativePath(root, file)
                val chapterId = chapterMeta?.id
                    ?: DownloadStorage.stableChapterId(
                        numberText = normalized,
                        url = chapterMeta?.url,
                        slug = chapterMeta?.slug,
                    )
                val chapterIsRead = isRead(relativePath) || chapterId in persistedReadIds
                DownloadedChapter(
                    title = "Capitolo $normalized",
                    numberText = normalized,
                    numberValue = DownloadStorage.parseChapterValueOrNull(normalized),
                    file = file,
                    relativePath = relativePath,
                    chapterId = chapterId,
                    isRead = chapterIsRead,
                )
            }
            .sortedWith(DownloadStorage.chapterComparator())

        if (chapters.isEmpty()) {
            return null
        }

        val readChapterIds = buildSet {
            addAll(persistedReadIds)
            chapters.filter { it.isRead }.mapTo(this) { it.chapterId }
        }
        val totalChapterCount = (metadata?.totalChapters ?: chapters.size)
            .coerceAtLeast(chapters.size)
            .coerceAtLeast(readChapterIds.size)

        return DownloadedSeries(
            title = metadata?.title?.takeIf { it.isNotBlank() }
                ?: directory.name.replace('_', ' ').trim(),
            mangaUrl = metadata?.mangaUrl,
            coverFile = coverFile,
            directory = directory,
            chapters = chapters,
            totalChapterCount = totalChapterCount,
            readChapterIds = readChapterIds,
        )
    }

    private fun resolveCoverFile(
        directory: File,
        metadata: SeriesMetadata?,
    ): File? {
        val metadataCover = metadata?.coverFileName
            ?.let { File(directory, it) }
            ?.takeIf { it.isFile }
        if (metadataCover != null) {
            return metadataCover
        }

        return directory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("cover.", ignoreCase = true) &&
                    file.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png", "webp")
            }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }
}

class LibraryRepository(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scanLibrary(): List<DownloadedSeries> {
        val root = DownloadStorage.libraryRoot(context)
        val series = LibraryScanner.scan(root, ::isChapterRead)
        series.forEach(::backfillMetadataIfMissing)
        return series
    }

    fun markChapterRead(chapter: DownloadedChapter) {
        prefs.edit()
            .putBoolean(readPrefKey(chapter.relativePath), true)
            .apply()
        updateSeriesMetadata(chapter.file.parentFile) { metadata ->
            val updatedReadIds = metadata.readChapterIds + chapter.chapterId
            metadata.copy(
                totalChapters = (metadata.totalChapters ?: metadata.chapters.size)
                    .coerceAtLeast(updatedReadIds.size),
                readChapterIds = updatedReadIds,
            )
        }
    }

    suspend fun deleteChapters(
        series: DownloadedSeries,
        chapters: List<DownloadedChapter>,
    ) = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) {
            return@withContext
        }

        val deletedReadIds = chapters
            .asSequence()
            .filter { it.isRead }
            .map { it.chapterId }
            .toSet()
        if (deletedReadIds.isNotEmpty()) {
            updateSeriesMetadata(series.directory) { metadata ->
                val updatedReadIds = metadata.readChapterIds + deletedReadIds
                metadata.copy(
                    totalChapters = (metadata.totalChapters ?: metadata.chapters.size)
                        .coerceAtLeast(updatedReadIds.size),
                    readChapterIds = updatedReadIds,
                )
            }
        }

        chapters.forEach { chapter ->
            if (chapter.file.exists()) {
                chapter.file.delete()
            }
            clearChapterState(chapter.relativePath, clearReadState = false)
        }

        val remainingChapterFiles = series.directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
            .orEmpty()

        if (remainingChapterFiles.isEmpty()) {
            series.directory.deleteRecursively()
            return@withContext
        }

        rewriteMetadataForExistingFiles(
            directory = series.directory,
            fallbackTitle = series.title,
            fallbackMangaUrl = series.mangaUrl,
            fallbackCoverFileName = series.coverFile?.name,
        )
    }

    suspend fun deleteSeries(series: DownloadedSeries) = withContext(Dispatchers.IO) {
        series.chapters.forEach { chapter ->
            clearChapterState(chapter.relativePath, clearReadState = true)
        }
        if (series.directory.exists()) {
            series.directory.deleteRecursively()
        }
    }

    fun isChapterRead(relativePath: String): Boolean {
        return prefs.getBoolean(readPrefKey(relativePath), false)
    }

    suspend fun extractReaderPages(chapter: DownloadedChapter): List<File> = withContext(Dispatchers.IO) {
        val cacheRoot = File(context.cacheDir, "reader-pages").apply { mkdirs() }
        val cacheDir = File(cacheRoot, DownloadStorage.readerCacheDirectoryName(chapter.relativePath))
        val existing = cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        if (existing.isNotEmpty()) {
            return@withContext existing
        }

        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        cacheDir.mkdirs()

        val extracted = mutableListOf<File>()
        ZipInputStream(chapter.file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            var index = 1
            while (entry != null) {
                if (!entry.isDirectory) {
                    val extension = entry.name
                        .substringAfterLast('.', "jpg")
                        .lowercase(Locale.US)
                        .ifBlank { "jpg" }
                    val outFile = File(cacheDir, "${index.toString().padStart(3, '0')}.$extension")
                    outFile.outputStream().buffered().use { output ->
                        zip.copyTo(output)
                    }
                    extracted += outFile
                    index += 1
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (extracted.isEmpty()) {
            cacheDir.deleteRecursively()
            throw IOException("Nessuna pagina trovata nel capitolo scaricato")
        }
        extracted.sortedBy { it.name }
    }

    private fun backfillMetadataIfMissing(series: DownloadedSeries) {
        val metadataFile = File(series.directory, DownloadStorage.SERIES_METADATA_FILE_NAME)
        if (metadataFile.isFile) {
            return
        }

        val metadata = SeriesMetadata(
            title = series.title,
            mangaUrl = series.mangaUrl,
            coverFileName = series.coverFile?.name,
            totalChapters = series.totalChapterCount,
            readChapterIds = series.readChapterIds,
            chapters = series.chapters.map { chapter ->
                SeriesMetadataChapter(
                    numberText = chapter.numberText,
                    url = null,
                    slug = null,
                    fileName = chapter.file.name,
                    id = chapter.chapterId,
                )
            },
        )
        SeriesMetadataJson.write(metadataFile, metadata)
    }

    private fun rewriteMetadataForExistingFiles(
        directory: File,
        fallbackTitle: String,
        fallbackMangaUrl: String?,
        fallbackCoverFileName: String?,
    ) {
        val metadataFile = File(directory, DownloadStorage.SERIES_METADATA_FILE_NAME)
        val existingMetadata = SeriesMetadataJson.read(metadataFile)
        val existingByFileName = existingMetadata?.chapters?.associateBy { it.fileName }.orEmpty()
        val chapterFiles = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("cbz", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }

        val updated = SeriesMetadata(
            title = existingMetadata?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            mangaUrl = existingMetadata?.mangaUrl ?: fallbackMangaUrl,
            coverFileName = existingMetadata?.coverFileName ?: fallbackCoverFileName,
            totalChapters = existingMetadata?.totalChapters,
            readChapterIds = existingMetadata?.readChapterIds.orEmpty(),
            chapters = chapterFiles.mapNotNull { file ->
                val preserved = existingByFileName[file.name]
                val numberText = preserved?.numberText
                    ?: DownloadStorage.parseChapterLabelFromFileName(file.name)
                    ?: return@mapNotNull null
                SeriesMetadataChapter(
                    numberText = numberText,
                    url = preserved?.url,
                    slug = preserved?.slug,
                    fileName = file.name,
                    id = preserved?.id ?: DownloadStorage.stableChapterId(
                        numberText = numberText,
                        url = preserved?.url,
                        slug = preserved?.slug,
                    ),
                )
            },
        )
        SeriesMetadataJson.write(metadataFile, updated)
    }

    private fun updateSeriesMetadata(
        directory: File,
        transform: (SeriesMetadata) -> SeriesMetadata,
    ) {
        val metadataFile = File(directory, DownloadStorage.SERIES_METADATA_FILE_NAME)
        val existing = SeriesMetadataJson.read(metadataFile) ?: return
        SeriesMetadataJson.write(metadataFile, transform(existing))
    }

    private fun clearChapterState(relativePath: String, clearReadState: Boolean) {
        if (clearReadState) {
            prefs.edit()
                .remove(readPrefKey(relativePath))
                .apply()
        }
        val cacheDir = File(
            File(context.cacheDir, "reader-pages"),
            DownloadStorage.readerCacheDirectoryName(relativePath),
        )
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    private fun readPrefKey(relativePath: String): String = "read::$relativePath"

    companion object {
        private const val PREFS_NAME = "manga_library_prefs"
    }
}
