package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.DownloadPlan
import com.lorenzo.mangadownloader.data.model.identityKey
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.store.edit
import com.lorenzo.mangadownloader.data.store.getStringSet
import com.lorenzo.mangadownloader.data.store.putStringSet
import com.lorenzo.mangadownloader.platform.currentTimeMillis
import com.lorenzo.mangadownloader.platform.extension
import com.lorenzo.mangadownloader.platform.isDirectory
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.listOrEmpty
import com.lorenzo.mangadownloader.platform.readText
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.platform.writeText
import com.russhwolf.settings.Settings
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

class LibraryRepository(
    private val paths: AppPaths,
    private val prefs: Settings,
    private val fileSystem: FileSystem = systemFileSystem,
) {
    /** Cartella della libreria, creata se manca. */
    val libraryRoot: Path
        get() = paths.libraryRoot.also { fileSystem.createDirectories(it) }

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
                currentTimeMillis() - cachedSnapshotAtMs < CACHE_TTL_MS
            ) {
                return snapshot
            }
        }
        val scanned = LibraryScanner.scanWithMetadata(libraryRoot, ::isChapterRead, ::readerPagePosition, fileSystem)
        scanned.forEach { backfillMetadata(it.series, it.metadata) }
        val series = scanned.map { it.series }
        cachedSnapshot = series
        cachedSnapshotAtMs = currentTimeMillis()
        return series
    }

    fun invalidateCache() {
        cachedSnapshot = null
        cachedSnapshotAtMs = 0L
    }

    suspend fun markChapterRead(chapter: DownloadedChapter) {
        markChaptersRead(listOf(chapter))
    }

    /**
     * Marca letti più capitoli della stessa serie in un colpo solo (un solo write dei
     * metadata): usato da "Segna come letti fino a qui" e "Segna tutti come letti".
     * Le prefs si aggiornano subito (in memoria, `apply` asincrono) così una scansione
     * concorrente vede già il "letto"; la riscrittura del JSON va su I/O.
     */
    suspend fun markChaptersRead(chapters: List<DownloadedChapter>) {
        if (chapters.isEmpty()) {
            return
        }
        prefs.edit {
            chapters.forEach { putBoolean(readPrefKey(it.relativePath), true) }
        }
        val parentDirectory = chapters.firstNotNullOfOrNull { it.file.parent } ?: return
        withContext(Dispatchers.IO) {
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
    }

    /**
     * Duale di [markChapterRead] (prima inesistente: lo stato "letto" si poteva solo
     * acquisire finendo il capitolo nel reader). Azzera anche il progresso di lettura:
     * un capitolo segnato "da leggere" riparte da pagina 1 e non deve più risultare
     * completato nelle righe della serie.
     */
    suspend fun markChapterUnread(chapter: DownloadedChapter) = withContext(Dispatchers.IO) {
        clearChapterState(chapter.relativePath, clearReadState = true)
        val parentDirectory = chapter.file.parent ?: return@withContext
        updateSeriesMetadata(parentDirectory) { metadata ->
            metadata.copy(readChapterIds = metadata.readChapterIds - chapter.chapterId)
        }
        invalidateCache()
    }

    fun streamingReadChapterIds(sourceId: String, mangaUrl: String): Set<String> {
        return streamingReadChapterIds(seriesKey = null, sourceId = sourceId, mangaUrl = mangaUrl)
    }

    /**
     * Id dei capitoli letti in streaming: unione del set per-serie (chiave canonica, con
     * chiavi `number:<label>` che sopravvivono al cambio fonte) e del set legacy per-fonte.
     */
    fun streamingReadChapterIds(seriesKey: String?, sourceId: String, mangaUrl: String): Set<String> {
        val legacy = prefs.getStringSet(streamingReadPrefKey(sourceId, mangaUrl))
        val perSeries = seriesKey
            ?.let { prefs.getStringSet(streamingReadSeriesPrefKey(it)) }
            .orEmpty()
        return legacy + perSeries
    }

    fun streamingReadChapterIds(plan: DownloadPlan): Set<String> {
        return streamingReadChapterIds(seriesKey = null, sourceId = plan.sourceId, mangaUrl = plan.mangaUrl)
    }

    fun markStreamingChapterRead(
        sourceId: String,
        mangaUrl: String,
        chapter: ChapterEntry,
    ): String {
        return markStreamingChapterRead(seriesKey = null, sourceId = sourceId, mangaUrl = mangaUrl, chapter = chapter)
    }

    /**
     * Segna un capitolo streaming come letto: scrive l'id stabile nel set legacy per-fonte
     * e, se c'è una [seriesKey], anche id stabile + chiave `number:<label>` nel set
     * per-serie, così il progresso segue la serie a prescindere dal server.
     */
    fun markStreamingChapterRead(
        seriesKey: String?,
        sourceId: String,
        mangaUrl: String,
        chapter: ChapterEntry,
    ): String {
        val chapterId = DownloadStorage.stableChapterId(chapter)
        val numberKey = DownloadStorage.chapterNumberKey(chapter.displayNumber(), chapter.variantTag)
        prefs.edit {
            val legacyKey = streamingReadPrefKey(sourceId, mangaUrl)
            putStringSet(legacyKey, prefs.getStringSet(legacyKey) + chapterId)
            seriesKey?.let { key ->
                val seriesPrefKey = streamingReadSeriesPrefKey(key)
                putStringSet(
                    seriesPrefKey,
                    prefs.getStringSet(seriesPrefKey) + chapterId + numberKey,
                )
            }
        }
        return chapterId
    }

    /** Sposta il set streaming per-serie da una chiave all'altra (promozione title:→anilist:). */
    fun migrateStreamingSeriesKey(oldKey: String, newKey: String) {
        val oldPrefKey = streamingReadSeriesPrefKey(oldKey)
        val existing = prefs.getStringSet(oldPrefKey)
        if (existing.isEmpty()) return
        val newPrefKey = streamingReadSeriesPrefKey(newKey)
        prefs.edit {
            putStringSet(newPrefKey, prefs.getStringSet(newPrefKey) + existing)
            remove(oldPrefKey)
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
            fileSystem.delete(chapter.file, mustExist = false)
            clearChapterState(chapter.relativePath, clearReadState = false)
        }

        invalidateCache()

        val remainingChapterFiles = fileSystem.listOrEmpty(series.directory)
            .filter { fileSystem.isFile(it) && it.extension.equals("cbz", ignoreCase = true) }

        if (remainingChapterFiles.isEmpty()) {
            fileSystem.deleteRecursively(series.directory, mustExist = false)
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
        fileSystem.deleteRecursively(series.directory, mustExist = false)
        invalidateCache()
    }

    fun isChapterRead(relativePath: String): Boolean {
        return prefs.getBoolean(readPrefKey(relativePath), false)
    }

    fun readerPagePosition(relativePath: String): ReaderPagePosition? {
        if (!prefs.hasKey(readerPageIndexPrefKey(relativePath))) {
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

    suspend fun extractReaderPages(chapter: DownloadedChapter): List<Path> = withContext(Dispatchers.IO) {
        val cacheRoot = paths.readerPagesCache.also { fileSystem.createDirectories(it) }
        val cacheDir = cacheRoot / DownloadStorage.readerCacheDirectoryName(chapter.relativePath)
        val existing = readerPageFiles(cacheDir)
        // La cache si riusa solo se integra: un file vuoto (scrittura interrotta, spazio
        // esaurito) sarebbe una pagina irrecuperabile a ogni rilettura — il .cbz in
        // libreria è ancora lì, meglio ributtare giù tutto da quello.
        if (existing.isNotEmpty() && existing.all { (fileSystem.metadataOrNull(it)?.size ?: 0L) > 0L }) {
            // Rinfresca l'ultimo uso: per l'eviction LRU questo capitolo è appena stato usato.
            markReaderCacheUsed(cacheDir)
            return@withContext existing
        }

        fileSystem.deleteRecursively(cacheDir, mustExist = false)
        // Estrazione in una cartella temporanea rinominata solo a lavoro finito: se il
        // processo muore a metà non resta una cache parziale che alla riapertura verrebbe
        // scambiata per estrazione completa (pagine mancanti in silenzio).
        val tempDir = cacheRoot / "${cacheDir.name}.tmp"
        fileSystem.deleteRecursively(tempDir, mustExist = false)

        // Un .cbz corrotto/troncato non lascia residui: ChapterArchive ripulisce tempDir.
        ChapterArchive.extractPages(fileSystem, chapter.file, tempDir)
        try {
            fileSystem.atomicMove(tempDir, cacheDir)
        } catch (e: IOException) {
            fileSystem.deleteRecursively(tempDir, mustExist = false)
            throw IOException("Impossibile finalizzare le pagine estratte", e)
        }

        markReaderCacheUsed(cacheDir)
        evictOldReaderPageCaches(cacheRoot, justExtracted = cacheDir)
        readerPageFiles(cacheDir)
    }

    /** Le pagine estratte in [cacheDir], in ordine di nome (i file nascosti sono di servizio). */
    private fun readerPageFiles(cacheDir: Path): List<Path> =
        fileSystem.listOrEmpty(cacheDir)
            .filter { !it.name.startsWith(".") && fileSystem.isFile(it) }
            .sortedBy { it.name }

    /**
     * Ultimo uso di una cartella di pagine estratte, scritto in un marcatore: okio non può
     * aggiornare la data di modifica di una cartella, che prima faceva da orologio della LRU.
     */
    private fun markReaderCacheUsed(cacheDir: Path) {
        fileSystem.writeText(cacheDir / READER_CACHE_USED_MARKER, currentTimeMillis().toString())
    }

    private fun readerCacheLastUsed(cacheDir: Path): Long =
        runCatching { fileSystem.readText(cacheDir / READER_CACHE_USED_MARKER).trim().toLong() }.getOrDefault(0L)

    /**
     * Tiene la cache delle pagine estratte entro [MAX_EXTRACTED_READER_CHAPTERS] capitoli,
     * cancellando i meno usati di recente (LRU sul marcatore di ultimo uso, rinfrescato a ogni
     * apertura). Le pagine estratte servono solo alla lettura corrente e alle riletture
     * ravvicinate: il .cbz in libreria resta la copia primaria e riaprire un capitolo evitto
     * costa solo una nuova estrazione. Senza tetto la cache duplicava per sempre ogni capitolo letto.
     */
    private fun evictOldReaderPageCaches(cacheRoot: Path, justExtracted: Path) {
        fileSystem.listOrEmpty(cacheRoot)
            .filter { fileSystem.isDirectory(it) && it != justExtracted }
            .sortedByDescending { readerCacheLastUsed(it) }
            .drop((MAX_EXTRACTED_READER_CHAPTERS - 1).coerceAtLeast(0))
            .forEach { fileSystem.deleteRecursively(it, mustExist = false) }
    }

    private fun backfillMetadata(series: DownloadedSeries, existingMetadata: SeriesMetadata?) {
        val metadataFile = series.directory / DownloadStorage.SERIES_METADATA_FILE_NAME
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
                fileSystem,
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
                    variantTag = chapter.variantTag,
                    publishedAtMillis = chapter.publishedAtMillis,
                )
            },
        )
        SeriesMetadataJson.write(metadataFile, metadata, fileSystem)
    }

    private fun rewriteMetadataForExistingFiles(
        directory: Path,
        fallbackTitle: String,
        fallbackMangaUrl: String?,
        fallbackCoverFileName: String?,
    ) {
        val metadataFile = directory / DownloadStorage.SERIES_METADATA_FILE_NAME
        val existingMetadata = SeriesMetadataJson.read(metadataFile, fileSystem)
        val existingByFileName = existingMetadata?.chapters?.associateBy { it.fileName }.orEmpty()
        val chapterFiles = fileSystem.listOrEmpty(directory)
            .filter { fileSystem.isFile(it) && it.extension.equals("cbz", ignoreCase = true) }
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
                    publishedAtMillis = preserved?.publishedAtMillis,
                )
            },
        )
        SeriesMetadataJson.write(metadataFile, updated, fileSystem)
    }

    /**
     * Lettura-modifica-scrittura del JSON della serie sotto [METADATA_LOCK]: letto/non letto
     * ed eliminazioni girano su thread I/O diversi e, senza lock, l'ultima scrittura
     * cancellerebbe gli id aggiunti dall'altra.
     */
    private fun updateSeriesMetadata(
        directory: Path,
        transform: (SeriesMetadata) -> SeriesMetadata,
    ) = synchronized(METADATA_LOCK) {
        val metadataFile = directory / DownloadStorage.SERIES_METADATA_FILE_NAME
        val existing = SeriesMetadataJson.read(metadataFile, fileSystem) ?: return@synchronized
        SeriesMetadataJson.write(metadataFile, transform(existing), fileSystem)
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
        fileSystem.deleteRecursively(
            paths.readerPagesCache / DownloadStorage.readerCacheDirectoryName(relativePath),
            mustExist = false,
        )
    }

    private fun readPrefKey(relativePath: String): String = "read::$relativePath"
    private fun readerPageIndexPrefKey(relativePath: String): String = "reader_page_index::$relativePath"
    private fun readerPageCountPrefKey(relativePath: String): String = "reader_page_count::$relativePath"
    private fun readerReadAtPrefKey(relativePath: String): String = "reader_read_at::$relativePath"
    private fun streamingReadPrefKey(sourceId: String, mangaUrl: String): String {
        return "streaming_read::${MangaSourceCatalog.identityKey(sourceId, mangaUrl)}"
    }

    private fun streamingReadSeriesPrefKey(seriesKey: String): String {
        return "streaming_read_series::$seriesKey"
    }

    companion object {
        const val PREFS_NAME = "manga_library_prefs"
        private const val CACHE_TTL_MS = 5_000L

        // Di processo, non d'istanza: ViewModel e fonti (nel worker) creano repository distinti.
        private val METADATA_LOCK = SynchronizedObject()

        /** Marcatore di ultimo uso nelle cartelle di pagine estratte (vedi [markReaderCacheUsed]). */
        private const val READER_CACHE_USED_MARKER = ".last-used"

        // Massimo di capitoli con le pagine estratte tenuti in cache (LRU): copre il
        // capitolo in lettura e le riletture recenti senza duplicare l'intera libreria.
        private const val MAX_EXTRACTED_READER_CHAPTERS = 10
    }
}
