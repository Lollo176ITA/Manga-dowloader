package com.lorenzo.mangadownloader.data.sources

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.lorenzo.mangadownloader.data.library.ChapterArchive
import com.lorenzo.mangadownloader.data.library.DownloadStorage
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.library.SeriesMetadata
import com.lorenzo.mangadownloader.data.library.SeriesMetadataChapter
import com.lorenzo.mangadownloader.data.library.SeriesMetadataJson
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.DownloadPlan
import com.lorenzo.mangadownloader.data.model.DownloadResult
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.identityKey
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.domain.isAdultGenre
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.listOrEmpty
import com.lorenzo.mangadownloader.platform.nameWithoutExtension
import com.lorenzo.mangadownloader.platform.platformImageOps
import com.lorenzo.mangadownloader.platform.renameTo
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.platform.writeBytes
import com.lorenzo.mangadownloader.ui.reader.TallPageNormalizer
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

interface MangaSource {
    val descriptor: MangaSourceDescriptor

    fun canHandleUrl(url: String): Boolean

    suspend fun searchManga(query: String): List<MangaSearchResult>

    suspend fun fetchMangaDetails(mangaUrl: String): MangaDetails

    suspend fun fetchChapterPageImageUrls(chapterUrl: String): List<String>

    suspend fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String? = null): DownloadPlan

    suspend fun prepareSeriesStorage(plan: DownloadPlan)

    suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: Path,
        pageConcurrency: Int,
        onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit = { _, _ -> },
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult
}

class MangaSourceRegistry(
    appSettings: Settings,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository,
) {
    private val sources = mapOf(
        MangaSourceIds.MANGAPILL to MangapillSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.HASTA_TEAM to HastaTeamSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.MANGA_WORLD to MangaWorldSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.VYMANGA to VyMangaSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.ASURA_SCANS to AsuraScansSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.DEMONIC_SCANS to DemonicScansSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.TCB_SCANS to TcbScansSource(appSettings, networkClient, libraryRepository),
        MangaSourceIds.WEEB_CENTRAL to WeebCentralSource(appSettings, networkClient, libraryRepository),
    )
    val descriptors: List<MangaSourceDescriptor>
        get() = MangaSourceCatalog.descriptors

    fun requireById(sourceId: String): MangaSource {
        return sources.getValue(MangaSourceCatalog.resolveSourceId(sourceId))
    }

    fun resolve(
        sourceId: String?,
        url: String?,
    ): MangaSource {
        val resolvedId = MangaSourceCatalog.resolveSourceId(sourceId, url)
        return sources.getValue(resolvedId)
    }
}

