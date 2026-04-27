package com.lorenzo.mangadownloader

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MangaSourceDescriptor(
    val id: String,
    val displayName: String,
    val shortName: String,
)

data class MangaSearchConfig(
    val minQueryLength: Int,
    val showAllOnEmptyQuery: Boolean = false,
)

object MangaSourceIds {
    const val MANGAPILL = "mangapill"
    const val HASTA_TEAM = "hasta_team"
    const val MANGA_WORLD = "manga_world"
    const val DEFAULT = MANGAPILL
}

object MangaSourceCatalog {
    val descriptors = listOf(
        MangaSourceDescriptor(MangaSourceIds.MANGAPILL, "Mangapill", "MP"),
        MangaSourceDescriptor(MangaSourceIds.HASTA_TEAM, "Hasta Team", "HT"),
        MangaSourceDescriptor(MangaSourceIds.MANGA_WORLD, "MangaWorld", "MW"),
    )

    fun resolveSourceId(
        sourceId: String?,
        url: String? = null,
    ): String {
        val normalizedSourceId = sourceId
            ?.trim()
            ?.takeIf { candidate -> descriptors.any { it.id == candidate } }
        if (normalizedSourceId != null) {
            return normalizedSourceId
        }
        return sourceIdForUrl(url) ?: MangaSourceIds.DEFAULT
    }

    fun sourceIdForUrl(url: String?): String? {
        val normalizedUrl = url?.trim().orEmpty()
        if (normalizedUrl.isBlank()) {
            return null
        }
        return when {
            MangapillSource.handlesUrl(normalizedUrl) -> MangaSourceIds.MANGAPILL
            HastaTeamSource.handlesUrl(normalizedUrl) -> MangaSourceIds.HASTA_TEAM
            MangaWorldSource.handlesUrl(normalizedUrl) -> MangaSourceIds.MANGA_WORLD
            else -> null
        }
    }

    fun displayName(sourceId: String): String {
        val resolved = resolveSourceId(sourceId)
        return descriptors.firstOrNull { it.id == resolved }?.displayName ?: descriptors.first().displayName
    }

    fun shortDisplayName(sourceId: String): String {
        val resolved = resolveSourceId(sourceId)
        return descriptors.firstOrNull { it.id == resolved }?.shortName ?: descriptors.first().shortName
    }

    fun searchConfig(sourceId: String): MangaSearchConfig {
        return when (resolveSourceId(sourceId)) {
            MangaSourceIds.HASTA_TEAM -> MangaSearchConfig(
                minQueryLength = 1,
                showAllOnEmptyQuery = true,
            )
            else -> MangaSearchConfig(minQueryLength = DEFAULT_MIN_QUERY_LENGTH)
        }
    }

    fun identityKey(
        sourceId: String,
        mangaUrl: String,
    ): String {
        val resolvedSourceId = resolveSourceId(sourceId, mangaUrl)
        val normalizedUrl = normalizeSeriesUrl(resolvedSourceId, mangaUrl) ?: mangaUrl.trim()
        return "$resolvedSourceId::$normalizedUrl"
    }

    fun identityKeyOrNull(
        sourceId: String?,
        mangaUrl: String?,
        title: String? = null,
    ): String? {
        val normalizedUrl = mangaUrl?.trim()?.takeIf(String::isNotBlank)
        if (normalizedUrl != null) {
            return identityKey(resolveSourceId(sourceId, normalizedUrl), normalizedUrl)
        }
        val normalizedTitle = title?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotBlank) ?: return null
        val resolvedSourceId = resolveSourceId(sourceId)
        return "$resolvedSourceId::title:$normalizedTitle"
    }

    fun normalizeSeriesUrl(
        sourceId: String,
        url: String,
    ): String? {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            return null
        }
        return when (resolveSourceId(sourceId, normalizedUrl)) {
            MangaSourceIds.MANGAPILL -> MangapillSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.HASTA_TEAM -> HastaTeamSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.MANGA_WORLD -> MangaWorldSource.canonicalSeriesUrl(normalizedUrl)
            else -> normalizedUrl
        } ?: normalizedUrl
    }

    private const val DEFAULT_MIN_QUERY_LENGTH = 3
}

interface MangaSource {
    val descriptor: MangaSourceDescriptor

    fun canHandleUrl(url: String): Boolean

    fun searchManga(query: String): List<MangaSearchResult>

    fun fetchMangaDetails(mangaUrl: String): MangaDetails

    fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String? = null): DownloadPlan

    fun prepareSeriesStorage(plan: DownloadPlan)

    suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: File,
        pageConcurrency: Int,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult
}

