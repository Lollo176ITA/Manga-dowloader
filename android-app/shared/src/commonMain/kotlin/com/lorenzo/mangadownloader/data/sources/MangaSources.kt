package com.lorenzo.mangadownloader.data.sources

import android.content.Context
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
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.domain.isAdultGenre
import com.lorenzo.mangadownloader.ui.reader.TallPageNormalizer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Lingua dei contenuti di una fonte: è il criterio con cui l'utente sceglie dove cercare. */
enum class MangaSourceLanguage(val displayName: String) {
    ITA("Italiano"),
    ENG("English"),
}

data class MangaSourceDescriptor(
    val id: String,
    val displayName: String,
    val shortName: String,
    val language: MangaSourceLanguage,
)

/**
 * Ambito della ricerca nella tab Cerca. L'utente ragiona per lingua ("lo voglio in italiano
 * o in inglese?"), non per server: lo scope [SOURCE] (fonte singola) non è più selezionabile
 * dalla UI e i valori persistiti da versioni precedenti vengono riportati alla lingua della
 * fonte in lettura (prefs e backup).
 */
enum class SearchScope(val language: MangaSourceLanguage?) {
    /** Aggregata su tutte le fonti (chip "Tutte" o ponte AniList della tab Scopri). */
    ALL(null),

    /** Aggregata sulle sole fonti italiane. */
    ITA(MangaSourceLanguage.ITA),

    /** Aggregata sulle sole fonti inglesi. */
    ENG(MangaSourceLanguage.ENG),

    /** Valore legacy, mantenuto soltanto per deserializzare preferenze e backup precedenti. */
    SOURCE(null),
    ;

    companion object {
        fun forLanguage(language: MangaSourceLanguage): SearchScope = when (language) {
            MangaSourceLanguage.ITA -> ITA
            MangaSourceLanguage.ENG -> ENG
        }
    }
}

/**
 * Spazio su disco insufficiente per completare un download. Volutamente **non** è una
 * [java.io.IOException]: il `DownloadWorker` ritenta gli `IOException` (blip di rete), ma
 * un disco pieno deve fermarsi subito con un messaggio per l'utente, non ciclare in retry.
 */
class InsufficientStorageException(message: String) : RuntimeException(message)

object MangaSourceIds {
    const val MANGAPILL = "mangapill"
    const val HASTA_TEAM = "hasta_team"
    const val MANGA_WORLD = "manga_world"
    const val VYMANGA = "vymanga"
    const val ASURA_SCANS = "asura_scans"
    const val DEMONIC_SCANS = "demonic_scans"
    const val TCB_SCANS = "tcb_scans"
    const val WEEB_CENTRAL = "weeb_central"
    const val DEFAULT = MANGAPILL
}

object MangaSourceCatalog {
    val descriptors = listOf(
        MangaSourceDescriptor(MangaSourceIds.MANGAPILL, "Mangapill", "MP", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.HASTA_TEAM, "Hasta Team", "HT", MangaSourceLanguage.ITA),
        MangaSourceDescriptor(MangaSourceIds.MANGA_WORLD, "MangaWorld", "MW", MangaSourceLanguage.ITA),
        MangaSourceDescriptor(MangaSourceIds.VYMANGA, "VyManga", "VY", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.ASURA_SCANS, "Asura Scans", "AS", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.DEMONIC_SCANS, "DemonicScans", "DS", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.TCB_SCANS, "TCB Scans", "TC", MangaSourceLanguage.ENG),
        MangaSourceDescriptor(MangaSourceIds.WEEB_CENTRAL, "Weeb Central", "WC", MangaSourceLanguage.ENG),
    )

    /**
     * Fonti senza un segnale "adulti" nella risposta di ricerca: niente generi né flag, e
     * nessun filtro lato server. Col filtro per adulti attivo la ricerca non le interroga,
     * perché non si potrebbe garantire il risultato (DemonicScans espone i generi solo nella
     * pagina serie: servirebbe una richiesta in più per ogni risultato).
     */
    val sourcesWithoutAdultSignal: Set<String> = setOf(MangaSourceIds.DEMONIC_SCANS)

    /**
     * Fonti da interrogare nella ricerca aggregata: [descriptorsForScope] più, col filtro per
     * adulti attivo, l'esclusione di [sourcesWithoutAdultSignal]. L'esclusione si applica
     * **dopo** il ripiego "mai zero fonti" di [descriptorsForScope], che altrimenti potrebbe
     * rimetterle in gioco.
     */
    fun descriptorsForSearch(
        scope: SearchScope,
        disabledSourceIds: Set<String>,
        hideAdultContent: Boolean,
    ): List<MangaSourceDescriptor> {
        val candidates = descriptorsForScope(scope, disabledSourceIds)
        if (!hideAdultContent) return candidates
        return candidates.filterNot { it.id in sourcesWithoutAdultSignal }
    }

