package com.lorenzo.mangadownloader

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val volumeText: String?,
    val labelPrefix: String,
    val file: File,
    val relativePath: String,
    val chapterId: String,
    val isRead: Boolean,
    val readerPageIndex: Int?,
    val readerPageCount: Int?,
    val lastReadAtMillis: Long? = null,
)

data class ReaderPagePosition(
    val pageIndex: Int,
    val pageCount: Int?,
    val lastReadAtMillis: Long? = null,
)

data class DownloadedSeries(
    val sourceId: String,
    val title: String,
    val mangaUrl: String?,
    val coverFile: File?,
    val directory: File,
    val chapters: List<DownloadedChapter>,
    val totalChapterCount: Int,
    val readChapterIds: Set<String>,
)

@Serializable
data class SeriesMetadata(
    val sourceId: String = "",
    val title: String = "",
    val mangaUrl: String? = null,
    val coverFileName: String? = null,
    val totalChapters: Int? = null,
    val readChapterIds: Set<String> = emptySet(),
    val chapters: List<SeriesMetadataChapter> = emptyList(),
)

@Serializable
data class SeriesMetadataChapter(
    val numberText: String = "",
    val url: String? = null,
    val slug: String? = null,
    val fileName: String = "",
    val id: String? = null,
    val volumeText: String? = null,
    val labelPrefix: String = "Capitolo",
)

object DownloadStorage {
    const val LIBRARY_FOLDER_NAME = "MangaDownloader"
    const val SERIES_METADATA_FILE_NAME = "series.json"

    private val chapterFileRegex = Regex("""^chapter_(.+)\.cbz$""", RegexOption.IGNORE_CASE)
    private val numericRegex = Regex("""\d+(?:\.\d+)?""")

    /**
     * Soglia minima di spazio libero richiesta prima di scaricare un capitolo.
     * Margine prudente: una richiesta scrive le pagine in una cartella temporanea e
     * poi lo zip finale, quindi serve spazio per entrambi durante la finalizzazione.
     */
    const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024

    fun libraryRoot(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Cartella download dell'app non disponibile")
        return File(root, LIBRARY_FOLDER_NAME).apply { mkdirs() }
    }

    /** Policy pura: c'è abbastanza spazio libero? (testabile senza toccare il filesystem) */
    fun hasEnoughFreeSpace(
        availableBytes: Long,
        requiredBytes: Long = MIN_FREE_SPACE_BYTES,
    ): Boolean = availableBytes >= requiredBytes

