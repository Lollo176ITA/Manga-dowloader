package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.platform.extension
import com.lorenzo.mangadownloader.platform.isDirectory
import com.lorenzo.mangadownloader.platform.isFile
import com.lorenzo.mangadownloader.platform.listOrEmpty
import com.lorenzo.mangadownloader.platform.readText
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.platform.writeText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

object SeriesMetadataJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun write(target: Path, metadata: SeriesMetadata, fileSystem: FileSystem = systemFileSystem) {
        val stableMetadata = metadata.copy(readChapterIds = metadata.readChapterIds.sorted().toSet())
        fileSystem.writeText(target, json.encodeToString(stableMetadata))
    }

    fun read(target: Path, fileSystem: FileSystem = systemFileSystem): SeriesMetadata? {
        if (!fileSystem.isFile(target)) return null
        return try {
            parse(fileSystem.readText(target))
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

data class ScannedSeries(
    val series: DownloadedSeries,
    val metadata: SeriesMetadata?,
)

object LibraryScanner {
    fun scan(
        root: Path,
        isRead: (String) -> Boolean,
        readerPagePosition: (String) -> ReaderPagePosition? = { null },
        fileSystem: FileSystem = systemFileSystem,
    ): List<DownloadedSeries> = scanWithMetadata(root, isRead, readerPagePosition, fileSystem).map { it.series }

    /**
     * Come [scan], ma restituisce anche i metadati letti per ogni serie (`null` se mancanti o
     * illeggibili): chi deve completarli li riusa invece di rileggere e riparsare il JSON.
     */
    fun scanWithMetadata(
        root: Path,
        isRead: (String) -> Boolean,
        readerPagePosition: (String) -> ReaderPagePosition? = { null },
        fileSystem: FileSystem = systemFileSystem,
    ): List<ScannedSeries> {
        if (!fileSystem.exists(root)) return emptyList()

        return fileSystem.listOrEmpty(root)
            .filter { fileSystem.isDirectory(it) }
            .mapNotNull { directory ->
                scanSeriesDirectory(root, directory, isRead, readerPagePosition, fileSystem)
            }
            .sortedBy { it.series.title.lowercase() }
    }

    private fun scanSeriesDirectory(
        root: Path,
        directory: Path,
        isRead: (String) -> Boolean,
        readerPagePosition: (String) -> ReaderPagePosition?,
        fileSystem: FileSystem,
    ): ScannedSeries? {
        if (!fileSystem.isDirectory(directory)) return null

        val metadata = SeriesMetadataJson.read(directory / DownloadStorage.SERIES_METADATA_FILE_NAME, fileSystem)
        val metadataByFileName = metadata?.chapters?.associateBy { it.fileName }.orEmpty()
        val persistedReadIds = metadata?.readChapterIds.orEmpty()
        val coverFile = resolveCoverFile(directory, metadata, fileSystem)
        val sourceId = MangaSourceCatalog.resolveSourceId(
            sourceId = metadata?.sourceId,
            url = metadata?.mangaUrl,
        )

        val chapters = fileSystem.listOrEmpty(directory)
            .filter { fileSystem.isFile(it) && it.extension.equals("cbz", ignoreCase = true) }
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
                // Senza metadati la variante si recupera dal nome file, così un .cbz di un
                // gruppo secondario non viene riattribuito a quello principale.
                val variantTag = chapterMeta?.variantTag?.trim()?.takeIf(String::isNotBlank)
                    ?: DownloadStorage.parseChapterVariantFromFileName(file.name)
                val baseTitle = variantTag
                    ?.let { "$labelPrefix $normalized ($it)" }
                    ?: "$labelPrefix $normalized"
                DownloadedChapter(
                    title = volumeText?.let { "$it - $baseTitle" } ?: baseTitle,
                    numberText = normalized,
                    numberValue = DownloadStorage.parseChapterValueOrNull(normalized),
                    volumeText = volumeText,
                    labelPrefix = labelPrefix,
                    variantTag = variantTag,
                    file = file,
                    relativePath = relativePath,
                    chapterId = chapterId,
                    isRead = chapterIsRead,
                    readerPageIndex = pagePosition?.pageIndex,
                    readerPageCount = pagePosition?.pageCount,
                    lastReadAtMillis = pagePosition?.lastReadAtMillis,
                    publishedAtMillis = chapterMeta?.publishedAtMillis,
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

        val series = DownloadedSeries(
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
        return ScannedSeries(series, metadata)
    }

    private fun resolveCoverFile(
        directory: Path,
        metadata: SeriesMetadata?,
        fileSystem: FileSystem,
    ): Path? {
        val metadataCover = metadata?.coverFileName
            ?.let { directory / it }
            ?.takeIf { fileSystem.isFile(it) }
        if (metadataCover != null) {
            return metadataCover
        }

        return fileSystem.listOrEmpty(directory)
            .filter { file ->
                fileSystem.isFile(file) &&
                    file.name.startsWith("cover.", ignoreCase = true) &&
                    file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
            }
            .sortedBy { it.name }
            .firstOrNull()
    }
}