    /** Fonti interrogate dalla ricerca aggregata per [scope]: tutte, o solo quelle della lingua. */
    fun descriptorsForScope(scope: SearchScope): List<MangaSourceDescriptor> {
        require(scope != SearchScope.SOURCE) { "SOURCE deve essere convertito durante la migrazione" }
        val language = scope.language ?: return descriptors
        return descriptors.filter { it.language == language }
    }

    /**
     * Come [descriptorsForScope], escludendo le fonti disabilitate dall'utente. Se la lingua
     * dello scope non ha più fonti attive (scope salvato da prima che l'utente le spegnesse),
     * ripiega su **tutte le fonti attive**, non su quelle disattivate della lingua: una fonte
     * spenta in impostazioni non va mai interrogata. Se l'utente spegnesse tutto, l'elenco
     * completo resta l'ultima rete di sicurezza: la ricerca non deve interrogare zero fonti.
     */
    fun descriptorsForScope(
        scope: SearchScope,
        disabledSourceIds: Set<String>,
    ): List<MangaSourceDescriptor> {
        val enabled = descriptors.filterNot { it.id in disabledSourceIds }.ifEmpty { descriptors }
        val language = scope.language ?: return enabled
        return enabled.filter { it.language == language }.ifEmpty { enabled }
    }

    /**
     * Lingue che hanno almeno una fonte attiva, nell'ordine di [MangaSourceLanguage]. La tab
     * Cerca mostra una chip per ciascuna: filtrare per una lingua senza fonti attive non
     * cambierebbe nulla. Con una sola lingua rimasta la riga di chip sparisce del tutto,
     * perché "Tutte" e quell'unica lingua interrogherebbero le stesse fonti.
     */
    fun languagesWithEnabledSources(disabledSourceIds: Set<String>): List<MangaSourceLanguage> {
        val enabled = descriptors.filterNot { it.id in disabledSourceIds }.ifEmpty { descriptors }
        return MangaSourceLanguage.entries.filter { language ->
            enabled.any { it.language == language }
        }
    }

    /** Lingua della fonte [sourceId] (con fallback sulla fonte di default se sconosciuta). */
    fun languageOf(sourceId: String): MangaSourceLanguage {
        val resolved = resolveSourceId(sourceId)
        return descriptors.first { it.id == resolved }.language
    }

    /**
     * Combina i risultati per-fonte della ricerca aggregata alternandoli round-robin (il 1°
     * di ogni fonte, poi i 2°, ...): le fonti si mescolano invece di accodarsi a blocchi, e
     * l'ordine interno di ciascuna — la rilevanza calcolata dal suo server, che conosce anche
     * i titoli alternativi (es. "demon slayer" → "Kimetsu no Yaiba") — resta intatto.
     * Riordinare lato client per somiglianza col testo la distruggerebbe.
     */
    fun <T> interleaveBySource(resultsPerSource: List<List<T>>): List<T> {
        val combined = ArrayList<T>(resultsPerSource.sumOf { it.size })
        var index = 0
        do {
            var added = false
            for (results in resultsPerSource) {
                results.getOrNull(index)?.let {
                    combined.add(it)
                    added = true
                }
            }
            index++
        } while (added)
        return combined
    }

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
            VyMangaSource.handlesUrl(normalizedUrl) -> MangaSourceIds.VYMANGA
            AsuraScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.ASURA_SCANS
            DemonicScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.DEMONIC_SCANS
            TcbScansSource.handlesUrl(normalizedUrl) -> MangaSourceIds.TCB_SCANS
            WeebCentralSource.handlesUrl(normalizedUrl) -> MangaSourceIds.WEEB_CENTRAL
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
            MangaSourceIds.VYMANGA -> VyMangaSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.ASURA_SCANS -> AsuraScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.DEMONIC_SCANS -> DemonicScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.TCB_SCANS -> TcbScansSource.canonicalSeriesUrl(normalizedUrl)
            MangaSourceIds.WEEB_CENTRAL -> WeebCentralSource.canonicalSeriesUrl(normalizedUrl)
            else -> normalizedUrl
        } ?: normalizedUrl
    }

}

interface MangaSource {
    val descriptor: MangaSourceDescriptor

    fun canHandleUrl(url: String): Boolean

    fun searchManga(query: String): List<MangaSearchResult>

    fun fetchMangaDetails(mangaUrl: String): MangaDetails

    fun fetchChapterPageImageUrls(chapterUrl: String): List<String>

    fun buildDownloadPlan(firstChapterUrl: String, lastChapterUrl: String? = null): DownloadPlan

