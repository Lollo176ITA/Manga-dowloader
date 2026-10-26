package com.lorenzo.mangadownloader.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.data.library.DownloadStorage
import com.lorenzo.mangadownloader.data.library.DownloadedChapter
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheKey
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheRepository
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.model.ReaderPage
import com.lorenzo.mangadownloader.data.model.ReadingMode
import com.lorenzo.mangadownloader.data.model.StreamingReaderChapter
import com.lorenzo.mangadownloader.data.model.identityKey
import com.lorenzo.mangadownloader.data.model.restoreReaderPageIndex
import com.lorenzo.mangadownloader.data.model.toReaderChapter
import com.lorenzo.mangadownloader.data.model.toReaderPages
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.store.ReadingDiaryStore
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ReadingDayStats
import com.lorenzo.mangadownloader.domain.reading.diaryDayKey
import com.lorenzo.mangadownloader.domain.reading.displayLabel
import com.lorenzo.mangadownloader.domain.reading.mergedWith
import com.lorenzo.mangadownloader.domain.reading.pruneReadingDiary
import com.lorenzo.mangadownloader.domain.reading.seedReadingMemory
import com.lorenzo.mangadownloader.domain.reading.seriesKeyOf
import com.lorenzo.mangadownloader.domain.reading.withReaderProgressApplied
import com.lorenzo.mangadownloader.domain.reading.withReadingActivity
import com.lorenzo.mangadownloader.domain.reading.withReadingMemoryApplied
import com.lorenzo.mangadownloader.domain.series.LibraryMatching
import com.lorenzo.mangadownloader.ui.reader.PageBounds
import com.lorenzo.mangadownloader.ui.reader.ReaderPageExpansion
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode
import com.lorenzo.mangadownloader.ui.reader.expandSpreadPages
import com.lorenzo.mangadownloader.ui.reader.readPageBounds
import com.lorenzo.mangadownloader.ui.reader.unexpandedReaderPages
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reader e memoria di lettura estratti da [MangaViewModel]: apertura dei capitoli (scaricati
 * o in streaming, con cache e anticipo del successivo), posizione e stato "letto", modalità
 * di lettura e pagine doppie per serie, memoria/diario di lettura e le scritture sulla
 * libreria che il reader deve vedere (letto a mano, eliminazioni, refresh). Condivide il
 * [MutableStateFlow] del ViewModel.
 */