class MangaSourceRegistry(
    context: Context,
) {
    private val networkClient = MangaNetworkClient()
    private val sources = mapOf(
        MangaSourceIds.MANGAPILL to MangapillSource(context, networkClient),
        MangaSourceIds.HASTA_TEAM to HastaTeamSource(context, networkClient),
        MangaSourceIds.MANGA_WORLD to MangaWorldSource(context, networkClient),
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
    protected val context: Context,
    protected val networkClient: MangaNetworkClient,
) : MangaSource {
    protected abstract val invalidChapterUrlMessage: String

    protected abstract fun canonicalMangaUrl(url: String): String?

    protected abstract fun fetchPageImageUrls(chapterUrl: String): List<String>

    override fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String?): DownloadPlan {
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

        val outputDir = File(
            DownloadStorage.libraryRoot(context),
            DownloadStorage.safeFilename(details.title),
        )
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        return DownloadPlan(
            sourceId = descriptor.id,
            seriesTitle = details.title,
            mangaUrl = details.mangaUrl,
            coverUrl = details.coverUrl,
            outputDir = outputDir,
            chapters = selected,
            totalChapterCount = details.chapters.size,
            startChapterLabel = selected.first().displayNumber(),
            endChapterLabel = selected.last().displayNumber(),
        )
    }

    override fun prepareSeriesStorage(plan: DownloadPlan) {
        val coverFileName = ensureCoverFile(plan.coverUrl, plan.mangaUrl, plan.outputDir)
        val metadataFile = File(plan.outputDir, DownloadStorage.SERIES_METADATA_FILE_NAME)
        val existingMetadata = SeriesMetadataJson.read(metadataFile)
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
            )
        }
        val metadata = SeriesMetadata(
            sourceId = existingMetadata?.sourceId ?: plan.sourceId,
            title = plan.seriesTitle,
            mangaUrl = plan.mangaUrl,
            coverFileName = coverFileName,
            totalChapters = maxOf(existingMetadata?.totalChapters ?: 0, plan.totalChapterCount),
            readChapterIds = existingMetadata?.readChapterIds.orEmpty(),
            chapters = mergedChapters.values.toList(),
        )
        SeriesMetadataJson.write(metadataFile, metadata)
    }

    override suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: File,
        pageConcurrency: Int,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult {
        val outputFile = File(outputDir, DownloadStorage.buildChapterFileName(chapter))
        if (outputFile.exists()) {
            return DownloadResult.SKIPPED_EXISTING
        }

        val tempFile = File(outputDir, "${outputFile.name}.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val tempPageDir = File(outputDir, ".${outputFile.nameWithoutExtension}_pages")
        if (tempPageDir.exists()) {
            tempPageDir.deleteRecursively()
        }
        tempPageDir.mkdirs()

        try {
            val pageFiles = downloadPageFiles(
                chapter = chapter,
                pageConcurrency = pageConcurrency,
                outputDir = tempPageDir,
                onPageProgress = onPageProgress,
            )

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                for (page in pageFiles.sortedBy { it.index }) {
                    zip.putNextEntry(ZipEntry(page.file.name))
                    page.file.inputStream().buffered().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }

            if (!tempFile.renameTo(outputFile)) {
                throw IOException("Impossibile finalizzare ${outputFile.name}")
            }
            return DownloadResult.DOWNLOADED
        } finally {
            tempPageDir.deleteRecursively()
            if (tempFile.exists() && !outputFile.exists()) {
                tempFile.delete()
            }
        }
    }

    protected fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = networkClient.fetchDocument(url, headers)

    protected fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ) = networkClient.fetchString(url, headers)

    protected fun absolutize(baseUrl: String, value: String) = networkClient.absolutize(baseUrl, value)

    protected fun parseChapterNumber(text: String) = DownloadStorage.parseChapterValueOrNull(text)
        ?: throw IllegalArgumentException("Numero capitolo non valido: $text")

    protected fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    protected fun extractImageExtension(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('.', "jpg")
        val cleaned = raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        return if (cleaned.isBlank()) "jpg" else cleaned
    }

    private suspend fun downloadPageFiles(
        chapter: ChapterEntry,
        pageConcurrency: Int,
        outputDir: File,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): List<DownloadedPageTempFile> = coroutineScope {
        val pageUrls = fetchPageImageUrls(chapter.url)
        val semaphore = Semaphore(pageConcurrency)
        val progressLock = Any()
        var completedPages = 0

        pageUrls.mapIndexed { index, pageUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val extension = extractImageExtension(pageUrl)
                    val finalName = "${(index + 1).toString().padStart(3, '0')}.$extension"
                    val tempName = "$finalName.part"
                    val tempFile = File(outputDir, tempName)
                    val finalFile = File(outputDir, finalName)

                    tempFile.outputStream().buffered().use { output ->
                        output.write(networkClient.fetchBytes(pageUrl, referer = chapter.url))
                    }

                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.delete()
                        throw IOException("Impossibile finalizzare la pagina $finalName")
                    }

                    val progressValue = synchronized(progressLock) {
                        completedPages += 1
                        completedPages
                    }
                    onPageProgress(progressValue, pageUrls.size)
                    DownloadedPageTempFile(index = index, file = finalFile)
                }
            }
        }.awaitAll()
    }

    private fun ensureCoverFile(
        coverUrl: String?,
        mangaUrl: String,
        outputDir: File,
    ): String? {
        val existing = outputDir.listFiles()
            ?.firstOrNull { file ->
                file.isFile && file.name.startsWith("cover.", ignoreCase = true)
            }
        if (existing != null) {
            return existing.name
        }
        if (coverUrl.isNullOrBlank()) {
            return null
        }

        val extension = extractImageExtension(coverUrl)
        val finalFile = File(outputDir, "cover.$extension")
        val tempFile = File(outputDir, "${finalFile.name}.part")
        tempFile.outputStream().buffered().use { output ->
            output.write(networkClient.fetchBytes(coverUrl, referer = mangaUrl))
        }
        if (!tempFile.renameTo(finalFile)) {
            tempFile.delete()
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
    val file: File,
)