    fun prepareSeriesStorage(plan: DownloadPlan)

    suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: File,
        pageConcurrency: Int,
        onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit = { _, _ -> },
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult
}

class MangaSourceRegistry(
    context: Context,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) {
    private val networkClient = MangaNetworkClient(SharedHttpClient.get(context))
    private val sources = mapOf(
        MangaSourceIds.MANGAPILL to MangapillSource(context, networkClient, libraryRepository),
        MangaSourceIds.HASTA_TEAM to HastaTeamSource(context, networkClient, libraryRepository),
        MangaSourceIds.MANGA_WORLD to MangaWorldSource(context, networkClient, libraryRepository),
        MangaSourceIds.VYMANGA to VyMangaSource(context, networkClient, libraryRepository),
        MangaSourceIds.ASURA_SCANS to AsuraScansSource(context, networkClient, libraryRepository),
        MangaSourceIds.DEMONIC_SCANS to DemonicScansSource(context, networkClient, libraryRepository),
        MangaSourceIds.TCB_SCANS to TcbScansSource(context, networkClient, libraryRepository),
        MangaSourceIds.WEEB_CENTRAL to WeebCentralSource(context, networkClient, libraryRepository),
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
    private val libraryRepository: LibraryRepository = LibraryRepository(context),
) : MangaSource {
    protected abstract val invalidChapterUrlMessage: String

    protected abstract fun canonicalMangaUrl(url: String): String?

    protected abstract fun fetchPageImageUrls(chapterUrl: String): List<String>

    override fun fetchChapterPageImageUrls(chapterUrl: String): List<String> {
        return fetchPageImageUrls(chapterUrl)
    }

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
            startChapterLabel = selected.first().displayLabel(),
            endChapterLabel = selected.last().displayLabel(),
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
        SeriesMetadataJson.write(metadataFile, metadata)
    }

    override suspend fun downloadChapterAsCbz(
        chapter: ChapterEntry,
        outputDir: File,
        pageConcurrency: Int,
        onProcessingProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
        onPageProgress: suspend (completedPages: Int, pageTotal: Int) -> Unit,
    ): DownloadResult {
        val outputFile = File(outputDir, DownloadStorage.buildChapterFileName(chapter))
        if (outputFile.exists()) {
            return DownloadResult.SKIPPED_EXISTING
        }

        ensureEnoughFreeSpace(outputDir)

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
                onProcessingProgress = onProcessingProgress,
                onPageProgress = onPageProgress,
            )

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zip ->
                // JPEG/PNG/WebP sono gia compressi: Deflate a livello zero evita lavoro CPU
                // inutile e mantiene la compatibilita delle entry ZIP senza un prepass CRC.
                zip.setLevel(Deflater.NO_COMPRESSION)
                for (page in pageFiles.sortedBy { it.index }) {
                    for (file in page.files) {
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().buffered().use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
            }

            if (!tempFile.renameTo(outputFile)) {
                throw IOException("Impossibile finalizzare ${outputFile.name}")
            }
            return DownloadResult.DOWNLOADED
        } finally {
            tempPageDir.deleteRecursively()
            if (tempFile.exists()) {
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

    /** Spazio libero sul volume di [dir]. Overridabile nei test per simulare il disco pieno. */
    protected open fun availableSpaceBytes(dir: File): Long = DownloadStorage.freeSpaceBytes(dir)

    private fun ensureEnoughFreeSpace(dir: File) {
        if (!DownloadStorage.hasEnoughFreeSpace(availableSpaceBytes(dir))) {
            throw InsufficientStorageException(
                "Spazio insufficiente sul dispositivo: libera spazio e riprova.",
            )
        }
    }

    private suspend fun downloadPageFiles(
        chapter: ChapterEntry,
        pageConcurrency: Int,
        outputDir: File,
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
                    val tempFile = File(outputDir, tempName)
                    val finalFile = File(outputDir, finalName)

                    tempFile.outputStream().buffered().use { output ->
                        // Scrittura a blocchi: evita quattro ByteArray di pagine intere e il
                        // relativo lavoro del GC mentre i trasferimenti procedono in parallelo.
                        networkClient.fetchToStream(pageUrl, sink = output, referer = chapter.url)
                    }

                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.delete()
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
                TallPageNormalizer.normalize(
                    source = finalFile,
                    outputDirectory = outputDir,
                    outputBaseName = finalFile.nameWithoutExtension,
                )
            }
            if (normalization.wasSplit && finalFile.exists() && !finalFile.delete()) {
                throw IOException("Impossibile rimuovere la pagina originale ${finalFile.name}")
            }
            onProcessingProgress(processedIndex + 1, downloadedPages.size)
            DownloadedPageTempFile(index = page.index, files = normalization.files)
        }
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

        val extension = DownloadStorage.imageExtension(coverUrl)
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
    val files: List<File>,
)

/**
 * Primo valore non-bianco (trimmato) tra quelli passati, o `null`. Usata dai parser
 * statici delle fonti (companion object), che non vedono i metodi d'istanza della base.
 */
internal fun firstNonBlankTrimmed(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            return trimmed
        }
    }
    return null
}