abstract class BaseMangaSource(
    protected val appSettings: Settings,
    protected val networkClient: MangaNetworkClient,
    private val libraryRepository: LibraryRepository,
) : MangaSource {
    protected abstract val invalidChapterUrlMessage: String

    /** File system dei download: quello del dispositivo, sostituibile nei test. */
    protected open val fileSystem: FileSystem = systemFileSystem

    private val tallPageNormalizer by lazy { TallPageNormalizer(platformImageOps, fileSystem) }

    protected abstract fun canonicalMangaUrl(url: String): String?

    protected abstract suspend fun fetchPageImageUrls(chapterUrl: String): List<String>

    override suspend fun fetchChapterPageImageUrls(chapterUrl: String): List<String> {
        return fetchPageImageUrls(chapterUrl)
    }

    override suspend fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String?): DownloadPlan {
        val normalizedFirstUrl = firstChapterUrl.trim()
        val normalizedLastUrl = lastChapterUrl?.trim().orEmpty().ifBlank { null }

        val canonical = canonicalMangaUrl(normalizedFirstUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        val details = fetchMangaDetails(canonical)

        val startIndex = details.chapters.indexOfFirst { sameUrl(it.url, normalizedFirstUrl) }
        if (startIndex < 0) {
            throw IllegalStateException("Capitolo iniziale non trovato nella pagina manga")
        }
        val endIndex = normalizedLastUrl?.let { targetUrl ->
            details.chapters.indexOfFirst { sameUrl(it.url, targetUrl) }
        } ?: details.chapters.lastIndex
        if (endIndex < 0) {
            throw IllegalStateException("Capitolo finale non trovato nella pagina manga")
        }
        if (endIndex < startIndex) {
            throw IllegalStateException("Il capitolo finale deve essere successivo o uguale a quello iniziale")
        }

        val selected = details.chapters.subList(startIndex, endIndex + 1)
        if (selected.isEmpty()) {
            throw IllegalStateException("Nessun capitolo trovato nell'intervallo selezionato")
        }

        val outputDir = libraryRepository.libraryRoot / DownloadStorage.safeFilename(details.title)
        fileSystem.createDirectories(outputDir)

        return DownloadPlan(
            sourceId = descriptor.id,
            seriesTitle = details.title,
            mangaUrl = details.mangaUrl,
            coverUrl = details.coverUrl,
            outputDir = outputDir,
            chapters = selected,
            totalChapterCount = details.chapters.size,
            startChapterLabel = selected.first().displayLabel(),
            endChapterLabel = selected.last().displayLabel(),
        )
    }

    override suspend fun prepareSeriesStorage(plan: DownloadPlan) {
        val coverFileName = ensureCoverFile(plan.coverUrl, plan.mangaUrl, plan.outputDir)
        val metadataFile = plan.outputDir / DownloadStorage.SERIES_METADATA_FILE_NAME
        val existingMetadata = SeriesMetadataJson.read(metadataFile, fileSystem)
        val mergedChapters = linkedMapOf<String, SeriesMetadataChapter>()
        existingMetadata?.chapters.orEmpty().forEach { chapter ->
            mergedChapters[chapter.fileName] = chapter
        }
        plan.chapters.forEach { chapter ->
            val fileName = DownloadStorage.buildChapterFileName(chapter)
            mergedChapters[fileName] = SeriesMetadataChapter(
                numberText = chapter.displayNumber(),
                url = chapter.url,
                slug = chapter.slug,
                fileName = fileName,
                id = DownloadStorage.stableChapterId(
                    numberText = chapter.displayNumber(),
                    url = chapter.url,
                    slug = chapter.slug,
                ),
                volumeText = chapter.volumeText,
                labelPrefix = chapter.labelPrefix,
                variantTag = chapter.normalizedVariantTag(),
                publishedAtMillis = chapter.publishedAtMillis,
            )
        }
        val streamingReadChapterIds = libraryRepository.streamingReadChapterIds(plan)
        val metadata = SeriesMetadata(
            sourceId = existingMetadata?.sourceId ?: plan.sourceId,
            title = plan.seriesTitle,
            mangaUrl = plan.mangaUrl,
            coverFileName = coverFileName,
            totalChapters = maxOf(existingMetadata?.totalChapters ?: 0, plan.totalChapterCount),
            readChapterIds = existingMetadata?.readChapterIds.orEmpty() + streamingReadChapterIds,
            chapters = mergedChapters.values.toList(),
        )
        SeriesMetadataJson.write(metadataFile, metadata, fileSystem)
    }

    override suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: Path,
        pageConcurrency: Int,
        onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult {
        val outputFile = outputDir / DownloadStorage.buildChapterFileName(chapter)
        if (fileSystem.exists(outputFile)) {
            return DownloadResult.SKIPPED_EXISTING
        }

        ensureEnoughFreeSpace(outputDir)

        val tempFile = outputDir / "${outputFile.name}.part"
        fileSystem.delete(tempFile, mustExist = false)

        val tempPageDir = outputDir / ".${outputFile.nameWithoutExtension}_pages"
        fileSystem.deleteRecursively(tempPageDir, mustExist = false)
        fileSystem.createDirectories(tempPageDir)

        try {
            val pageFiles = downloadPageFiles(
                chapter = chapter,
                pageConcurrency = pageConcurrency,
                outputDir = tempPageDir,
                onProcessingProgress = onProcessingProgress,
                onPageProgress = onPageProgress,
            )

            // JPEG/PNG/WebP sono già compressi: archivio STORED, nell'ordine delle pagine.
            ChapterArchive.write(
                fileSystem = fileSystem,
                target = tempFile,
                files = pageFiles.sortedBy { it.index }.flatMap { it.files },
            )

            if (!fileSystem.renameTo(tempFile, outputFile)) {
                throw IOException("Impossibile finalizzare ${outputFile.name}")
            }
            return DownloadResult.DOWNLOADED
        } finally {
            fileSystem.deleteRecursively(tempPageDir, mustExist = false)
            fileSystem.delete(tempFile, mustExist = false)
        }
    }

    protected suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = networkClient.fetchDocument(url, headers)

    protected suspend fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = networkClient.fetchString(url, headers)

    /** Spazio libero sul volume di [dir]. Overridabile nei test per simulare il disco pieno. */
    protected open fun availableSpaceBytes(dir: Path): Long = DownloadStorage.freeSpaceBytes(dir)

    private fun ensureEnoughFreeSpace(dir: Path) {
        if (!DownloadStorage.hasEnoughFreeSpace(availableSpaceBytes(dir))) {
            throw InsufficientStorageException(
                "Spazio insufficiente sul dispositivo: libera spazio e riprova.",
            )
        }
    }

    private suspend fun downloadPageFiles(
        chapter: ChapterEntry,
        pageConcurrency: Int,
        outputDir: Path,
        onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): List<DownloadedPageTempFile> = coroutineScope {
        val pageUrls = fetchPageImageUrls(chapter.url)
        val semaphore = Semaphore(pageConcurrency)
        val progressMutex = Mutex()
        var completedPages = 0

        val downloadedPages = pageUrls.mapIndexed { index, pageUrl ->
            async(Dispatchers.IO) {
                val downloadedPage = semaphore.withPermit {
                    val extension = DownloadStorage.imageExtension(pageUrl)
                    val finalName = "${(index + 1).toString().padStart(3, '0')}.$extension"
                    val tempName = "$finalName.part"
                    val tempFile = outputDir / tempName
                    val finalFile = outputDir / finalName

                    fileSystem.sink(tempFile).use { output ->
                        // Scrittura a blocchi: evita quattro ByteArray di pagine intere e il
                        // relativo lavoro del GC mentre i trasferimenti procedono in parallelo.
                        networkClient.fetchToSink(pageUrl, sink = output, referer = chapter.url)
                    }

                    if (!fileSystem.renameTo(tempFile, finalFile)) {
                        fileSystem.delete(tempFile, mustExist = false)
                        throw IOException("Impossibile finalizzare la pagina $finalName")
                    }

                    DownloadedPageTempFile(index = index, files = listOf(finalFile))
                }

                // La UI non trattiene un permit di rete e gli eventi restano monotoni anche
                // quando piu trasferimenti terminano nello stesso istante.
                progressMutex.withLock {
                    completedPages += 1
                    onPageProgress(completedPages, pageUrls.size)
                }
                downloadedPage
            }
        }.awaitAll()

        if (descriptor.id == MangaSourceIds.VYMANGA) {
            // VyManga mantiene intenzionalmente invariata la propria pipeline.
            return@coroutineScope downloadedPages
        }

        // La rete ha gia rilasciato tutti i permit: la preparazione delle immagini non puo
        // piu bloccare gli altri download. Le pagine normali fanno solo un rapido bounds check;
        // il normalizzatore serializza internamente esclusivamente le pagine davvero alte.
        onProcessingProgress(0, downloadedPages.size)
        downloadedPages.mapIndexed { processedIndex, page ->
            val finalFile = page.files.single()
            val normalization = withContext(Dispatchers.IO) {
                tallPageNormalizer.normalize(
                    source = finalFile,
                    outputDirectory = outputDir,
                    outputBaseName = finalFile.nameWithoutExtension,
                )
            }
            if (normalization.wasSplit) {
                try {
                    fileSystem.delete(finalFile, mustExist = false)
                } catch (e: IOException) {
                    throw IOException("Impossibile rimuovere la pagina originale ${finalFile.name}", e)
                }
            }
            onProcessingProgress(processedIndex + 1, downloadedPages.size)
            DownloadedPageTempFile(index = page.index, files = normalization.files)
        }
    }

    private suspend fun ensureCoverFile(
        coverUrl: String?,
        mangaUrl: String,
        outputDir: Path,
    ): String? {
        val existing = fileSystem.listOrEmpty(outputDir)
            .firstOrNull { file ->
                fileSystem.isFile(file) && file.name.startsWith("cover.", ignoreCase = true)
            }
        if (existing != null) {
            return existing.name
        }
        if (coverUrl.isNullOrBlank()) {
            return null
        }

        val extension = DownloadStorage.imageExtension(coverUrl)
        val finalFile = outputDir / "cover.$extension"
        val tempFile = outputDir / "${finalFile.name}.part"
        fileSystem.writeBytes(tempFile, networkClient.fetchBytes(coverUrl, referer = mangaUrl))
        if (!fileSystem.renameTo(tempFile, finalFile)) {
            fileSystem.delete(tempFile, mustExist = false)
            throw IOException("Impossibile salvare la copertina")
        }
        return finalFile.name
    }

    private fun sameUrl(
        left: String,
        right: String,
    ): Boolean {
        return normalizeChapterUrlForComparison(left) == normalizeChapterUrlForComparison(right)
    }

    protected open fun normalizeChapterUrlForComparison(url: String): String {
        return url.trim().substringBefore('#').removeSuffix("/")
    }
}

private data class DownloadedPageTempFile(
    val index: Int,
    val files: List<Path>,
)