    /**
     * Spazio libero (byte) sul volume che contiene [dir]. **Fail-open**: se la misura
     * non è possibile restituisce `Long.MAX_VALUE`, così un guasto della misura non
     * blocca i download (al massimo si ricade nel vecchio comportamento su `IOException`).
     */
    fun freeSpaceBytes(dir: File): Long {
        return try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
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

    /** Estensione immagine (minuscola, solo alfanumerici) dall'URL; `jpg` se assente. */
    fun imageExtension(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "jpg")
        val cleaned = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "jpg" else cleaned
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

    fun stableChapterId(chapter: ChapterEntry): String {
        return stableChapterId(
            numberText = chapter.displayNumber(),
            url = chapter.url,
            slug = chapter.slug,
        )
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
        encodeDefaults = true
        explicitNulls = false
    }

    fun write(target: File, metadata: SeriesMetadata) {
        val stableMetadata = metadata.copy(readChapterIds = metadata.readChapterIds.toSortedSet())
        target.writeText(json.encodeToString(stableMetadata))
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
            json.decodeFromString<SeriesMetadata>(raw).normalizedOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun SeriesMetadata.normalizedOrNull(): SeriesMetadata? {
        val normalizedTitle = title.trim().takeIf(String::isNotBlank) ?: return null
        return copy(
            sourceId = MangaSourceCatalog.resolveSourceId(
                sourceId = sourceId,
                url = mangaUrl,
            ),
            title = normalizedTitle,
            readChapterIds = readChapterIds.mapNotNullTo(linkedSetOf()) {
                it.trim().takeIf(String::isNotBlank)
            },
            chapters = chapters.mapNotNull { it.normalizedOrNull() },
        )
    }

    private fun SeriesMetadataChapter.normalizedOrNull(): SeriesMetadataChapter? {
        val normalizedNumber = numberText.trim().takeIf(String::isNotBlank) ?: return null
        val normalizedFileName = fileName.trim().takeIf(String::isNotBlank) ?: return null
        return copy(
            numberText = normalizedNumber,
            fileName = normalizedFileName,
            labelPrefix = labelPrefix.trim().takeIf(String::isNotBlank) ?: "Capitolo",
        )
    }
}

object LibraryScanner {
    fun scan(
        root: File,
        isRead: (String) -> Boolean,
        readerPagePosition: (String) -> ReaderPagePosition? = { null },
    ): List<DownloadedSeries> {
        if (!root.exists()) return emptyList()

        return root.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()
            .mapNotNull { directory -> scanSeriesDirectory(root, directory, isRead, readerPagePosition) }
            .sortedBy { it.title.lowercase(Locale.US) }
    }

    fun scanSeriesDirectory(
        root: File,
        directory: File,
        isRead: (String) -> Boolean,
        readerPagePosition: (String) -> ReaderPagePosition? = { null },
    ): DownloadedSeries? {
        if (!directory.isDirectory) return null

        val metadata = SeriesMetadataJson.read(File(directory, DownloadStorage.SERIES_METADATA_FILE_NAME))
        val metadataByFileName = metadata?.chapters?.associateBy { it.fileName }.orEmpty()
        val persistedReadIds = metadata?.readChapterIds.orEmpty()
        val coverFile = resolveCoverFile(directory, metadata)
        val sourceId = MangaSourceCatalog.resolveSourceId(
            sourceId = metadata?.sourceId,
            url = metadata?.mangaUrl,
        )

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
                val pagePosition = readerPagePosition(relativePath)
                val chapterId = chapterMeta?.id
                    ?: DownloadStorage.stableChapterId(
                        numberText = normalized,
                        url = chapterMeta?.url,
                        slug = chapterMeta?.slug,
                    )
                val chapterIsRead = isRead(relativePath) || chapterId in persistedReadIds
                val volumeText = chapterMeta?.volumeText?.trim()?.takeIf(String::isNotBlank)
                val labelPrefix = chapterMeta?.labelPrefix
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: "Capitolo"
                DownloadedChapter(
                    title = volumeText?.let { "$it - $labelPrefix $normalized" } ?: "$labelPrefix $normalized",
                    numberText = normalized,
                    numberValue = DownloadStorage.parseChapterValueOrNull(normalized),
                    volumeText = volumeText,
                    labelPrefix = labelPrefix,
                    file = file,
                    relativePath = relativePath,
                    chapterId = chapterId,
                    isRead = chapterIsRead,
                    readerPageIndex = pagePosition?.pageIndex,
                    readerPageCount = pagePosition?.pageCount,
                    lastReadAtMillis = pagePosition?.lastReadAtMillis,
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
            sourceId = sourceId,
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

    @Volatile
    private var cachedSnapshot: List<DownloadedSeries>? = null

    @Volatile
    private var cachedSnapshotAtMs: Long = 0L

    /**
     * Returns the list of downloaded series, reusing a recent snapshot when
     * possible to avoid hitting the filesystem on every UI refresh during a
     * download. Mutating operations (delete, markRead, downloads completing)
     * call [invalidateCache] so the next scan reflects the change.
     */
    fun scanLibrary(forceRefresh: Boolean = false): List<DownloadedSeries> {
        if (!forceRefresh) {
            val snapshot = cachedSnapshot
            if (snapshot != null &&
                System.currentTimeMillis() - cachedSnapshotAtMs < CACHE_TTL_MS
            ) {
                return snapshot
            }
        }
        val root = DownloadStorage.libraryRoot(context)
        val series = LibraryScanner.scan(root, ::isChapterRead, ::readerPagePosition)
        series.forEach(::backfillMetadata)
        cachedSnapshot = series
        cachedSnapshotAtMs = System.currentTimeMillis()
        return series
    }

    fun invalidateCache() {
        cachedSnapshot = null
        cachedSnapshotAtMs = 0L
    }

    fun markChapterRead(chapter: DownloadedChapter) {
        markChaptersRead(listOf(chapter))
    }

    /**
     * Marca letti più capitoli della stessa serie in un colpo solo (un solo write dei
     * metadata): usato da "Segna come letti fino a qui" e "Segna tutti come letti".
     */
    fun markChaptersRead(chapters: List<DownloadedChapter>) {
        if (chapters.isEmpty()) {
            return
        }
        prefs.edit {
            chapters.forEach { putBoolean(readPrefKey(it.relativePath), true) }
        }
        val parentDirectory = chapters.firstNotNullOfOrNull { it.file.parentFile } ?: return
        updateSeriesMetadata(parentDirectory) { metadata ->
            val updatedReadIds = metadata.readChapterIds + chapters.map { it.chapterId }
            metadata.copy(
                totalChapters = (metadata.totalChapters ?: metadata.chapters.size)
                    .coerceAtLeast(updatedReadIds.size),
                readChapterIds = updatedReadIds,
            )
        }
        invalidateCache()
    }

    /**
     * Duale di [markChapterRead] (prima inesistente: lo stato "letto" si poteva solo
     * acquisire finendo il capitolo nel reader). Azzera anche il progresso di lettura:
     * un capitolo segnato "da leggere" riparte da pagina 1 e non deve più risultare
     * completato nelle righe della serie.
     */
    fun markChapterUnread(chapter: DownloadedChapter) {
        clearChapterState(chapter.relativePath, clearReadState = true)
        val parentDirectory = chapter.file.parentFile ?: return
        updateSeriesMetadata(parentDirectory) { metadata ->
            metadata.copy(readChapterIds = metadata.readChapterIds - chapter.chapterId)
        }
        invalidateCache()
    }

    fun streamingReadChapterIds(sourceId: String, mangaUrl: String): Set<String> {
        return prefs.getStringSet(streamingReadPrefKey(sourceId, mangaUrl), emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun streamingReadChapterIds(plan: DownloadPlan): Set<String> {
        return streamingReadChapterIds(plan.sourceId, plan.mangaUrl)
    }

    fun markStreamingChapterRead(
        sourceId: String,
        mangaUrl: String,
        chapter: ChapterEntry,
    ): String {
        val chapterId = DownloadStorage.stableChapterId(chapter)
        val updated = streamingReadChapterIds(sourceId, mangaUrl) + chapterId
        prefs.edit {
            putStringSet(streamingReadPrefKey(sourceId, mangaUrl), updated)
        }
        return chapterId
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

        invalidateCache()

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
        invalidateCache()
    }

    fun isChapterRead(relativePath: String): Boolean {
        return prefs.getBoolean(readPrefKey(relativePath), false)
    }

    fun readerPagePosition(relativePath: String): ReaderPagePosition? {
        if (!prefs.contains(readerPageIndexPrefKey(relativePath))) {
            return null
        }
        val pageIndex = prefs.getInt(readerPageIndexPrefKey(relativePath), 0).coerceAtLeast(0)
        val pageCount = prefs
            .getInt(readerPageCountPrefKey(relativePath), -1)
            .takeIf { it > 0 }
        val lastReadAtMillis = prefs
            .getLong(readerReadAtPrefKey(relativePath), 0L)
            .takeIf { it > 0L }
        return ReaderPagePosition(
            pageIndex = pageIndex,
            pageCount = pageCount,
            lastReadAtMillis = lastReadAtMillis,
        )
    }

    fun saveReaderPagePosition(
        relativePath: String,
        pageIndex: Int,
        pageCount: Int?,
        lastReadAtMillis: Long? = null,
    ) {
        prefs.edit {
            putInt(readerPageIndexPrefKey(relativePath), pageIndex.coerceAtLeast(0))
            if (pageCount != null && pageCount > 0) {
                putInt(readerPageCountPrefKey(relativePath), pageCount)
            }
            if (lastReadAtMillis != null && lastReadAtMillis > 0L) {
                putLong(readerReadAtPrefKey(relativePath), lastReadAtMillis)
            }
        }
    }

    suspend fun extractReaderPages(chapter: DownloadedChapter): List<File> = withContext(Dispatchers.IO) {
        val cacheRoot = File(context.cacheDir, "reader-pages").apply { mkdirs() }
        val cacheDir = File(cacheRoot, DownloadStorage.readerCacheDirectoryName(chapter.relativePath))
        val existing = cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        // La cache si riusa solo se integra: un file vuoto (scrittura interrotta, spazio
        // esaurito) sarebbe una pagina irrecuperabile a ogni rilettura — il .cbz in
        // libreria è ancora lì, meglio ributtare giù tutto da quello.
        if (existing.isNotEmpty() && existing.all { it.length() > 0L }) {
            // Rinfresca il timestamp: per l'eviction LRU questo capitolo è appena stato usato.
            cacheDir.setLastModified(System.currentTimeMillis())
            return@withContext existing
        }

        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
        // Estrazione in una cartella temporanea rinominata solo a lavoro finito: se il
        // processo muore a metà non resta una cache parziale che alla riapertura verrebbe
        // scambiata per estrazione completa (pagine mancanti in silenzio).
        val tempDir = File(cacheRoot, "${cacheDir.name}.tmp")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        val extracted = mutableListOf<File>()
        try {
            ZipInputStream(chapter.file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                var index = 1
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val extension = entry.name
                            .substringAfterLast('.', "jpg")
                            .lowercase(Locale.US)
                            .ifBlank { "jpg" }
                        val outFile = File(tempDir, "${index.toString().padStart(3, '0')}.$extension")
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
        } catch (e: IOException) {
            // Un .cbz corrotto/troncato non deve lasciare residui su disco.
            tempDir.deleteRecursively()
            throw IOException("Capitolo scaricato corrotto o illeggibile", e)
        }

        if (extracted.isEmpty()) {
            tempDir.deleteRecursively()
            throw IOException("Nessuna pagina trovata nel capitolo scaricato")
        }
        if (!tempDir.renameTo(cacheDir)) {
            tempDir.deleteRecursively()
            throw IOException("Impossibile finalizzare le pagine estratte")
        }

        cacheDir.setLastModified(System.currentTimeMillis())
        evictOldReaderPageCaches(cacheRoot, justExtracted = cacheDir)
        extracted
            .map { File(cacheDir, it.name) }
            .sortedBy { it.name }
    }

    /**
     * Tiene la cache delle pagine estratte entro [MAX_EXTRACTED_READER_CHAPTERS] capitoli,
     * cancellando i meno usati di recente (LRU su lastModified, rinfrescato a ogni apertura).
     * Le pagine estratte servono solo alla lettura corrente e alle riletture ravvicinate:
     * il .cbz in libreria resta la copia primaria e riaprire un capitolo evitto costa solo
     * una nuova estrazione. Senza tetto la cache duplicava per sempre ogni capitolo letto.
     */
    private fun evictOldReaderPageCaches(cacheRoot: File, justExtracted: File) {
        cacheRoot.listFiles()
            ?.filter { it.isDirectory && it != justExtracted }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop((MAX_EXTRACTED_READER_CHAPTERS - 1).coerceAtLeast(0))
            .forEach { it.deleteRecursively() }
    }

    private fun backfillMetadata(series: DownloadedSeries) {
        val metadataFile = File(series.directory, DownloadStorage.SERIES_METADATA_FILE_NAME)
        val existingMetadata = SeriesMetadataJson.read(metadataFile)
        if (existingMetadata != null) {
            val resolvedSourceId = MangaSourceCatalog.resolveSourceId(
                sourceId = existingMetadata.sourceId,
                url = existingMetadata.mangaUrl ?: series.mangaUrl,
            )
            if (existingMetadata.sourceId == resolvedSourceId) {
                return
            }
            SeriesMetadataJson.write(
                metadataFile,
                existingMetadata.copy(sourceId = resolvedSourceId),
            )
            return
        }

        val metadata = SeriesMetadata(
            sourceId = series.sourceId,
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
                    volumeText = chapter.volumeText,
                    labelPrefix = chapter.labelPrefix,
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
            sourceId = existingMetadata?.sourceId
                ?: MangaSourceCatalog.resolveSourceId(null, fallbackMangaUrl),
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
                    volumeText = preserved?.volumeText,
                    labelPrefix = preserved?.labelPrefix ?: "Capitolo",
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
            prefs.edit {
                remove(readPrefKey(relativePath))
                remove(readerPageIndexPrefKey(relativePath))
                remove(readerPageCountPrefKey(relativePath))
                remove(readerReadAtPrefKey(relativePath))
            }
        } else {
            prefs.edit {
                remove(readerPageIndexPrefKey(relativePath))
                remove(readerPageCountPrefKey(relativePath))
                remove(readerReadAtPrefKey(relativePath))
            }
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
    private fun readerPageIndexPrefKey(relativePath: String): String = "reader_page_index::$relativePath"
    private fun readerPageCountPrefKey(relativePath: String): String = "reader_page_count::$relativePath"
    private fun readerReadAtPrefKey(relativePath: String): String = "reader_read_at::$relativePath"
    private fun streamingReadPrefKey(sourceId: String, mangaUrl: String): String {
        return "streaming_read::${MangaSourceCatalog.identityKey(sourceId, mangaUrl)}"
    }

    companion object {
        private const val PREFS_NAME = "manga_library_prefs"
        private const val CACHE_TTL_MS = 5_000L

        // Massimo di capitoli con le pagine estratte tenuti in cache (LRU): copre il
        // capitolo in lettura e le riletture recenti senza duplicare l'intera libreria.
        private const val MAX_EXTRACTED_READER_CHAPTERS = 10
    }
}