/**
 * Il riquadro ("card") di un risultato di ricerca: l'antenato più ampio di [anchor] che non
 * contiene link ad **altre** serie (secondo [seriesKeyOf]) né la cornice della pagina
 * (`nav`, `header`, `form`...). Serve a leggere i metadati della card, come i generi, senza
 * agganciarsi alle classi CSS del layout e senza sconfinare nella card accanto.
 */
internal fun searchResultCard(anchor: Element, seriesKeyOf: (Element) -> String?): Element {
    val own = seriesKeyOf(anchor)
    var card = anchor
    while (true) {
        val parent = card.parent() ?: break
        if (parent.tagName() in PAGE_ROOT_TAGS || parent.selectFirst(PAGE_CHROME_SELECTOR) != null) {
            break
        }
        val hasForeignSeries = parent.select("a[href]").any { link ->
            seriesKeyOf(link)?.let { it != own } == true
        }
        if (hasForeignSeries) {
            break
        }
        card = parent
    }
    return card
}

private val PAGE_ROOT_TAGS = setOf("html", "body")

/**
 * Un oggetto JSON di una fonte ha tra i [key] (array di `{ name, slug }` o di stringhe) un
 * genere per adulti? Tollera chiave assente, `null` o forme inattese: nel dubbio `false`.
 */
internal fun JsonObject.hasAdultGenre(key: String = "genres"): Boolean {
    val genres = this[key] as? JsonArray ?: return false
    return genres.any { genre ->
        val values = when (genre) {
            is JsonObject -> listOf("name", "slug").mapNotNull { (genre[it] as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(genre.contentOrNull)
            else -> emptyList()
        }
        values.any(::isAdultGenre)
    }
}
private const val PAGE_CHROME_SELECTOR = "nav, header, footer, form"

/**
 * Valore testuale associato a un'etichetta tipo "Stato"/"Status" in una pagina, cercando
 * sia il fratello successivo dell'etichetta sia il testo del genitore meno l'etichetta
 * (pattern "Etichetta: valore"). Tollerante alla struttura; `null` se nessuna combacia.
 * Usato dalle fonti per estrarre lo stato di pubblicazione in modo best-effort.
 */
internal fun statusTextNearLabel(document: Document, vararg labels: String): String? {
    for (label in labels) {
        // Etichetta = elemento il cui testo proprio, prima dei due punti, è esattamente la label
        // (cattura "Stato", "Stato:", "Stato: Valore").
        val element = document.allElements.firstOrNull { el ->
            el.ownText().trim().substringBefore(":").trim().equals(label, ignoreCase = true)
        } ?: continue

        // 1) Valore nello stesso elemento (anche dentro un figlio): "Status: <a>Completed</a>".
        if (element.ownText().contains(":")) {
            element.text().trim().substringAfter(":", "").trim()
                .takeIf(String::isNotBlank)?.let { return it }
        }
        // 2) Valore nei fratelli successivi, saltando i separatori (":", spazi). Es. VyManga:
        //    <span>Status</span><span>:</span><span>Ongoing</span>.
        var sibling = element.nextElementSibling()
        while (sibling != null) {
            sibling.text().trim().removePrefix(":").trim()
                .takeIf(String::isNotBlank)?.let { return it }
            sibling = sibling.nextElementSibling()
        }
        // 3) Valore nel testo del genitore, tolta l'etichetta.
        element.parent()?.text()?.trim()
            ?.substringAfter(element.text(), "")?.trim()?.removePrefix(":")?.trim()
            ?.takeIf(String::isNotBlank)?.let { return it }
    }
    return null
}

/**
 * Sinossi/descrizione di una pagina manga: prova prima i [selectors] specifici della fonte,
 * poi ricade sui meta OpenGraph/description (presenti su quasi tutti i siti). `null` se nulla.
 * Best-effort, usata dalle fonti HTML per riempire il pulsante info.
 */
internal fun parseDescription(document: Document, vararg selectors: String): String? {
    for (selector in selectors) {
        document.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return firstNonBlankTrimmed(
        document.selectFirst("""meta[property="og:description"]""")?.attr("content"),
        document.selectFirst("""meta[name="description"]""")?.attr("content"),
        document.selectFirst("""meta[name="twitter:description"]""")?.attr("content"),
    )
}