class ReaderController(
    private val state: MutableStateFlow<MangaUiState>,
    private val scope: CoroutineScope,
    private val context: Context,
    private val prefs: SharedPreferences,
    private val sourceRegistry: MangaSourceRegistry,
    private val libraryRepository: LibraryRepository,
    private val streamingCacheRepository: StreamingReaderCacheRepository,
    private val readingMemoryStore: ReadingMemoryStore,
    private val readingDiaryStore: ReadingDiaryStore,
    initialReadingMemory: Map<String, ReadChapterMemory>,
    initialReadingDiary: Map<String, ReadingDayStats>,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    /** SeriesKey di (sourceId, mangaUrl, titolo), tenendo conto del link della scheda aperta. */
    private val seriesKeyFor: (sourceId: String, mangaUrl: String, title: String) -> String,
    /** Capitolo appena finito di leggere (sync AniList). */
    private val onChapterRead: (ReaderChapter) -> Unit,
    /** Libreria appena riletta da disco (registro adulti). */
    private val onLibraryRefreshed: () -> Unit,
) {
    private var libraryJob: Job? = null
    private var readerJob: Job? = null
    private var streamingCacheJob: Job? = null
    private var streamingPrefetchJob: Job? = null
    private var autoDownloadJob: Job? = null
    private var smartCleanupJob: Job? = null
    private val readStateMutex = Mutex()

    /** Avanzamento (pagina, totale) non ancora riportato sulla libreria: vedi saveReaderPagePosition. */
    private val pendingLibraryProgress = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * Pagine del prossimo capitolo risolte in anticipo (vedi [maybePrefetchNextStreamingChapter]).
     * Vale per un capitolo solo: si consuma aprendolo, o decade appena il "prossimo" cambia.
     */
    private var prefetchedStreamingChapter: PrefetchedStreamingChapter? = null

    // Scritture di memoria e diario di lettura: fuori dal main thread ma in fila (FIFO), così
    // l'ultima versione accodata è anche l'ultima scritta. Scope non legato a quello del
    // ViewModel: una scrittura accodata mentre l'app si chiude deve arrivare comunque su disco.
    private val readingMemoryDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val readingMemoryWriter = CoroutineScope(SupervisorJob() + readingMemoryDispatcher)

    // Ultimi memoria/diario di lettura scritti su disco: persistono solo sui cambi reali
    // (il seed restituisce la stessa istanza quando non c'è nulla di nuovo).
    private var lastPersistedReadingMemory = initialReadingMemory
    private var lastPersistedReadingDiary = initialReadingDiary

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    /**
     * Ferma il reader e lo toglie dallo stato, applicando [andThen] nella stessa emissione
     * (es. aprire un'altra schermata). [cancelCleanup] ferma anche la pulizia automatica.
     */
    fun dismiss(cancelCleanup: Boolean = false, andThen: MangaUiState.() -> MangaUiState = { this }) {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        streamingPrefetchJob?.cancel()
        if (cancelCleanup) smartCleanupJob?.cancel()
        updateState { clearedReaderState().andThen() }
        pendingLibraryProgress.clear()
    }

    /**
     * Esegue [block] sulla stessa coda delle scritture di memoria e diario: il backup deve
     * vederle tutte, e un ripristino non deve essere sovrascritto da una scrittura vecchia.
     */
    suspend fun <T> onMemoryQueue(block: () -> T): T = withContext(readingMemoryDispatcher) { block() }

    /** Memoria e diario appena ripristinati da un backup, già su disco: non vanno riscritti. */
    fun onReadingMemoryRestored(memory: Map<String, ReadChapterMemory>, diary: Map<String, ReadingDayStats>) {
        lastPersistedReadingMemory = memory
        lastPersistedReadingDiary = diary
    }

    /** Rilegge la libreria da disco (o tiene quella in memoria se la scansione fallisce). */
    suspend fun reloadLibrarySnapshot() {
        val snapshot = try {
            scanLibrarySnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value.library
        }
        updateState { withLibrarySnapshot(snapshot) }
    }

    fun setSpreadPageMode(mode: SpreadPageMode) {
        // Default globale, applicato alle serie senza una preferenza esplicita.
        if (mode == state.value.settings.spreadPageMode) return
        updateSettings { it.copy(spreadPageMode = mode) }
        val current = state.value
        val seriesKey = current.readerSeriesKey
        if (seriesKey != null && prefs.contains(KEY_SPREAD_PAGE_MODE_SERIES_PREFIX + seriesKey)) {
            return
        }
        updateState { copy(readerSpreadPageMode = mode) }
        // Il capitolo aperto è già stato espanso (o no) secondo la modalità precedente:
        // ricaricarlo è l'unico modo perché il cambio si veda subito invece che al prossimo.
        current.readerChapter?.let(::openReaderChapter)
    }

    /**
     * Override ricordato per la serie attualmente in lettura: le pagine doppie di un volume
     * non si trattano come quelle di un webtoon, e la scelta giusta cambia da manga a manga.
     */
    fun setReaderSpreadPageMode(mode: SpreadPageMode) {
        val current = state.value
        val seriesKey = current.readerSeriesKey ?: return
        prefs.edit {
            putString(KEY_SPREAD_PAGE_MODE_SERIES_PREFIX + seriesKey, mode.name)
        }
        if (mode == current.readerSpreadPageMode) return
        updateState { copy(readerSpreadPageMode = mode) }
        // Riaprire il capitolo lo ri-espande con la nuova modalità; il segno di lettura è
        // salvato con il numero di pagine di allora, quindi restoredIndex lo ritraduce.
        current.readerChapter?.let(::openReaderChapter)
    }

    private fun resolveSpreadPageMode(seriesKey: String): SpreadPageMode {
        val stored = prefs.getString(KEY_SPREAD_PAGE_MODE_SERIES_PREFIX + seriesKey, null)
        return stored?.let { runCatching { SpreadPageMode.valueOf(it) }.getOrNull() }
            ?: state.value.settings.spreadPageMode
    }

    /**
     * L'elenco pagine da consegnare al reader, con le pagine doppie già divise se
     * l'impostazione lo chiede. Le proporzioni si leggono dall'intestazione dei file su un
     * dispatcher I/O: costa una manciata di microsecondi a pagina, e solo per le pagine locali
     * (di una pagina ancora solo remota non si conoscono le dimensioni senza scaricarla).
     */
    private suspend fun expandedReaderPages(
        pages: List<ReaderPage>,
        seriesKey: String,
    ): ReaderPageExpansion {
        if (resolveSpreadPageMode(seriesKey) != SpreadPageMode.SPLIT) {
            return unexpandedReaderPages(pages)
        }
        val rightFirst = resolveReadingMode(seriesKey).splitsSpreadRightFirst
        return withContext(Dispatchers.IO) {
            val bounds = mutableMapOf<String, PageBounds?>()
            expandSpreadPages(pages = pages, rightFirst = rightFirst) { local ->
                bounds.getOrPut(local.file.absolutePath) { readPageBounds(local.file) }
            }
        }
    }


    fun setReadingMode(mode: ReadingMode) {
        // Default globale, applicato alle serie senza una preferenza esplicita.
        updateSettings { it.copy(readingMode = mode) }
        val current = state.value
        val seriesKey = current.readerSeriesKey
        if (current.readerChapter != null &&
            seriesKey != null &&
            !prefs.contains(KEY_READING_MODE_SERIES_PREFIX + seriesKey)
        ) {
            updateState { copy(readerReadingMode = mode) }
            reopenIfSpreadsAreSplit(seriesKey)
        }
    }

    fun setReaderReadingMode(mode: ReadingMode) {
        // Override ricordato per la serie attualmente in lettura.
        val current = state.value
        val seriesKey = current.readerSeriesKey ?: return
        prefs.edit {
            putString(KEY_READING_MODE_SERIES_PREFIX + seriesKey, mode.name)
        }
        // Riparte dalla pagina corrente così il cambio modalità non perde il segno.
        val currentPage = (current.readerChapter?.readerPageIndex ?: current.readerInitialPageIndex)
            .coerceAtLeast(0)
        updateState { copy(readerReadingMode = mode, readerInitialPageIndex = currentPage) }
        reopenIfSpreadsAreSplit(seriesKey)
    }

    /**
     * Il senso di lettura decide anche in che ordine stanno le due metà di una pagina doppia
     * divisa: cambiandolo a capitolo aperto, l'elenco pagine già espanso va rifatto o le
     * facciate restano invertite fino alla riapertura. Solo con [SpreadPageMode.SPLIT]: le
     * altre due modalità non toccano l'elenco pagine e si aggiornano da sole ridisegnando.
     */
    private fun reopenIfSpreadsAreSplit(seriesKey: String) {
        if (resolveSpreadPageMode(seriesKey) != SpreadPageMode.SPLIT) return
        state.value.readerChapter?.let(::openReaderChapter)
    }

    private fun seriesKeyForDownloaded(relativePath: String): String =
        "dl:" + relativePath.substringBefore('/')

    private fun seriesKeyForStreaming(sourceId: String, mangaUrl: String): String =
        "st:" + MangaSourceCatalog.identityKey(sourceId, mangaUrl)

    private fun resolveReadingMode(seriesKey: String): ReadingMode {
        val stored = prefs.getString(KEY_READING_MODE_SERIES_PREFIX + seriesKey, null)
        return stored?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
            ?: state.value.settings.readingMode
    }

    fun openReader(chapter: DownloadedChapter) {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        streamingPrefetchJob?.cancel()
        val savedPagePosition = libraryRepository.readerPagePosition(chapter.relativePath)
        val savedPageIndex = savedPagePosition?.pageIndex
            ?: chapter.readerPageIndex
            ?: 0
        val savedPageCount = savedPagePosition?.pageCount ?: chapter.readerPageCount
        val initialReaderChapter = chapter.copy(readerPageIndex = savedPageIndex).toReaderChapter()
        val seriesKey = seriesKeyForDownloaded(chapter.relativePath)
        val readerSourceId = sequenceOf(state.value.selectedDownloadedSeries)
            .plus(state.value.library.asSequence())
            .filterNotNull()
            .firstOrNull { series ->
                series.chapters.any { it.relativePath == chapter.relativePath }
            }
            ?.sourceId

        updateState {
            copy(
                readerChapter = initialReaderChapter,
                readerPreviousChapter = null,
                readerNextChapter = null,
                readerPages = emptyList(),
                readerInitialPageIndex = savedPageIndex,
                readerSeriesKey = seriesKey,
                readerReadingMode = resolveReadingMode(seriesKey),
                readerSpreadPageMode = resolveSpreadPageMode(seriesKey),
                isLoadingReader = true,
                errorMessage = null,
            )
                .withReaderProgress(chapter.relativePath, pageIndex = savedPageIndex, pageCount = chapter.readerPageCount)
                .withReaderAdjacency(chapter.relativePath)
        }

        readerJob = scope.launch {
            try {
                val pages = libraryRepository.extractReaderPages(chapter)
                val expansion = expandedReaderPages(
                    pages = pages.map { ReaderPage.Local(file = it, sourceId = readerSourceId) },
                    seriesKey = seriesKey,
                )
                val restoredPageIndex = expansion.restoredIndex(savedPageIndex, savedPageCount)
                libraryRepository.saveReaderPagePosition(
                    chapter.relativePath,
                    restoredPageIndex,
                    expansion.pages.size,
                )
                updateState {
                    copy(
                        readerPages = expansion.pages,
                        readerInitialPageIndex = restoredPageIndex,
                        isLoadingReader = false,
                    )
                        .withReaderProgress(
                            chapter.relativePath,
                            pageIndex = restoredPageIndex,
                            pageCount = expansion.pages.size,
                        )
                        .withReaderAdjacency(chapter.relativePath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingReader = false,
                        errorMessage = userFacingErrorMessage(exc, "Impossibile aprire il reader"),
                    )
                }
            }
        }

        maybeTriggerAutoDownload(chapter)
        maybePerformSmartCleanup(chapter)
    }

    /**
     * Apre un capitolo dalla lista del dettaglio scegliendo la strada giusta: se la copia
     * scaricata esiste si legge quella (niente rete, e la posizione salvata è la stessa del
     * reader offline), altrimenti si va in streaming. Prima questa scelta era un
     * interruttore globale nelle impostazioni, che per giunta rimandava allo streaming
     * anche i capitoli già in libreria.
     */
    fun openChapterFromDetail(details: MangaDetails, chapter: ChapterEntry) {
        val downloaded = LibraryMatching.downloadedChapterFor(
            details = details,
            chapter = chapter,
            library = state.value.library,
            extraBindings = state.value.selectedSeriesLink?.sources.orEmpty(),
        )
        if (downloaded != null) {
            openReader(downloaded)
        } else {
            openStreamingReader(details, chapter)
        }
    }

    fun openStreamingReader(details: MangaDetails, chapter: ChapterEntry) {
        openStreamingReader(
            StreamingReaderChapter(
                sourceId = details.sourceId,
                mangaTitle = details.title,
                mangaUrl = details.mangaUrl,
                chapter = chapter,
                chapters = details.chapters,
            ),
        )
    }

    private fun openStreamingReader(streamingChapter: StreamingReaderChapter) {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        streamingPrefetchJob?.cancel()

        val streamingReadChapterIds = libraryRepository.streamingReadChapterIds(
            seriesKeyFor(
                streamingChapter.sourceId,
                streamingChapter.mangaUrl,
                state.value.selected?.title.orEmpty(),
            ),
            streamingChapter.sourceId,
            streamingChapter.mangaUrl,
        )
        val chapterId = DownloadStorage.stableChapterId(streamingChapter.chapter)
        val readerChapter = streamingChapter.toReaderChapter(isRead = chapterId in streamingReadChapterIds)
        val cacheKey = StreamingReaderCacheKey(
            sourceId = streamingChapter.sourceId,
            mangaUrl = streamingChapter.mangaUrl,
            chapterUrl = streamingChapter.chapter.url,
        )
        val savedPagePosition = libraryRepository.readerPagePosition(readerChapter.relativePath)
        val savedPageIndex = savedPagePosition?.pageIndex ?: 0
        val seriesKey = seriesKeyForStreaming(streamingChapter.sourceId, streamingChapter.mangaUrl)
        updateState {
            copy(
                readerChapter = readerChapter.copy(readerPageIndex = savedPageIndex),
                readerPreviousChapter = null,
                readerNextChapter = null,
                readerPages = emptyList(),
                readerInitialPageIndex = savedPageIndex,
                readerSeriesKey = seriesKey,
                readerReadingMode = resolveReadingMode(seriesKey),
                readerSpreadPageMode = resolveSpreadPageMode(seriesKey),
                isLoadingReader = true,
                errorMessage = null,
                selectedMangaReadChapterIds = streamingReadChapterIds,
            ).withStreamingReaderAdjacency(streamingChapter)
        }

        readerJob = scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    streamingCacheRepository.getCachedChapter(cacheKey)
                }
                if (cached != null) {
                    val expansion = expandedReaderPages(cached.toReaderPages(), seriesKey)
                    val restored = expansion.restoredIndex(
                        savedIndex = cached.restoreReaderPageIndex(savedPagePosition),
                        savedCount = expansion.originalCount,
                    )
                    libraryRepository.saveReaderPagePosition(
                        readerChapter.relativePath,
                        restored,
                        expansion.pages.size,
                    )
                    updateState {
                        withStreamingReaderPayload(
                            expectedRelativePath = readerChapter.relativePath,
                            streamingChapter = streamingChapter,
                            pages = expansion.pages,
                            restoredPageIndex = restored,
                        )
                    }
                    return@launch
                }

                // Se il prefetch del capitolo successivo ha fatto in tempo a finire mentre
                // si leggevano le ultime pagine, qui la rete non si tocca affatto.
                val pageUrls = consumePrefetchedStreamingPages(streamingChapter)
                    ?: withContext(Dispatchers.IO) {
                        sourceRegistry
                            .requireById(streamingChapter.sourceId)
                            .fetchChapterPageImageUrls(streamingChapter.chapter.url)
                    }
                if (pageUrls.isEmpty()) {
                    throw IllegalStateException("Nessuna pagina trovata per il capitolo")
                }

                val restored = savedPageIndex.coerceIn(0, pageUrls.lastIndex.coerceAtLeast(0))
                libraryRepository.saveReaderPagePosition(readerChapter.relativePath, restored, pageUrls.size)
                updateState {
                    withStreamingReaderPayload(
                        expectedRelativePath = readerChapter.relativePath,
                        streamingChapter = streamingChapter,
                        pages = pageUrls.map { url ->
                                ReaderPage.Remote(
                                    url = url,
                                    referer = streamingChapter.chapter.url,
                                    sourceId = streamingChapter.sourceId,
                                )
                        },
                        restoredPageIndex = restored,
                    )
                }

                streamingCacheJob = scope.launch {
                    try {
                        val completed = withContext(Dispatchers.IO) {
                            streamingCacheRepository.cacheCompleteChapter(
                                key = cacheKey,
                                title = streamingChapter.chapter.displayLabel(),
                                pageUrls = pageUrls,
                                referer = streamingChapter.chapter.url,
                            )
                        }
                        val currentOriginalPage = state.value.readerChapter
                            ?.takeIf { it.relativePath == readerChapter.relativePath }
                            ?.readerPageIndex
                        if (currentOriginalPage != null) {
                            val expansion = expandedReaderPages(completed.toReaderPages(), seriesKey)
                            val cachedPageIndex = completed
                                .readerPageIndexForOriginalPage(currentOriginalPage)
                                ?.coerceIn(0, completed.pages.lastIndex.coerceAtLeast(0))
                                ?: currentOriginalPage.coerceIn(
                                    0,
                                    completed.pages.lastIndex.coerceAtLeast(0),
                                )
                            val mappedPageIndex = expansion.restoredIndex(
                                savedIndex = cachedPageIndex,
                                savedCount = expansion.originalCount,
                            )
                            libraryRepository.saveReaderPagePosition(
                                readerChapter.relativePath,
                                mappedPageIndex,
                                expansion.pages.size,
                            )
                            updateState {
                                withStreamingReaderPayload(
                                    expectedRelativePath = readerChapter.relativePath,
                                    streamingChapter = streamingChapter,
                                    pages = expansion.pages,
                                    restoredPageIndex = mappedPageIndex,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Streaming already has remote pages. Cache refresh is best-effort.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingReader = false,
                        errorMessage = userFacingErrorMessage(
                            exc = exc,
                            fallback = "Impossibile aprire il reader online",
                            sourceName = MangaSourceCatalog.displayName(streamingChapter.sourceId),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Consuma il prefetch se riguarda proprio [streamingChapter]. Il risultato vale una volta
     * sola: dopo l'apertura il "prossimo capitolo" è un altro, e tenerlo in giro significherebbe
     * rischiare di servire pagine vecchie a un capitolo omonimo di un'altra serie.
     */
    private fun consumePrefetchedStreamingPages(streamingChapter: StreamingReaderChapter): List<String>? {
        val prefetched = prefetchedStreamingChapter ?: return null
        prefetchedStreamingChapter = null
        return prefetched.pagesFor(
            sourceId = streamingChapter.sourceId,
            chapterUrl = streamingChapter.chapter.url,
        )
    }

    /**
     * Vicino alla fine di un capitolo letto in streaming, risolve in anticipo le pagine del
     * successivo e scalda la cache di Coil con le prime.
     *
     * È il momento di attesa che un lettore online incontra a ogni capitolo: premuto "avanti",
     * l'app deve scaricare la pagina HTML del capitolo, estrarne gli URL e solo allora chiedere
     * la prima immagine — tre viaggi in fila, tutti a schermo vuoto. Anticipandoli mentre si
     * leggono le ultime pagine, il salto diventa quasi immediato.
     *
     * Costa una richiesta HTML e poche immagini, non l'intero capitolo: chi si ferma a fine
     * capitolo non si porta a casa megabyte che non ha chiesto.
     */
    private fun maybePrefetchNextStreamingChapter(pageIndex: Int, pageCount: Int) {
        // Solo in streaming: da un capitolo scaricato il successivo si apre già da disco.
        if (state.value.readerChapter?.streamingChapter == null) return
        val next = state.value.readerNextChapter?.streamingChapter ?: return
        if (!isNearChapterEnd(pageIndex, pageCount, STREAMING_PREFETCH_TRIGGER_PAGES)) return
        if (prefetchedStreamingChapter?.chapterUrl == next.chapter.url) return
        if (streamingPrefetchJob?.isActive == true) return

        streamingPrefetchJob = scope.launch {
            try {
                val cacheKey = StreamingReaderCacheKey(
                    sourceId = next.sourceId,
                    mangaUrl = next.mangaUrl,
                    chapterUrl = next.chapter.url,
                )
                val pageUrls = withContext(Dispatchers.IO) {
                    // Già in cache su disco: si aprirà da lì, non c'è niente da anticipare.
                    if (streamingCacheRepository.getCachedChapter(cacheKey) != null) return@withContext null
                    sourceRegistry
                        .requireById(next.sourceId)
                        .fetchChapterPageImageUrls(next.chapter.url)
                        .takeIf { it.isNotEmpty() }
                } ?: return@launch

                prefetchedStreamingChapter = PrefetchedStreamingChapter(
                    sourceId = next.sourceId,
                    chapterUrl = next.chapter.url,
                    pageUrls = pageUrls,
                )
                warmImageCache(
                    urls = pageUrls.take(STREAMING_PREFETCH_WARM_PAGES),
                    referer = next.chapter.url,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Anticipare è un di più: se fallisce, l'apertura del capitolo rifà la strada
                // normale e l'utente non vede alcuna differenza se non l'attesa di prima.
            }
        }
    }

    /**
     * Mette in coda a Coil le immagini indicate, così quando toccherà mostrarle saranno già
     * scaricate. La chiave di memoria può differire da quella che userà il reader (le pagine
     * doppie applicano trasformazioni), ma la cache su disco è per URL: il viaggio in rete si
     * risparmia comunque, che è la parte lenta.
     */
    private fun warmImageCache(urls: List<String>, referer: String) {
        val context = context
        val headers = NetworkHeaders.Builder().set("Referer", referer).build()
        urls.forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .httpHeaders(headers)
                    .build(),
            )
        }
    }

    fun saveReaderPagePosition(pageIndex: Int, pageCount: Int, allowCompletion: Boolean) {
        val chapter = state.value.readerChapter ?: return
        val safePageCount = pageCount.coerceAtLeast(1)
        val safePageIndex = pageIndex.coerceIn(0, safePageCount - 1)
        val currentPageIndex = chapter.readerPageIndex ?: -1
        val nextPageIndex = if (allowCompletion) {
            maxOf(currentPageIndex, safePageIndex)
        } else {
            currentPageIndex.coerceAtLeast(0).coerceIn(0, safePageCount - 1)
        }
        if (chapter.readerPageIndex == nextPageIndex && chapter.readerPageCount == safePageCount) {
            return
        }

        // Unico punto di persistenza: stessa chiave (relativePath) per scaricati e streaming.
        // Scrittura immediata (prefs.apply è già asincrono): la posizione deve essere durevole
        // anche se l'app viene chiusa subito dopo, senza attendere alcun boundary.
        // Il timestamp di lettura si scrive SOLO qui (avanzamento reale di pagina), non nei
        // restore di apertura, così "Continua a leggere" ordina per lettura, non per apertura.
        libraryRepository.saveReaderPagePosition(
            relativePath = chapter.relativePath,
            pageIndex = nextPageIndex,
            pageCount = safePageCount,
            lastReadAtMillis = System.currentTimeMillis(),
        )

        // A fine capitolo marca "letto" nello store giusto (metadata per gli scaricati,
        // prefs streaming per l'online).
        val newlyRead = allowCompletion && nextPageIndex >= safePageCount - 1 && !chapter.isRead
        var streamingReadId: String? = null
        if (newlyRead) {
            val downloaded = chapter.downloadedChapter
            val streaming = chapter.streamingChapter
            if (downloaded != null) {
                writeReadState { libraryRepository.markChapterRead(downloaded) }
            } else if (streaming != null) {
                streamingReadId = libraryRepository.markStreamingChapterRead(
                    seriesKey = seriesKeyFor(
                        streaming.sourceId,
                        streaming.mangaUrl,
                        state.value.selected?.title.orEmpty(),
                    ),
                    sourceId = streaming.sourceId,
                    mangaUrl = streaming.mangaUrl,
                    chapter = streaming.chapter,
                )
            }
        }

        val memoryUpdate = readerMemoryUpdate(
            chapter = chapter,
            pagesSeen = nextPageIndex + 1,
            pageCount = safePageCount,
            newlyRead = newlyRead,
        )
        // Libreria e serie aperta non si vedono col reader aperto: ricostruirle a ogni pagina
        // (e con loro le etichette dei preferiti in MainActivity) costava più dell'avanzamento
        // stesso. Si allineano alla chiusura del reader, o subito se il capitolo è finito.
        if (newlyRead) {
            pendingLibraryProgress.remove(chapter.relativePath)
        } else {
            pendingLibraryProgress[chapter.relativePath] = nextPageIndex to safePageCount
        }

        // Una sola emissione per pagina: avanzamento, eventuale "letto" streaming e memoria.
        updateState {
            withReaderProgress(
                relativePath = chapter.relativePath,
                pageIndex = nextPageIndex,
                pageCount = safePageCount,
                markRead = newlyRead,
                applyToLibrary = newlyRead,
            ).let { current ->
                if (streamingReadId != null) {
                    current.copy(selectedMangaReadChapterIds = current.selectedMangaReadChapterIds + streamingReadId)
                } else {
                    current
                }
            }.withReaderMemoryUpdate(memoryUpdate)
        }
        if (memoryUpdate?.persistNow == true) {
            persistReadingMemory()
        }

        if (newlyRead) {
            onChapterRead(chapter)
        }

        maybePrefetchNextStreamingChapter(pageIndex = nextPageIndex, pageCount = safePageCount)
    }

    private fun maybeTriggerAutoDownload(chapter: DownloadedChapter) {
        val settings = state.value.settings
        if (!settings.autoDownloadEnabled) return
        val series = state.value.selectedDownloadedSeries ?: return
        val mangaUrl = series.mangaUrl?.takeIf { it.isNotBlank() } ?: return

        val chapters = series.chapters
        val currentIndex = chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (currentIndex < 0) return
        val chaptersAfter = chapters.size - 1 - currentIndex
        if (chaptersAfter > settings.autoDownloadTriggerChapters) return

        if (autoDownloadJob?.isActive == true) return

        val downloadedNumbers = chapters.mapNotNull { it.numberValue }.toSet()
        val highestDownloaded = downloadedNumbers.maxOrNull() ?: return
        val batchSize = settings.autoDownloadBatchSize

        autoDownloadJob = scope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(series.sourceId, mangaUrl).fetchMangaDetails(mangaUrl)
                }
                val missing = details.chapters
                    .asSequence()
                    .filter { remote ->
                        remote.numberValue > highestDownloaded &&
                            remote.numberValue !in downloadedNumbers
                    }
                    .sortedBy { it.numberValue }
                    .take(batchSize)
                    .toList()
                if (missing.isEmpty()) return@launch
                withContext(Dispatchers.IO) {
                    DownloadWorker.enqueue(
                        context,
                        missing.first().url,
                        missing.last().url,
                        sourceId = series.sourceId,
                        series.title,
                        mangaUrl,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent: auto-download is best-effort
            }
        }
    }

    private fun maybePerformSmartCleanup(chapter: DownloadedChapter) {
        val settings = state.value.settings
        if (!settings.smartCleanupEnabled) return
        if (smartCleanupJob?.isActive == true) return

        val series = state.value.selectedDownloadedSeries ?: return
        val currentIndex = series.chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (currentIndex <= 0) return

        val keepPrevious = settings.smartCleanupKeepPreviousChapters.coerceAtLeast(0)
        val deleteUntilIndex = (currentIndex - keepPrevious).coerceAtLeast(0)
        if (deleteUntilIndex <= 0) return

        val chaptersToDelete = series.chapters
            .take(deleteUntilIndex)
            .filter { it.isRead }

        if (chaptersToDelete.isEmpty()) return

        smartCleanupJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteChapters(series, chaptersToDelete)
                }
                val snapshot = scanLibrarySnapshot()
                updateState { withLibrarySnapshot(snapshot) }
                persistReadingMemory()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent: automatic cleanup is best-effort
            }
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        streamingPrefetchJob?.cancel()
        // Consolida su disco le pagine avanzate durante la sessione di lettura (la scrittura
        // per-swipe è deliberatamente rimandata, vedi readerMemoryUpdate).
        persistReadingMemory()
        updateState { clearedReaderState() }
        pendingLibraryProgress.clear()
    }


    /**
     * Long-press su un capitolo scaricato: letto/non letto a mano, senza doverlo aprire
     * (es. già letto altrove in streaming, o da rileggere). Prima lo stato cambiava solo
     * arrivando in fondo al capitolo nel reader.
     */
    fun setChapterRead(chapter: DownloadedChapter, read: Boolean) {
        // Prima del refresh: il seed dello snapshot deve vedere la memoria già aggiornata.
        recordChaptersMarkedInMemory(listOf(chapter), read)
        writeReadState(refreshLibrary = true) {
            if (read) {
                libraryRepository.markChapterRead(chapter)
            } else {
                libraryRepository.markChapterUnread(chapter)
            }
        }
    }

    /** "Segna come letti fino a qui": tutti i capitoli della serie fino a [chapter] incluso. */
    fun markChaptersReadUpTo(chapter: DownloadedChapter) {
        val series = state.value.selectedDownloadedSeries ?: return
        val index = series.chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (index < 0) {
            return
        }
        val toMark = series.chapters.take(index + 1).filterNot { it.isRead }
        recordChaptersMarkedInMemory(toMark, read = true)
        writeReadState(refreshLibrary = true) { libraryRepository.markChaptersRead(toMark) }
    }

    /** Voce del menu serie in libreria: tutti i capitoli scaricati segnati come letti. */
    fun markAllChaptersRead(series: DownloadedSeries) {
        val toMark = series.chapters.filterNot { it.isRead }
        recordChaptersMarkedInMemory(toMark, read = true)
        writeReadState(refreshLibrary = true) { libraryRepository.markChaptersRead(toMark) }
    }

    /**
     * Scrive su disco un cambio di stato "letto" fuori dal main thread. Le scritture passano
     * in fila da [readStateMutex] (FIFO: due tap rapidi arrivano su disco nell'ordine giusto)
     * e girano in un job proprio, non in [libraryJob]: il refresh successivo cancella il
     * refresh precedente, e con esso sparirebbe anche una scrittura non ancora partita.
     */
    private fun writeReadState(refreshLibrary: Boolean = false, write: suspend () -> Unit) {
        scope.launch {
            try {
                readStateMutex.withLock { write() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: lo stato in memoria è già aggiornato, il refresh riallinea la UI.
            }
            if (refreshLibrary) refreshLibraryAfterReadChange()
        }
    }

    /** Riallinea libreria/serie aperta dopo un cambio manuale dello stato di lettura. */
    private fun refreshLibraryAfterReadChange() {
        libraryJob?.cancel()
        libraryJob = scope.launch {
            try {
                val snapshot = scanLibrarySnapshot()
                updateState { withLibrarySnapshot(snapshot) }
                persistReadingMemory()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: lo stato su disco è già scritto, la UI si riallinea al prossimo refresh.
            }
        }
    }

    fun deleteDownloadedChapter(chapter: DownloadedChapter) {
        val series = state.value.selectedDownloadedSeries ?: return
        mutateLibrary(fallbackErrorMessage = "Errore eliminazione capitolo") {
            libraryRepository.deleteChapters(series, listOf(chapter))
        }
    }

    /**
     * Elimina i soli capitoli già **letti** di [series] (tiene quello in corso e i non letti),
     * liberando spazio senza smontare la serie. Riusa lo stesso percorso di [deleteDownloadedChapter]
     * ma su una lista, e funziona per qualunque serie della libreria (non solo quella selezionata),
     * così è invocabile sia dalla Libreria sia dalla Gestione memoria. La baseline "letto" resta nei
     * metadati (vedi [LibraryRepository.deleteChapters]): i capitoli si possono riscaricare restando
     * segnati come letti. Se tutti i capitoli sono letti, la cartella viene rimossa del tutto.
     */
    fun deleteReadChapters(series: DownloadedSeries) {
        val readChapters = series.chapters.filter { it.isRead }
        if (readChapters.isEmpty()) return

        mutateLibrary(fallbackErrorMessage = "Errore eliminazione capitoli letti") {
            libraryRepository.deleteChapters(series, readChapters)
        }
    }

    fun deleteDownloadedSeries(series: DownloadedSeries? = state.value.selectedDownloadedSeries) {
        val targetSeries = series ?: return

        mutateLibrary(
            fallbackErrorMessage = "Errore eliminazione manga",
            clearReader = true,
        ) {
            libraryRepository.deleteSeries(targetSeries)
        }
    }

    private fun mutateLibrary(
        fallbackErrorMessage: String,
        clearReader: Boolean = false,
        mutation: suspend () -> Unit,
    ) {
        libraryJob?.cancel()
        if (clearReader) readerJob?.cancel()
        streamingCacheJob?.cancel()
        streamingPrefetchJob?.cancel()
        smartCleanupJob?.cancel()
        updateState { copy(isLoadingLibrary = true, errorMessage = null) }
        libraryJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { mutation() }
                val snapshot = scanLibrarySnapshot()
                updateState {
                    val baseState = if (clearReader) clearedReaderState() else this
                    baseState.withLibrarySnapshot(snapshot).copy(isLoadingLibrary = false)
                }
                if (clearReader) pendingLibraryProgress.clear()
                persistReadingMemory()
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingLibrary = false,
                        errorMessage = exc.message ?: fallbackErrorMessage,
                    )
                }
            }
        }
    }

    fun openPreviousReaderChapter() {
        state.value.readerPreviousChapter?.let(::openReaderChapter)
    }

    fun openNextReaderChapter() {
        state.value.readerNextChapter?.let(::openReaderChapter)
    }

    /** Riapre il capitolo corrente del reader: CTA "Riprova" quando il fetch delle pagine fallisce. */
    fun retryReaderLoad() {
        state.value.readerChapter?.let(::openReaderChapter)
    }

    private fun openReaderChapter(chapter: ReaderChapter) {
        chapter.downloadedChapter?.let {
            openReader(it)
            return
        }
        chapter.streamingChapter?.let(::openStreamingReader)
    }


    private fun MangaUiState.withLibrarySnapshot(snapshot: List<DownloadedSeries>): MangaUiState {
        // Memoria di lettura: assorbe i progressi dello snapshot (seed monotono, che è anche
        // la migrazione per chi aveva già letture) e reidrata il "letto" sulle serie
        // eliminate e riscaricate. La scrittura su disco la fa il chiamante
        // (persistReadingMemory) fuori dalla lambda di updateState.
        val seededMemory = seedReadingMemory(readingMemory, snapshot)
        val rehydrated = snapshot.map { it.withReadingMemoryApplied(seededMemory) }

        val selectedDirectory = selectedDownloadedSeries?.directory?.absolutePath
        val updatedSelected = rehydrated.firstOrNull { it.directory.absolutePath == selectedDirectory }
        val readerPath = readerChapter?.downloadedChapter?.relativePath
        val updatedReader = readerPath?.let { path ->
            updatedSelected?.chapters?.firstOrNull { it.relativePath == path }
                ?: rehydrated.asSequence()
                    .flatMap { it.chapters.asSequence() }
                    .firstOrNull { it.relativePath == path }
        }

        return copy(
            library = rehydrated,
            readingMemory = seededMemory,
            selectedDownloadedSeries = updatedSelected,
            readerChapter = updatedReader?.toReaderChapter() ?: readerChapter,
        ).withReaderAdjacency(updatedReader?.relativePath ?: readerPath)
    }

    /**
     * Scrive memoria e diario di lettura su disco solo se diversi dagli ultimi persistiti.
     * La serializzazione (una voce per ogni capitolo mai letto) gira su [readingMemoryDispatcher],
     * non sul main thread. Ritorna la scrittura accodata, o `null` se non c'era niente da fare.
     */
    fun persistReadingMemory(): Job? {
        val memory = state.value.readingMemory
        val diary = state.value.readingDiary
        val memoryChanged = memory !== lastPersistedReadingMemory && memory != lastPersistedReadingMemory
        val diaryChanged = diary !== lastPersistedReadingDiary && diary != lastPersistedReadingDiary
        if (!memoryChanged && !diaryChanged) return null
        if (memoryChanged) lastPersistedReadingMemory = memory
        if (diaryChanged) lastPersistedReadingDiary = diary
        return readingMemoryWriter.launch {
            if (memoryChanged) readingMemoryStore.persist(memory)
            if (diaryChanged) readingDiaryStore.persist(diary)
        }
    }

    /** La serie che possiede [seriesKey] (nome cartella), se ancora in libreria. */
    private fun seriesForKey(seriesKey: String): DownloadedSeries? {
        val current = state.value
        return current.selectedDownloadedSeries?.takeIf { it.directory.name == seriesKey }
            ?: current.library.firstOrNull { it.directory.name == seriesKey }
    }

    /**
     * Calcola il record della memoria di lettura per l'avanzamento del reader (merge monotono:
     * i numeri non regrediscono), da applicare con [withReaderMemoryUpdate] nella stessa
     * emissione di stato dell'avanzamento di pagina; `null` se non cambia niente. Vale per scaricati e streaming: le statistiche contano anche
     * le letture online e sopravvivono all'eliminazione dei download.
     */
    private fun readerMemoryUpdate(
        chapter: ReaderChapter,
        pagesSeen: Int,
        pageCount: Int,
        newlyRead: Boolean,
    ): ReaderMemoryUpdate? {
        val streaming = chapter.streamingChapter
        val seriesKey: String
        val seriesTitle: String
        val sourceId: String
        if (streaming != null) {
            seriesKey = seriesKeyForStreaming(streaming.sourceId, streaming.mangaUrl)
            seriesTitle = streaming.mangaTitle
            sourceId = streaming.sourceId
        } else {
            seriesKey = seriesKeyOf(chapter.relativePath)
            val series = seriesForKey(seriesKey)
            seriesTitle = series?.title ?: seriesKey
            sourceId = series?.sourceId.orEmpty()
        }
        val now = System.currentTimeMillis()
        val record = ReadChapterMemory(
            seriesKey = seriesKey,
            seriesTitle = seriesTitle,
            chapterLabel = chapter.title.ifBlank {
                chapter.downloadedChapter?.displayLabel().orEmpty()
            },
            pagesRead = pagesSeen,
            pageCount = pageCount,
            isRead = chapter.isRead || newlyRead,
            lastReadAtMillis = now,
            sourceId = sourceId,
            // Solo per lo streaming: la chiave del record è un hash, quindi senza queste
            // due coordinate il capitolo sparirebbe dalla cronologia come destinazione.
            mangaUrl = streaming?.mangaUrl.orEmpty(),
            chapterUrl = streaming?.chapter?.url.orEmpty(),
            // La copertina si prende dalla scheda aperta, e solo se è davvero questa serie.
            coverUrl = streaming
                ?.let { s -> state.value.selected?.takeIf { it.mangaUrl == s.mangaUrl }?.coverUrl }
                .orEmpty(),
        )
        val current = state.value.readingMemory[chapter.relativePath]
        val next = current?.mergedWith(record) ?: record
        if (next == current) return null

        // Diario giornaliero: registra i delta reali di questa sessione (pagine avanzate,
        // capitolo appena finito), mai i valori assoluti — le riletture non gonfiano i numeri.
        return ReaderMemoryUpdate(
            relativePath = chapter.relativePath,
            record = next,
            dayKey = diaryDayKey(now),
            chaptersDelta = if (next.isRead && current?.isRead != true && newlyRead) 1 else 0,
            pagesDelta = (next.pagesRead - (current?.pagesRead ?: 0)).coerceAtLeast(0),
            // Su disco solo ai passaggi significativi (nuovo record, capitolo completato): la
            // posizione per-pagina è già durevole nelle prefs del reader e riserializzare
            // l'intera mappa a ogni swipe crescerebbe col totale dei capitoli mai letti.
            // Il resto viene scritto alla chiusura del reader e a ogni snapshot libreria.
            persistNow = current == null || next.isRead != current.isRead,
        )
    }

    private fun MangaUiState.withReaderMemoryUpdate(update: ReaderMemoryUpdate?): MangaUiState {
        if (update == null) return this
        return copy(
            readingMemory = readingMemory + (update.relativePath to update.record),
            readingDiary = pruneReadingDiary(
                readingDiary.withReadingActivity(update.dayKey, update.chaptersDelta, update.pagesDelta),
                today = LocalDate.now(),
            ),
        )
    }

    /**
     * Allinea la memoria di lettura a un letto/non letto deciso a mano (long-press, "fino a
     * qui", "tutti letti"). Override esplicito, non merge: "non letto" deve poter regredire.
     * Nessun timestamp nuovo: il letto a mano non entra nella cronologia "Letti di recente";
     * il "non letto" azzera anche pagine e timestamp, come il reset del progresso su disco.
     */
    private fun recordChaptersMarkedInMemory(chapters: List<DownloadedChapter>, read: Boolean) {
        val first = chapters.firstOrNull() ?: return
        val current = state.value
        // Tutti i capitoli di una chiamata appartengono alla stessa serie: risolve una volta.
        val seriesKey = seriesKeyOf(first.relativePath)
        val series = seriesForKey(seriesKey)
        val updates = mutableMapOf<String, ReadChapterMemory>()
        for (chapter in chapters) {
            val existing = current.readingMemory[chapter.relativePath]
            val base = existing ?: ReadChapterMemory(
                seriesKey = seriesKeyOf(chapter.relativePath),
                seriesTitle = series?.title ?: seriesKeyOf(chapter.relativePath),
                chapterLabel = chapter.displayLabel(),
                pagesRead = 0,
                pageCount = chapter.readerPageCount,
                isRead = false,
                lastReadAtMillis = chapter.lastReadAtMillis ?: 0L,
                sourceId = series?.sourceId.orEmpty(),
            )
            val next = if (read) {
                base.copy(
                    isRead = true,
                    pagesRead = maxOf(base.pagesRead, base.pageCount ?: chapter.readerPageCount ?: 0),
                )
            } else {
                base.copy(isRead = false, pagesRead = 0, lastReadAtMillis = 0L)
            }
            if (next != existing) {
                updates[chapter.relativePath] = next
            }
        }
        if (updates.isEmpty()) return
        updateState { copy(readingMemory = readingMemory + updates) }
        persistReadingMemory()
    }

    /**
     * Unico aggiornatore dello stato di lettura di un capitolo (posizione pagina e/o
     * "letto"), applicato in modo coerente ovunque viva: libreria, serie selezionata e
     * capitolo aperto nel reader. Vale sia per i capitoli scaricati sia per lo streaming
     * (per lo streaming aggiorna solo il readerChapter, non essendo presente in libreria).
     * La persistenza su disco la fa il chiamante via LibraryRepository.
     */
    private fun MangaUiState.withReaderProgress(
        relativePath: String,
        pageIndex: Int? = null,
        pageCount: Int? = null,
        markRead: Boolean = false,
        applyToLibrary: Boolean = true,
    ): MangaUiState {
        val readChapterId = if (markRead) {
            (selectedDownloadedSeries?.chapters.orEmpty() + library.flatMap { it.chapters })
                .firstOrNull { it.relativePath == relativePath }
                ?.chapterId
        } else {
            null
        }

        val updatedReader = readerChapter?.let { reader ->
            if (reader.relativePath != relativePath) {
                reader
            } else {
                reader.copy(
                    readerPageIndex = pageIndex ?: reader.readerPageIndex,
                    readerPageCount = pageCount ?: reader.readerPageCount,
                    isRead = reader.isRead || markRead,
                    downloadedChapter = reader.downloadedChapter
                        ?.withReaderProgressApplied(relativePath, pageIndex, pageCount, markRead),
                )
            }
        }

        if (!applyToLibrary) return copy(readerChapter = updatedReader)
        return copy(
            library = library.map {
                it.withReaderProgressApplied(relativePath, pageIndex, pageCount, markRead, readChapterId)
            },
            selectedDownloadedSeries = selectedDownloadedSeries
                ?.withReaderProgressApplied(relativePath, pageIndex, pageCount, markRead, readChapterId),
            readerChapter = updatedReader,
        )
    }

    private fun MangaUiState.withReaderAdjacency(relativePath: String?): MangaUiState {
        // Il reader può essere aperto senza passare dalla schermata serie (es. "Riprendi"
        // dalla libreria): in quel caso selectedDownloadedSeries è null, quindi i capitoli
        // adiacenti vanno cercati nella serie di appartenenza dentro la libreria.
        val chapters = relativePath?.let { path ->
            selectedDownloadedSeries?.chapters
                ?.takeIf { list -> list.any { it.relativePath == path } }
                ?: library.firstOrNull { series ->
                    series.chapters.any { it.relativePath == path }
                }?.chapters
        }.orEmpty()
        val currentIndex = relativePath?.let { path ->
            chapters.indexOfFirst { it.relativePath == path }
        } ?: -1

        if (currentIndex < 0) {
            return copy(
                readerPreviousChapter = null,
                readerNextChapter = null,
            )
        }

        return copy(
            readerPreviousChapter = chapters.getOrNull(currentIndex - 1)?.toReaderChapter(),
            readerNextChapter = chapters.getOrNull(currentIndex + 1)?.toReaderChapter(),
        )
    }

    private fun MangaUiState.withStreamingReaderAdjacency(
        streamingChapter: StreamingReaderChapter,
    ): MangaUiState {
        val currentIndex = streamingChapter.chapters.indexOfFirst {
            it.url == streamingChapter.chapter.url
        }
        if (currentIndex < 0) {
            return copy(
                readerPreviousChapter = null,
                readerNextChapter = null,
            )
        }

        fun ChapterEntry.toStreamingReaderChapter(): ReaderChapter {
            val readChapterIds = selectedMangaReadChapterIds
                .ifEmpty {
                    libraryRepository.streamingReadChapterIds(
                        seriesKeyFor(
                            streamingChapter.sourceId,
                            streamingChapter.mangaUrl,
                            selected?.title.orEmpty(),
                        ),
                        streamingChapter.sourceId,
                        streamingChapter.mangaUrl,
                    )
                }
            return streamingChapter.copy(chapter = this)
                .toReaderChapter(isRead = DownloadStorage.stableChapterId(this) in readChapterIds)
        }

        return copy(
            readerPreviousChapter = streamingChapter.chapters
                .getOrNull(currentIndex - 1)
                ?.toStreamingReaderChapter(),
            readerNextChapter = streamingChapter.chapters
                .getOrNull(currentIndex + 1)
                ?.toStreamingReaderChapter(),
        )
    }

    /** Applica pagine cache/remoto solo se il reader richiesto è ancora quello visibile. */
    private fun MangaUiState.withStreamingReaderPayload(
        expectedRelativePath: String,
        streamingChapter: StreamingReaderChapter,
        pages: List<ReaderPage>,
        restoredPageIndex: Int? = null,
    ): MangaUiState {
        val currentChapter = readerChapter
            ?.takeIf { it.relativePath == expectedRelativePath }
            ?: return this
        return copy(
            readerChapter = currentChapter.copy(
                readerPageIndex = restoredPageIndex ?: currentChapter.readerPageIndex,
                readerPageCount = pages.size,
            ),
            readerPages = pages,
            readerInitialPageIndex = restoredPageIndex ?: readerInitialPageIndex,
            isLoadingReader = false,
        ).withStreamingReaderAdjacency(streamingChapter)
    }


    fun refreshLibrary(forceRefresh: Boolean = false) {
        libraryJob?.cancel()
        updateState { copy(isLoadingLibrary = true) }
        libraryJob = scope.launch {
            try {
                val snapshot = scanLibrarySnapshot(forceRefresh)
                updateState { withLibrarySnapshot(snapshot).copy(isLoadingLibrary = false) }
                persistReadingMemory()
                onLibraryRefreshed()
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingLibrary = false,
                        errorMessage = exc.message ?: "Errore caricamento libreria",
                    )
                }
            }
        }
    }


    private suspend fun scanLibrarySnapshot(forceRefresh: Boolean = false): List<DownloadedSeries> {
        return withContext(Dispatchers.IO) { libraryRepository.scanLibrary(forceRefresh) }
    }

    /**
     * Chiude il reader. Riporta anche su libreria e serie aperta l'avanzamento tenuto da parte
     * durante la lettura (vedi saveReaderPagePosition): il chiamante svuota poi
     * [pendingLibraryProgress], fuori da `updateState` perché la lambda può essere rieseguita.
     */
    private fun MangaUiState.clearedReaderState(): MangaUiState {
        val withProgress = pendingLibraryProgress.entries.fold(this) { current, (relativePath, progress) ->
            current.withReaderProgress(relativePath, pageIndex = progress.first, pageCount = progress.second)
        }
        return withProgress.copy(
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            readerInitialPageIndex = 0,
            isLoadingReader = false,
        )
    }

    private companion object {
        const val KEY_READING_MODE_SERIES_PREFIX = "reading_mode_series::"
        const val KEY_SPREAD_PAGE_MODE_SERIES_PREFIX = "spread_page_mode_series::"

        /** Pagine dalla fine entro cui si inizia ad anticipare il capitolo successivo. */
        const val STREAMING_PREFETCH_TRIGGER_PAGES = 3

        /** Quante pagine del capitolo successivo si scaldano in cache immagini. */
        const val STREAMING_PREFETCH_WARM_PAGES = 3
    }
}

