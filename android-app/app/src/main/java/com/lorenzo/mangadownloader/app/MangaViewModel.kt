package com.lorenzo.mangadownloader.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lorenzo.mangadownloader.BuildConfig
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.FavoriteUpdatesScheduler
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.anilist.AniListFavoritesSyncStore
import com.lorenzo.mangadownloader.data.anilist.AniListListStatus
import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.anilist.AniListStore
import com.lorenzo.mangadownloader.data.anilist.AniListTracking
import com.lorenzo.mangadownloader.data.anilist.AniListViewer
import com.lorenzo.mangadownloader.data.anilist.UnmatchedAniListFavorite
import com.lorenzo.mangadownloader.data.anilist.visibleUnmatchedAniListFavorites
import com.lorenzo.mangadownloader.data.backup.BackupManager
import com.lorenzo.mangadownloader.data.backup.BackupRestoreMode
import com.lorenzo.mangadownloader.data.backup.BackupRestoreResult
import com.lorenzo.mangadownloader.data.library.DownloadedChapter
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheRepository
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.model.ReaderPage
import com.lorenzo.mangadownloader.data.model.ReadingMode
import com.lorenzo.mangadownloader.data.model.ThemeMode
import com.lorenzo.mangadownloader.data.model.canonicalKey
import com.lorenzo.mangadownloader.data.model.identityKey
import com.lorenzo.mangadownloader.data.model.matchKeys
import com.lorenzo.mangadownloader.data.model.toDetailsStub
import com.lorenzo.mangadownloader.data.model.toFavoriteManga
import com.lorenzo.mangadownloader.data.model.toSearchResult
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.data.sources.MangaSourceLanguage
import com.lorenzo.mangadownloader.data.sources.SOURCE_SEARCH_BUDGET_MILLIS
import com.lorenzo.mangadownloader.data.sources.SearchScope
import com.lorenzo.mangadownloader.data.sources.SourceHealthStore
import com.lorenzo.mangadownloader.data.sources.SourceReachability
import com.lorenzo.mangadownloader.data.sources.isSourceOutage
import com.lorenzo.mangadownloader.data.sources.isSourceSkipped
import com.lorenzo.mangadownloader.data.sources.recordSourceProbeFailure
import com.lorenzo.mangadownloader.data.sources.recordSourceProbeSuccess
import com.lorenzo.mangadownloader.data.sources.sourcesToQuery
import com.lorenzo.mangadownloader.data.store.AdultSeriesStore
import com.lorenzo.mangadownloader.data.store.FavoriteDescriptionsStore
import com.lorenzo.mangadownloader.data.store.FavoriteSeenState
import com.lorenzo.mangadownloader.data.store.FavoriteShelvesStore
import com.lorenzo.mangadownloader.data.store.FavoriteSourceHealthStore
import com.lorenzo.mangadownloader.data.store.FavoriteSourceNotice
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesStore
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import com.lorenzo.mangadownloader.data.store.HomeFeedCacheStore
import com.lorenzo.mangadownloader.data.store.ParentalLockoutStore
import com.lorenzo.mangadownloader.data.store.ReadingDiaryStore
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.RecentSearchesStore
import com.lorenzo.mangadownloader.data.store.SeriesLink
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import com.lorenzo.mangadownloader.data.store.SeriesSourceBinding
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.data.store.deviceBootInstant
import com.lorenzo.mangadownloader.data.store.favoriteSourceNotice
import com.lorenzo.mangadownloader.data.store.initialBinding
import com.lorenzo.mangadownloader.data.store.markAllSeen
import com.lorenzo.mangadownloader.data.update.AppUpdateInfo
import com.lorenzo.mangadownloader.data.update.AppUpdateRepository
import com.lorenzo.mangadownloader.domain.FilteredSearchResults
import com.lorenzo.mangadownloader.domain.adultRegistryKeys
import com.lorenzo.mangadownloader.domain.adultSearchRegistryKeys
import com.lorenzo.mangadownloader.domain.filterAdultSearchResults
import com.lorenzo.mangadownloader.domain.home.DEFAULT_HOME_BLOCK_ORDER
import com.lorenzo.mangadownloader.domain.home.DiscoverGenre
import com.lorenzo.mangadownloader.domain.home.HomeBlock
import com.lorenzo.mangadownloader.domain.home.moveHomeBlockInOrder
import com.lorenzo.mangadownloader.domain.home.reconcileHomeBlocks
import com.lorenzo.mangadownloader.domain.isAdultContent
import com.lorenzo.mangadownloader.domain.looksLikeAdultTitle
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ReadingDayStats
import com.lorenzo.mangadownloader.domain.reading.ResumeTarget
import com.lorenzo.mangadownloader.domain.reading.canReopenStreaming
import com.lorenzo.mangadownloader.domain.reading.computeHomeResume
import com.lorenzo.mangadownloader.domain.searchSourcesIncrementally
import com.lorenzo.mangadownloader.domain.series.FavoriteReadingState
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves
import com.lorenzo.mangadownloader.domain.series.FavoriteSort
import com.lorenzo.mangadownloader.domain.series.FavoritesSeriesMigration
import com.lorenzo.mangadownloader.domain.series.GroupedSearchResult
import com.lorenzo.mangadownloader.domain.series.SeriesGrouping
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import com.lorenzo.mangadownloader.domain.series.favoriteSourceCandidates
import com.lorenzo.mangadownloader.domain.series.fetchFromFirstAvailable
import com.lorenzo.mangadownloader.domain.series.firstChaptersForReading
import com.lorenzo.mangadownloader.domain.series.seriesFetchCandidates
import com.lorenzo.mangadownloader.domain.withoutAdultContent
import com.lorenzo.mangadownloader.sharedLibraryRepository
import com.lorenzo.mangadownloader.sharedSourceRegistry
import com.lorenzo.mangadownloader.ui.components.CardDensity
import com.lorenzo.mangadownloader.ui.library.LibrarySort
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode
import com.lorenzo.mangadownloader.ui.theme.AnimationLevel
import com.lorenzo.mangadownloader.ui.widget.ReadingWidget
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // Pagina "esplora per genere": genere aperto, risultati e stato di caricamento.
    val selectedGenre: DiscoverGenre? = null,
    val genreResults: List<AniListManga> = emptyList(),
    val isLoadingGenre: Boolean = false,
    val genreError: String? = null,
)

/**
 * Stato del blocco Home "Consigliati per te": raccomandazioni della community AniList a partire
 * da preferiti e letture dell'utente (vedi [MangaViewModel.loadRecommendations]). Come per la
 * Scopri, sono solo metadati: il tap fa il ponte verso le fonti reali.
 */
