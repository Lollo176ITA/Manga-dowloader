package com.lorenzo.mangadownloader.data.download

import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.data.library.ChapterArchive
import com.lorenzo.mangadownloader.data.library.DownloadStorage
import com.lorenzo.mangadownloader.data.model.DownloadResult
import com.lorenzo.mangadownloader.data.sources.InsufficientStorageException
import com.lorenzo.mangadownloader.data.sources.MangaSource
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.platform.platformImageOps
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.ui.reader.TallPageNormalizer
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.coroutines.coroutineContext

/** Persistito dal trasporto nativo: solo percorsi relativi, mai l'UUID del sandbox iOS. */
@Serializable
data class BackgroundDownloadManifest(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val directory: String,
    val chapters: List<BackgroundChapter>,
) {
    fun encode(): String = JSON.encodeToString(this)
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): BackgroundDownloadManifest = JSON.decodeFromString(text)
    }
}

@Serializable
data class BackgroundChapter(
    val fileName: String,
    val label: String,
    val url: String,
    val pages: List<BackgroundPage>,
    val alreadyPresent: Boolean = false,
    /** False solo nei checkpoint della preparazione: pagine ancora da chiedere alla fonte. */
    val prepared: Boolean = true,
)

@Serializable
data class BackgroundPage(val url: String, val name: String)

/** Parsing e CBZ condivisi; la piattaforma può trasferire le pagine senza coroutine attive. */
class BackgroundChapterDownloader(
    private val libraryRoot: Path,
    private val resolveSource: (String?, String) -> MangaSource,
    private val fileSystem: FileSystem = systemFileSystem,
    private val normalizePage: (suspend (Path, Path, String) -> List<Path>)? = null,
) {
    private val normalizer by lazy { TallPageNormalizer(platformImageOps, fileSystem) }

    /**
     * Con [previous] riprende un checkpoint senza ricostruire il piano né richiedere le pagine
     * dei capitoli già pronti; [onCheckpoint] riceve il manifest dopo il piano e dopo ogni capitolo.
     */
    suspend fun prepare(
        request: ChapterDownloadRequest,
        previous: BackgroundDownloadManifest? = null,
        onCheckpoint: suspend (BackgroundDownloadManifest) -> Unit = {},
    ): BackgroundDownloadManifest {
        val source = resolveSource(request.sourceId, request.firstUrl)
        var manifest = previous?.also(::requireSafeManifest) ?: plan(source, request).also { onCheckpoint(it) }
        val outputDir = libraryRoot / manifest.directory
        manifest.chapters.forEachIndexed { index, chapter ->
            if (chapter.prepared) return@forEachIndexed
            coroutineContext.ensureActive()
            val existing = fileSystem.metadataOrNull(outputDir / chapter.fileName)?.size?.let { it > 0 } == true
            val pages = if (existing) emptyList() else {
                if (!DownloadStorage.hasEnoughFreeSpace(DownloadStorage.freeSpaceBytes(outputDir))) {
                    throw InsufficientStorageException("Spazio insufficiente sul dispositivo: libera spazio e riprova.")
                }
                source.fetchChapterPageImageUrls(chapter.url).mapIndexed { pageIndex, url ->
                    BackgroundPage(url, "${(pageIndex + 1).toString().padStart(3, '0')}.${DownloadStorage.imageExtension(url)}")
                }.also { if (it.isEmpty()) throw IOException("Nessuna pagina trovata: ${chapter.label}") }
            }
            val ready = chapter.copy(pages = pages, alreadyPresent = existing, prepared = true)
            manifest = manifest.copy(chapters = manifest.chapters.toMutableList().also { it[index] = ready })
            onCheckpoint(manifest)
        }
        return manifest
    }

    private suspend fun plan(source: MangaSource, request: ChapterDownloadRequest): BackgroundDownloadManifest {
        val plan = source.buildDownloadPlan(request.firstUrl, request.lastUrl)
        source.prepareSeriesStorage(plan)
        val directory = plan.outputDir.relativeTo(libraryRoot).toString()
        requireSafeRelativePath(directory)
        val chapters = plan.chapters.map { chapter ->
            BackgroundChapter(DownloadStorage.buildChapterFileName(chapter), chapter.displayLabel(), chapter.url, emptyList(), prepared = false)
        }
        return BackgroundDownloadManifest(plan.sourceId, plan.seriesTitle, plan.mangaUrl, directory, chapters)
    }

    /** Ripetibile dopo un'interruzione: non modifica un CBZ già completo né le pagine originali. */
    suspend fun finalizeChapter(manifest: BackgroundDownloadManifest, index: Int, stagingDirectory: Path): DownloadResult {
        requireSafeManifest(manifest)
        val chapter = manifest.chapters[index]
        val directory = libraryRoot / manifest.directory
        val output = directory / chapter.fileName
        if (fileSystem.metadataOrNull(output)?.size?.let { it > 0 } == true) return DownloadResult.SKIPPED_EXISTING
        if (chapter.pages.isEmpty()) throw IOException("Pagine del capitolo non disponibili")
        fileSystem.createDirectories(directory)
        val temporary = directory / "${chapter.fileName}.part"
        val normalizedDirectory = stagingDirectory / "normalized"
        try {
            fileSystem.deleteRecursively(normalizedDirectory, mustExist = false)
            fileSystem.createDirectories(normalizedDirectory)
            val pages = chapter.pages.flatMap { page ->
                coroutineContext.ensureActive()
                val raw = stagingDirectory / page.name
                if ((fileSystem.metadataOrNull(raw)?.size ?: 0) <= 0) throw IOException("Pagina mancante o vuota: ${page.name}")
                if (manifest.sourceId == MangaSourceIds.VYMANGA) listOf(raw)
                else normalizePage?.invoke(raw, normalizedDirectory, page.name.substringBeforeLast('.'))
                    ?: normalizer.normalize(raw, normalizedDirectory, page.name.substringBeforeLast('.')).files
            }
            coroutineContext.ensureActive()
            ChapterArchive.write(fileSystem, temporary, pages)
            coroutineContext.ensureActive()
            fileSystem.atomicMove(temporary, output)
            return DownloadResult.DOWNLOADED
        } finally {
            fileSystem.delete(temporary, mustExist = false)
            fileSystem.deleteRecursively(normalizedDirectory, mustExist = false)
        }
    }
}

private fun requireSafeManifest(manifest: BackgroundDownloadManifest) {
    requireSafeRelativePath(manifest.directory)
    manifest.chapters.forEach { chapter ->
        requireSafeRelativePath(chapter.fileName, fileOnly = true)
        chapter.pages.forEach { requireSafeRelativePath(it.name, fileOnly = true) }
    }
}

private fun requireSafeRelativePath(path: String, fileOnly: Boolean = false) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':'))
    val parts = path.split('/')
    require(parts.all { it.isNotBlank() && it != "." && it != ".." })
    require(!fileOnly || parts.size == 1)
}