/** Aggiornamento di memoria e diario di lettura per una pagina avanzata nel reader. */
private class ReaderMemoryUpdate(
    val relativePath: String,
    val record: ReadChapterMemory,
    val dayKey: String,
    val chaptersDelta: Int,
    val pagesDelta: Int,
    val persistNow: Boolean,
)

/** Pagine di un capitolo streaming risolte prima che l'utente lo apra. */
internal data class PrefetchedStreamingChapter(
    val sourceId: String,
    val chapterUrl: String,
    val pageUrls: List<String>,
)

/**
 * Le pagine anticipate valgono **solo** per il capitolo esatto per cui erano state chieste:
 * fonte e URL devono coincidere entrambi. Senza questo controllo, un capitolo omonimo di
 * un'altra serie (o della stessa serie su un'altra fonte) si aprirebbe con le pagine sbagliate.
 */
internal fun PrefetchedStreamingChapter?.pagesFor(sourceId: String, chapterUrl: String): List<String>? {
    val prefetched = this ?: return null
    return prefetched.pageUrls.takeIf {
        prefetched.sourceId == sourceId && prefetched.chapterUrl == chapterUrl
    }
}

/**
 * Se il lettore è abbastanza vicino alla fine da valere la pena di anticipare il capitolo
 * successivo. [triggerPages] è la distanza dall'ultima pagina entro cui scatta.
 */
internal fun isNearChapterEnd(pageIndex: Int, pageCount: Int, triggerPages: Int): Boolean =
    pageCount - 1 - pageIndex <= triggerPages
