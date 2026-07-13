package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab {
    HOME,
    SEARCH,
    FAVORITES,
    LIBRARY,
}

/**
 * Stato del blocco "Scopri" nella Home (AniList). AniList fornisce solo metadati: le tre sezioni
 * a caroselli ([trending]/[topRated]/[newest]) mostrano [AniListManga], che NON sono scaricabili
 * direttamente — il tap fa il "ponte" verso le fonti reali (vedi
 * [MangaViewModel.onPickAniListManga]). [info] è il manga di cui mostrare la trama nel dialog.
 */
data class DiscoveryUiState(
    val trending: List<AniListManga> = emptyList(),
    val topRated: List<AniListManga> = emptyList(),
    val newest: List<AniListManga> = emptyList(),
    val isLoadingSections: Boolean = false,
    val sectionsError: String? = null,
    val loaded: Boolean = false,
    val info: AniListManga? = null,
)

/**
 * Stato del tracking AniList. [viewer] presente ⇔ account collegato. [trackings] è la mappa
 * `identityKey → legame` persistita da [AniListStore]. [match] pilota il dialog di matching
 * (collega una serie a un media AniList), [trackerKey] quello di modifica stato/progresso/voto.
 */
data class AniListUiState(
    val viewer: AniListViewer? = null,
    val isConnecting: Boolean = false,
    val trackings: Map<String, AniListTracking> = emptyMap(),
    val match: AniListMatchUiState? = null,
    val trackerKey: String? = null,
    val isSavingEntry: Boolean = false,
)

/** Dialog di matching serie→AniList: ricerca per titolo con conferma esplicita dell'utente. */
data class AniListMatchUiState(
    val identityKey: String,
    val query: String,
    val isLoading: Boolean = false,
    val candidates: List<AniListManga> = emptyList(),
    val errorMessage: String? = null,
    val isLinking: Boolean = false,
)

data class FavoriteManga(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val addedAt: Long = 0L,
)

/** Chiavi d'identità dei preferiti (ordine d'inserimento preservato), per `favoriteMangaKeys`. */
private fun List<FavoriteManga>.identityKeys(): Set<String> =
    mapTo(linkedSetOf()) { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) }

/** Mappa `identityKey -> stato pubblicazione` derivata dalla baseline notifiche (per sort/filtro). */
private fun Map<String, FavoriteSeenState>.toStatusMap(): Map<String, MangaPublicationStatus> =
    mapValues { (_, seen) ->
        runCatching { MangaPublicationStatus.valueOf(seen.status) }
            .getOrDefault(MangaPublicationStatus.UNKNOWN)
    }

/** Interspazio (dp) tra le pagine del reader: 8 è il valore storico dell'app. */
const val DEFAULT_READER_PAGE_SPACING_DP = 8
const val MAX_READER_PAGE_SPACING_DP = 24

data class AppSettings(
    // Ambito della ricerca: per lingua (ITA per un'app in italiano) o su tutte le fonti.
    // Lo scope SOURCE (fonte singola) non è più raggiungibile dalla UI: un valore
    // persistito da versioni precedenti viene riportato alla lingua della fonte in lettura.
    val searchScope: SearchScope = SearchScope.ITA,
    val searchSourceId: String = MangaSourceIds.DEFAULT,
    val autoDownloadEnabled: Boolean = false,
    val autoDownloadTriggerChapters: Int = 3,
    val autoDownloadBatchSize: Int = 3,
    val smartCleanupEnabled: Boolean = false,
    val smartCleanupKeepPreviousChapters: Int = 3,
    val streamingReaderEnabled: Boolean = false,
    val parentalControlEnabled: Boolean = false,
    val parentalPinConfigured: Boolean = false,
    val parentalBiometricEnabled: Boolean = false,
    val parentalPinSalt: String? = null,
    val parentalPinHash: String? = null,
    val labsEnabled: Boolean = false,
    val downloadDevUpdates: Boolean = false,
    val highResImages: Boolean = false,
    val privacyBrightnessEnabled: Boolean = false,
    val readerBrightness: Float = 1f,
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val readerPageSpacingDp: Int = DEFAULT_READER_PAGE_SPACING_DP,
    val doubleTapZoomEnabled: Boolean = false,
    val keepScreenOnEnabled: Boolean = true,
    val allowLandscapeRotation: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val useDynamicColor: Boolean = false,
    val tutorialCompleted: Boolean = false,
    val favoriteNewChapterNotificationsEnabled: Boolean = false,
    val favoriteSort: FavoriteSort = FavoriteSort.DATE_ADDED,
    val librarySort: LibrarySort = LibrarySort.TITLE_ASC,
    // Push automatico del progresso su AniList a fine capitolo (ha effetto solo con
    // l'account collegato). Default attivo: collegare l'account esprime già l'intento.
    val aniListSyncEnabled: Boolean = true,
    // Personalizzazione della Home: ordine dei blocchi e insieme di quelli nascosti.
    val homeBlockOrder: List<HomeBlock> = DEFAULT_HOME_BLOCK_ORDER,
    val hiddenHomeBlocks: Set<HomeBlock> = emptySet(),
)

enum class ParentalAction {
    OPEN_SEARCH,
    CHANGE_PIN,
    DISABLE_PARENTAL_CONTROL,
    ENABLE_BIOMETRIC,
    DISABLE_BIOMETRIC,
}

enum class ParentalPinSetupMode {
    CREATE,
    CHANGE,
}

data class ParentalPinSetupState(
    val mode: ParentalPinSetupMode,
    val pin: String = "",
    val confirmPin: String = "",
    val errorMessage: String? = null,
    val completionAction: ParentalAction? = null,
)

data class ParentalPinEntryState(
    val action: ParentalAction,
    val pin: String = "",
    val errorMessage: String? = null,
)

data class ParentalBiometricPromptRequest(
    val requestId: Long,
    val action: ParentalAction,
    val title: String,
    val subtitle: String,
)

enum class TutorialPhase {
    Idle,
    Welcome,
    Preloading,
    AwaitingSearchBar,
    AwaitingResultTap,
    AwaitingFavorite,
    AwaitingDownload,
    AwaitingFavoritesTab,
    AwaitingLibraryTab,
    AwaitingSeriesTap,
    AwaitingChapterTap,
    InReader,
    AwaitingOverflow,
    Closing,
    FallbackShowcase,
    FallbackClosing,
}

data class TutorialSample(
    val sourceId: String,
    val mangaUrl: String,
    val title: String,
    val coverUrl: String?,
    val chapterUrl: String,
)

data class TutorialUiState(
    val phase: TutorialPhase = TutorialPhase.Idle,
    val sample: TutorialSample? = null,
)

data class MangaUiState(
    val currentTab: AppTab = AppTab.HOME,
    val pendingSearchAccessReturnTab: AppTab? = null,
    val query: String = "",
    val favoritesQuery: String = "",
    val libraryQuery: String = "",
    val recentSearches: List<String> = emptyList(),
    val results: List<MangaSearchResult> = emptyList(),
    val discovery: DiscoveryUiState = DiscoveryUiState(),
    val favorites: List<FavoriteManga> = emptyList(),
    val favoriteMangaKeys: Set<String> = emptySet(),
    val favoriteFilterReadingState: FavoriteReadingState? = null,
    val favoriteStatusByKey: Map<String, MangaPublicationStatus> = emptyMap(),
    val favoriteSeenStates: Map<String, FavoriteSeenState> = emptyMap(),
    val isSearching: Boolean = false,
    // Fallimento dell'ultima ricerca (rete assente, fonte down): mostrato dalla tab Cerca
    // come stato dedicato con "Riprova", invece di un falso "Nessun risultato".
    val searchError: String? = null,
    // Quando il fetch dei dettagli fallisce, il manga da ritentare: la snackbar d'errore
    // offre "Riprova" che rilancia selectManga senza dover ripetere la ricerca.
    val errorRetrySearchResult: MangaSearchResult? = null,
    val selected: MangaDetails? = null,
    val selectedMangaReadChapterIds: Set<String> = emptySet(),
    val isLoadingDetails: Boolean = false,
    val mangaInfoDialog: MangaInfoDialogState? = null,
    val library: List<DownloadedSeries> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val selectedDownloadedSeries: DownloadedSeries? = null,
    val readerChapter: ReaderChapter? = null,
    val readerPreviousChapter: ReaderChapter? = null,
    val readerNextChapter: ReaderChapter? = null,
    val readerPages: List<ReaderPage> = emptyList(),
    val readerInitialPageIndex: Int = 0,
    val readerReadingMode: ReadingMode = ReadingMode.VERTICAL,
    val readerSeriesKey: String? = null,
    val isLoadingReader: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isInstallingUpdate: Boolean = false,
    val showSettings: Boolean = false,
    val showStorageManager: Boolean = false,
    val showBackup: Boolean = false,
    val showChangelog: Boolean = false,
    val showFeedback: Boolean = false,
    val showUpdates: Boolean = false,
    val showHistory: Boolean = false,
    val aniList: AniListUiState = AniListUiState(),
    val favoriteUpdates: List<FavoriteUpdateEvent> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isBiometricAvailable: Boolean = false,
    val isParentalAuthInProgress: Boolean = false,
    val parentalPinSetupState: ParentalPinSetupState? = null,
    val parentalPinEntryState: ParentalPinEntryState? = null,
    val biometricPromptRequest: ParentalBiometricPromptRequest? = null,
    val tutorialState: TutorialUiState = TutorialUiState(),
    val errorMessage: String? = null,
)

private fun AppSettings.shouldStartTutorial(favorites: List<FavoriteManga>): Boolean {
    return !tutorialCompleted && favorites.isEmpty()
}

private fun AppSettings.shouldAutoCompleteTutorial(favorites: List<FavoriteManga>): Boolean {
    return !tutorialCompleted && favorites.isNotEmpty()
}

