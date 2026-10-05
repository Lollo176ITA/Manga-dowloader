package com.lorenzo.mangadownloader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorenzo.mangadownloader.app.AppTab
import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.app.Screen
import com.lorenzo.mangadownloader.app.canHandleBack
import com.lorenzo.mangadownloader.app.currentScreen
import com.lorenzo.mangadownloader.app.hidesAdultContent
import com.lorenzo.mangadownloader.app.saveableScreenKey
import com.lorenzo.mangadownloader.app.tabPageIndex
import com.lorenzo.mangadownloader.app.unmatchedAniListFavoritesToShow
import com.lorenzo.mangadownloader.app.visibleTabs
import com.lorenzo.mangadownloader.app.withoutAdultContent
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.anilist.AniListScoreFormat
import com.lorenzo.mangadownloader.data.backup.BackupRestoreMode
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.model.toSearchResult
import com.lorenzo.mangadownloader.data.store.unseenCount
import com.lorenzo.mangadownloader.domain.home.DiscoverGenre
import com.lorenzo.mangadownloader.domain.series.LibraryMatching
import com.lorenzo.mangadownloader.domain.series.favoriteReadingStatesByKey
import com.lorenzo.mangadownloader.ui.anilist.AniListMatchDialog
import com.lorenzo.mangadownloader.ui.anilist.AniListTrackerDialog
import com.lorenzo.mangadownloader.ui.components.AppBottomBar
import com.lorenzo.mangadownloader.ui.components.AppTopBar
import com.lorenzo.mangadownloader.ui.components.AvailableUpdateDialog
import com.lorenzo.mangadownloader.ui.components.ConfirmationDialog
import com.lorenzo.mangadownloader.ui.components.LocalCardDensity
import com.lorenzo.mangadownloader.ui.components.NotificationPermissionRationaleDialog
import com.lorenzo.mangadownloader.ui.components.ParentalPinEntryDialog
import com.lorenzo.mangadownloader.ui.components.ParentalPinSetupDialog
import com.lorenzo.mangadownloader.ui.components.showAutoDismissSnackbar
import com.lorenzo.mangadownloader.ui.detail.DetailScreen
import com.lorenzo.mangadownloader.ui.favorites.FavoritesScreen
import com.lorenzo.mangadownloader.ui.history.HistoryScreen
import com.lorenzo.mangadownloader.ui.history.StatsScreen
import com.lorenzo.mangadownloader.ui.home.DiscoverGenreScreen
import com.lorenzo.mangadownloader.ui.home.HomeScreen
import com.lorenzo.mangadownloader.ui.info.ChangelogScreen
import com.lorenzo.mangadownloader.ui.library.DownloadedSeriesScreen
import com.lorenzo.mangadownloader.ui.library.LibraryScreen
import com.lorenzo.mangadownloader.ui.library.rememberDownloadWorkUiState
import com.lorenzo.mangadownloader.ui.reader.ReaderScreen
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode
import com.lorenzo.mangadownloader.ui.search.SearchLockedContent
import com.lorenzo.mangadownloader.ui.search.SearchScreen
import com.lorenzo.mangadownloader.ui.settings.BackupScreen
import com.lorenzo.mangadownloader.ui.settings.SettingsScreen
import com.lorenzo.mangadownloader.ui.settings.StorageScreen
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import com.lorenzo.mangadownloader.ui.tutorial.TutorialAnchor
import com.lorenzo.mangadownloader.ui.tutorial.TutorialOverlay
import com.lorenzo.mangadownloader.ui.updates.UpdatesScreen
import kotlinx.coroutines.launch

