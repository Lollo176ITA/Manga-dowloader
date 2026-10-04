package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.anilist.AniListStore
import com.lorenzo.mangadownloader.data.anilist.AniListTracking
import com.lorenzo.mangadownloader.data.anilist.AniListViewer
import com.lorenzo.mangadownloader.data.anilist.UnmatchedAniListFavorite
import com.lorenzo.mangadownloader.data.anilist.visibleUnmatchedAniListFavorites
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.model.ReaderPage
import com.lorenzo.mangadownloader.data.model.ReadingMode
import com.lorenzo.mangadownloader.data.model.ThemeMode
import com.lorenzo.mangadownloader.data.model.canonicalKey
import com.lorenzo.mangadownloader.data.model.identityKey
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.data.sources.SearchScope
import com.lorenzo.mangadownloader.data.sources.SourceReachability
import com.lorenzo.mangadownloader.data.store.FavoriteSeenState
import com.lorenzo.mangadownloader.data.store.FavoriteSourceNotice
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SeriesLink
import com.lorenzo.mangadownloader.data.update.AppUpdateInfo
import com.lorenzo.mangadownloader.domain.home.DEFAULT_HOME_BLOCK_ORDER
import com.lorenzo.mangadownloader.domain.home.DiscoverGenre
import com.lorenzo.mangadownloader.domain.home.HomeBlock
import com.lorenzo.mangadownloader.domain.isAdultContent
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ReadingDayStats
import com.lorenzo.mangadownloader.domain.series.FavoriteReadingState
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves
import com.lorenzo.mangadownloader.domain.series.FavoriteSort
import com.lorenzo.mangadownloader.domain.series.FavoritesSeriesMigration
import com.lorenzo.mangadownloader.domain.series.GroupedSearchResult
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import com.lorenzo.mangadownloader.domain.withoutAdultContent
import com.lorenzo.mangadownloader.ui.components.CardDensity
import com.lorenzo.mangadownloader.ui.library.LibrarySort
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode

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
fun Map<String, FavoriteSeenState>.toStatusMap(): Map<String, MangaPublicationStatus> =
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
    val settings: AppSettings = AppSettings(),
    val isBiometricAvailable: Boolean = false,
    val isParentalAuthInProgress: Boolean = false,
    val parentalPinSetupState: ParentalPinSetupState? = null,
    val parentalPinEntryState: ParentalPinEntryState? = null,
    val biometricPromptRequest: ParentalBiometricPromptRequest? = null,
    val tutorialState: TutorialUiState = TutorialUiState(),
    val errorMessage: String? = null,
)

fun AppSettings.shouldStartTutorial(favorites: List<FavoriteManga>): Boolean {
    return !tutorialCompleted && favorites.isEmpty()
}

fun AppSettings.shouldAutoCompleteTutorial(favorites: List<FavoriteManga>): Boolean {
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