data class RecommendationsUiState(
    val items: List<AniListManga> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * Le vetrine AniList senza i titoli per adulti. Filtrate al momento di mostrarle, non quando
 * arrivano: così accendere o spegnere il filtro ha effetto subito, senza ricaricare.
 */
fun DiscoveryUiState.withoutAdultContent(): DiscoveryUiState = copy(
    trending = trending.withoutAdultContent(),
    topRated = topRated.withoutAdultContent(),
    newest = newest.withoutAdultContent(),
    genreResults = genreResults.withoutAdultContent(),
    info = info?.takeUnless { it.isAdultContent() },
)

fun RecommendationsUiState.withoutAdultContent(): RecommendationsUiState =
    copy(items = items.withoutAdultContent())

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

/**
 * Voce del selettore fonte nella scheda manga: una fonte collegata alla serie con le info
 * comparative caricate in lazy (capitoli disponibili, ultimo uscito). [hasError] marca la
 * singola voce come non raggiungibile senza rompere le altre.
 */
data class SourceOptionUi(
    val sourceId: String,
    val mangaUrl: String,
    val chapterCount: Int? = null,
    val lastChapterLabel: String? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
)

/**
 * Un preferito è **una serie**, non una serie-su-una-fonte: la sua identità è [seriesKey]
 * ([SeriesIdentity]). [sourceId]/[mangaUrl] restano, ma valgono solo come "da dove la sto
 * leggendo adesso" e cambiano quando cambi fonte dal selettore o quando il fallback del
 * `FavoriteUpdatesWorker` promuove un altro mirror.
 *
 * [seriesKey] è vuota solo nelle istanze costruite al volo prima di risolverla: leggila
 * sempre con `canonicalKey()`, mai direttamente.
 */
data class FavoriteManga(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val addedAt: Long = 0L,
    val seriesKey: String = "",
)

/** Mappa `identityKey -> stato pubblicazione` derivata dalla baseline notifiche (per sort/filtro). */
private fun Map<String, FavoriteSeenState>.toStatusMap(): Map<String, MangaPublicationStatus> =
    mapValues { (_, seen) ->
        runCatching { MangaPublicationStatus.valueOf(seen.status) }
            .getOrDefault(MangaPublicationStatus.UNKNOWN)
    }

/**
 * Il filtro dei contenuti per adulti è attivo? Scelta dell'utente, oppure imposto dal controllo
 * parentale: lì non si può spegnere senza PIN, perché si spegne solo spegnendo il parentale.
 */
fun AppSettings.hidesAdultContent(): Boolean = hideAdultContent || parentalControlEnabled

/**
 * Il gruppo "Senza scan" dei Preferiti: solo con la sincronizzazione dei preferiti AniList
 * accesa e l'account collegato (spenta, quei titoli non sono affar suo), senza i titoli
 * diventati nel frattempo preferiti dell'app e, col filtro attivo, senza quelli per adulti.
 */
fun MangaUiState.unmatchedAniListFavoritesToShow(): List<UnmatchedAniListFavorite> {
    if (!settings.aniListFavoritesSyncEnabled || aniList.viewer == null) return emptyList()
    return visibleUnmatchedAniListFavorites(
        unmatched = aniListUnmatchedFavorites,
        favoriteSeriesKeys = favoriteSeriesKeys,
        hideAdult = settings.hidesAdultContent(),
    )
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
    val parentalControlEnabled: Boolean = false,
    val parentalPinConfigured: Boolean = false,
    val parentalBiometricEnabled: Boolean = false,
    val parentalPinSalt: String? = null,
    val parentalPinHash: String? = null,
    /** Nasconde i manga per adulti da ricerca e vetrine. Sempre attivo col controllo parentale. */
    val hideAdultContent: Boolean = false,
    val labsEnabled: Boolean = false,
    val downloadDevUpdates: Boolean = false,
    val privacyBrightnessEnabled: Boolean = false,
    val readerBrightness: Float = 1f,
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    // Come trattare le pagine doppie (le facciate affiancate distribuite come
    // un'immagine sola): dividerle è il default, perché intere — su un telefono
    // tenuto in verticale — lasciano a ogni facciata metà larghezza.
    val spreadPageMode: SpreadPageMode = SpreadPageMode.SPLIT,
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
    // Riconciliazione dei preferiti con i favourites AniList, in unione e senza rimozioni
    // (vedi [planAniListFavoritesSync]). Attiva di default per la stessa ragione del sync
    // di lettura: chi collega l'account vuole che le due parti si parlino.
    val aniListFavoritesSyncEnabled: Boolean = true,
    // Personalizzazione della Home: ordine dei blocchi e insieme di quelli nascosti.
    val homeBlockOrder: List<HomeBlock> = DEFAULT_HOME_BLOCK_ORDER,
    val hiddenHomeBlocks: Set<HomeBlock> = emptySet(),
    // Densità globale delle card (come il tema): guida dimensioni e varianti compatte.
    val cardDensity: CardDensity = CardDensity.NORMAL,
    // Quanto si muove l'interfaccia (Complete/Ridotte/Nessuna), anch'essa stile tema.
    val animationLevel: AnimationLevel = AnimationLevel.FULL,
    // Tab Home visibile nella bottom bar. Disattivata, l'app si apre sulla Ricerca.
    val showHomeTab: Boolean = true,
    // Fonti escluse da ricerca aggregata e selettore fonte. Vuoto = tutte attive.
    val disabledSourceIds: Set<String> = emptySet(),
)

data class MangaUiState(
    val currentTab: AppTab = AppTab.HOME,
    val pendingSearchAccessReturnTab: AppTab? = null,
    val query: String = "",
    val favoritesQuery: String = "",
    val libraryQuery: String = "",
    val recentSearches: List<String> = emptyList(),
    val results: List<MangaSearchResult> = emptyList(),
    // Risultati raggruppati per serie (una card per serie): è ciò che la tab Cerca mostra.
    val groupedResults: List<GroupedSearchResult> = emptyList(),
    val discovery: DiscoveryUiState = DiscoveryUiState(),
    val recommendations: RecommendationsUiState = RecommendationsUiState(),
    val favorites: List<FavoriteManga> = emptyList(),
    /**
     * Identità delle serie tra i preferiti (chiave canonica + alias titolo). È l'**unica**
     * domanda che la UI fa sui preferiti — "questa serie ce l'ho?" — indipendentemente dalla
     * fonte da cui la stai guardando: è ciò che tiene la stella coerente tra ricerca e scheda.
     */
    val favoriteSeriesKeys: Set<String> = emptySet(),
    val favoriteFilterReadingState: FavoriteReadingState? = null,
    /** Scaffali scelti dall'utente e filtro attivo (`null` = tutti i preferiti). */
    val favoriteShelves: FavoriteShelves = FavoriteShelves(),
    val favoriteFilterShelfId: String? = null,
    /** Favourites AniList che nessuna fonte espone (gruppo "Senza scan" dei Preferiti). */
    val aniListUnmatchedFavorites: List<UnmatchedAniListFavorite> = emptyList(),
    // Mappe indicizzate per SeriesKey (vedi FavoritesSeriesMigration): sopravvivono al
    // cambio fonte, che per un preferito è un evento normale.
    val favoriteStatusByKey: Map<String, MangaPublicationStatus> = emptyMap(),
    val favoriteSeenStates: Map<String, FavoriteSeenState> = emptyMap(),
    /** Avvisi per-serie mostrati sulla card: vuoto finché l'approvvigionamento funziona. */
    val favoriteNotices: Map<String, FavoriteSourceNotice> = emptyMap(),
    /**
     * Salute per-fonte (`sourceId -> `[SourceReachability]): alimenta l'avviso rosso nelle
     * impostazioni e l'interruttore che salta i siti giù. Vuoto = tutte stanno rispondendo.
     */
    val sourceHealth: Map<String, SourceReachability> = emptyMap(),
    val isSearching: Boolean = false,
    // Contatore delle richieste di ricerca esplicite (vedi [SearchTrigger]): distingue due
    // richieste con query e ambito identici, che devono comunque produrre due fetch.
    val searchRequestId: Int = 0,
    // Fallimento dell'ultima ricerca (rete assente, fonte down): mostrato dalla tab Cerca
    // come stato dedicato con "Riprova", invece di un falso "Nessun risultato".
    val searchError: String? = null,
    // Quando il fetch dei dettagli fallisce, il manga da ritentare: la snackbar d'errore
    // offre "Riprova" che rilancia selectManga senza dover ripetere la ricerca.
    val errorRetrySearchResult: MangaSearchResult? = null,
    val selected: MangaDetails? = null,
    // Link serie→fonti della scheda aperta (null per percorsi legacy senza link).
    val selectedSeriesLink: SeriesLink? = null,
    // SeriesKey canonica della scheda aperta: àncora di tracking AniList e progressi.
    val selectedSeriesKey: String? = null,
    // Voci del selettore fonte, popolate in lazy alla prima apertura del menu.
    val sourceOptions: List<SourceOptionUi> = emptyList(),
    val selectedMangaReadChapterIds: Set<String> = emptySet(),
    val isLoadingDetails: Boolean = false,
    val mangaInfoDialog: MangaInfoDialogState? = null,
    val library: List<DownloadedSeries> = emptyList(),
    // Memoria di lettura persistente (statistiche/cronologia): sopravvive all'eliminazione
    // dei download. Fonte di verità su disco: ReadingMemoryStore.
    val readingMemory: Map<String, ReadChapterMemory> = emptyMap(),
    // Diario giornaliero (capitoli/pagine per data): andamento, streak, heatmap, record.
    val readingDiary: Map<String, ReadingDayStats> = emptyMap(),
    val isLoadingLibrary: Boolean = false,
    val selectedDownloadedSeries: DownloadedSeries? = null,
    val readerChapter: ReaderChapter? = null,
    val readerPreviousChapter: ReaderChapter? = null,
    val readerNextChapter: ReaderChapter? = null,
    val readerPages: List<ReaderPage> = emptyList(),
    val readerInitialPageIndex: Int = 0,
    val readerReadingMode: ReadingMode = ReadingMode.VERTICAL,
    val readerSpreadPageMode: SpreadPageMode = SpreadPageMode.SPLIT,
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
    val showStats: Boolean = false,
    val aniList: AniListUiState = AniListUiState(),
    val favoriteUpdates: List<FavoriteUpdateEvent> = emptyList(),
    /** Registro delle serie per adulti (vedi [AdultSeriesStore]); si applica solo a filtro attivo. */
    val adultSeriesKeys: Set<String> = emptySet(),
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
    private val aniListStore = AniListStore(
        prefs,
        application.getSharedPreferences(AniListStore.ACCOUNT_PREFS_NAME, Context.MODE_PRIVATE),
    )
    private val aniListFavoritesSyncStore = AniListFavoritesSyncStore(prefs)
    private val adultSeriesStore = AdultSeriesStore(prefs)

    // Chiavi delle serie per adulti viste nelle ricerche di questa sessione: se una di queste
    // finisce tra i preferiti o viene scaricata, entra nel registro.
    private val adultSearchKeys = mutableSetOf<String>()
    private var adultRegistryJob: Job? = null
    private val seriesLinksStore = SeriesLinksStore(prefs)
    private val favoriteSourceHealthStore = FavoriteSourceHealthStore(prefs)
    private val sourceHealthStore = SourceHealthStore(prefs)
    private val readingMemoryStore = ReadingMemoryStore(prefs)
    private val readingDiaryStore = ReadingDiaryStore(prefs)
    private val favoriteShelvesStore = FavoriteShelvesStore(prefs)
    private val backupManager = BackupManager(
        favoritesStore = favoritesStore,
        favoriteShelvesStore = favoriteShelvesStore,
        favoriteUpdatesStore = favoriteUpdatesStore,
        favoriteDescriptionsStore = favoriteDescriptionsStore,
        recentSearchesStore = recentSearchesStore,
        settingsStore = settingsStore,
        readingMemoryStore = readingMemoryStore,
        readingDiaryStore = readingDiaryStore,
        adultSeriesStore = adultSeriesStore,
        appVersionName = BuildConfig.VERSION_NAME,
    )

    /**
     * Cache in memoria delle trame (descrizioni) per **SeriesKey**: il pulsante info diventa
     * istantaneo dopo il primo fetch o dopo aver aperto il manga, senza ri-scaricare la pagina.
     * Per i **preferiti** è anche persistita su disco ([favoriteDescriptionsStore]) e ricaricata
     * all'avvio, così la loro info è pronta anche dopo il riavvio. È solo testo.
     */
    private val mangaDescriptionCache = mutableMapOf<String, String>()

    // I preferiti passano da chiave-fonte a chiave-serie prima di qualunque lettura (vedi
    // FavoritesSeriesMigration): one-shot e idempotente, la rifà anche il worker se arriva prima.
    private val initialFavorites = FavoritesSeriesMigration(
        prefs = prefs,
        favoritesStore = favoritesStore,
        favoriteUpdatesStore = favoriteUpdatesStore,
        favoriteDescriptionsStore = favoriteDescriptionsStore,
        favoriteUpdatesFeedStore = favoriteUpdatesFeedStore,
        seriesLinksStore = seriesLinksStore,
    ).migrateIfNeeded()
    private val initialSettings = settingsStore.read()
    private val initialFavoriteSeen = favoriteUpdatesStore.read()
    private val initialReadingMemory = readingMemoryStore.read()
    private val initialReadingDiary = readingDiaryStore.read()

    private val _state = MutableStateFlow(
        MangaUiState(
            // Home è il centro dell'app e la tab d'avvio; il controllo parentale continua a
            // forzare l'atterraggio su Libreria (Cerca resta dietro il PIN).
            currentTab = when {
                initialSettings.parentalControlEnabled -> AppTab.LIBRARY
                !initialSettings.showHomeTab -> AppTab.SEARCH
                else -> AppTab.HOME
            },
            recentSearches = recentSearchesStore.read(),
            readingMemory = initialReadingMemory,
            readingDiary = initialReadingDiary,
            favorites = initialFavorites,
            favoriteSeriesKeys = favoriteSeriesKeys(initialFavorites),
            favoriteSeenStates = initialFavoriteSeen,
            favoriteStatusByKey = initialFavoriteSeen.toStatusMap(),
            favoriteShelves = favoriteShelvesStore.read(),
            aniListUnmatchedFavorites = aniListFavoritesSyncStore.readUnmatchedFavorites(),
            favoriteNotices = favoriteSourceHealthStore.read()
                .mapNotNull { (key, health) -> favoriteSourceNotice(health)?.let { key to it } }
                .toMap(),
            sourceHealth = sourceHealthStore.read(),
            favoriteUpdates = favoriteUpdatesFeedStore.read(),
            adultSeriesKeys = adultSeriesStore.read(),
            aniList = AniListUiState(
                viewer = aniListStore.readViewer().takeIf { aniListStore.readToken() != null },
                trackings = aniListStore.readTrackings(),
            ),
            settings = if (initialSettings.shouldAutoCompleteTutorial(initialFavorites)) {
                initialSettings.copy(tutorialCompleted = true)
            } else {
                initialSettings
            },
            isBiometricAvailable = ParentalControlController.isBiometricAvailable(application),
            tutorialState = if (initialSettings.shouldStartTutorial(initialFavorites)) {
                TutorialUiState(phase = TutorialPhase.Welcome)
            } else {
                TutorialUiState()
            },
        ),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    /** PIN, biometria e accesso protetto a Cerca: vedi [ParentalControlController]. */
    val parental = ParentalControlController(
        state = _state,
        lockoutStore = ParentalLockoutStore(prefs) { deviceBootInstant(application.contentResolver) },
        updateSettings = ::updateSettings,
    )

    /** Reader, memoria di lettura e scritture sulla libreria: vedi [ReaderController]. */
    private val reader = ReaderController(
        state = _state,
        scope = viewModelScope,
        context = application,
        prefs = prefs,
        sourceRegistry = sourceRegistry,
        libraryRepository = libraryRepository,
        streamingCacheRepository = streamingCacheRepository,
        readingMemoryStore = readingMemoryStore,
        readingDiaryStore = readingDiaryStore,
        initialReadingMemory = initialReadingMemory,
        initialReadingDiary = initialReadingDiary,
        updateSettings = ::updateSettings,
        seriesKeyFor = ::currentSeriesKey,
        onChapterRead = { chapter -> aniList.onChapterRead(chapter) },
        onLibraryRefreshed = ::refreshAdultSeriesRegistry,
    )

    /** Account, preferiti e progressi su AniList: vedi [AniListController]. */
    private val aniList = AniListController(
        state = _state,
        scope = viewModelScope,
        aniListClient = aniListClient,
        aniListStore = aniListStore,
        aniListFavoritesSyncStore = aniListFavoritesSyncStore,
        favoritesStore = favoritesStore,
        seriesLinksStore = seriesLinksStore,
        libraryRepository = libraryRepository,
        sourceRegistry = sourceRegistry,
        seriesKeyFor = ::currentSeriesKey,
        skippedSourceIds = ::skippedSourceIds,
        onFavoritesImported = { updated ->
            updateState { copy(favorites = updated, favoriteSeriesKeys = favoriteSeriesKeys(updated)) }
            refreshAdultSeriesRegistry()
        },
    )

    /** Vetrine AniList della Home e della Scopri: vedi [HomeFeedController]. */
    private val homeFeed = HomeFeedController(
        state = _state,
        scope = viewModelScope,
        aniListClient = aniListClient,
        homeFeedCacheStore = HomeFeedCacheStore(prefs),
    )

    /** Controllo, download e installazione degli aggiornamenti: vedi [AppUpdateController]. */
    private val appUpdate = AppUpdateController(
        state = _state,
        scope = viewModelScope,
        context = application,
        prefs = prefs,
        appUpdateRepository = appUpdateRepository,
    )

    /** Percorso guidato del primo avvio: vedi [TutorialController]. */
    val tutorial = TutorialController(
        state = _state,
        scope = viewModelScope,
        context = application,
        sourceRegistry = sourceRegistry,
        favoritesStore = favoritesStore,
        libraryRepository = libraryRepository,
        updateSettings = ::updateSettings,
        refreshLibrary = ::refreshLibrary,
    )

    private var searchJob: Job? = null

    // Media AniList "pinnato" dal ponte Scopri: messo in testa ai candidati del matcher così
    // il raggruppamento è ancorato al mediaId anche se la ricerca AniList fallisce.
    private var pendingAniListPick: AniListManga? = null

    /** Cache di sessione dei dettagli per-fonte del selettore, chiave = identityKey. */
    private val sourceOptionDetailsCache = mutableMapOf<String, MangaDetails>()
    private var sourceOptionsJob: Job? = null
    private var detailJob: Job? = null
    private var infoJob: Job? = null

    /**
     * Capitolo da aprire non appena i dettagli della serie saranno caricati: è così che la
     * cronologia riapre una lettura in streaming, di cui conosce l'indirizzo ma non l'elenco
     * capitoli necessario a navigare avanti e indietro. Tiene anche il manga richiesto, così
     * un'attesa rimasta appesa (dettagli falliti) non può aprirsi su una serie diversa.
     */
    private var pendingStreamingChapter: PendingStreamingChapter? = null

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
        aniList.flushPendingSync()
        // Preferiti aggiunti su AniList da un altro dispositivo (o dal sito): li porta
        // dentro, e porta fuori quelli aggiunti qui mentre l'account era scollegato.
        syncAniListFavorites()
        // Le cartelle dei preferiti sono state rimosse (lo stato di lettura è automatico):
        // ripulisce le chiavi legacy rimaste su disco dalle versioni precedenti.
        prefs.edit {
            remove("favorite_categories_json")
            remove("favorite_category_assignments_json")
        }
    }

    /**
     * Rilancia la ricerca aggregata quando cambia ciò che la definisce (vedi [SearchTrigger]):
     * la query, l'ambito, **l'insieme delle fonti attive** e il contatore delle richieste
     * esplicite. Includere le fonti attive evita che dopo un cambio in impostazioni restino a
     * schermo risultati di fonti appena spente (o manchino quelli di una appena accesa): i
     * risultati mostrati devono venire dalle fonti che l'utente ha ora. Il debounce collassa
     * una raffica di toggle in una sola ricerca.
     */
    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            _state
                .map { it.searchTrigger() }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { trigger ->
                    val query = trigger.query
                    if (query.isNotEmpty()) {
                        runAggregatedSearch(query)
                    } else {
                        searchJob?.cancel()
                        updateState {
                            copy(
                                results = emptyList(),
                                groupedResults = emptyList(),
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
        if (text != _state.value.query) {
            // Una modifica manuale della query invalida il pin del ponte Scopri.
            pendingAniListPick = null
        }
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
            parental.requestSearchAccess()
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

    /** Attiva/disattiva una fonte. L'ultima fonte attiva non è disabilitabile. */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        updateSettings { settings ->
            val updated = if (enabled) {
                settings.disabledSourceIds - sourceId
            } else {
                settings.disabledSourceIds + sourceId
            }
            if (updated.size >= MangaSourceCatalog.descriptors.size) {
                settings
            } else {
                settings.copy(disabledSourceIds = updated)
            }
        }
    }

    fun setFavoriteFilterReadingState(state: FavoriteReadingState?) {
        updateState { copy(favoriteFilterReadingState = state) }
    }

    /**
     * Filtro dei manga per adulti. Sotto controllo parentale è imposto: spegnerlo qui non ha
     * effetto finché il parentale è attivo (vedi [hidesAdultContent]).
     */
    fun setHideAdultContent(enabled: Boolean) {
        val wasHiding = _state.value.settings.hidesAdultContent()
        updateSettings { it.copy(hideAdultContent = enabled) }
        if (enabled && !wasHiding) {
            updateState { copy(errorMessage = ADULT_FILTER_ENABLED_NOTICE) }
        }
        _state.value.query.trim().takeIf { it.isNotEmpty() && _state.value.results.isNotEmpty() }
            ?.let(::runAggregatedSearch)
    }

    fun setFavoriteFilterShelf(shelfId: String?) {
        updateState { copy(favoriteFilterShelfId = shelfId) }
    }

    /** Crea uno scaffale e restituisce il suo id; `null` se il nome è vuoto o già usato. */
    fun createFavoriteShelf(name: String): String? {
        val id = UUID.randomUUID().toString()
        val updated = favoriteShelvesStore.update { it.withNewShelf(name, id) } ?: return null
        updateState { copy(favoriteShelves = updated) }
        return id
    }

    /** `false` se il nome è vuoto o già usato da un altro scaffale. */
    fun renameFavoriteShelf(shelfId: String, name: String): Boolean {
        val updated = favoriteShelvesStore.update { it.withRenamedShelf(shelfId, name) } ?: return false
        updateState { copy(favoriteShelves = updated) }
        return true
    }

    /** Elimina lo scaffale; i preferiti restano, perdono solo l'etichetta. */
    fun deleteFavoriteShelf(shelfId: String) {
        val updated = favoriteShelvesStore.update { it.withoutShelf(shelfId) } ?: return
        updateState {
            copy(
                favoriteShelves = updated,
                favoriteFilterShelfId = favoriteFilterShelfId.takeIf { it != shelfId },
            )
        }
    }

    fun setShelvesForFavorite(favorite: FavoriteManga, shelfIds: Set<String>) {
        val updated = favoriteShelvesStore.update { it.withShelvesFor(favorite, shelfIds) } ?: return
        updateState { copy(favoriteShelves = updated) }
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
                // Stessa regola del worker: se il mirror abituale non risponde si prova il
                // successivo, invece di far fallire la scorciatoia "Leggi".
                val fetched = fetchFromFirstAvailable(
                    favoriteSourceCandidates(
                        favorite = favorite,
                        link = seriesLinksStore.linkFor(favorite.canonicalKey()),
                        disabledSourceIds = _state.value.settings.disabledSourceIds,
                    ),
                ) { binding ->
                    withContext(Dispatchers.IO) {
                        sourceRegistry.resolve(binding.sourceId, binding.mangaUrl)
                            .fetchMangaDetails(binding.mangaUrl)
                    }
                }
                if (fetched == null) {
                    updateState {
                        copy(errorMessage = "${favorite.title} non è raggiungibile da nessuna fonte")
                    }
                    return@launch
                }
                val details = fetched.details
                cacheMangaDescription(
                    details.sourceId,
                    details.mangaUrl,
                    details.title,
                    details.description,
                )
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
            // Dietro le scritture di memoria/diario già accodate: il backup le deve contenere.
            val ok = reader.onMemoryQueue {
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
            // Sulla stessa coda delle scritture di memoria/diario: una scrittura vecchia ancora
            // in attesa non deve arrivare dopo il ripristino e sovrascriverlo.
            val result = reader.onMemoryQueue {
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
            // Il BackupManager ha già persistito memoria e diario ripristinati: allinea le
            // baseline per non riscriverli identici al prossimo confronto.
            reader.onReadingMemoryRestored(result.readingMemory, result.readingDiary)
            updateState {
                copy(
                    favorites = result.favorites,
                    favoriteSeriesKeys = favoriteSeriesKeys(result.favorites),
                    favoriteSeenStates = restoredSeen,
                    favoriteStatusByKey = restoredSeen.toStatusMap(),
                    favoriteShelves = result.favoriteShelves,
                    favoriteFilterShelfId = favoriteFilterShelfId
                        ?.takeIf { result.favoriteShelves.shelf(it) != null },
                    favoriteUpdates = if (clearFeed) emptyList() else favoriteUpdates,
                    recentSearches = result.recentSearches,
                    settings = result.settings,
                    readingMemory = result.readingMemory,
                    readingDiary = result.readingDiary,
                    adultSeriesKeys = result.adultSeriesKeys,
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

    /** Apre la pagina Statistiche (dal "Vedi tutto" del blocco Statistiche). */
    fun openStats() {
        updateState { copy(showStats = true) }
    }

    fun closeStats() {
        updateState { copy(showStats = false) }
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
        markUpdatesSeenForManga(MangaSourceCatalog.identityKey(sourceId, mangaUrl))
        reader.dismiss { copy(showUpdates = false) }
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
        reader.dismiss {
            copy(
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

    fun setLabsEnabled(enabled: Boolean) {
        updateSettings {
            if (enabled) it.copy(labsEnabled = true)
            else it.copy(
                labsEnabled = false,
                downloadDevUpdates = false,
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

    fun setFavoriteNotificationsEnabled(enabled: Boolean) {
        updateSettings { it.copy(favoriteNewChapterNotificationsEnabled = enabled) }
        FavoriteUpdatesScheduler.setEnabled(getApplication<Application>(), enabled)
    }

    fun setPrivacyBrightnessEnabled(enabled: Boolean) {
        updateSettings { it.copy(privacyBrightnessEnabled = enabled) }
    }

    /**
     * Trascinamento dello slider luminosità: solo stato, così l'oscuramento segue il dito.
     * Il salvataggio arriva al rilascio ([commitReaderBrightness]): persistere a ogni frame
     * riscriveva l'intero JSON delle impostazioni decine di volte al secondo.
     */
    fun previewReaderBrightness(brightness: Float) {
        updateState { copy(settings = settings.copy(readerBrightness = brightness.coerceIn(0f, 1f))) }
    }

    fun commitReaderBrightness() {
        settingsStore.persist(_state.value.settings)
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

    fun setSpreadPageMode(mode: SpreadPageMode) = reader.setSpreadPageMode(mode)

    fun setReaderSpreadPageMode(mode: SpreadPageMode) = reader.setReaderSpreadPageMode(mode)

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

    /**
     * Stella su una card raggruppata della ricerca. Il preferito nasce con la chiave della
     * **serie** e con tutti i mirror del gruppo già agganciati, così il fallback del worker ha
     * alternative fin dal primo giro. I titoli di tutte le fonti del gruppo entrano tra le
     * chiavi di confronto: se la serie era già tra i preferiti sotto il titolo di un'altra
     * fonte, questo tap la toglie invece di crearne una copia.
     */
    fun toggleFavoriteFromGroup(group: GroupedSearchResult) {
        val alreadyFavorite = group.seriesKey in _state.value.favoriteSeriesKeys
        if (!alreadyFavorite) {
            seriesLinksStore.mergeFromGroup(group, now = System.currentTimeMillis())
        }
        val primary = group.primary
        toggleFavorite(
            manga = FavoriteManga(
                sourceId = primary.sourceId,
                title = group.title,
                mangaUrl = primary.mangaUrl,
                coverUrl = group.coverUrl ?: primary.coverUrl,
                seriesKey = group.seriesKey,
            ),
            extraMatchKeys = group.results.mapNotNullTo(mutableSetOf()) {
                SeriesIdentity.keyForTitle(it.title)
            },
        )
    }

    /**
     * Tap su una card raggruppata: persiste/aggiorna il [SeriesLink] (unico momento in cui
     * il raggruppamento effimero della ricerca diventa stato) e apre la scheda sulla fonte
     * iniziale (preferita → lingua dello scope → prima).
     */
    fun selectSeries(group: GroupedSearchResult) {
        val link = seriesLinksStore.mergeFromGroup(group, now = System.currentTimeMillis())
        updateState { copy(selectedSeriesLink = link, sourceOptions = emptyList()) }
        val binding = link.initialBinding(_state.value.settings.searchScope)
        val bindingResult = group.results.firstOrNull { it.sourceId == binding.sourceId }
            ?: group.primary
        selectManga(bindingResult)
    }

    /**
     * Popola le voci del selettore fonte (lazy, alla prima apertura del menu): per ogni
     * binding del link, capitoli disponibili e ultimo capitolo, in parallelo. Il fallimento
     * di una fonte marca solo quella voce, senza rompere le altre. Le fonti disabilitate
     * ma già collegate restano visibili: non si nasconde ciò che l'utente ha già.
     */
    fun loadSourceOptions() {
        val link = _state.value.selectedSeriesLink ?: return
        val current = _state.value.sourceOptions
        if (current.isNotEmpty() && current.none { it.isLoading }) return
        sourceOptionsJob?.cancel()
        updateState {
            copy(
                sourceOptions = link.sources.map { binding ->
                    val cached = sourceOptionDetailsCache[
                        MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl),
                    ]
                    SourceOptionUi(
                        sourceId = binding.sourceId,
                        mangaUrl = binding.mangaUrl,
                        chapterCount = cached?.chapters?.size,
                        lastChapterLabel = cached?.chapters?.lastOrNull()?.displayShortLabel(),
                        isLoading = cached == null,
                    )
                },
            )
        }
        sourceOptionsJob = viewModelScope.launch {
            link.sources.forEach { binding ->
                val key = MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl)
                if (sourceOptionDetailsCache.containsKey(key)) return@forEach
                launch {
                    val details = withContext(Dispatchers.IO) {
                        runCatching {
                            sourceRegistry.resolve(binding.sourceId, binding.mangaUrl)
                                .fetchMangaDetails(binding.mangaUrl)
                        }.getOrNull()
                    }
                    if (details != null) {
                        sourceOptionDetailsCache[key] = details
                    }
                    updateState {
                        copy(
                            sourceOptions = sourceOptions.map { option ->
                                if (option.sourceId == binding.sourceId && option.mangaUrl == binding.mangaUrl) {
                                    option.copy(
                                        chapterCount = details?.chapters?.size,
                                        lastChapterLabel = details?.chapters?.lastOrNull()?.displayShortLabel(),
                                        isLoading = false,
                                        hasError = details == null,
                                    )
                                } else {
                                    option
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Chiavi con cui riconoscere i preferiti (ordine preservato): per ciascuno la chiave
     * canonica **e** l'alias titolo, così la stella si accende anche quando la stessa serie
     * arriva sotto l'altra forma di chiave (vedi [FavoriteManga.matchKeys]).
     */
    private fun favoriteSeriesKeys(favorites: List<FavoriteManga>): Set<String> =
        favorites.flatMapTo(linkedSetOf()) { it.matchKeys() }

    /**
     * Sposta il preferito di [seriesKey] sul mirror indicato. No-op se quella serie non è tra
     * i preferiti: il cambio fonte resta comunque registrato nel link.
     */
    private fun updateFavoriteBinding(seriesKey: String, sourceId: String, mangaUrl: String) {
        val existing = _state.value.favorites.firstOrNull { seriesKey in it.matchKeys() } ?: return
        if (existing.sourceId == sourceId && existing.mangaUrl == mangaUrl) return
        val updated = favoritesStore.update { stored ->
            stored.map { favorite ->
                if (seriesKey in favorite.matchKeys()) {
                    favorite.copy(sourceId = sourceId, mangaUrl = mangaUrl)
                } else {
                    favorite
                }
            }
        }
        updateState {
            copy(favorites = updated, favoriteSeriesKeys = favoriteSeriesKeys(updated))
        }
    }

    /**
     * SeriesKey della serie corrente: dal link selezionato se contiene questo binding,
     * altrimenti derivata da (fonte, url, titolo) via [SeriesLinksStore.seriesKeyFor].
     */
    private fun currentSeriesKey(sourceId: String, mangaUrl: String, title: String): String {
        _state.value.selectedSeriesLink?.let { link ->
            val key = MangaSourceCatalog.identityKey(sourceId, mangaUrl)
            if (link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == key }) {
                return link.seriesKey
            }
        }
        return seriesLinksStore.seriesKeyFor(sourceId, mangaUrl, title)
    }

    /** Cambio fonte dal selettore: ricarica la scheda e ricorda la scelta per la serie. */
    fun switchSource(option: SourceOptionUi) {
        val link = _state.value.selectedSeriesLink ?: return
        if (option.sourceId == _state.value.selected?.sourceId) return
        // La voce deve essere un mirror *di questa* serie: una rimasta a schermo da una
        // scheda precedente riscriverebbe il binding del preferito aperto con l'URL di
        // un'altra opera, mandando i tap successivi sulla serie sbagliata.
        val optionKey = MangaSourceCatalog.identityKey(option.sourceId, option.mangaUrl)
        val belongsToSeries = link.sources.any {
            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == optionKey
        }
        if (!belongsToSeries) return
        seriesLinksStore.setPreferredSource(link.seriesKey, option.sourceId)
        updateState {
            copy(selectedSeriesLink = link.copy(preferredSourceId = option.sourceId))
        }
        // Se la serie è tra i preferiti, la scelta vale anche per lei: `sourceId`/`mangaUrl`
        // del preferito sono "da dove la leggo adesso", quindi riaprirla dai Preferiti deve
        // portare sulla fonte appena scelta, non su quella con cui era stata aggiunta.
        updateFavoriteBinding(link.seriesKey, option.sourceId, option.mangaUrl)
        selectManga(
            MangaSearchResult(
                sourceId = option.sourceId,
                title = link.canonicalTitle,
                mangaUrl = option.mangaUrl,
                coverUrl = link.coverUrl,
            ),
        )
    }

    fun selectManga(result: MangaSearchResult) {
        if (_state.value.currentTab == AppTab.SEARCH) {
            recordRecentSearch(_state.value.query)
        }
        // Percorsi che non passano da selectSeries (preferiti, retry, tutorial): riallinea
        // il link della serie a quello che contiene questo binding, se esiste.
        val currentLink = _state.value.selectedSeriesLink
        val resultKey = MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
        val linkContainsResult = currentLink?.sources
            ?.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == resultKey } == true
        if (!linkContainsResult) {
            // Serie diversa da quella aperta finora: le voci del selettore fonte sono state
            // *di quella* serie e non devono sopravviverle (il menu le riuserebbe, perche'
            // `loadSourceOptions` non ricarica una lista gia' completa).
            sourceOptionsJob?.cancel()
            updateState {
                copy(
                    selectedSeriesLink = seriesLinksStore.linkForBinding(result.sourceId, result.mangaUrl),
                    sourceOptions = emptyList(),
                )
            }
        }
        detailJob?.cancel()
        val seriesKey = currentSeriesKey(result.sourceId, result.mangaUrl, result.title)
        // Serie già tra i preferiti aperta da una fonte non ancora collegata: aggancia il
        // mirror in silenzio. È così che l'app impara le alternative da usare nel fallback,
        // senza chiedere niente all'utente.
        if (seriesKey in _state.value.favoriteSeriesKeys) {
            val link = seriesLinksStore.ensureLink(
                seriesKey = seriesKey,
                title = result.title,
                coverUrl = result.coverUrl,
                binding = SeriesSourceBinding(
                    result.sourceId,
                    result.mangaUrl,
                    System.currentTimeMillis(),
                ),
            )
            updateState { copy(selectedSeriesLink = link) }
        }
        val readChapterIds = libraryRepository.streamingReadChapterIds(
            seriesKey,
            result.sourceId,
            result.mangaUrl,
        )
        updateState {
            copy(
                isLoadingDetails = true,
                errorMessage = null,
                selectedMangaReadChapterIds = readChapterIds,
                selected = result.toDetailsStub(),
                selectedSeriesKey = seriesKey,
            )
        }
        detailJob = viewModelScope.launch {
            try {
                // Aprire una scheda ripiega sui mirror come già fa il controllo aggiornamenti:
                // il preferito è la serie, la fonte è solo il posto da cui la si legge adesso.
                // Senza questo, un sito giù rendeva inapribile una serie agganciata ad altre
                // due fonti perfettamente vive, e il "Riprova" ritentava la stessa fonte morta.
                val tapped = SeriesSourceBinding(result.sourceId, result.mangaUrl)
                val candidates = seriesFetchCandidates(
                    tapped = tapped,
                    link = _state.value.selectedSeriesLink,
                    disabledSourceIds = _state.value.settings.disabledSourceIds,
                    skippedSourceIds = skippedSourceIds(),
                )
                var firstFailure: Pair<SeriesSourceBinding, Throwable>? = null
                val fetched = fetchFromFirstAvailable(
                    candidates = candidates,
                    onFailure = { binding, exc ->
                        if (firstFailure == null) firstFailure = binding to exc
                        recordSourceOutcome(binding.sourceId, exc)
                    },
                ) { binding ->
                    withContext(Dispatchers.IO) {
                        sourceRegistry.resolve(binding.sourceId, binding.mangaUrl)
                            .fetchMangaDetails(binding.mangaUrl)
                    }
                }
                if (fetched == null) {
                    val (binding, exc) = firstFailure
                        ?: (tapped to IOException("Nessuna fonte disponibile"))
                    pendingStreamingChapter = null
                    updateState {
                        copy(
                            isLoadingDetails = false,
                            errorMessage = userFacingErrorMessage(
                                exc = exc,
                                fallback = "Errore nel caricare il manga",
                                sourceName = MangaSourceCatalog.displayName(binding.sourceId),
                            ),
                            errorRetrySearchResult = result,
                        )
                    }
                    return@launch
                }
                recordSourceOutcome(fetched.binding.sourceId, error = null)
                val details = fetched.details
                // Ripiego avvenuto: dillo, altrimenti l'utente vede una fonte diversa da
                // quella che ha toccato senza sapere perché.
                val fallbackNotice = firstFailure
                    ?.takeIf { (failed, _) -> failed.sourceId != fetched.binding.sourceId }
                    ?.let { (failed, _) ->
                        "${MangaSourceCatalog.displayName(failed.sourceId)} non risponde: " +
                            "aperto da ${MangaSourceCatalog.displayName(fetched.binding.sourceId)}"
                    }
                cacheMangaDescription(
                    details.sourceId,
                    details.mangaUrl,
                    details.title,
                    details.description,
                )
                // Se è un preferito, aggiorna in memoria lo stato di pubblicazione per i sort/filtri
                // (utile quando le notifiche non hanno ancora popolato la baseline).
                val detailsKey = currentSeriesKey(details.sourceId, details.mangaUrl, details.title)
                val updatedStatusByKey = if (detailsKey in _state.value.favoriteSeriesKeys) {
                    _state.value.favoriteStatusByKey + (detailsKey to details.status)
                } else {
                    _state.value.favoriteStatusByKey
                }
                updateState {
                    copy(
                        selected = details,
                        selectedMangaReadChapterIds = libraryRepository.streamingReadChapterIds(
                            currentSeriesKey(details.sourceId, details.mangaUrl, details.title),
                            details.sourceId,
                            details.mangaUrl,
                        ),
                        favoriteStatusByKey = updatedStatusByKey,
                        isLoadingDetails = false,
                        errorMessage = fallbackNotice,
                        // La scheda è aperta: un "Riprova" sulla fonte morta non ha più senso.
                        errorRetrySearchResult = null,
                    )
                }

                // Riapertura dalla cronologia: i dettagli servivano solo a ritrovare il
                // capitolo (e i suoi vicini). Se non c'è più — capitolo rimosso dalla fonte,
                // o ripiego su un mirror che non ce l'ha — si resta sulla scheda, che è
                // comunque il posto giusto da cui riprendere a mano.
                consumePendingStreamingChapter(details)?.let { chapter ->
                    reader.openStreamingReader(details, chapter)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        isLoadingDetails = false,
                        errorMessage = userFacingErrorMessage(
                            exc = exc,
                            fallback = "Errore nel caricare il manga",
                            sourceName = MangaSourceCatalog.displayName(result.sourceId),
                        ),
                        errorRetrySearchResult = result,
                    )
                }
            }
        }
    }

    fun showMangaInfo(result: MangaSearchResult) {
        infoJob?.cancel()
        val key = currentSeriesKey(result.sourceId, result.mangaUrl, result.title)
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
                cacheMangaDescription(
                    details.sourceId,
                    details.mangaUrl,
                    details.title,
                    details.description,
                )
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
        sourceOptionsJob?.cancel()
        updateState {
            copy(
                selected = null,
                selectedMangaReadChapterIds = emptySet(),
                isLoadingDetails = false,
                errorMessage = null,
                // Tutto cio' che descrive *quale* serie era aperta se ne va con lei: senza
                // questo la scheda successiva ereditava link, chiave e mirror della precedente.
                selectedSeriesLink = null,
                selectedSeriesKey = null,
                sourceOptions = emptyList(),
            )
        }
    }

    /**
     * Aggiunge/rimuove **la serie** dai preferiti. La fonte da cui arriva il tap conta solo
     * come primo mirror da agganciare: premere la stella su One Piece letto da un'altra fonte
     * agisce sullo stesso preferito, non ne crea un secondo.
     */
    fun toggleFavorite(manga: FavoriteManga, extraMatchKeys: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        val targetSeriesKey = manga.seriesKey.takeIf(String::isNotBlank)
            ?: seriesLinksStore.seriesKeyFor(manga.sourceId, manga.mangaUrl, manga.title)
        val target = manga.copy(seriesKey = targetSeriesKey)
        val matchKeys = target.matchKeys() + extraMatchKeys
        var added = false
        val current = favoritesStore.update { stored ->
            val existingIndex = stored.indexOfFirst { favorite ->
                favorite.matchKeys().any { it in matchKeys }
            }
            added = existingIndex < 0
            if (added) {
                listOf(target.copy(addedAt = now)) + stored
            } else {
                stored.filterIndexed { index, _ -> index != existingIndex }
            }
        }
        if (added) {
            // Ogni preferito nasce con il suo link: è il contenitore dove il fallback e le
            // aperture da altre fonti accumuleranno i mirror alternativi.
            seriesLinksStore.ensureLink(
                seriesKey = targetSeriesKey,
                title = target.title,
                coverUrl = target.coverUrl,
                binding = SeriesSourceBinding(target.sourceId, target.mangaUrl, now),
            )
            rememberAdultSeries(target.title, target.sourceId, target.mangaUrl, targetSeriesKey)
        }
        updateState {
            copy(
                favorites = current,
                favoriteSeriesKeys = favoriteSeriesKeys(current),
            )
        }
        if (added) {
            // Appena aggiunto ai preferiti: se la trama è già in cache, persistila subito.
            mangaDescriptionCache[targetSeriesKey]?.let { description ->
                favoriteDescriptionsStore.write(
                    favoriteDescriptionsStore.read() + (targetSeriesKey to description),
                )
            }
            refreshAdultSeriesRegistry()
        }
        // La stella è appena cambiata: riallinea AniList. Il giro precedente viene annullato,
        // quindi una raffica di stelle toccate in fila produce una sola riconciliazione.
        syncAniListFavorites()
    }

    /**
     * Mette la trama in cache (info istantanea) e, se il manga è tra i preferiti, la persiste su
     * disco così resta pronta anche dopo il riavvio dell'app.
     */
    private fun cacheMangaDescription(
        sourceId: String,
        mangaUrl: String,
        title: String,
        description: String?,
    ) {
        val text = description?.trim()?.takeIf(String::isNotBlank) ?: return
        // Indicizzata per serie: cambiando fonte la trama resta quella giusta.
        val key = currentSeriesKey(sourceId, mangaUrl, title)
        mangaDescriptionCache[key] = text
        if (key in _state.value.favoriteSeriesKeys) {
            favoriteDescriptionsStore.write(favoriteDescriptionsStore.read() + (key to text))
        }
    }

    fun toggleFavoriteSelectedManga() {
        val selected = _state.value.selected ?: return
        toggleFavorite(
            selected.toFavoriteManga(
                seriesKey = currentSeriesKey(selected.sourceId, selected.mangaUrl, selected.title),
            ),
        )
    }

    /**
     * Stella nella schermata di una serie scaricata: magari il manga è stato scaricato senza
     * ricordarsi di metterlo tra i preferiti. No-op se la serie non ha un URL d'origine
     * (identità del preferito) — in quel caso la stella non viene proprio mostrata.
     */
    fun toggleFavoriteSelectedSeries() {
        val series = _state.value.selectedDownloadedSeries ?: return
        val url = series.mangaUrl?.trim()?.takeIf(String::isNotBlank) ?: return
        val favorite = series.toFavoriteManga(
            seriesKey = currentSeriesKey(series.sourceId, url, series.title),
        ) ?: return
        toggleFavorite(favorite)
    }

    fun refreshLibrary(forceRefresh: Boolean = false) = reader.refreshLibrary(forceRefresh)

    /** Download appena accodato dal dettaglio: se la serie è per adulti, entra nel registro. */
    fun onDownloadQueued(details: MangaDetails) {
        rememberAdultSeries(details.title, details.sourceId, details.mangaUrl)
    }

    /**
     * Mette nel registro adulti una serie appena salvata (preferito o download) se una ricerca
     * di questa sessione l'ha vista segnalata per adulti, o se il titolo è esplicito.
     */
    private fun rememberAdultSeries(title: String, sourceId: String, mangaUrl: String, seriesKey: String? = null) {
        val keys = adultRegistryKeys(title, sourceId, mangaUrl, seriesKey)
        if (looksLikeAdultTitle(title) || keys.any { it in adultSearchKeys }) {
            val updated = adultSeriesStore.add(keys)
            updateState { copy(adultSeriesKeys = updated) }
        }
    }

    /**
     * Recupero del registro adulti per preferiti e download già presenti: titoli espliciti, poi
     * AniList per le serie agganciate a un media non ancora controllato (ognuno una volta sola,
     * a blocchi). Gira a prescindere dal filtro, così accendendolo è già tutto pronto. Il giro
     * precedente si annulla: ogni blocco è salvato appena arriva, quindi non si perde nulla.
     */
    private fun refreshAdultSeriesRegistry() {
        val favorites = _state.value.favorites
        val library = _state.value.library
        adultRegistryJob?.cancel()
        adultRegistryJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { recoverAdultSeries(favorites, library) }
            } finally {
                val stored = adultSeriesStore.read()
                if (stored != _state.value.adultSeriesKeys) {
                    updateState { copy(adultSeriesKeys = stored) }
                }
            }
        }
    }

    private fun recoverAdultSeries(favorites: List<FavoriteManga>, library: List<DownloadedSeries>) {
        val candidates = favorites.map { favorite ->
            AdultRecoveryCandidate(
                title = favorite.title,
                keys = adultRegistryKeys(favorite.title, favorite.sourceId, favorite.mangaUrl, favorite.seriesKey),
                aniListId = SeriesIdentity.aniListIdFromKey(favorite.seriesKey)
                    ?: seriesLinksStore.linkFor(favorite.seriesKey)?.aniListId,
            )
        } + library.map { series ->
            val link = series.mangaUrl?.let { seriesLinksStore.linkForBinding(series.sourceId, it) }
            AdultRecoveryCandidate(
                title = series.title,
                keys = adultRegistryKeys(series.title, series.sourceId, series.mangaUrl, link?.seriesKey),
                aniListId = link?.aniListId,
            )
        }
        adultSeriesStore.add(candidates.filter { looksLikeAdultTitle(it.title) }.flatMap { it.keys })

        val checked = adultSeriesStore.readCheckedAniListIds()
        val toCheck = candidates.mapNotNull { it.aniListId }.distinct().filterNot { it in checked }
        for (batch in toCheck.chunked(AniListClient.ADULT_FLAGS_BATCH)) {
            val adultIds = try {
                aniListClient.fetchAdultFlags(batch).filterValues { it }.keys
            } catch (_: Exception) {
                return // Rete o AniList giù: i blocchi mancanti si riprovano al prossimo giro.
            }
            adultSeriesStore.add(
                candidates.filter { it.aniListId in adultIds }
                    .flatMap { it.keys + SeriesIdentity.keyForAniList(it.aniListId!!) },
            )
            adultSeriesStore.addCheckedAniListIds(batch)
        }
    }

    private data class AdultRecoveryCandidate(val title: String, val keys: Set<String>, val aniListId: Int?)

    fun selectDownloadedSeries(series: DownloadedSeries) {
        updateState {
            copy(
                selectedDownloadedSeries = series,
                currentTab = AppTab.LIBRARY,
                // Chiude eventuali overlay sopra: aprendo la serie dalla Gestione memoria
                // (dalle Impostazioni) o dalla classifica della pagina Statistiche si deve
                // atterrare sulla schermata serie.
                showStorageManager = false,
                showSettings = false,
                showStats = false,
                errorMessage = null,
            )
        }
    }

    fun clearDownloadedSelection() {
        reader.dismiss(cancelCleanup = true) {
            copy(
                selectedDownloadedSeries = null,
                errorMessage = null,
            )
        }
    }

    // --- Reader e stato di lettura: vedi [ReaderController] ---------------------------------

    fun setReadingMode(mode: ReadingMode) = reader.setReadingMode(mode)

    fun setReaderReadingMode(mode: ReadingMode) = reader.setReaderReadingMode(mode)

    fun openReader(chapter: DownloadedChapter) = reader.openReader(chapter)

    /**
     * Riapre un capitolo letto in streaming a partire dal suo record di lettura (cronologia,
     * "Riprendi"). Passa dalla scheda della serie perché il reader ha bisogno dell'elenco
     * capitoli per le frecce avanti/indietro: l'utente vede il caricamento del dettaglio e
     * poi il capitolo, e se il capitolo non esiste più resta comunque sulla scheda.
     *
     * I record salvati prima che si annotassero queste coordinate non sono riapribili: la
     * chiave è un hash, l'indirizzo non è ricostruibile. La UI non li rende toccabili.
     */
    fun resumeStreamingChapter(record: ReadChapterMemory) {
        if (!record.canReopenStreaming()) return
        pendingStreamingChapter = PendingStreamingChapter(
            mangaUrl = record.mangaUrl,
            chapterUrl = record.chapterUrl,
        )
        selectManga(
            MangaSearchResult(
                sourceId = record.sourceId,
                title = record.seriesTitle,
                mangaUrl = record.mangaUrl,
                coverUrl = null,
            ),
        )
    }

    /** Il capitolo in attesa, se i dettagli appena caricati sono quelli richiesti e lo contengono ancora. */
    private fun consumePendingStreamingChapter(details: MangaDetails): ChapterEntry? {
        val pending = pendingStreamingChapter ?: return null
        pendingStreamingChapter = null
        if (pending.mangaUrl != details.mangaUrl) return null
        return details.chapters.firstOrNull { it.url == pending.chapterUrl }
    }

    fun openChapterFromDetail(details: MangaDetails, chapter: ChapterEntry) =
        reader.openChapterFromDetail(details, chapter)

    fun openStreamingReader(details: MangaDetails, chapter: ChapterEntry) =
        reader.openStreamingReader(details, chapter)

    fun saveReaderPagePosition(pageIndex: Int, pageCount: Int, allowCompletion: Boolean) =
        reader.saveReaderPagePosition(pageIndex, pageCount, allowCompletion)

    fun closeReader() = reader.closeReader()

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
            Screen.Stats -> closeStats()
            Screen.DiscoverGenre -> closeDiscoverGenre()
            Screen.Detail -> clearSelection()
            Screen.DownloadedSeries -> clearDownloadedSelection()
            Screen.Tabs -> Unit
        }
    }

    fun setChapterRead(chapter: DownloadedChapter, read: Boolean) = reader.setChapterRead(chapter, read)

    fun markChaptersReadUpTo(chapter: DownloadedChapter) = reader.markChaptersReadUpTo(chapter)

    fun markAllChaptersRead(series: DownloadedSeries) = reader.markAllChaptersRead(series)

    fun deleteDownloadedChapter(chapter: DownloadedChapter) = reader.deleteDownloadedChapter(chapter)

    fun deleteReadChapters(series: DownloadedSeries) = reader.deleteReadChapters(series)

    fun deleteDownloadedSeries(series: DownloadedSeries? = _state.value.selectedDownloadedSeries) =
        reader.deleteDownloadedSeries(series)

    fun openPreviousReaderChapter() = reader.openPreviousReaderChapter()

    fun openNextReaderChapter() = reader.openNextReaderChapter()

    /** Riapre il capitolo corrente del reader: CTA "Riprova" quando il fetch delle pagine fallisce. */
    fun retryReaderLoad() = reader.retryReaderLoad()

    fun checkForAppUpdate(force: Boolean = false) = appUpdate.checkForUpdate(force)

    fun dismissAvailableUpdate() = appUpdate.dismiss()

    fun installAvailableUpdate() = appUpdate.install()

    /**
     * Ricerca aggregata sulle fonti dello scope attivo: tutte (chip "Tutte" o ponte
     * AniList→fonti della tab Scopri) o solo quelle di una lingua (chip "Italiano"/"English").
     * Lo stesso titolo può esistere su fonti diverse e l'utente sceglie quale scaricare.
     * I risultati sono combinati alternando le fonti (vedi
     * [MangaSourceCatalog.interleaveBySource]), non accodati a blocchi.
     *
     * Ogni fonte è interrogata in parallelo e **pubblica appena risponde**: aspettare che
     * finiscano tutte significa tenere in ostaggio sette fonti veloci per una lenta. È il caso
     * che si è visto davvero con l'origin di VyManga giù, con Cloudflare che rispondeva 522
     * dopo venti secondi: la barra di caricamento sembrava non finire mai.
     *
     * Le difese sono tre, in fila: le fonti che l'interruttore automatico sta saltando non si
     * interrogano proprio ([isSourceSkipped]), chi risponde troppo tardi viene mollato
     * ([SOURCE_SEARCH_BUDGET_MILLIS]), e ogni esito aggiorna la salute della fonte. Il
     * fallimento della singola fonte resta silenzioso: si parla di errore solo se falliscono
     * **tutte**, altrimenti l'utente vedrebbe un allarme sopra risultati validi.
     */
    private fun runAggregatedSearch(query: String) {
        searchJob?.cancel()
        updateState { copy(isSearching = true, searchError = null, errorMessage = null) }
        searchJob = viewModelScope.launch {
            val settings = _state.value.settings
            val now = System.currentTimeMillis()
            val health = _state.value.sourceHealth
            val queried = sourcesToQuery(
                descriptors = MangaSourceCatalog.descriptorsForSearch(
                    scope = settings.searchScope,
                    disabledSourceIds = settings.disabledSourceIds,
                    hideAdultContent = settings.hidesAdultContent(),
                ),
                health = health,
                now = now,
            )

            // Risultati per fonte, riempiti man mano. L'ordine è quello del catalogo, così
            // l'interleave resta stabile mentre le fonti arrivano in ordine sparso.
            val resultsBySource = LinkedHashMap<String, List<MangaSearchResult>>()
            var aniCandidates = emptyList<AniListManga>()
            var failures = 0
            var lastFailure: Throwable? = null

            fun publish(searching: Boolean) {
                val interleaved = MangaSourceCatalog.interleaveBySource(
                    queried.mapNotNull { resultsBySource[it.id] },
                )
                val pinned = pendingAniListPick?.let(::listOf).orEmpty()
                // Errore solo se non ha risposto **nessuno**: con anche una sola fonte viva,
                // zero risultati significa "non ce l'ha nessuno", non "la ricerca è rotta".
                val friendly = lastFailure
                    ?.takeIf { failures == queried.size }
                    ?.let { userFacingErrorMessage(it, "Errore di ricerca") }
                val grouped = SeriesGrouping.groupResults(interleaved, pinned + aniCandidates)
                adultSearchKeys += adultSearchRegistryKeys(interleaved, grouped, pinned + aniCandidates)
                // Filtro per adulti: il segnale arriva dai candidati AniList di questa stessa
                // ricerca, quindi si riapplica a ogni pubblicazione (AniList può arrivare dopo).
                val visible = if (_state.value.settings.hidesAdultContent()) {
                    filterAdultSearchResults(interleaved, grouped, pinned + aniCandidates)
                } else {
                    FilteredSearchResults(interleaved, grouped)
                }
                updateState {
                    copy(
                        results = visible.results,
                        groupedResults = visible.groups,
                        isSearching = searching,
                        searchError = if (searching) searchError else friendly,
                    )
                }
            }

            // AniList in parallelo al fan-out: serve solo a raggruppare. Un suo fallimento —
            // o un suo ritardo, con lo stesso budget delle fonti — degrada al raggruppamento
            // per titolo, mai a ricerca rotta e mai a barra che non finisce.
            val aniListCall = viewModelScope.async(Dispatchers.IO) {
                runCatching { aniListClient.searchManga(query) }.getOrDefault(emptyList())
            }
            val aniListJob = launch {
                val candidates = withTimeoutOrNull(SOURCE_SEARCH_BUDGET_MILLIS) { aniListCall.await() }
                    ?: run {
                        aniListCall.cancel()
                        emptyList()
                    }
                aniCandidates = candidates
                if (resultsBySource.isNotEmpty()) publish(searching = true)
            }

            searchSourcesIncrementally(
                sourceIds = queried.map { it.id },
                detachedScope = viewModelScope,
                search = { sourceId ->
                    withContext(Dispatchers.IO) {
                        sourceRegistry.requireById(sourceId).searchManga(query)
                    }
                },
                onSourceDone = { sourceId, outcome ->
                    outcome.exceptionOrNull()?.let {
                        lastFailure = it
                        failures++
                    }
                    recordSourceOutcome(sourceId, outcome.exceptionOrNull())
                    resultsBySource[sourceId] = outcome.getOrDefault(emptyList())
                    publish(searching = true)
                },
            )
            aniListJob.join()
            publish(searching = false)
        }
    }

    /**
     * Registra l'esito di un tentativo verso una fonte e muove l'interruttore automatico.
     * [error] `null` = ha risposto. Solo i guasti "di fonte" contano ([isSourceOutage]): un
     * 404 o un parsing storto non dicono che il sito è giù.
     */
    private fun recordSourceOutcome(sourceId: String, error: Throwable?) {
        if (error != null && !isSourceOutage(error)) return
        val now = System.currentTimeMillis()
        val current = _state.value.sourceHealth
        val updated = if (error == null) {
            // Niente da riscrivere se la fonte era già sana: evita un write a ogni ricerca.
            if (current[sourceId] == null) return
            current - sourceId + (sourceId to recordSourceProbeSuccess(now))
        } else {
            current + (sourceId to recordSourceProbeFailure(current[sourceId], now))
        }
        sourceHealthStore.write(updated)
        updateState { copy(sourceHealth = updated) }
    }

    /** Fonti che l'interruttore automatico sta saltando in questo momento. */
    private fun skippedSourceIds(): Set<String> {
        val now = System.currentTimeMillis()
        return _state.value.sourceHealth
            .filterValues { isSourceSkipped(it, now) }
            .keys
    }

    // --- Tab Scopri e vetrine della Home: vedi [HomeFeedController] -------------------------

    fun loadDiscovery(forceRefresh: Boolean = false) = homeFeed.loadDiscovery(forceRefresh)

    fun loadRecommendations(forceRefresh: Boolean = false) = homeFeed.loadRecommendations(forceRefresh)

    fun refreshHomeFeeds() = homeFeed.refreshHomeFeeds()

    fun openDiscoverGenre(genre: DiscoverGenre) = homeFeed.openDiscoverGenre(genre)

    fun loadDiscoverGenre(genre: DiscoverGenre) = homeFeed.loadDiscoverGenre(genre)

    fun closeDiscoverGenre() = homeFeed.closeDiscoverGenre()

    fun showDiscoveryInfo(manga: AniListManga) = homeFeed.showDiscoveryInfo(manga)

    fun dismissDiscoveryInfo() = homeFeed.dismissDiscoveryInfo()

    /**
     * Ponte AniList→fonti: prende il titolo del manga scoperto (English→romaji) e lo cerca su
     * tutte le fonti reali, portando l'utente nella tab Cerca. Rispetta il parental control
     * (Cerca passa dallo sblocco), riusando [selectTab].
     */
    fun onPickAniListManga(manga: AniListManga) {
        val title = manga.searchTitle() ?: return
        pendingAniListPick = manga
        updateSettings { it.copy(searchScope = SearchScope.ALL) }
        updateState {
            copy(
                query = title,
                // Richiesta esplicita: va rifatta anche se la query non cambia, altrimenti
                // ritoccare lo stesso consigliato lascia la ricerca in caricamento per sempre.
                searchRequestId = searchRequestId + 1,
                results = emptyList(),
                groupedResults = emptyList(),
                isSearching = true,
                searchError = null,
                errorMessage = null,
                discovery = discovery.copy(
                    info = null,
                    selectedGenre = null,
                    genreResults = emptyList(),
                    isLoadingGenre = false,
                    genreError = null,
                ),
            )
        }
        selectTab(AppTab.SEARCH)
    }

    /** App in background: salva le letture e ridisegna il widget "Continua a leggere". */
    fun onAppBackgrounded() {
        val write = reader.persistReadingMemory()
        val app = getApplication<Application>()
        viewModelScope.launch {
            // Il widget legge la memoria dalle prefs: prima deve essere arrivata su disco.
            write?.join()
            ReadingWidget.updateAll(app)
        }
    }

    /**
     * Tocco sul widget: riapre l'ultima lettura in corso, la stessa del blocco "Riprendi" della
     * Home. La libreria viene riletta prima, perché all'apertura a freddo non è ancora caricata.
     */
    fun resumeLatestReading() {
        viewModelScope.launch {
            reader.reloadLibrarySnapshot()
            when (val target = computeHomeResume(_state.value.library, _state.value.readingMemory)?.target) {
                is ResumeTarget.Downloaded -> reader.openReader(target.chapter)
                is ResumeTarget.Streaming -> resumeStreamingChapter(target.memory)
                null -> Unit
            }
        }
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

    // --- Tracking AniList: vedi [AniListController] ----------------------------------------

    /** Avvia il login AniList: ricorda lo `state` monouso e restituisce l'URL da aprire. */
    fun beginAniListLogin(): String = aniList.beginLogin()

    fun onAniListAuthRedirect(fragment: String?) = aniList.onAuthRedirect(fragment)

    fun syncAniListAccountState() = aniList.syncAccountState()

    fun disconnectAniList() = aniList.disconnect()

    fun setAniListSyncEnabled(enabled: Boolean) {
        updateSettings { it.copy(aniListSyncEnabled = enabled) }
    }

    fun setAniListFavoritesSyncEnabled(enabled: Boolean) {
        updateSettings { it.copy(aniListFavoritesSyncEnabled = enabled) }
        // Accendendolo si vuole vedere subito l'effetto, non al prossimo avvio.
        if (enabled) syncAniListFavorites()
    }

    fun syncAniListFavorites() = aniList.syncFavorites()

    fun openAniListMatch() = aniList.openMatch()

    fun onAniListMatchQueryChange(query: String) = aniList.onMatchQueryChange(query)

    fun submitAniListMatchSearch() = aniList.submitMatchSearch()

    fun dismissAniListMatch() = aniList.dismissMatch()

    fun confirmAniListMatch(media: AniListManga) = aniList.confirmMatch(media)

    fun openAniListTracker() = aniList.openTracker()

    fun dismissAniListTracker() = aniList.dismissTracker()

    fun unlinkAniListTracking() = aniList.unlinkTracking()

    fun saveAniListEntry(status: AniListListStatus?, progress: Int?, score: Double?) =
        aniList.saveEntry(status, progress, score)

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

    /** Densità globale delle card (Grande/Normale/Compatta), come il tema. */
    fun setCardDensity(density: CardDensity) = updateSettings {
        it.copy(cardDensity = density)
    }

    fun setAnimationLevel(level: AnimationLevel) = updateSettings {
        it.copy(animationLevel = level)
    }

    /**
     * Mostra/nasconde la tab Home. Spegnendola mentre si è sulla Home si atterra su Cerca
     * (o Libreria sotto parental control, dove Cerca è dietro il PIN) senza passare da
     * [selectTab], che scatenerebbe lo sblocco.
     */
    fun setShowHomeTab(enabled: Boolean) {
        updateSettings { it.copy(showHomeTab = enabled) }
        if (!enabled && _state.value.currentTab == AppTab.HOME) {
            val fallback = if (_state.value.settings.parentalControlEnabled) {
                AppTab.LIBRARY
            } else {
                AppTab.SEARCH
            }
            updateState { copy(currentTab = fallback) }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _state.value.settings
        val updated = transform(current)
        if (updated == current) return
        updateState { copy(settings = updated) }
        settingsStore.persist(updated)
    }

    /**
     * Unico punto di scrittura dello stato. Usa `update` (compare-and-set) invece di
     * `_state.value = _state.value.transform()`: quest'ultimo e' una lettura-modifica-scrittura
     * che perde aggiornamenti se due chiamate si incrociano. Tutte le trasformazioni sono
     * `copy` pure (o letture idempotenti da uno store), quindi rieseguirle in caso di contesa
     * non ha effetti collaterali.
     */
    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        _state.update(transform)
    }

    companion object {
        private const val DEBOUNCE_MS = 350L
        private const val READ_NOW_CHAPTER_COUNT = 3
    }
}

/** Capitolo streaming da aprire quando arriveranno i dettagli della sua serie. */
private data class PendingStreamingChapter(
    val mangaUrl: String,
    val chapterUrl: String,
)

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