data class MangaInfoDialogState(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val description: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class MangaViewModel internal constructor(
    application: Application,
    private val appUpdateRepository: AppUpdateRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, AppUpdateRepository(application))

    private val sourceRegistry = sharedSourceRegistry(application)
    private val aniListClient = AniListClient(SharedHttpClient.get(application))
    private val libraryRepository = sharedLibraryRepository(application)
    private val streamingCacheRepository = StreamingReaderCacheRepository(
        context = application,
        networkClient = MangaNetworkClient(SharedHttpClient.get(application)),
        // Le pagine dello streaming reader vengono già scaricate da Coil per mostrarle: se
        // sono nella sua disk-cache le copiamo invece di riscaricarle, così una pagina letta
        // non viaggia sulla rete due volte solo per finire in cache offline.
        reusablePageCopier = { url, target -> copyCoilCachedPage(application, url, target) },
    )
    private val prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsStore = SettingsStore(prefs)
    private val favoritesStore = FavoritesStore(prefs)
    private val recentSearchesStore = RecentSearchesStore(prefs)
    private val favoriteDescriptionsStore = FavoriteDescriptionsStore(prefs)
    private val favoriteUpdatesFeedStore = FavoriteUpdatesFeedStore(prefs)
    private val favoriteUpdatesStore = FavoriteUpdatesStore(prefs)
    private val aniListStore = AniListStore(prefs)
    private val backupManager = BackupManager(
        favoritesStore = favoritesStore,
        favoriteUpdatesStore = favoriteUpdatesStore,
        favoriteDescriptionsStore = favoriteDescriptionsStore,
        recentSearchesStore = recentSearchesStore,
        settingsStore = settingsStore,
        appVersionName = BuildConfig.VERSION_NAME,
    )

    /**
     * Cache in memoria delle trame (descrizioni) per `identityKey`: il pulsante info diventa
     * istantaneo dopo il primo fetch o dopo aver aperto il manga, senza ri-scaricare la pagina.
     * Per i **preferiti** è anche persistita su disco ([favoriteDescriptionsStore]) e ricaricata
     * all'avvio, così la loro info è pronta anche dopo il riavvio. È solo testo.
     */
    private val mangaDescriptionCache = mutableMapOf<String, String>()

    private val initialFavorites = favoritesStore.read()
    private val initialSettings = settingsStore.read()
    private val initialFavoriteSeen = favoriteUpdatesStore.read()

    private val _state = MutableStateFlow(
        MangaUiState(
            // Home è il centro dell'app e la tab d'avvio; il controllo parentale continua a
            // forzare l'atterraggio su Libreria (Cerca resta dietro il PIN).
            currentTab = when {
                initialSettings.parentalControlEnabled -> AppTab.LIBRARY
                else -> AppTab.HOME
            },
            recentSearches = recentSearchesStore.read(),
            favorites = initialFavorites,
            favoriteMangaKeys = initialFavorites.identityKeys(),
            favoriteSeenStates = initialFavoriteSeen,
            favoriteStatusByKey = initialFavoriteSeen.toStatusMap(),
            favoriteUpdates = favoriteUpdatesFeedStore.read(),
            aniList = AniListUiState(
                viewer = aniListStore.readViewer().takeIf { aniListStore.readToken() != null },
                trackings = aniListStore.readTrackings(),
            ),
            settings = if (initialSettings.shouldAutoCompleteTutorial(initialFavorites)) {
                initialSettings.copy(tutorialCompleted = true)
            } else {
                initialSettings
            },
            isBiometricAvailable = isBiometricAvailable(application),
            tutorialState = if (initialSettings.shouldStartTutorial(initialFavorites)) {
                TutorialUiState(phase = TutorialPhase.Welcome)
            } else {
                TutorialUiState()
            },
        ),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var discoveryJob: Job? = null
    private var detailJob: Job? = null
    private var infoJob: Job? = null
    private var libraryJob: Job? = null
    private var readerJob: Job? = null
    private var streamingCacheJob: Job? = null
    private var updateJob: Job? = null
    private var autoDownloadJob: Job? = null
    private var smartCleanupJob: Job? = null
    private var aniListMatchJob: Job? = null
    private var nextBiometricRequestId = 1L

    init {
        if (initialSettings.shouldAutoCompleteTutorial(initialFavorites)) {
            settingsStore.persist(initialSettings.copy(tutorialCompleted = true))
        }
        // Trame dei preferiti persistite: pronte subito (info istantanea) anche dopo il riavvio.
        mangaDescriptionCache.putAll(favoriteDescriptionsStore.read())
        observeQueryChanges()
        refreshLibrary()
        if (initialSettings.favoriteNewChapterNotificationsEnabled) {
            FavoriteUpdatesScheduler.onAppStart(application)
        }
        // Progressi AniList rimasti in sospeso (offline/errore): riprova all'avvio.
        flushPendingAniListSync()
        // Le cartelle dei preferiti sono state rimosse (lo stato di lettura è automatico):
        // ripulisce le chiavi legacy rimaste su disco dalle versioni precedenti.
        prefs.edit {
            remove("favorite_categories_json")
            remove("favorite_category_assignments_json")
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            _state
                .map { it.query.trim() to it.settings.searchScope }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { (query, _) ->
                    if (query.isNotEmpty()) {
                        runAggregatedSearch(query)
                    } else {
                        searchJob?.cancel()
                        updateState {
                            copy(
                                results = emptyList(),
                                isSearching = false,
                                searchError = null,
                                errorMessage = null,
                            )
                        }
                    }
                }
        }
    }

    fun onQueryChange(text: String) {
        updateState { copy(query = text) }
    }

    fun submitSearch() {
        _state.value.query.trim().takeIf(String::isNotEmpty)?.let(::runAggregatedSearch)
    }

    fun selectTab(tab: AppTab) {
        if (tab == _state.value.currentTab) {
            // Same tab: don't trigger another disk scan; the cached snapshot is current.
            return
        }
        if (tab == AppTab.SEARCH && _state.value.settings.parentalControlEnabled) {
            requestSearchAccess()
            return
        }
        updateState { copy(currentTab = tab) }
        if (tab == AppTab.LIBRARY) {
            refreshLibrary()
        }
    }

    fun onFavoritesQueryChange(text: String) {
        updateState { copy(favoritesQuery = text) }
    }

    // --- Organizzazione preferiti: ordinamento, filtro categoria, gestione categorie ---

    fun setFavoriteSort(sort: FavoriteSort) {
        updateSettings { it.copy(favoriteSort = sort) }
    }

    fun setFavoriteFilterReadingState(state: FavoriteReadingState?) {
        updateState { copy(favoriteFilterReadingState = state) }
    }

    /**
     * Shortcut "Leggi" dai preferiti: scarica automaticamente i primi capitoli (fino a 3) così
     * puoi iniziare a leggere offline dalla Libreria. Il **1° capitolo** ha priorità assoluta
     * (accodato da solo prima dei successivi).
     */
    fun readNowFromFavorite(favorite: FavoriteManga) {
        viewModelScope.launch {
            updateState { copy(errorMessage = "Preparo i primi capitoli di ${favorite.title}…") }
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(favorite.sourceId, favorite.mangaUrl)
                        .fetchMangaDetails(favorite.mangaUrl)
                }
                cacheMangaDescription(details.sourceId, details.mangaUrl, details.description)
                val firstChapters = firstChaptersForReading(details.chapters, READ_NOW_CHAPTER_COUNT)
                if (firstChapters.isEmpty()) {
                    updateState { copy(errorMessage = "Nessun capitolo disponibile per ${details.title}") }
                    return@launch
                }
                val app = getApplication<Application>()
                // 1° capitolo da solo = priorità assoluta nella coda di download.
                DownloadWorker.enqueue(
                    context = app,
                    firstUrl = firstChapters.first().url,
                    lastUrl = firstChapters.first().url,
                    sourceId = details.sourceId,
                    seriesTitle = details.title,
                    mangaUrl = details.mangaUrl,
                    coverUrl = details.coverUrl,
                )
                // Eventuali capitoli successivi (2°-3°) accodati dopo il primo.
                if (firstChapters.size > 1) {
                    DownloadWorker.enqueue(
                        context = app,
                        firstUrl = firstChapters[1].url,
                        lastUrl = firstChapters.last().url,
                        sourceId = details.sourceId,
                        seriesTitle = details.title,
                        mangaUrl = details.mangaUrl,
                        coverUrl = details.coverUrl,
                    )
                }
                updateState {
                    copy(
                        errorMessage = "Scarico i primi ${firstChapters.size} capitoli di ${details.title}: " +
                            "li trovi nella Libreria appena pronti.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(errorMessage = exc.message ?: "Errore nel preparare la lettura")
                }
            }
        }
    }

    fun onLibraryQueryChange(text: String) {
        updateState { copy(libraryQuery = text) }
    }

    fun openSettings() {
        updateState { copy(showSettings = true) }
    }

    /**
     * Chip "Tutte" (o CTA "Cerca su tutte le fonti" a zero risultati): attiva la ricerca
     * aggregata su ogni fonte. Il rilancio della query corrente lo fa [observeQueryChanges],
     * che osserva anche `settings.searchScope`.
     */
    fun selectAllSourcesSearch() {
        setSearchScope(SearchScope.ALL)
    }

    /** Chip lingua ("Italiano"/"English"): ricerca aggregata sulle fonti di quella lingua. */
    fun selectLanguageSearch(language: MangaSourceLanguage) {
        setSearchScope(SearchScope.forLanguage(language))
    }

    private fun setSearchScope(scope: SearchScope) {
        require(scope != SearchScope.SOURCE) { "SOURCE è riservato alla migrazione dei dati legacy" }
        if (_state.value.settings.searchScope == scope) return
        val query = _state.value.query.trim()
        updateSettings { it.copy(searchScope = scope) }
        updateState {
            copy(
                results = emptyList(),
                isSearching = query.isNotEmpty(),
                searchError = null,
                errorMessage = null,
            )
        }
    }

    fun closeSettings() {
        updateState {
            copy(
                showSettings = false,
                showStorageManager = false,
                showBackup = false,
                showChangelog = false,
                showFeedback = false,
            )
        }
    }

    fun openBackup() {
        updateState { copy(showBackup = true) }
    }

    fun closeBackup() {
        updateState { copy(showBackup = false) }
    }

    fun openChangelog() {
        updateState { copy(showChangelog = true) }
    }

    fun closeChangelog() {
        updateState { copy(showChangelog = false) }
    }

    fun openFeedback() {
        updateState { copy(showFeedback = true) }
    }

    fun closeFeedback() {
        updateState { copy(showFeedback = false) }
    }

    /** Esporta il backup nel documento scelto (SAF). L'IO gira fuori dal main thread. */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        backupManager.export(out, System.currentTimeMillis())
                    } ?: error("Stream di output nullo")
                }.isSuccess
            }
            updateState {
                copy(errorMessage = if (ok) "Backup esportato" else "Esportazione del backup non riuscita")
            }
        }
    }

    /** Importa un backup dal documento scelto (SAF) e riflette i dati ripristinati nello stato. */
    fun importBackup(uri: Uri, mode: BackupRestoreMode) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        backupManager.restore(input, mode)
                    }
                }.getOrNull()
            }
            if (result == null) {
                updateState { copy(errorMessage = "File di backup non valido") }
                return@launch
            }
            mangaDescriptionCache.putAll(result.favoriteDescriptions)
            // Riallinea le mappe in memoria alla baseline appena ripristinata, così i sort
            // "Stato"/"Ultimo capitolo" sono corretti subito (non solo dopo un riavvio).
            val restoredSeen = favoriteUpdatesStore.read()
            // REPLACE = ripartenza pulita: svuota anche il feed degli aggiornamenti.
            val clearFeed = result.mode == BackupRestoreMode.REPLACE
            if (clearFeed) {
                favoriteUpdatesFeedStore.write(emptyList())
            }
            updateState {
                copy(
                    favorites = result.favorites,
                    favoriteMangaKeys = result.favorites.identityKeys(),
                    favoriteSeenStates = restoredSeen,
                    favoriteStatusByKey = restoredSeen.toStatusMap(),
                    favoriteUpdates = if (clearFeed) emptyList() else favoriteUpdates,
                    recentSearches = result.recentSearches,
                    settings = result.settings,
                    errorMessage = restoreMessage(result),
                )
            }
            // Le notifiche potrebbero essere cambiate col ripristino: risincronizza lo scheduler.
            FavoriteUpdatesScheduler.setEnabled(
                getApplication<Application>(),
                result.settings.favoriteNewChapterNotificationsEnabled,
            )
            refreshLibrary()
        }
    }

    private fun restoreMessage(result: BackupRestoreResult): String = when (result.mode) {
        BackupRestoreMode.MERGE -> "Backup unito: ${result.favoritesAdded} nuovi preferiti"
        BackupRestoreMode.REPLACE -> "Backup ripristinato: ${result.favoritesTotal} preferiti"
    }

    fun openStorageManager() {
        updateState { copy(showStorageManager = true) }
        refreshLibrary()
    }

    fun closeStorageManager() {
        updateState { copy(showStorageManager = false) }
    }

    /**
     * Apre il feed "Aggiornamenti". Rilegge gli eventi da disco così compaiono anche quelli
     * scritti dal [FavoriteUpdatesWorker] mentre l'app era già avviata.
     */
    fun openUpdates() {
        updateState { copy(showUpdates = true, favoriteUpdates = favoriteUpdatesFeedStore.read()) }
    }

    /** Chiude il feed marcando tutto come visto (azzera il badge). */
    fun closeUpdates() {
        markAllUpdatesSeen()
        updateState { copy(showUpdates = false) }
    }

    /**
     * Rilegge il feed da disco SENZA marcare come visto: aggiorna il badge quando il worker
     * scrive nuovi eventi mentre l'app è in primo piano (es. al ritorno in foreground).
     */
    fun refreshUpdatesFeed() {
        updateState { copy(favoriteUpdates = favoriteUpdatesFeedStore.read()) }
    }

    /** Apre la pagina Cronologia (dal "Vedi tutto" del blocco Letti di recente). */
    fun openHistory() {
        updateState { copy(showHistory = true) }
    }

    fun closeHistory() {
        updateState { copy(showHistory = false) }
    }

    /** Marca tutti gli aggiornamenti come visti, persistendo (azzera il badge). */
    fun markAllUpdatesSeen() {
        // Update atomico su disco (non sullo stato in memoria): così non si perdono gli
        // eventi che il worker ha appena aggiunto e che lo stato non ha ancora visto.
        val seen = favoriteUpdatesFeedStore.update(::markAllSeen)
        updateState { copy(favoriteUpdates = seen) }
    }

    /**
     * Tap su una notifica "nuovo capitolo": apre direttamente il dettaglio del manga,
     * marcando come visti i suoi eventi nel feed. Chiude reader/feed se aperti.
     */
    fun openMangaFromNotification(sourceId: String, title: String, mangaUrl: String, coverUrl: String?) {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        markUpdatesSeenForManga(MangaSourceCatalog.identityKey(sourceId, mangaUrl))
        updateState { clearedReaderState().copy(showUpdates = false) }
        selectManga(
            MangaSearchResult(
                sourceId = sourceId,
                title = title,
                mangaUrl = mangaUrl,
                coverUrl = coverUrl,
            ),
        )
    }

    /** Tap sul riepilogo delle notifiche ("N nuovi capitoli"): porta dritto al feed Aggiornamenti. */
    fun openUpdatesFromNotification() {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        updateState {
            clearedReaderState().copy(
                selected = null,
                showUpdates = true,
                favoriteUpdates = favoriteUpdatesFeedStore.read(),
            )
        }
    }

    /** Marca come visti tutti gli eventi del feed relativi a un manga (tap su notifica). */
    private fun markUpdatesSeenForManga(identityKey: String) {
        val updated = favoriteUpdatesFeedStore.update { events ->
            events.map {
                if (it.identityKey == identityKey && !it.seen) it.copy(seen = true) else it
            }
        }
        updateState { copy(favoriteUpdates = updated) }
    }

    /** Marca come visto il singolo evento toccato, lasciando evidenziati gli altri. */
    private fun markUpdateSeen(event: FavoriteUpdateEvent) {
        val updated = favoriteUpdatesFeedStore.update { events ->
            events.map {
                if (it.identityKey == event.identityKey &&
                    it.chapterNumber == event.chapterNumber &&
                    !it.seen
                ) {
                    it.copy(seen = true)
                } else {
                    it
                }
            }
        }
        updateState { copy(favoriteUpdates = updated) }
    }

    /**
     * Tap su una riga del feed: marca come visto SOLO quell'evento e apre il dettaglio
     * sopra il feed (che resta aperto: il back ci torna con gli altri eventi evidenziati).
     */
    fun openMangaFromUpdate(event: FavoriteUpdateEvent) {
        markUpdateSeen(event)
        selectManga(event.toSearchResult())
    }

    fun setParentalControlEnabled(enabled: Boolean) {
        val currentSettings = _state.value.settings
        if (enabled) {
            if (currentSettings.parentalControlEnabled) return
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE)
            return
        }

        if (!currentSettings.parentalControlEnabled) return
        if (!currentSettings.parentalPinConfigured) {
            disableParentalControl(clearCredentials = true)
        } else {
            requestParentalAuthentication(ParentalAction.DISABLE_PARENTAL_CONTROL)
        }
    }

    fun requestChangeParentalPin() {
        val settings = _state.value.settings
        if (!settings.parentalControlEnabled) return
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE)
        } else {
            requestParentalAuthentication(ParentalAction.CHANGE_PIN)
        }
    }

    fun setParentalBiometricEnabled(enabled: Boolean) {
        val settings = _state.value.settings
        if (!settings.parentalControlEnabled || !settings.parentalPinConfigured) return
        val action = if (enabled) {
            ParentalAction.ENABLE_BIOMETRIC
        } else {
            ParentalAction.DISABLE_BIOMETRIC
        }
        requestParentalAuthentication(action)
    }

    fun onParentalPinSetupChange(pin: String? = null, confirmPin: String? = null) {
        val setupState = _state.value.parentalPinSetupState ?: return
        updateState {
            copy(
                parentalPinSetupState = setupState.copy(
                    pin = pin?.let(::sanitizeParentalPin) ?: setupState.pin,
                    confirmPin = confirmPin?.let(::sanitizeParentalPin) ?: setupState.confirmPin,
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissParentalPinSetup() {
        val setupState = _state.value.parentalPinSetupState ?: return
        if (setupState.mode == ParentalPinSetupMode.CREATE && !_state.value.settings.parentalPinConfigured) {
            disableParentalControl(clearCredentials = true)
            return
        }
        updateState {
            copy(
                parentalPinSetupState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (setupState.completionAction == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmParentalPinSetup() {
        val setupState = _state.value.parentalPinSetupState ?: return
        when {
            setupState.pin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Il PIN deve avere 6 cifre",
                        ),
                    )
                }
            }
            setupState.confirmPin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Conferma il PIN di 6 cifre",
                        ),
                    )
                }
            }
            setupState.pin != setupState.confirmPin -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "I due PIN non coincidono",
                        ),
                    )
                }
            }
            else -> {
                val salt = generateParentalPinSalt()
                val hash = hashParentalPin(setupState.pin, salt)
                updateSettings {
                    it.copy(
                        parentalControlEnabled = true,
                        parentalPinConfigured = true,
                        parentalBiometricEnabled = _state.value.isBiometricAvailable,
                        parentalPinSalt = salt,
                        parentalPinHash = hash,
                    )
                }
                updateState {
                    copy(
                        currentTab = if (
                            setupState.completionAction == null &&
                            (currentTab == AppTab.SEARCH || currentTab == AppTab.HOME)
                        ) {
                            // Attivando il parental si atterra su Libreria: Home e Cerca
                            // mostrano/portano a contenuti online che il parental limita.
                            AppTab.LIBRARY
                        } else {
                            currentTab
                        },
                        parentalPinSetupState = null,
                        isParentalAuthInProgress = false,
                    )
                }
                setupState.completionAction?.let(::completeParentalAction)
            }
        }
    }

    fun onParentalPinEntryChange(pin: String) {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = pinEntryState.copy(
                    pin = sanitizeParentalPin(pin),
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissParentalPinEntry() {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (pinEntryState.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmParentalPinEntry() {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        val settings = _state.value.settings
        if (pinEntryState.pin.length != PARENTAL_PIN_LENGTH) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        errorMessage = "Inserisci un PIN di 6 cifre",
                    ),
                )
            }
            return
        }

        val salt = settings.parentalPinSalt
        val expectedHash = settings.parentalPinHash
        if (salt.isNullOrBlank() || expectedHash.isNullOrBlank()) {
            updateState {
                copy(
                    parentalPinEntryState = null,
                    isParentalAuthInProgress = false,
                    errorMessage = "Configura di nuovo il parental control",
                )
            }
            disableParentalControl(clearCredentials = true)
            return
        }

        val providedHash = hashParentalPin(pinEntryState.pin, salt)
        if (providedHash != expectedHash) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        pin = "",
                        errorMessage = "PIN non corretto",
                    ),
                )
            }
            return
        }

        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
            )
        }
        completeParentalAction(pinEntryState.action)
    }

    fun onBiometricAuthenticationSucceeded(requestId: Long) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
        completeParentalAction(request.action)
    }

    fun usePinInsteadOfBiometric(requestId: Long) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        showPinEntryForAction(request.action)
    }

    fun cancelBiometricAuthentication(requestId: Long, message: String? = null) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (request.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
                errorMessage = message ?: errorMessage,
            )
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoDownloadEnabled = enabled) }
    }

    fun setAutoDownloadTriggerChapters(value: Int) {
        updateSettings { it.copy(autoDownloadTriggerChapters = value.coerceAtLeast(1)) }
    }

    fun setAutoDownloadBatchSize(value: Int) {
        updateSettings { it.copy(autoDownloadBatchSize = value.coerceAtLeast(1)) }
    }

    fun setSmartCleanupEnabled(enabled: Boolean) {
        updateSettings { it.copy(smartCleanupEnabled = enabled) }
    }

    fun setSmartCleanupKeepPreviousChapters(value: Int) {
        updateSettings { it.copy(smartCleanupKeepPreviousChapters = value.coerceAtLeast(0)) }
    }

    fun setStreamingReaderEnabled(enabled: Boolean) {
        updateSettings { it.copy(streamingReaderEnabled = enabled) }
    }

    fun setLabsEnabled(enabled: Boolean) {
        updateSettings {
            if (enabled) it.copy(labsEnabled = true)
            else it.copy(
                labsEnabled = false,
                downloadDevUpdates = false,
                highResImages = false,
                privacyBrightnessEnabled = false,
                readerBrightness = 1f,
                allowLandscapeRotation = false,
            )
        }
    }

    fun setDownloadDevUpdates(enabled: Boolean) {
        updateSettings { it.copy(downloadDevUpdates = enabled) }
        if (enabled) {
            checkForAppUpdate(force = true)
        }
    }

    fun setHighResImages(enabled: Boolean) {
        updateSettings { it.copy(highResImages = enabled) }
    }

    fun setFavoriteNotificationsEnabled(enabled: Boolean) {
        updateSettings { it.copy(favoriteNewChapterNotificationsEnabled = enabled) }
        FavoriteUpdatesScheduler.setEnabled(getApplication<Application>(), enabled)
    }

    fun setPrivacyBrightnessEnabled(enabled: Boolean) {
        updateSettings { it.copy(privacyBrightnessEnabled = enabled) }
    }

    fun setReaderBrightness(brightness: Float) {
        updateSettings { it.copy(readerBrightness = brightness.coerceIn(0f, 1f)) }
    }

    fun setReaderPageSpacing(spacingDp: Int) {
        updateSettings {
            it.copy(readerPageSpacingDp = spacingDp.coerceIn(0, MAX_READER_PAGE_SPACING_DP))
        }
    }

    fun setAllowLandscapeRotation(enabled: Boolean) {
        updateSettings { it.copy(allowLandscapeRotation = enabled) }
    }

    fun setDoubleTapZoomEnabled(enabled: Boolean) {
        updateSettings { it.copy(doubleTapZoomEnabled = enabled) }
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        updateSettings { it.copy(keepScreenOnEnabled = enabled) }
    }

    fun setLibrarySort(sort: LibrarySort) {
        updateSettings { it.copy(librarySort = sort) }
    }

    fun setThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        updateSettings { it.copy(useDynamicColor = enabled) }
    }

    fun markTutorialCompleted() {
        updateSettings { it.copy(tutorialCompleted = true) }
        updateState { copy(tutorialState = TutorialUiState(phase = TutorialPhase.Idle)) }
    }

    fun onTutorialWelcomeStart() {
        if (_state.value.tutorialState.phase != TutorialPhase.Welcome) return
        updateState {
            copy(tutorialState = tutorialState.copy(phase = TutorialPhase.Preloading))
        }
        runTutorialPreload()
    }

    fun onTutorialWelcomeSkip() {
        markTutorialCompleted()
    }

    /** Chiude il percorso di fallback del tutorial, segnandolo come completato (permanente). */
    fun onTutorialFallbackCompleted() {
        markTutorialCompleted()
    }

    fun onTutorialFinish(keepSample: Boolean) {
        val sample = _state.value.tutorialState.sample
        if (!keepSample && sample != null) {
            cleanupTutorialSample(sample)
        }
        markTutorialCompleted()
    }

    fun advanceTutorialPhase(from: TutorialPhase, to: TutorialPhase) {
        val current = _state.value.tutorialState.phase
        if (current != from) return
        updateState {
            copy(tutorialState = tutorialState.copy(phase = to))
        }
    }

    private fun runTutorialPreload() {
        viewModelScope.launch {
            try {
                // Fonte deterministica: `searchSourceId` sopravvive soltanto per migrare i dati
                // delle versioni che permettevano la ricerca su una singola fonte.
                val sourceId = MangaSourceIds.DEFAULT
                val source = sourceRegistry.requireById(sourceId)
                val results = withContext(Dispatchers.IO) { source.searchManga("One Piece") }
                val match = results.firstOrNull { it.title.contains("One Piece", ignoreCase = true) }
                    ?: results.firstOrNull()
                    ?: throw NoSuchElementException("Nessun risultato")
                val details = withContext(Dispatchers.IO) {
                    source.fetchMangaDetails(match.mangaUrl)
                }
                val chapter = details.chapters.firstOrNull()
                    ?: throw NoSuchElementException("Nessun capitolo")
                val sample = TutorialSample(
                    sourceId = match.sourceId,
                    mangaUrl = match.mangaUrl,
                    title = match.title,
                    coverUrl = match.coverUrl,
                    chapterUrl = chapter.url,
                )
                DownloadWorker.enqueue(
                    context = getApplication(),
                    firstUrl = chapter.url,
                    lastUrl = chapter.url,
                    sourceId = match.sourceId,
                    seriesTitle = match.title,
                    mangaUrl = match.mangaUrl,
                    coverUrl = match.coverUrl,
                )
                updateState {
                    copy(
                        query = "One Piece",
                        results = results,
                        isSearching = false,
                        tutorialState = tutorialState.copy(
                            phase = TutorialPhase.AwaitingSearchBar,
                            sample = sample,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateState {
                    copy(
                        tutorialState = tutorialState.copy(
                            phase = TutorialPhase.FallbackShowcase,
                        ),
                    )
                }
            }
        }
    }

    private fun cleanupTutorialSample(sample: TutorialSample) {
        val targetKey = MangaSourceCatalog.identityKey(sample.sourceId, sample.mangaUrl)
        val current = _state.value.favorites.toMutableList()
        val removed = current.removeAll {
            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey
        }
        if (removed) {
            favoritesStore.persist(current)
            updateState {
                copy(
                    favorites = current,
                    favoriteMangaKeys = current.identityKeys(),
                )
            }
        }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    libraryRepository.scanLibrary(forceRefresh = true)
                }
                val series = snapshot.firstOrNull {
                    MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl ?: "") == targetKey ||
                        it.title.equals(sample.title, ignoreCase = true)
                } ?: return@launch
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteSeries(series)
                }
                refreshLibrary(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent — cleanup is best-effort.
            }
        }
    }

    fun toggleFavoriteFromResult(result: MangaSearchResult) {
        toggleFavorite(result.toFavoriteManga())
    }

    fun selectManga(result: MangaSearchResult) {
        if (_state.value.currentTab == AppTab.SEARCH) {
            recordRecentSearch(_state.value.query)
        }
        detailJob?.cancel()
        val readChapterIds = libraryRepository.streamingReadChapterIds(result.sourceId, result.mangaUrl)
        updateState {
            copy(
                isLoadingDetails = true,
                errorMessage = null,
                selectedMangaReadChapterIds = readChapterIds,
                selected = result.toDetailsStub(),
            )
        }
        detailJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(result.sourceId, result.mangaUrl).fetchMangaDetails(result.mangaUrl)
                }
                cacheMangaDescription(details.sourceId, details.mangaUrl, details.description)
                // Se è un preferito, aggiorna in memoria lo stato di pubblicazione per i sort/filtri
                // (utile quando le notifiche non hanno ancora popolato la baseline).
                val detailsKey = MangaSourceCatalog.identityKey(details.sourceId, details.mangaUrl)
                val updatedStatusByKey = if (detailsKey in _state.value.favoriteMangaKeys) {
                    _state.value.favoriteStatusByKey + (detailsKey to details.status)
                } else {
                    _state.value.favoriteStatusByKey
                }
                updateState {
                    copy(
                        selected = details,
                        selectedMangaReadChapterIds = libraryRepository.streamingReadChapterIds(
                            details.sourceId,
                            details.mangaUrl,
                        ),
                        favoriteStatusByKey = updatedStatusByKey,
                        isLoadingDetails = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingDetails = false,
                        errorMessage = userFacingErrorMessage(exc, "Errore nel caricare il manga"),
                        errorRetrySearchResult = result,
                    )
                }
            }
        }
    }

    fun showMangaInfo(result: MangaSearchResult) {
        infoJob?.cancel()
        val key = MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
        mangaDescriptionCache[key]?.let { cached ->
            // Trama già in cache (info "sempre pronta"): mostra subito, niente fetch né spinner.
            updateState {
                copy(
                    mangaInfoDialog = MangaInfoDialogState(
                        sourceId = result.sourceId,
                        title = result.title,
                        mangaUrl = result.mangaUrl,
                        coverUrl = result.coverUrl,
                        description = cached,
                        isLoading = false,
                    ),
                    errorMessage = null,
                )
            }
            return
        }
        updateState {
            copy(
                mangaInfoDialog = MangaInfoDialogState(
                    sourceId = result.sourceId,
                    title = result.title,
                    mangaUrl = result.mangaUrl,
                    coverUrl = result.coverUrl,
                    isLoading = true,
                ),
                errorMessage = null,
            )
        }
        infoJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(result.sourceId, result.mangaUrl).fetchMangaDetails(result.mangaUrl)
                }
                cacheMangaDescription(details.sourceId, details.mangaUrl, details.description)
                updateState {
                    copy(
                        mangaInfoDialog = MangaInfoDialogState(
                            sourceId = details.sourceId,
                            title = details.title,
                            mangaUrl = details.mangaUrl,
                            coverUrl = details.coverUrl,
                            description = details.description,
                            isLoading = false,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        mangaInfoDialog = MangaInfoDialogState(
                            sourceId = result.sourceId,
                            title = result.title,
                            mangaUrl = result.mangaUrl,
                            coverUrl = result.coverUrl,
                            isLoading = false,
                            errorMessage = exc.message ?: "Errore caricamento trama",
                        ),
                    )
                }
            }
        }
    }

    fun dismissMangaInfo() {
        infoJob?.cancel()
        updateState { copy(mangaInfoDialog = null) }
    }

    fun clearSelection() {
        detailJob?.cancel()
        updateState {
            copy(
                selected = null,
                selectedMangaReadChapterIds = emptySet(),
                isLoadingDetails = false,
                errorMessage = null,
            )
        }
    }

    fun toggleFavorite(manga: FavoriteManga) {
        val current = _state.value.favorites.toMutableList()
        val targetKey = MangaSourceCatalog.identityKey(manga.sourceId, manga.mangaUrl)
        val existingIndex = current.indexOfFirst {
            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey
        }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, manga.copy(addedAt = System.currentTimeMillis()))
        }
        favoritesStore.persist(current)
        updateState {
            copy(
                favorites = current,
                favoriteMangaKeys = current.identityKeys(),
            )
        }
        if (existingIndex < 0) {
            // Appena aggiunto ai preferiti: se la trama è già in cache, persistila subito.
            mangaDescriptionCache[targetKey]?.let { description ->
                favoriteDescriptionsStore.write(favoriteDescriptionsStore.read() + (targetKey to description))
            }
        }
    }

    /**
     * Mette la trama in cache (info istantanea) e, se il manga è tra i preferiti, la persiste su
     * disco così resta pronta anche dopo il riavvio dell'app.
     */
    private fun cacheMangaDescription(sourceId: String, mangaUrl: String, description: String?) {
        val text = description?.trim()?.takeIf(String::isNotBlank) ?: return
        val key = MangaSourceCatalog.identityKey(sourceId, mangaUrl)
        mangaDescriptionCache[key] = text
        if (key in _state.value.favoriteMangaKeys) {
            favoriteDescriptionsStore.write(favoriteDescriptionsStore.read() + (key to text))
        }
    }

    fun toggleFavoriteSelectedManga() {
        val selected = _state.value.selected ?: return
        toggleFavorite(selected.toFavoriteManga())
    }

    fun refreshLibrary(forceRefresh: Boolean = false) {
        libraryJob?.cancel()
        updateState { copy(isLoadingLibrary = true) }
        libraryJob = viewModelScope.launch {
            try {
                val snapshot = scanLibrarySnapshot(forceRefresh)
                updateState { withLibrarySnapshot(snapshot).copy(isLoadingLibrary = false) }
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

    fun selectDownloadedSeries(series: DownloadedSeries) {
        updateState {
            copy(
                selectedDownloadedSeries = series,
                currentTab = AppTab.LIBRARY,
                // Chiude eventuali overlay sopra: aprendo la serie dalla Gestione memoria
                // (raggiunta dalle Impostazioni) si deve atterrare sulla schermata serie.
                showStorageManager = false,
                showSettings = false,
                errorMessage = null,
            )
        }
    }

    fun clearDownloadedSelection() {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        smartCleanupJob?.cancel()
        updateState {
            copy(
                selectedDownloadedSeries = null,
                errorMessage = null,
            ).clearedReaderState()
        }
    }

    fun setReadingMode(mode: ReadingMode) {
        // Default globale, applicato alle serie senza una preferenza esplicita.
        updateSettings { it.copy(readingMode = mode) }
        val state = _state.value
        val seriesKey = state.readerSeriesKey
        if (state.readerChapter != null &&
            seriesKey != null &&
            !prefs.contains(KEY_READING_MODE_SERIES_PREFIX + seriesKey)
        ) {
            updateState { copy(readerReadingMode = mode) }
        }
    }

    fun setReaderReadingMode(mode: ReadingMode) {
        // Override ricordato per la serie attualmente in lettura.
        val state = _state.value
        val seriesKey = state.readerSeriesKey ?: return
        prefs.edit {
            putString(KEY_READING_MODE_SERIES_PREFIX + seriesKey, mode.name)
        }
        // Riparte dalla pagina corrente così il cambio modalità non perde il segno.
        val currentPage = (state.readerChapter?.readerPageIndex ?: state.readerInitialPageIndex)
            .coerceAtLeast(0)
        updateState { copy(readerReadingMode = mode, readerInitialPageIndex = currentPage) }
    }

    private fun seriesKeyForDownloaded(relativePath: String): String =
        "dl:" + relativePath.substringBefore('/')

    private fun seriesKeyForStreaming(sourceId: String, mangaUrl: String): String =
        "st:" + MangaSourceCatalog.identityKey(sourceId, mangaUrl)

    private fun resolveReadingMode(seriesKey: String): ReadingMode {
        val stored = prefs.getString(KEY_READING_MODE_SERIES_PREFIX + seriesKey, null)
        return stored?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
            ?: _state.value.settings.readingMode
    }

    fun openReader(chapter: DownloadedChapter) {
        readerJob?.cancel()
        streamingCacheJob?.cancel()
        val savedPageIndex = libraryRepository.readerPagePosition(chapter.relativePath)?.pageIndex
            ?: chapter.readerPageIndex
            ?: 0
        val initialReaderChapter = chapter.copy(readerPageIndex = savedPageIndex).toReaderChapter()
        val seriesKey = seriesKeyForDownloaded(chapter.relativePath)
        val readerSourceId = sequenceOf(_state.value.selectedDownloadedSeries)
            .plus(_state.value.library.asSequence())
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
                isLoadingReader = true,
                errorMessage = null,
            )
                .withReaderProgress(chapter.relativePath, pageIndex = savedPageIndex, pageCount = chapter.readerPageCount)
                .withReaderAdjacency(chapter.relativePath)
        }

        readerJob = viewModelScope.launch {
            try {
                val pages = libraryRepository.extractReaderPages(chapter)
                val restoredPageIndex = savedPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
                libraryRepository.saveReaderPagePosition(chapter.relativePath, restoredPageIndex, pages.size)
                updateState {
                    copy(
                        readerPages = pages.map { ReaderPage.Local(file = it, sourceId = readerSourceId) },
                        readerInitialPageIndex = restoredPageIndex,
                        isLoadingReader = false,
                    )
                        .withReaderProgress(chapter.relativePath, pageIndex = restoredPageIndex, pageCount = pages.size)
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

        val streamingReadChapterIds = libraryRepository.streamingReadChapterIds(
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
                isLoadingReader = true,
                errorMessage = null,
                selectedMangaReadChapterIds = streamingReadChapterIds,
            ).withStreamingReaderAdjacency(streamingChapter)
        }

        readerJob = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    streamingCacheRepository.getCachedChapter(cacheKey)
                }
                if (cached != null) {
                    val restored = cached.restoreReaderPageIndex(savedPagePosition)
                    libraryRepository.saveReaderPagePosition(readerChapter.relativePath, restored, cached.pages.size)
                    updateState {
                        withStreamingReaderPayload(
                            expectedRelativePath = readerChapter.relativePath,
                            streamingChapter = streamingChapter,
                            pages = cached.toReaderPages(),
                            restoredPageIndex = restored,
                        )
                    }
                    return@launch
                }

                val pageUrls = withContext(Dispatchers.IO) {
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

                streamingCacheJob = viewModelScope.launch {
                    try {
                        val completed = withContext(Dispatchers.IO) {
                            streamingCacheRepository.cacheCompleteChapter(
                                key = cacheKey,
                                title = streamingChapter.chapter.displayLabel(),
                                pageUrls = pageUrls,
                                referer = streamingChapter.chapter.url,
                            )
                        }
                        val currentOriginalPage = _state.value.readerChapter
                            ?.takeIf { it.relativePath == readerChapter.relativePath }
                            ?.readerPageIndex
                        if (currentOriginalPage != null) {
                            val mappedPageIndex = completed
                                .readerPageIndexForOriginalPage(currentOriginalPage)
                                ?.coerceIn(0, completed.pages.lastIndex.coerceAtLeast(0))
                                ?: currentOriginalPage.coerceIn(
                                    0,
                                    completed.pages.lastIndex.coerceAtLeast(0),
                                )
                            libraryRepository.saveReaderPagePosition(
                                readerChapter.relativePath,
                                mappedPageIndex,
                                completed.pages.size,
                            )
                            updateState {
                                withStreamingReaderPayload(
                                    expectedRelativePath = readerChapter.relativePath,
                                    streamingChapter = streamingChapter,
                                    pages = completed.toReaderPages(),
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
                        errorMessage = userFacingErrorMessage(exc, "Impossibile aprire il reader online"),
                    )
                }
            }
        }
    }

    fun saveReaderPagePosition(pageIndex: Int, pageCount: Int, allowCompletion: Boolean) {
        val chapter = _state.value.readerChapter ?: return
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
                libraryRepository.markChapterRead(downloaded)
            } else if (streaming != null) {
                streamingReadId = libraryRepository.markStreamingChapterRead(
                    sourceId = streaming.sourceId,
                    mangaUrl = streaming.mangaUrl,
                    chapter = streaming.chapter,
                )
            }
        }

        updateState {
            withReaderProgress(
                relativePath = chapter.relativePath,
                pageIndex = nextPageIndex,
                pageCount = safePageCount,
                markRead = newlyRead,
            ).let { state ->
                if (streamingReadId != null) {
                    state.copy(selectedMangaReadChapterIds = state.selectedMangaReadChapterIds + streamingReadId)
                } else {
                    state
                }
            }
        }

        if (newlyRead) {
            maybeSyncAniListOnChapterRead(chapter)
        }
    }

    private fun maybeTriggerAutoDownload(chapter: DownloadedChapter) {
        val settings = _state.value.settings
        if (!settings.autoDownloadEnabled) return
        val series = _state.value.selectedDownloadedSeries ?: return
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

        autoDownloadJob = viewModelScope.launch {
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
                        getApplication<Application>(),
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
        val settings = _state.value.settings
        if (!settings.smartCleanupEnabled) return
        if (smartCleanupJob?.isActive == true) return

        val series = _state.value.selectedDownloadedSeries ?: return
        val currentIndex = series.chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (currentIndex <= 0) return

        val keepPrevious = settings.smartCleanupKeepPreviousChapters.coerceAtLeast(0)
        val deleteUntilIndex = (currentIndex - keepPrevious).coerceAtLeast(0)
        if (deleteUntilIndex <= 0) return

        val chaptersToDelete = series.chapters
            .take(deleteUntilIndex)
            .filter { it.isRead }

        if (chaptersToDelete.isEmpty()) return

        smartCleanupJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteChapters(series, chaptersToDelete)
                }
                val snapshot = scanLibrarySnapshot()
                updateState { withLibrarySnapshot(snapshot) }
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
        updateState { clearedReaderState() }
    }

    fun dismissError() {
        updateState { copy(errorMessage = null, errorRetrySearchResult = null) }
    }

    /**
     * "Pop" della schermata corrente: chiude l'overlay in cima secondo la gerarchia di
     * [currentScreen]. Le close-action compongono lo stack (es. chiudere la gestione memoria
     * riporta alle impostazioni, perché `closeStorageManager` non tocca `showSettings`).
     */
    fun handleBack() {
        when (_state.value.currentScreen()) {
            Screen.Reader -> closeReader()
            Screen.StorageManager -> closeStorageManager()
            Screen.Backup -> closeBackup()
            Screen.Feedback -> closeFeedback()
            Screen.Changelog -> closeChangelog()
            Screen.Settings -> closeSettings()
            Screen.Updates -> closeUpdates()
            Screen.History -> closeHistory()
            Screen.Detail -> clearSelection()
            Screen.DownloadedSeries -> clearDownloadedSelection()
            Screen.Tabs -> Unit
        }
    }

    /**
     * Long-press su un capitolo scaricato: letto/non letto a mano, senza doverlo aprire
     * (es. già letto altrove in streaming, o da rileggere). Prima lo stato cambiava solo
     * arrivando in fondo al capitolo nel reader.
     */
    fun setChapterRead(chapter: DownloadedChapter, read: Boolean) {
        if (read) {
            libraryRepository.markChapterRead(chapter)
        } else {
            libraryRepository.markChapterUnread(chapter)
        }
        refreshLibraryAfterReadChange()
    }

    /** "Segna come letti fino a qui": tutti i capitoli della serie fino a [chapter] incluso. */
    fun markChaptersReadUpTo(chapter: DownloadedChapter) {
        val series = _state.value.selectedDownloadedSeries ?: return
        val index = series.chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (index < 0) {
            return
        }
        libraryRepository.markChaptersRead(
            series.chapters.take(index + 1).filterNot { it.isRead },
        )
        refreshLibraryAfterReadChange()
    }

    /** Voce del menu serie in libreria: tutti i capitoli scaricati segnati come letti. */
    fun markAllChaptersRead(series: DownloadedSeries) {
        libraryRepository.markChaptersRead(series.chapters.filterNot { it.isRead })
        refreshLibraryAfterReadChange()
    }

    /** Riallinea libreria/serie aperta dopo un cambio manuale dello stato di lettura. */
    private fun refreshLibraryAfterReadChange() {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            try {
                val snapshot = scanLibrarySnapshot()
                updateState { withLibrarySnapshot(snapshot) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: lo stato su disco è già scritto, la UI si riallinea al prossimo refresh.
            }
        }
    }

    fun deleteDownloadedChapter(chapter: DownloadedChapter) {
        val series = _state.value.selectedDownloadedSeries ?: return
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

    fun deleteDownloadedSeries(series: DownloadedSeries? = _state.value.selectedDownloadedSeries) {
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
        smartCleanupJob?.cancel()
        updateState { copy(isLoadingLibrary = true, errorMessage = null) }
        libraryJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { mutation() }
                val snapshot = scanLibrarySnapshot()
                updateState {
                    val baseState = if (clearReader) clearedReaderState() else this
                    baseState.withLibrarySnapshot(snapshot).copy(isLoadingLibrary = false)
                }
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
        _state.value.readerPreviousChapter?.let(::openReaderChapter)
    }

    fun openNextReaderChapter() {
        _state.value.readerNextChapter?.let(::openReaderChapter)
    }

    /** Riapre il capitolo corrente del reader: CTA "Riprova" quando il fetch delle pagine fallisce. */
    fun retryReaderLoad() {
        _state.value.readerChapter?.let(::openReaderChapter)
    }

    private fun openReaderChapter(chapter: ReaderChapter) {
        chapter.downloadedChapter?.let {
            openReader(it)
            return
        }
        chapter.streamingChapter?.let(::openStreamingReader)
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (updateJob?.isActive == true) {
            return
        }
        if (!force && _state.value.availableUpdate != null) {
            return
        }

        val includePreview = _state.value.settings.downloadDevUpdates
        // Stable channel: throttle cold-start checks to once per day to avoid
        // hammering GitHub on every app open. Preview channel and explicit
        // user-triggered checks always run, since previews ship more often and
        // users hitting "controlla aggiornamenti" expect immediate feedback.
        val shouldRecordStableCheck = !force && !includePreview
        if (shouldRecordStableCheck) {
            val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
            if (lastCheck > 0L &&
                System.currentTimeMillis() - lastCheck < UPDATE_CHECK_COOLDOWN_MS
            ) {
                return
            }
        }

        updateState { copy(isCheckingUpdate = true) }
        updateJob = viewModelScope.launch {
            var stableCheckCompleted = false
            try {
                val update = appUpdateRepository.checkForUpdate(
                    includePreview = includePreview,
                )
                stableCheckCompleted = true
                updateState {
                    copy(
                        availableUpdate = update,
                        isCheckingUpdate = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    if (force) {
                        copy(
                            isCheckingUpdate = false,
                            errorMessage = exc.message ?: "Errore controllo aggiornamenti",
                        )
                    } else {
                        copy(isCheckingUpdate = false)
                    }
                }
            } finally {
                if (shouldRecordStableCheck && stableCheckCompleted) {
                    prefs.edit {
                        putLong(KEY_LAST_UPDATE_CHECK_AT, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun dismissAvailableUpdate() {
        updateState { copy(availableUpdate = null) }
    }

    fun installAvailableUpdate() {
        val update = _state.value.availableUpdate ?: return
        updateJob?.cancel()
        updateState { copy(isInstallingUpdate = true, errorMessage = null) }
        updateJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                if (!AppUpdateInstaller.canInstallPackages(context)) {
                    AppUpdateInstaller.openInstallPermissionSettings(context)
                    updateState {
                        copy(
                            isInstallingUpdate = false,
                            errorMessage = "Abilita l'installazione da questa app e riprova",
                        )
                    }
                    return@launch
                }

                val apkFile = appUpdateRepository.downloadUpdateApk(update)
                AppUpdateInstaller.installApk(context, apkFile)
                updateState {
                    copy(
                        isInstallingUpdate = false,
                        availableUpdate = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isInstallingUpdate = false,
                        errorMessage = exc.message ?: "Errore installazione aggiornamento",
                    )
                }
            }
        }
    }

    /**
     * Ricerca aggregata sulle fonti dello scope attivo: tutte (chip "Tutte" o ponte
     * AniList→fonti della tab Scopri) o solo quelle di una lingua (chip "Italiano"/"English").
     * Lo stesso titolo può esistere su fonti diverse e l'utente sceglie quale scaricare.
     * Ogni fonte è interrogata in parallelo e i fallimenti della singola fonte sono ignorati
     * (best-effort), così una fonte down non azzera i risultati delle altre. I risultati sono
     * combinati alternando le fonti (vedi [MangaSourceCatalog.interleaveBySource]), non
     * accodati a blocchi.
     */
    private fun runAggregatedSearch(query: String) {
        searchJob?.cancel()
        updateState { copy(isSearching = true, searchError = null, errorMessage = null) }
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    coroutineScope {
                        MangaSourceCatalog.descriptorsForScope(_state.value.settings.searchScope)
                            .map { descriptor ->
                                async {
                                    runCatching {
                                        sourceRegistry.requireById(descriptor.id).searchManga(query)
                                    }.getOrDefault(emptyList())
                                }
                            }
                            .awaitAll()
                    }
                }
                updateState {
                    copy(
                        results = MangaSourceCatalog.interleaveBySource(results),
                        isSearching = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                val friendly = userFacingErrorMessage(exc, "Errore di ricerca")
                updateState {
                    copy(
                        isSearching = false,
                        searchError = friendly,
                        errorMessage = if (results.isNotEmpty()) friendly else errorMessage,
                    )
                }
            }
        }
    }

    // --- Tab Scopri (AniList) -------------------------------------------------------------

    /** Carica le tre sezioni a caroselli (tendenze, più votati, novità). Idempotente. */
    fun loadDiscovery(forceRefresh: Boolean = false) {
        val current = _state.value.discovery
        if (!forceRefresh && (current.loaded || current.isLoadingSections)) {
            return
        }
        discoveryJob?.cancel()
        updateState { copy(discovery = discovery.copy(isLoadingSections = true, sectionsError = null)) }
        discoveryJob = viewModelScope.launch {
            try {
                val sections = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val trending = async { aniListClient.fetchMedia(AniListSort.TRENDING) }
                        val topRated = async { aniListClient.fetchMedia(AniListSort.TOP_RATED) }
                        val newest = async { aniListClient.fetchMedia(AniListSort.NEWEST) }
                        Triple(trending.await(), topRated.await(), newest.await())
                    }
                }
                updateState {
                    copy(
                        discovery = discovery.copy(
                            trending = sections.first,
                            topRated = sections.second,
                            newest = sections.third,
                            isLoadingSections = false,
                            loaded = true,
                            sectionsError = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        discovery = discovery.copy(
                            isLoadingSections = false,
                            sectionsError = exc.message ?: "Errore caricamento Scopri",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Ponte AniList→fonti: prende il titolo del manga scoperto (English→romaji) e lo cerca su
     * tutte le fonti reali, portando l'utente nella tab Cerca. Rispetta il parental control
     * (Cerca passa dallo sblocco), riusando [selectTab].
     */
    fun onPickAniListManga(manga: AniListManga) {
        val title = manga.searchTitle() ?: return
        updateSettings { it.copy(searchScope = SearchScope.ALL) }
        updateState {
            copy(
                query = title,
                results = emptyList(),
                isSearching = true,
                searchError = null,
                errorMessage = null,
                discovery = discovery.copy(info = null),
            )
        }
        selectTab(AppTab.SEARCH)
    }

    fun showDiscoveryInfo(manga: AniListManga) {
        updateState { copy(discovery = discovery.copy(info = manga)) }
    }

    fun dismissDiscoveryInfo() {
        updateState { copy(discovery = discovery.copy(info = null)) }
    }

    private fun requestSearchAccess() {
        val settings = _state.value.settings
        val originTab = _state.value.currentTab
        if (!settings.parentalControlEnabled) {
            updateState { copy(currentTab = AppTab.SEARCH) }
            return
        }
        updateState { copy(pendingSearchAccessReturnTab = originTab) }
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(
                mode = ParentalPinSetupMode.CREATE,
                completionAction = ParentalAction.OPEN_SEARCH,
            )
            return
        }
        requestParentalAuthentication(ParentalAction.OPEN_SEARCH)
    }

    private fun requestParentalAuthentication(action: ParentalAction) {
        if (_state.value.isParentalAuthInProgress) return
        val settings = _state.value.settings
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE, completionAction = action)
            return
        }
        if (settings.parentalBiometricEnabled && _state.value.isBiometricAvailable) {
            val requestId = nextBiometricRequestId++
            updateState {
                copy(
                    isParentalAuthInProgress = true,
                    parentalPinEntryState = null,
                    biometricPromptRequest = ParentalBiometricPromptRequest(
                        requestId = requestId,
                        action = action,
                        title = "Parental control",
                        subtitle = when (action) {
                            ParentalAction.OPEN_SEARCH -> "Autenticati per aprire Cerca"
                            ParentalAction.CHANGE_PIN -> "Autenticati per cambiare il PIN"
                            ParentalAction.DISABLE_PARENTAL_CONTROL ->
                                "Autenticati per disattivare il parental control"
                            ParentalAction.ENABLE_BIOMETRIC,
                            ParentalAction.DISABLE_BIOMETRIC ->
                                "Autenticati per aggiornare la biometria"
                        },
                    ),
                )
            }
        } else {
            showPinEntryForAction(action)
        }
    }

    private fun startParentalPinSetup(
        mode: ParentalPinSetupMode,
        completionAction: ParentalAction? = null,
    ) {
        updateState {
            copy(
                isParentalAuthInProgress = true,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                parentalPinSetupState = ParentalPinSetupState(
                    mode = mode,
                    completionAction = completionAction,
                ),
            )
        }
    }

    private fun showPinEntryForAction(action: ParentalAction) {
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = true,
                parentalPinEntryState = ParentalPinEntryState(action = action),
            )
        }
    }

    private fun completeParentalAction(action: ParentalAction) {
        when (action) {
            ParentalAction.OPEN_SEARCH -> updateState {
                copy(
                    currentTab = AppTab.SEARCH,
                    pendingSearchAccessReturnTab = null,
                )
            }
            ParentalAction.CHANGE_PIN -> startParentalPinSetup(mode = ParentalPinSetupMode.CHANGE)
            ParentalAction.DISABLE_PARENTAL_CONTROL -> disableParentalControl(clearCredentials = true)
            ParentalAction.ENABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = true) }
            ParentalAction.DISABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = false) }
        }
    }

    private fun disableParentalControl(clearCredentials: Boolean) {
        updateSettings {
            if (clearCredentials) {
                it.copy(
                    parentalControlEnabled = false,
                    parentalPinConfigured = false,
                    parentalBiometricEnabled = false,
                    parentalPinSalt = null,
                    parentalPinHash = null,
                )
            } else {
                it.copy(parentalControlEnabled = false, parentalBiometricEnabled = false)
            }
        }
        updateState {
            copy(
                currentTab = if (currentTab == AppTab.SEARCH) AppTab.LIBRARY else currentTab,
                pendingSearchAccessReturnTab = null,
                parentalPinSetupState = null,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
    }

    private fun isBiometricAvailable(context: Context): Boolean {
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun MangaUiState.withLibrarySnapshot(snapshot: List<DownloadedSeries>): MangaUiState {
        val selectedDirectory = selectedDownloadedSeries?.directory?.absolutePath
        val updatedSelected = snapshot.firstOrNull { it.directory.absolutePath == selectedDirectory }
        val readerPath = readerChapter?.downloadedChapter?.relativePath
        val updatedReader = readerPath?.let { path ->
            updatedSelected?.chapters?.firstOrNull { it.relativePath == path }
                ?: snapshot.asSequence()
                    .flatMap { it.chapters.asSequence() }
                    .firstOrNull { it.relativePath == path }
        }

        return copy(
            library = snapshot,
            selectedDownloadedSeries = updatedSelected,
            readerChapter = updatedReader?.toReaderChapter() ?: readerChapter,
        ).withReaderAdjacency(updatedReader?.relativePath ?: readerPath)
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

    fun clearRecentSearches() {
        updateState { copy(recentSearches = emptyList()) }
        recentSearchesStore.persist(emptyList())
    }

    private fun recordRecentSearch(query: String) {
        val updated = RecentSearchesStore.withRecorded(_state.value.recentSearches, query)
        if (updated == _state.value.recentSearches) {
            return
        }
        updateState { copy(recentSearches = updated) }
        recentSearchesStore.persist(updated)
    }

    // --- Tracking AniList: account, matching serie→media, push stato/progresso ---

    /**
     * Redirect OAuth dal browser (`mangapp://anilist-auth#access_token=…`): valida il token
     * recuperando il profilo, persiste l'account e riprova eventuali progressi in sospeso.
     */
    fun onAniListAuthRedirect(fragment: String?) {
        val token = AniListAuth.extractAccessToken(fragment)
        if (token == null) {
            updateState { copy(errorMessage = "Accesso ad AniList annullato o non riuscito") }
            return
        }
        updateState { copy(aniList = aniList.copy(isConnecting = true)) }
        viewModelScope.launch {
            try {
                val viewer = withContext(Dispatchers.IO) { aniListClient.fetchViewer(token) }
                aniListStore.persistAccount(token, viewer)
                updateState {
                    copy(
                        aniList = aniList.copy(viewer = viewer, isConnecting = false),
                        errorMessage = "AniList collegato come ${viewer.name}",
                    )
                }
                flushPendingAniListSync()
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(isConnecting = false),
                        errorMessage = exc.message ?: "Impossibile collegare AniList",
                    )
                }
            }
        }
    }

    /** Scollega l'account. I legami serie→media restano per un futuro nuovo login. */
    fun disconnectAniList() {
        aniListStore.clearAccount()
        updateState {
            copy(aniList = aniList.copy(viewer = null, match = null, trackerKey = null))
        }
    }

    fun setAniListSyncEnabled(enabled: Boolean) {
        updateSettings { it.copy(aniListSyncEnabled = enabled) }
    }

    /** Apre il dialog di matching per la serie aperta nel dettaglio, cercandone il titolo. */
    fun openAniListMatch() {
        val selected = _state.value.selected ?: return
        val key = MangaSourceCatalog.identityKeyOrNull(
            selected.sourceId,
            selected.mangaUrl,
            selected.title,
        ) ?: return
        val query = selected.title.trim()
        updateState {
            copy(
                aniList = aniList.copy(
                    match = AniListMatchUiState(identityKey = key, query = query, isLoading = true),
                ),
            )
        }
        runAniListMatchSearch(query)
    }

    fun onAniListMatchQueryChange(query: String) {
        updateState {
            copy(aniList = aniList.copy(match = aniList.match?.copy(query = query)))
        }
    }

    fun submitAniListMatchSearch() {
        val match = _state.value.aniList.match ?: return
        val query = match.query.trim()
        if (query.isEmpty()) return
        updateState {
            copy(
                aniList = aniList.copy(
                    match = aniList.match?.copy(isLoading = true, errorMessage = null),
                ),
            )
        }
        runAniListMatchSearch(query)
    }

    fun dismissAniListMatch() {
        aniListMatchJob?.cancel()
        updateState { copy(aniList = aniList.copy(match = null)) }
    }

    private fun runAniListMatchSearch(query: String) {
        aniListMatchJob?.cancel()
        aniListMatchJob = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { aniListClient.searchManga(query) }
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLoading = false,
                                candidates = candidates,
                                errorMessage = null,
                            ),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLoading = false,
                                errorMessage = exc.message ?: "Ricerca su AniList non riuscita",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * L'utente ha confermato il candidato: legge dal server capitoli totali ed eventuale entry
     * già esistente (per non azzerare un progresso fatto sul sito) e persiste il legame.
     */
    fun confirmAniListMatch(media: AniListManga) {
        val match = _state.value.aniList.match ?: return
        val token = aniListStore.readToken() ?: return
        updateState {
            copy(aniList = aniList.copy(match = aniList.match?.copy(isLinking = true)))
        }
        viewModelScope.launch {
            try {
                val mediaEntry = withContext(Dispatchers.IO) {
                    aniListClient.fetchMediaEntry(media.id, token)
                }
                saveAniListTracking(
                    match.identityKey,
                    AniListTracking(
                        mediaId = media.id,
                        title = media.displayTitle(),
                        totalChapters = mediaEntry.totalChapters ?: media.chapters,
                        status = mediaEntry.entry?.status,
                        progress = mediaEntry.entry?.progress ?: 0,
                        score = mediaEntry.entry?.score,
                    ),
                )
                updateState { copy(aniList = aniList.copy(match = null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAniListAuthError()
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLinking = false,
                                errorMessage = exc.message ?: "Collegamento non riuscito",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Apre il dialog del tracker (stato/progresso/voto) per la serie aperta nel dettaglio. */
    fun openAniListTracker() {
        val selected = _state.value.selected ?: return
        val key = MangaSourceCatalog.identityKeyOrNull(
            selected.sourceId,
            selected.mangaUrl,
            selected.title,
        ) ?: return
        if (_state.value.aniList.trackings[key] == null) return
        updateState { copy(aniList = aniList.copy(trackerKey = key)) }
    }

    fun dismissAniListTracker() {
        updateState { copy(aniList = aniList.copy(trackerKey = null, isSavingEntry = false)) }
    }

    /** Rimuove il legame della serie col tracker aperto. Non tocca la lista su AniList. */
    fun unlinkAniListTracking() {
        val key = _state.value.aniList.trackerKey ?: return
        saveAniListTracking(key, null)
        updateState { copy(aniList = aniList.copy(trackerKey = null)) }
    }

    /** Salvataggio manuale dal dialog del tracker: una sola mutation con i campi modificati. */
    fun saveAniListEntry(status: AniListListStatus?, progress: Int?, score: Double?) {
        val key = _state.value.aniList.trackerKey ?: return
        val tracking = _state.value.aniList.trackings[key] ?: return
        val token = aniListStore.readToken() ?: return
        updateState { copy(aniList = aniList.copy(isSavingEntry = true)) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    aniListClient.saveListEntry(
                        token = token,
                        mediaId = tracking.mediaId,
                        status = status,
                        progress = progress,
                        score = score,
                    )
                }
                updateAniListTracking(key) {
                    it.copy(
                        status = saved.status ?: it.status,
                        progress = saved.progress,
                        score = saved.score ?: it.score,
                        pendingProgress = null,
                    )
                }
                updateState { copy(aniList = aniList.copy(trackerKey = null, isSavingEntry = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAniListAuthError()
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(isSavingEntry = false),
                        errorMessage = exc.message ?: "Salvataggio su AniList non riuscito",
                    )
                }
            }
        }
    }

    /**
     * Sync automatico a fine capitolo: risale alla serie (scaricata o streaming) e spinge il
     * numero di capitolo come progresso, se la serie è collegata e il sync è attivo.
     */
    private fun maybeSyncAniListOnChapterRead(chapter: ReaderChapter) {
        val streaming = chapter.streamingChapter
        val downloaded = chapter.downloadedChapter
        val (sourceId, mangaUrl, chapterNumber) = when {
            streaming != null -> Triple(
                streaming.sourceId,
                streaming.mangaUrl,
                streaming.chapter.numberValue,
            )
            downloaded != null -> {
                val series = sequenceOf(_state.value.selectedDownloadedSeries)
                    .filterNotNull()
                    .plus(_state.value.library)
                    .firstOrNull { s -> s.chapters.any { it.relativePath == chapter.relativePath } }
                    ?: return
                val url = series.mangaUrl?.takeIf(String::isNotBlank) ?: return
                Triple(series.sourceId, url, downloaded.numberValue)
            }
            else -> return
        }
        val progress = chapterNumber?.toInt()?.takeIf { it > 0 } ?: return
        pushAniListProgress(MangaSourceCatalog.identityKey(sourceId, mangaUrl), progress)
    }

    /**
     * Spinge [progress] su AniList per la serie [identityKey]. Mai regressioni: se il server
     * (o un pending) è già più avanti non invia nulla. A capitolo finale raggiunto marca
     * COMPLETED; le entry già COMPLETED non vengono toccate (rilettura ≠ nuovo progresso).
     * In caso di errore di rete il progresso resta in [AniListTracking.pendingProgress].
     */
    private fun pushAniListProgress(identityKey: String, progress: Int) {
        if (!_state.value.settings.aniListSyncEnabled) return
        val token = aniListStore.readToken() ?: return
        val tracking = _state.value.aniList.trackings[identityKey] ?: return
        if (tracking.status == AniListListStatus.COMPLETED) return
        val target = maxOf(progress, tracking.pendingProgress ?: 0)
        if (target <= tracking.progress) {
            if (tracking.pendingProgress != null) {
                updateAniListTracking(identityKey) { it.copy(pendingProgress = null) }
            }
            return
        }

        val completed = tracking.totalChapters?.let { target >= it } == true
        val newStatus = when {
            completed -> AniListListStatus.COMPLETED
            tracking.status == AniListListStatus.REPEATING -> null
            else -> AniListListStatus.CURRENT
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    aniListClient.saveListEntry(
                        token = token,
                        mediaId = tracking.mediaId,
                        status = newStatus,
                        progress = target,
                    )
                }
                updateAniListTracking(identityKey) {
                    it.copy(
                        status = saved.status ?: it.status,
                        progress = saved.progress,
                        pendingProgress = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAniListAuthError()
            } catch (_: Exception) {
                // Offline o errore transitorio: si riprova all'avvio o al prossimo capitolo.
                updateAniListTracking(identityKey) { it.copy(pendingProgress = target) }
            }
        }
    }

    private fun flushPendingAniListSync() {
        val pending = _state.value.aniList.trackings.filterValues { it.pendingProgress != null }
        pending.forEach { (key, tracking) ->
            pushAniListProgress(key, tracking.pendingProgress ?: return@forEach)
        }
    }

    private fun saveAniListTracking(identityKey: String, tracking: AniListTracking?) {
        val current = _state.value.aniList.trackings
        val updated = if (tracking == null) current - identityKey else current + (identityKey to tracking)
        aniListStore.persistTrackings(updated)
        updateState { copy(aniList = aniList.copy(trackings = updated)) }
    }

    private fun updateAniListTracking(
        identityKey: String,
        transform: (AniListTracking) -> AniListTracking,
    ) {
        val current = _state.value.aniList.trackings[identityKey] ?: return
        saveAniListTracking(identityKey, transform(current))
    }

    /** Token rifiutato dal server: account scollegato, l'utente deve riautorizzare. */
    private fun handleAniListAuthError() {
        aniListStore.clearAccount()
        updateState {
            copy(
                aniList = aniList.copy(
                    viewer = null,
                    match = null,
                    trackerKey = null,
                    isSavingEntry = false,
                ),
                errorMessage = "Sessione AniList scaduta: ricollega l'account dalle impostazioni",
            )
        }
    }

    /**
     * Sposta un blocco Home su/giù rispetto ai soli blocchi VISIBILI e persiste. Sotto controllo
     * parentale il blocco Scopri è nascosto dalla vista: lo scambio salta quel blocco così le
     * frecce agiscono sull'ordine che l'utente vede davvero (niente tap "morti").
     */
    fun moveHomeBlock(block: HomeBlock, up: Boolean) = updateSettings { settings ->
        val order = reconcileHomeBlocks(settings.homeBlockOrder)
        settings.copy(
            homeBlockOrder = moveHomeBlockInOrder(order, block, up) { candidate ->
                candidate == HomeBlock.DISCOVER && settings.parentalControlEnabled
            },
        )
    }

    /** Nasconde/mostra un blocco Home e persiste. */
    fun setHomeBlockHidden(block: HomeBlock, hidden: Boolean) = updateSettings {
        val hiddenSet = if (hidden) it.hiddenHomeBlocks + block else it.hiddenHomeBlocks - block
        it.copy(hiddenHomeBlocks = hiddenSet)
    }

    /**
     * Rilancia il tutorial dall'inizio (usato da "Rivedi il tutorial" in Impostazioni). Riporta
     * anche su HOME: la card di benvenuto vive nella Home, quindi senza cambiare tab l'azione
     * sarebbe un no-op dalle altre schermate.
     */
    fun restartTutorial() {
        updateSettings { it.copy(tutorialCompleted = false) }
        updateState {
            copy(
                showSettings = false,
                currentTab = AppTab.HOME,
                tutorialState = TutorialUiState(phase = TutorialPhase.Welcome),
            )
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _state.value.settings
        val updated = transform(current)
        if (updated == current) return
        updateState { copy(settings = updated) }
        settingsStore.persist(updated)
    }

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        _state.value = _state.value.transform()
    }

    private suspend fun scanLibrarySnapshot(forceRefresh: Boolean = false): List<DownloadedSeries> {
        return withContext(Dispatchers.IO) { libraryRepository.scanLibrary(forceRefresh) }
    }

    private fun MangaUiState.clearedReaderState(): MangaUiState {
        return copy(
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            readerInitialPageIndex = 0,
            isLoadingReader = false,
        )
    }

    companion object {
        // Chiavi non-impostazioni che restano nel ViewModel (gli store gestiscono le altre).
        private const val KEY_READING_MODE_SERIES_PREFIX = "reading_mode_series::"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at_ms"
        private const val PARENTAL_PIN_LENGTH = 6
        private const val DEBOUNCE_MS = 350L
        private const val UPDATE_CHECK_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        private const val READ_NOW_CHAPTER_COUNT = 3
    }
}

/**
 * Copia su [target] i byte dell'immagine [url] dalla disk-cache di Coil, se presente (Coil
 * l'ha scaricata per mostrarla nel reader in streaming). Ritorna true se ha copiato. La
 * chiave della cache di Coil, con `ImageRequest.data(url)` senza diskCacheKey custom, è
 * l'URL grezzo. La copia avviene mentre lo snapshot è aperto, perché Coil può poi
 * rimuovere/rimpiazzare il file. Best-effort: qualsiasi errore ⇒ false (si riscarica).
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private fun copyCoilCachedPage(context: Context, url: String, target: File): Boolean {
    return try {
        val diskCache = context.imageLoader.diskCache ?: return false
        diskCache.openSnapshot(url)?.use { snapshot ->
            val source = snapshot.data.toFile()
            if (source.isFile && source.length() > 0L) {
                target.outputStream().buffered().use { output ->
                    source.inputStream().buffered().use { it.copyTo(output) }
                }
                true
            } else {
                false
            }
        } ?: false
    } catch (_: Exception) {
        target.delete()
        false
    }
}