/**
 * La radice dell'app, uguale su ogni piattaforma: tema, navigazione tra schermate, snackbar e
 * dialoghi. Ciò che tocca il sistema operativo passa da [host].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun App(viewModel: MangaViewModel, host: PlatformHost) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MangaDownloaderTheme(
        themeMode = state.settings.themeMode,
        useDynamicColor = state.settings.useDynamicColor,
    ) {
        // Densità globale delle card (impostazione stile tema): fornita qui alla radice,
        // le card condivise la leggono via LocalCardDensity senza parametri da infilare.
        CompositionLocalProvider(LocalCardDensity provides state.settings.cardDensity) {
            MangaDownloaderAppContent(state = state, viewModel = viewModel, host = host)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun MangaDownloaderAppContent(
    state: MangaUiState,
    viewModel: MangaViewModel,
    host: PlatformHost,
) {
    val platformUi = LocalPlatformUi.current
    // Etichette di lettura automatiche dei preferiti, derivate dalla libreria scaricata.
    val favoriteReadingStates = remember(state.favorites, state.library) {
        favoriteReadingStatesByKey(state.favorites, state.library)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val downloadWorkUiState = rememberDownloadWorkUiState(viewModel, snackbarHostState)
    val downloadStatuses = downloadWorkUiState.statuses
    val scope = rememberCoroutineScope()
    // Conserva lo stato salvabile (posizione di scroll in testa) di ogni schermata quando
    // viene smontata navigando altrove, e lo ripristina al ritorno. Vive qui, alla radice
    // dei contenuti, così sopravvive a tutte le transizioni tra schermate.
    val screenStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        // Dove il retry è naturale (fetch dei dettagli fallito) la snackbar offre "Riprova",
        // che rilancia lo stesso manga senza dover ripetere la ricerca.
        val retryResult = state.errorRetrySearchResult
        scope.launch {
            val result = snackbarHostState.showAutoDismissSnackbar(
                message = message,
                actionLabel = if (retryResult != null) "Riprova" else null,
            )
            viewModel.dismissError()
            if (result == SnackbarResult.ActionPerformed && retryResult != null) {
                viewModel.selectManga(retryResult)
            }
        }
    }

    val requestNotificationsPermission = host.rememberNotificationsPermissionRequest { }

    // Cose che possono cambiare fuori dall'app e vanno ricontrollate a ogni ritorno in
    // foreground: il permesso notifiche (revocabile dalle impostazioni di sistema, altrimenti
    // l'avviso sotto "Notifiche preferiti" mentirebbe) e l'account AniList, visto che il
    // ritorno in primo piano è tipicamente il rientro dal browser dell'OAuth.
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsPermissionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsPermissionTick++
                viewModel.syncAniListAccountState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // La lettura fuori da remember osserva anche gli aggiornamenti asincroni UIKit.
    val currentNotificationsPermission = host.notificationsAllowed()
    val notificationsPermissionGranted = remember(notificationsPermissionTick, currentNotificationsPermission) {
        currentNotificationsPermission
    }

    // Esito del permesso per "Notifiche preferiti": il toggle si accende SOLO se il
    // permesso viene concesso; se negato resta spento e la snackbar porta alle
    // impostazioni di sistema (al secondo rifiuto Android non mostra più il prompt).
    val requestFavoriteNotificationsPermission = host.rememberNotificationsPermissionRequest { granted ->
        notificationsPermissionTick++
        if (granted) {
            viewModel.setFavoriteNotificationsEnabled(true)
        } else {
            scope.launch {
                val result = snackbarHostState.showAutoDismissSnackbar(
                    message = "Le notifiche sono bloccate per l'app",
                    actionLabel = "Impostazioni",
                )
                if (result == SnackbarResult.ActionPerformed) {
                    host.openNotificationSettings()
                }
            }
        }
    }

    // Backup: la modalità di import scelta viene ricordata tra il tap e il ritorno dal
    // selettore di file. La sostituzione passa da una conferma esplicita.
    // rememberSaveable: la scelta MERGE/REPLACE deve sopravvivere a una morte del processo
    // mentre il selettore di file è in primo piano.
    var backupImportMode by rememberSaveable { mutableStateOf(BackupRestoreMode.MERGE) }
    var showReplaceBackupConfirm by rememberSaveable { mutableStateOf(false) }
    val backupPickers = host.rememberBackupPickers(
        onExport = viewModel::exportBackup,
        onImport = { document -> viewModel.importBackup(document, backupImportMode) },
    )

    // Niente più richiesta "alla cieca" del permesso notifiche al primo avvio (si impilava
    // sul dialogo di benvenuto e portava a negazioni riflesse): la chiediamo nei momenti in
    // cui il valore è evidente — fine del tutorial (con spiegazione, qui sotto), avvio di un
    // download lungo e attivazione delle notifiche preferiti.
    var showNotificationsRationale by rememberSaveable { mutableStateOf(false) }
    val maybeAskNotificationsPermission: () -> Unit = {
        if (!host.notificationsAllowed()) {
            showNotificationsRationale = true
        }
    }
    if (showNotificationsRationale) {
        NotificationPermissionRationaleDialog(
            onAccept = {
                showNotificationsRationale = false
                requestNotificationsPermission()
            },
            onDismiss = { showNotificationsRationale = false },
        )
    }

    LaunchedEffect(Unit) {
        // Aperti dal tap su una notifica di download: porta direttamente alla Libreria.
        // La richiesta viene consumata così non riscatta a ogni ricomposizione/rotazione.
        if (host.consumeOpenLibraryRequest()) {
            viewModel.clearSelection()
            viewModel.selectTab(AppTab.LIBRARY)
        }
        viewModel.checkForAppUpdate()
    }

    // Senza POST_NOTIFICATIONS (Android 13+) il worker non può promuoversi a foreground
    // service: i download lunghi vengono fermati dal sistema (~10 minuti) e ripresi solo a
    // singhiozzo sotto Doze. Richiediamo il permesso nel momento in cui serve davvero
    // (avvio di un download) e, se negato, spieghiamo la conseguenza. Il worker ri-controlla
    // il permesso a ogni aggiornamento di progresso, quindi concederlo a download già
    // partito lo promuove comunque a foreground.
    val requestDownloadNotificationsPermission = host.rememberNotificationsPermissionRequest { granted ->
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Senza notifiche i download lunghi possono essere interrotti dal sistema",
                )
            }
        }
    }

    val onStartDownload: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit = { details, startChapter, endChapter ->
        val firstUrl = startChapter.url.trim()
        val lastUrl = endChapter.url.trim()
        if (firstUrl.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("URL non valido")
            }
        } else {
            try {
                if (!host.notificationsAllowed()) {
                    requestDownloadNotificationsPermission()
                }
                viewModel.startDownload(
                    ChapterDownloadRequest(
                        firstUrl = firstUrl,
                        lastUrl = lastUrl,
                        sourceId = details.sourceId,
                        seriesTitle = details.title,
                        mangaUrl = details.mangaUrl,
                        coverUrl = details.coverUrl,
                    ),
                )
                scope.launch {
                    // Azione "Libreria": progresso, coda e stop vivono nella tab Libreria;
                    // un tap ci porta dove si monitora la coda appena creata (chiude il dettaglio).
                    val result = snackbarHostState.showAutoDismissSnackbar(
                        message = if (startChapter.url == endChapter.url) {
                            "Download aggiunto in coda: ${startChapter.displayLabel()}"
                        } else {
                            "Download aggiunto in coda: ${startChapter.displayLabel()} - ${endChapter.displayLabel()}"
                        },
                        actionLabel = "Libreria",
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.clearSelection()
                        viewModel.selectTab(AppTab.LIBRARY)
                    }
                }
            } catch (exc: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(exc.message ?: "Impossibile avviare il download")
                }
            }
        }
    }

    val visibleTabs = state.visibleTabs()
    val pagerState = rememberPagerState(
        initialPage = state.tabPageIndex(state.currentTab),
        // Il set di tab è fisso (Home·Cerca·Preferiti·Libreria): il conteggio è costante.
        pageCount = { visibleTabs.size },
    )
    val showPager = state.currentScreen() == Screen.Tabs
    // derivedStateOf: lo stato di scorrimento del pager cambia a ogni gesto, anche verticale;
    // letto qui direttamente ricomponeva tutta la radice due volte per swipe. Così la radice si
    // ricompone solo quando cambia davvero la tab visibile.
    val currentTab = state.currentTab
    val visiblePagerTab by remember(pagerState, visibleTabs, showPager, currentTab) {
        derivedStateOf {
            when {
                !showPager -> currentTab
                pagerState.isScrollInProgress -> visibleTabs.getOrElse(pagerState.targetPage) { currentTab }
                else -> visibleTabs.getOrElse(pagerState.currentPage) { currentTab }
            }
        }
    }
    val canHandleBack = state.canHandleBack()

    // Quando si arriva sulla tab Preferiti (dove è visibile il badge "Aggiornamenti"), rilegge
    // il feed così il conteggio riflette gli eventi scritti dal worker mentre l'app era aperta.
    LaunchedEffect(visiblePagerTab) {
        when (visiblePagerTab) {
            AppTab.FAVORITES -> viewModel.refreshUpdatesFeed()
            AppTab.HOME -> {
                // La Home mostra ripresa lettura e novità: entrambe vanno rinfrescate quando
                // diventa visibile (la libreria si ri-scansiona solo entrando in Libreria).
                viewModel.refreshLibrary()
                viewModel.refreshUpdatesFeed()
            }
            else -> {}
        }
    }

    val privacyDimAlpha = readerPrivacyDimAlpha(
        enabled = state.readerChapter != null && state.settings.privacyBrightnessEnabled,
        brightness = state.settings.readerBrightness,
    )
    // Keyed sull'apertura del reader (null → non-null), non sulla singola relativePath:
    // così il fullscreen si azzera solo quando apri il reader da fuori, mentre resta
    // invariato passando a capitolo successivo/precedente.
    var isReaderFullscreen by remember(state.readerChapter != null) { mutableStateOf(false) }

    // Modalità modifica della Home: vive qui (non in HomeScreen) perché la top bar la comanda.
    var homeEditMode by rememberSaveable { mutableStateOf(false) }

    // Nascondere la tab Home chiude la modalità modifica: al ritorno la Home riparte normale.
    LaunchedEffect(state.settings.showHomeTab) {
        if (!state.settings.showHomeTab) homeEditMode = false
    }

    // Vero schermo intero: quando il reader è in fullscreen nascondiamo anche le barre
    // di sistema (status + navigation), così la pagina occupa davvero tutto lo schermo.
    // Si esce con un tap (toggle) o con lo swipe dal bordo, che le ripristina da solo.
    // Durante la lettura i tocchi sono rari (pagine lunghe, tavole dense): senza questo
    // flag il timeout di sistema spegne lo schermo a metà pagina. Attivo solo a reader
    // aperto (e con l'impostazione dedicata accesa); onDispose lo ripulisce sempre.
    // Con le pagine doppie ruotate il telefono si gira per leggerle: se ruotasse anche
    // l'app, l'immagine tornerebbe sdraiata e la modalità non servirebbe a niente.
    val readerLocksPortrait = state.readerChapter != null &&
        state.readerSpreadPageMode == SpreadPageMode.ROTATE
    host.SystemEffects(
        SystemEffectsRequest(
            allowLandscapeRotation = state.settings.allowLandscapeRotation && !readerLocksPortrait,
            biometricRequest = state.biometricPromptRequest,
            readerOpen = state.readerChapter != null,
            readerFullscreen = isReaderFullscreen,
            keepReaderScreenOn = state.settings.keepScreenOnEnabled,
            onBiometricSucceeded = viewModel.parental::onBiometricSucceeded,
            onUsePinInstead = viewModel.parental::usePinInsteadOfBiometric,
            onBiometricCancelled = viewModel.parental::cancelBiometric,
        ),
    )

    // Porta l'utente alla ricerca dagli stati vuoti (es. Libreria/Preferiti vuoti):
    // trasforma il vicolo cieco in un passo successivo chiaro. Rispetta il lock parentale.
    val goToSearchTab: () -> Unit = {
        viewModel.selectTab(AppTab.SEARCH)
        val requiresSearchUnlock = state.settings.parentalControlEnabled &&
            state.currentTab != AppTab.SEARCH
        if (!requiresSearchUnlock) {
            scope.launch {
                pagerState.animateScrollToPage(state.tabPageIndex(AppTab.SEARCH))
            }
        }
    }

    BackHandler(enabled = canHandleBack) {
        if (isReaderFullscreen) {
            isReaderFullscreen = false
        } else {
            viewModel.handleBack()
        }
    }

    LaunchedEffect(
        state.currentTab,
        state.pendingSearchAccessReturnTab,
        showPager,
    ) {
        if (
            showPager &&
            !pagerState.isScrollInProgress &&
            state.pendingSearchAccessReturnTab == null &&
            pagerState.currentPage != state.tabPageIndex(state.currentTab)
        ) {
            pagerState.animateScrollToPage(state.tabPageIndex(state.currentTab))
        }
    }

    // Pagina e scorrimento letti in snapshotFlow, non come chiavi dell'effetto: come chiavi
    // ricomponevano la radice a ogni inizio e fine di scorrimento.
    val latestVisibleTabs by rememberUpdatedState(visibleTabs)
    val latestCurrentTab by rememberUpdatedState(state.currentTab)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) {
                    val newTab = latestVisibleTabs.getOrElse(page) { latestCurrentTab }
                    if (latestCurrentTab != newTab) {
                        viewModel.selectTab(newTab)
                    }
                }
            }
    }

    TutorialOverlay(
        state = state,
        onFallbackCompleted = {
            viewModel.tutorial.onFallbackCompleted()
            // A tour appena concluso il valore delle notifiche è chiaro: è il momento
            // giusto per chiedere il permesso, con la spiegazione del perché.
            maybeAskNotificationsPermission()
        },
        onAdvancePhase = viewModel.tutorial::advancePhase,
        onTargetTap = { anchor ->
            when (anchor) {
                TutorialAnchor.SEARCH_RESULT_FIRST -> {
                    state.results.firstOrNull()?.let(viewModel::selectManga)
                }
                TutorialAnchor.DETAIL_FAVORITE -> {
                    viewModel.toggleFavoriteSelectedManga()
                }
                TutorialAnchor.FAVORITES_TAB -> {
                    viewModel.selectTab(AppTab.FAVORITES)
                    scope.launch {
                        pagerState.animateScrollToPage(state.tabPageIndex(AppTab.FAVORITES))
                    }
                }
                TutorialAnchor.LIBRARY_TAB -> {
                    viewModel.selectTab(AppTab.LIBRARY)
                    scope.launch {
                        pagerState.animateScrollToPage(state.tabPageIndex(AppTab.LIBRARY))
                    }
                }
                TutorialAnchor.LIBRARY_SERIES_FIRST -> {
                    LibraryMatching.tutorialSampleSeries(state.tutorialState.sample, state.library)
                        ?.let(viewModel::selectDownloadedSeries)
                }
                TutorialAnchor.DOWNLOADED_CHAPTER_FIRST -> {
                    state.selectedDownloadedSeries
                        ?.chapters
                        ?.firstOrNull()
                        ?.let(viewModel::openReader)
                }
                TutorialAnchor.READER_FULLSCREEN -> {
                    viewModel.closeReader()
                }
                TutorialAnchor.SEARCH_TAB,
                TutorialAnchor.SEARCH_BAR,
                TutorialAnchor.SETTINGS,
                TutorialAnchor.DETAIL_DOWNLOAD -> Unit
            }
        },
        onFinish = { keepSample ->
            viewModel.tutorial.onFinish(keepSample)
            maybeAskNotificationsPermission()
        },
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        // Su iOS gli inset sicuri restano presenti anche nascondendo le barre di sistema.
        // Nel reader a schermo intero la pagina deve occupare anche queste aree.
        contentWindowInsets = if (state.readerChapter != null && isReaderFullscreen) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        containerColor = if (state.readerChapter != null && isReaderFullscreen) Color.Black else MaterialTheme.colorScheme.background,
        topBar = {
            if (!(state.readerChapter != null && isReaderFullscreen)) {
                AppTopBar(
                    state = state,
                    visibleTab = visiblePagerTab,
                    onBack = viewModel::handleBack,
                    onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                    onToggleFavoriteSeries = viewModel::toggleFavoriteSelectedSeries,
                    onOpenSettings = viewModel::openSettings,
                    onReaderBrightnessChange = viewModel::previewReaderBrightness,
                    onReaderBrightnessChangeFinished = viewModel::commitReaderBrightness,
                    onSelectReadingMode = viewModel::setReaderReadingMode,
                    onSelectSpreadPageMode = viewModel::setReaderSpreadPageMode,
                    // Leggendo in streaming si può tenere il capitolo: le pagine sono già
                    // state scaricate per mostrarlo, quindi salvarlo è a un tocco invece che
                    // a un giro completo (esci → dettaglio → selettore intervallo).
                    onSaveStreamingChapter = state.readerChapter?.streamingChapter?.let { streaming ->
                        {
                            onStartDownload(
                                MangaDetails(
                                    sourceId = streaming.sourceId,
                                    title = streaming.mangaTitle,
                                    // La copertina correda la serie salvata: si prende dal
                                    // dettaglio da cui siamo entrati, e solo se è la stessa serie.
                                    coverUrl = state.selected
                                        ?.takeIf { it.mangaUrl == streaming.mangaUrl }
                                        ?.coverUrl,
                                    mangaUrl = streaming.mangaUrl,
                                    chapters = streaming.chapters,
                                ),
                                streaming.chapter,
                                streaming.chapter,
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar("Capitolo aggiunto ai download")
                            }
                        }
                    },
                    unseenUpdatesCount = unseenCount(state.favoriteUpdates),
                    onOpenUpdates = viewModel::openUpdates,
                    onMarkAllUpdatesSeen = viewModel::markAllUpdatesSeen,
                    homeEditMode = homeEditMode,
                    onToggleHomeEdit = { homeEditMode = !homeEditMode },
                )
            }
        },
        bottomBar = {
            if (showPager) {
                AppBottomBar(
                    currentTab = visiblePagerTab,
                    favoritesBadgeCount = unseenCount(state.favoriteUpdates),
                    showHomeTab = state.settings.showHomeTab,
                    onSelect = { tab ->
                        viewModel.selectTab(tab)
                        val requiresSearchUnlock = tab == AppTab.SEARCH &&
                            state.settings.parentalControlEnabled &&
                            state.currentTab != AppTab.SEARCH
                        if (!requiresSearchUnlock) {
                            scope.launch {
                                pagerState.animateScrollToPage(state.tabPageIndex(tab))
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val selectedManga = state.selected
        val selectedSeries = state.selectedDownloadedSeries

        // Avvolge la schermata corrente: ne preserva/ripristina lo stato salvabile (scroll)
        // tra una navigazione e l'altra. La chiave distingue le schermate (vedi saveableScreenKey).
        screenStateHolder.SaveableStateProvider(state.saveableScreenKey()) {
        when (state.currentScreen()) {
            Screen.Reader -> {
                // Il capitolo cambia a ogni pagina solo per l'avanzamento, che al reader non
                // serve: `remember` confronta per uguaglianza e restituisce la stessa istanza
                // finché il capitolo resta quello, così ReaderScreen salta la ricomposizione
                // mentre si sfoglia.
                val chapterWithoutProgress = state.readerChapter?.withoutReaderProgress()
                val readerChapter = remember(chapterWithoutProgress) { chapterWithoutProgress }
                ReaderScreen(
                    chapter = readerChapter,
                    previousChapter = state.readerPreviousChapter,
                    nextChapter = state.readerNextChapter,
                    pages = state.readerPages,
                    isLoading = state.isLoadingReader,
                    readingMode = state.readerReadingMode,
                    doubleTapZoomEnabled = state.settings.doubleTapZoomEnabled,
                    spreadRotation = state.readerSpreadPageMode
                        .takeIf { it == SpreadPageMode.ROTATE }
                        ?.let { state.readerReadingMode.spreadRotation },
                    pageSpacing = state.settings.readerPageSpacingDp.dp,
                    navBarVisible = !isReaderFullscreen,
                    padding = innerPadding,
                    initialPageIndex = state.readerInitialPageIndex,
                    onOpenPrevious = viewModel::openPreviousReaderChapter,
                    onOpenNext = viewModel::openNextReaderChapter,
                    onPageVisible = viewModel::saveReaderPagePosition,
                    onToggleFullscreen = { isReaderFullscreen = !isReaderFullscreen },
                    onRetry = viewModel::retryReaderLoad,
                )
            }
            Screen.StorageManager -> {
                StorageScreen(
                    library = state.library,
                    padding = innerPadding,
                    onOpenSeries = viewModel::selectDownloadedSeries,
                    onDeleteSeries = viewModel::deleteDownloadedSeries,
                    onDeleteReadChapters = viewModel::deleteReadChapters,
                )
            }
            Screen.Updates -> {
                UpdatesScreen(
                    events = state.favoriteUpdates,
                    padding = innerPadding,
                    onSelect = viewModel::openMangaFromUpdate,
                    onBrowse = goToSearchTab,
                )
            }
            Screen.History -> {
                HistoryScreen(
                    memory = state.readingMemory,
                    library = state.library,
                    padding = innerPadding,
                    onOpenChapter = viewModel::openReader,
                    onResumeStreamingChapter = viewModel::resumeStreamingChapter,
                )
            }
            Screen.Stats -> {
                StatsScreen(
                    state = state,
                    padding = innerPadding,
                    onOpenSeries = viewModel::selectDownloadedSeries,
                )
            }
            Screen.DiscoverGenre -> {
                DiscoverGenreScreen(
                    discovery = if (state.settings.hidesAdultContent()) {
                        state.discovery.withoutAdultContent()
                    } else {
                        state.discovery
                    },
                    padding = innerPadding,
                    onPick = viewModel::onPickAniListManga,
                    onShowInfo = viewModel::showDiscoveryInfo,
                    onDismissInfo = viewModel::dismissDiscoveryInfo,
                    onRetry = {
                        state.discovery.selectedGenre?.let(viewModel::loadDiscoverGenre)
                    },
                )
            }
            Screen.Backup -> {
                BackupScreen(
                    padding = innerPadding,
                    onExport = { backupPickers.pickExportTarget("manga-downloader-backup.json") },
                    onImportMerge = {
                        backupImportMode = BackupRestoreMode.MERGE
                        backupPickers.pickImportSource()
                    },
                    onImportReplace = { showReplaceBackupConfirm = true },
                )
            }
            Screen.Feedback -> {
                platformUi.reportProblem?.invoke(innerPadding) { ok ->
                        scope.launch {
                            if (ok) {
                                viewModel.closeFeedback()
                                snackbarHostState.showSnackbar("Segnalazione inviata. Grazie!")
                            } else {
                                snackbarHostState.showSnackbar(
                                    if (!platformUi.isFeedbackConfigured) {
                                        "Segnalazioni non configurate in questa build"
                                    } else {
                                        "Invio non riuscito. Controlla la connessione e riprova."
                                    },
                                )
                            }
                        }
                }
            }
            Screen.Settings -> {
                SettingsScreen(
                    settings = state.settings,
                    sourceHealth = state.sourceHealth,
                    isBiometricAvailable = state.isBiometricAvailable,
                    isParentalAuthInProgress = state.isParentalAuthInProgress,
                    notificationsPermissionGranted = notificationsPermissionGranted,
                    aniListViewerName = state.aniList.viewer?.name,
                    isAniListConnecting = state.aniList.isConnecting,
                    padding = innerPadding,
                    onSelectThemeMode = viewModel::setThemeMode,
                    onSelectCardDensity = viewModel::setCardDensity,
                    onToggleDynamicColor = viewModel::setUseDynamicColor,
                    onRestartTutorial = viewModel.tutorial::restart,
                    onConnectAniList = {
                        scope.launch {
                            if (!AniListAuth.isConfigured()) {
                                snackbarHostState.showSnackbar(
                                    "Collegamento AniList non configurato in questa build",
                                )
                                return@launch
                            }
                            if (!host.openUrl(AniListAuth.authorizationUrl())) {
                                snackbarHostState.showSnackbar("Nessun browser disponibile")
                            }
                        }
                    },
                    onDisconnectAniList = viewModel::disconnectAniList,
                    onToggleAniListSync = viewModel::setAniListSyncEnabled,
                    onToggleAniListFavoritesSync =
                        viewModel::setAniListFavoritesSyncEnabled,
                    onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
                    onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
                    onBatchChange = viewModel::setAutoDownloadBatchSize,
                    onToggleSmartCleanup = viewModel::setSmartCleanupEnabled,
                    onSmartCleanupKeepChange = viewModel::setSmartCleanupKeepPreviousChapters,
                    onSelectReadingMode = viewModel::setReadingMode,
                    onSelectSpreadPageMode = viewModel::setSpreadPageMode,
                    onSelectReaderPageSpacing = viewModel::setReaderPageSpacing,
                    onToggleDoubleTapZoom = viewModel::setDoubleTapZoomEnabled,
                    onToggleKeepScreenOn = viewModel::setKeepScreenOnEnabled,
                    onSetSourceEnabled = viewModel::setSourceEnabled,
                    onToggleShowHomeTab = viewModel::setShowHomeTab,
                    onToggleParentalControl = viewModel.parental::setEnabled,
                    onRequestChangeParentalPin = viewModel.parental::requestChangePin,
                    onToggleParentalBiometric = viewModel.parental::setBiometricEnabled,
                    onToggleHideAdultContent = viewModel::setHideAdultContent,
                    onToggleLabs = viewModel::setLabsEnabled,
                    onToggleDownloadDevUpdates = viewModel::setDownloadDevUpdates.takeIf { viewModel.canSelfUpdate },
                    onTogglePrivacyBrightness = viewModel::setPrivacyBrightnessEnabled,
                    onToggleAllowLandscapeRotation = viewModel::setAllowLandscapeRotation,
                    onToggleFavoriteNotifications = { enabled ->
                        if (enabled && !notificationsPermissionGranted) {
                            // L'attivazione vera avviene nel callback del launcher,
                            // solo a permesso concesso: niente switch ON "a vuoto".
                            requestFavoriteNotificationsPermission()
                        } else {
                            viewModel.setFavoriteNotificationsEnabled(enabled)
                        }
                    },
                    onOpenStorageManager = viewModel::openStorageManager,
                    onOpenBackup = viewModel::openBackup,
                    onOpenReportProblem = viewModel::openFeedback.takeIf { platformUi.reportProblem != null },
                    appVersion = viewModel.appVersionName,
                    onOpenChangelog = viewModel::openChangelog,
                )
            }
            Screen.Changelog -> {
                ChangelogScreen(padding = innerPadding)
            }
            Screen.Detail -> if (selectedManga != null) {
                val linkBindings = state.selectedSeriesLink?.sources.orEmpty()
                val downloadedChapterKeys = remember(selectedManga, state.library, linkBindings) {
                    LibraryMatching.downloadedChapterKeys(selectedManga, state.library, linkBindings)
                }
                val readChapterIds = remember(selectedManga, state.library, state.selectedMangaReadChapterIds, linkBindings) {
                    state.selectedMangaReadChapterIds +
                        LibraryMatching.downloadedReadChapterIds(selectedManga, state.library, linkBindings)
                }
                val aniListTracking = remember(selectedManga, state.aniList.trackings, state.selectedSeriesKey) {
                    state.selectedSeriesKey?.let { state.aniList.trackings[it] }
                }
                DetailScreen(
                    details = selectedManga,
                    isLoading = state.isLoadingDetails,
                    padding = innerPadding,
                    downloadedChapterKeys = downloadedChapterKeys,
                    readChapterIds = readChapterIds,
                    autoDownloadEnabled = state.settings.autoDownloadEnabled,
                    showSourceSelector = state.selectedSeriesLink != null,
                    sourceOptions = state.sourceOptions,
                    onOpenSourceMenu = viewModel::loadSourceOptions,
                    onSwitchSource = viewModel::switchSource,
                    showAniListTracking = state.aniList.viewer != null,
                    aniListTracking = aniListTracking,
                    onLinkAniList = viewModel::openAniListMatch,
                    onOpenAniListTracker = viewModel::openAniListTracker,
                    onStart = onStartDownload,
                    onReadChapter = viewModel::openChapterFromDetail,
                    onEnableAutoDownload = { viewModel.setAutoDownloadEnabled(true) },
                )
            }
            Screen.DownloadedSeries -> if (selectedSeries != null) {
                DownloadedSeriesScreen(
                    series = selectedSeries,
                    padding = innerPadding,
                    onOpenChapter = viewModel::openReader,
                    onDeleteChapter = viewModel::deleteDownloadedChapter,
                    onSetChapterRead = viewModel::setChapterRead,
                    onMarkReadUpTo = viewModel::markChaptersReadUpTo,
                )
            }
            Screen.Tabs -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = showPager,
                ) { page ->
                    when (visibleTabs.getOrElse(page) { AppTab.SEARCH }) {
                        AppTab.HOME -> HomeScreen(
                            state = state,
                            editMode = homeEditMode,
                            padding = innerPadding,
                            onResume = viewModel::openReader,
                            onResumeStreamingChapter = viewModel::resumeStreamingChapter,
                            onOpenUpdate = viewModel::openMangaFromUpdate,
                            onOpenAllUpdates = viewModel::openUpdates,
                            onOpenHistory = viewModel::openHistory,
                            onOpenStats = viewModel::openStats,
                            onPickDiscover = viewModel::onPickAniListManga,
                            onShowDiscoverInfo = viewModel::showDiscoveryInfo,
                            onDismissDiscoverInfo = viewModel::dismissDiscoveryInfo,
                            onLoadDiscover = viewModel::loadDiscovery,
                            onLoadRecommendations = viewModel::loadRecommendations,
                            onRefreshFeeds = viewModel::refreshHomeFeeds,
                            onOpenGenre = viewModel::openDiscoverGenre,
                            onSearchFirst = goToSearchTab,
                            onStartTutorial = {
                                viewModel.tutorial.onWelcomeStart()
                                // Il tour interattivo parte dalla tab Cerca (primo spotlight sulla
                                // barra di ricerca): portaci l'utente, rispettando il lock parentale.
                                goToSearchTab()
                            },
                            onDismissTutorial = viewModel.tutorial::onWelcomeSkip,
                            onMoveBlock = viewModel::moveHomeBlock,
                            onSetBlockHidden = viewModel::setHomeBlockHidden,
                        )
                        // Sotto controllo parentale la pagina Cerca si vede solo dopo il PIN: con
                        // uno swipe ci si arriva comunque, e dietro al dialog del PIN restavano
                        // visibili le ricerche precedenti e i loro risultati.
                        AppTab.SEARCH -> if (
                            state.settings.parentalControlEnabled && state.currentTab != AppTab.SEARCH
                        ) {
                            SearchLockedContent(
                                padding = innerPadding,
                                onUnlock = { viewModel.selectTab(AppTab.SEARCH) },
                            )
                        } else {
                            SearchScreen(
                                state = state,
                                padding = innerPadding,
                                onQueryChange = viewModel::onQueryChange,
                                onClearRecentSearches = viewModel::clearRecentSearches,
                                onRefresh = viewModel::submitSearch,
                                onSelectSeries = viewModel::selectSeries,
                                onToggleFavorite = viewModel::toggleFavoriteFromGroup,
                                onShowInfo = viewModel::showMangaInfo,
                                onDismissInfo = viewModel::dismissMangaInfo,
                                onSelectLanguage = viewModel::selectLanguageSearch,
                                onSelectAllSources = viewModel::selectAllSourcesSearch,
                            )
                        }
                        AppTab.FAVORITES -> FavoritesScreen(
                            favorites = state.favorites,
                            query = state.favoritesQuery,
                            filterReadingState = state.favoriteFilterReadingState,
                            sort = state.settings.favoriteSort,
                            statusByKey = state.favoriteStatusByKey,
                            seenByKey = state.favoriteSeenStates,
                            noticesByKey = state.favoriteNotices,
                            readingStateByKey = favoriteReadingStates,
                            padding = innerPadding,
                            onQueryChange = viewModel::onFavoritesQueryChange,
                            onSelect = { favorite ->
                                viewModel.selectManga(favorite.toSearchResult())
                            },
                            onBrowse = goToSearchTab,
                            onSelectSort = viewModel::setFavoriteSort,
                            onSelectReadingState = viewModel::setFavoriteFilterReadingState,
                            shelves = state.favoriteShelves,
                            filterShelfId = state.favoriteFilterShelfId,
                            onSelectShelf = viewModel::setFavoriteFilterShelf,
                            onCreateShelf = viewModel::createFavoriteShelf,
                            onRenameShelf = viewModel::renameFavoriteShelf,
                            onDeleteShelf = viewModel::deleteFavoriteShelf,
                            onSetShelves = viewModel::setShelvesForFavorite,
                            unmatchedAniList = state.unmatchedAniListFavoritesToShow(),
                            // Come i titoli di Scopri: il pager segue da solo il cambio di tab.
                            onPickUnmatched = { viewModel.onPickAniListManga(it.toAniListManga()) },
                            onReadNow = viewModel::readNowFromFavorite,
                            onRemoveFavorite = { favorite ->
                                viewModel.toggleFavorite(favorite)
                                scope.launch {
                                    val result = snackbarHostState.showAutoDismissSnackbar(
                                        message = "Rimosso dai preferiti: ${favorite.title}",
                                        actionLabel = "Annulla",
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.toggleFavorite(favorite)
                                    }
                                }
                            },
                        )
                        AppTab.LIBRARY -> LibraryScreen(
                            state = state,
                            downloadStatuses = downloadStatuses,
                            padding = innerPadding,
                            onOpenSeries = viewModel::selectDownloadedSeries,
                            onDeleteSeries = viewModel::deleteDownloadedSeries,
                            onDeleteReadChapters = viewModel::deleteReadChapters,
                            onQueryChange = viewModel::onLibraryQueryChange,
                            onBrowse = goToSearchTab,
                            onStopDownloads = viewModel::stopAllDownloads,
                            onStopSeriesDownload = { status -> viewModel.stopDownloads(status.workIds) },
                            onResume = viewModel::openReader,
                            onSelectSort = viewModel::setLibrarySort,
                            onMarkAllRead = viewModel::markAllChaptersRead,
                        )
                    }
                }
            }
        }
        }
        }
        if (privacyDimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = privacyDimAlpha)),
            )
        }
    }
    }

    if (showReplaceBackupConfirm) {
        ConfirmationDialog(
            title = "Sostituisci dati",
            text = "I preferiti e le impostazioni attuali verranno rimpiazzati con quelli del backup. Continuare?",
            confirmLabel = "Sostituisci",
            onDismiss = { showReplaceBackupConfirm = false },
            onConfirm = {
                showReplaceBackupConfirm = false
                backupImportMode = BackupRestoreMode.REPLACE
                backupPickers.pickImportSource()
            },
        )
    }

    state.parentalPinSetupState?.let { setupState ->
        ParentalPinSetupDialog(
            state = setupState,
            onPinChange = { viewModel.parental.onPinSetupChange(pin = it) },
            onConfirmPinChange = { viewModel.parental.onPinSetupChange(confirmPin = it) },
            onDismiss = viewModel.parental::dismissPinSetup,
            onConfirm = viewModel.parental::confirmPinSetup,
        )
    }

    state.parentalPinEntryState?.let { pinEntryState ->
        ParentalPinEntryDialog(
            state = pinEntryState,
            onPinChange = viewModel.parental::onPinEntryChange,
            onDismiss = viewModel.parental::dismissPinEntry,
            onConfirm = viewModel.parental::confirmPinEntry,
        )
    }

    state.availableUpdate?.let { update ->
        AvailableUpdateDialog(
            update = update,
            isInstalling = state.isInstallingUpdate,
            onDismiss = viewModel::dismissAvailableUpdate,
            onConfirm = viewModel::installAvailableUpdate,
        )
    }

    state.aniList.match?.let { match ->
        AniListMatchDialog(
            match = match,
            onQueryChange = viewModel::onAniListMatchQueryChange,
            onSearch = viewModel::submitAniListMatchSearch,
            onSelect = viewModel::confirmAniListMatch,
            onDismiss = viewModel::dismissAniListMatch,
        )
    }

    state.aniList.trackerKey
        ?.let { key -> state.aniList.trackings[key] }
        ?.let { tracking ->
            AniListTrackerDialog(
                tracking = tracking,
                scoreFormat = state.aniList.viewer?.scoreFormat ?: AniListScoreFormat.POINT_10,
                isSaving = state.aniList.isSavingEntry,
                onSave = viewModel::saveAniListEntry,
                onUnlink = viewModel::unlinkAniListTracking,
                onOpenOnSite = {
                    if (!host.openUrl(tracking.siteUrl())) {
                        scope.launch { snackbarHostState.showSnackbar("Nessun browser disponibile") }
                    }
                },
                onDismiss = viewModel::dismissAniListTracker,
            )
        }
}

/** Il capitolo senza l'avanzamento di lettura (pagina, totale, ultimo accesso). */
private fun ReaderChapter.withoutReaderProgress(): ReaderChapter = copy(
    readerPageIndex = null,
    readerPageCount = null,
    downloadedChapter = downloadedChapter?.copy(
        readerPageIndex = null,
        readerPageCount = null,
        lastReadAtMillis = null,
    ),
)

private fun readerPrivacyDimAlpha(enabled: Boolean, brightness: Float): Float {
    if (!enabled) return 0f
    return (1f - brightness.coerceIn(0f, 1f)) * ReaderPrivacyMaxDimAlpha
}

private const val ReaderPrivacyMaxDimAlpha = 0.86f
