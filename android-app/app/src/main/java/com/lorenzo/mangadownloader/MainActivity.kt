package com.lorenzo.mangadownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAniListRedirect(intent)
        handleNotificationIntent(intent)

        setContent {
            MangaDownloaderApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTask: i nuovi intent (redirect OAuth, tap su notifica) arrivano qui.
        setIntent(intent)
        handleAniListRedirect(intent)
        handleNotificationIntent(intent)
    }

    /** Consuma il redirect OAuth di AniList (`mangapp://anilist-auth#access_token=…`). */
    private fun handleAniListRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != AniListAuth.REDIRECT_SCHEME || uri.host != AniListAuth.REDIRECT_HOST) {
            return
        }
        intent.data = null
        ViewModelProvider(this)[MangaViewModel::class.java]
            .onAniListAuthRedirect(uri.fragment ?: uri.encodedQuery)
    }

    /**
     * Consuma gli extras del tap su una notifica "nuovo capitolo": apre direttamente il
     * dettaglio del manga (notifica singola) o il feed Aggiornamenti (riepilogo), invece
     * della tab generica. Gli extras vengono rimossi così non riscattano alla ricomposizione.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val viewModel = ViewModelProvider(this)[MangaViewModel::class.java]
        if (intent.getBooleanExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED, false)) {
            intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED)
            viewModel.openUpdatesFromNotification()
            return
        }
        val mangaUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL) ?: return
        intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL)
        viewModel.openMangaFromNotification(
            sourceId = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_SOURCE_ID).orEmpty(),
            title = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_TITLE).orEmpty(),
            mangaUrl = mangaUrl,
            coverUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_COVER),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    MangaDownloaderTheme(
        themeMode = state.settings.themeMode,
        useDynamicColor = state.settings.useDynamicColor,
    ) {
        MangaDownloaderAppContent(state = state, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("InlinedApi") // POST_NOTIFICATIONS è inlined e ogni uso è protetto dal check API 33.
@Composable
private fun MangaDownloaderAppContent(
    state: MangaUiState,
    viewModel: MangaViewModel,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val appContext = remember(context) { context.applicationContext }
    // Etichette di lettura automatiche dei preferiti, derivate dalla libreria scaricata.
    val favoriteReadingStates = remember(state.favorites, state.library) {
        favoriteReadingStatesByKey(state.favorites, state.library)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val downloadWorkUiState = rememberDownloadWorkUiState(context, viewModel, snackbarHostState)
    val workManager = downloadWorkUiState.manager
    val downloadStatuses = downloadWorkUiState.statuses
    val scope = rememberCoroutineScope()
    // Conserva lo stato salvabile (posizione di scroll in testa) di ogni schermata quando
    // viene smontata navigando altrove, e lo ripristina al ritorno. Vive qui, alla radice
    // dei contenuti, così sopravvive a tutte le transizioni tra schermate.
    val screenStateHolder = rememberSaveableStateHolder()
    var lastCrashReport by remember {
        mutableStateOf(CrashReporter.readLastCrash(appContext))
    }
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    // Il permesso notifiche può cambiare fuori dall'app (impostazioni di sistema):
    // ricontrollato a ogni ritorno in foreground, così l'avviso sotto "Notifiche
    // preferiti" resta veritiero anche dopo una revoca.
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsPermissionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsPermissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsPermissionGranted = remember(notificationsPermissionTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    // Esito del permesso per "Notifiche preferiti": il toggle si accende SOLO se il
    // permesso viene concesso; se negato resta spento e la snackbar porta alle
    // impostazioni di sistema (al secondo rifiuto Android non mostra più il prompt).
    val favoriteNotificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
                    runCatching {
                        appContext.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE,
                                    appContext.packageName,
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
    }

    // Backup (Storage Access Framework): la modalità di import scelta viene ricordata tra il tap
    // e il ritorno dal selettore di file. La sostituzione passa da una conferma esplicita.
    // rememberSaveable: la scelta MERGE/REPLACE deve sopravvivere a una morte del processo
    // mentre il selettore di file SAF è in primo piano (l'enum è Serializable).
    var backupImportMode by rememberSaveable { mutableStateOf(BackupRestoreMode.MERGE) }
    var showReplaceBackupConfirm by rememberSaveable { mutableStateOf(false) }
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it, backupImportMode) } }

    // Niente più richiesta "alla cieca" del permesso notifiche al primo avvio (si impilava
    // sul dialogo di benvenuto e portava a negazioni riflesse): la chiediamo nei momenti in
    // cui il valore è evidente — fine del tutorial (con spiegazione, qui sotto), avvio di un
    // download lungo e attivazione delle notifiche preferiti.
    var showNotificationsRationale by rememberSaveable { mutableStateOf(false) }
    val maybeAskNotificationsPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showNotificationsRationale = true
        }
    }
    if (showNotificationsRationale) {
        NotificationPermissionRationaleDialog(
            onAccept = {
                showNotificationsRationale = false
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onDismiss = { showNotificationsRationale = false },
        )
    }

    LaunchedEffect(Unit) {
        // Aperti dal tap su una notifica di download: porta direttamente alla Libreria.
        // L'extra viene consumato così non riscatta a ogni ricomposizione/rotazione.
        val intent = activity?.intent
        if (intent?.getStringExtra(DownloadWorker.EXTRA_OPEN_TAB) == DownloadWorker.OPEN_TAB_LIBRARY) {
            intent.removeExtra(DownloadWorker.EXTRA_OPEN_TAB)
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
    val downloadNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    downloadNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                DownloadWorker.enqueue(
                    context = appContext,
                    firstUrl = firstUrl,
                    lastUrl = lastUrl,
                    sourceId = details.sourceId,
                    seriesTitle = details.title,
                    mangaUrl = details.mangaUrl,
                    coverUrl = details.coverUrl,
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
    val visiblePagerTab = when {
        !showPager -> state.currentTab
        pagerState.isScrollInProgress -> visibleTabs.getOrElse(pagerState.targetPage) { state.currentTab }
        else -> visibleTabs.getOrElse(pagerState.currentPage) { state.currentTab }
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
    AppSystemEffects(
        activity = activity,
        allowLandscapeRotation = state.settings.allowLandscapeRotation,
        biometricRequest = state.biometricPromptRequest,
        readerOpen = state.readerChapter != null,
        readerFullscreen = isReaderFullscreen,
        keepReaderScreenOn = state.settings.keepScreenOnEnabled,
        onBiometricSucceeded = viewModel::onBiometricAuthenticationSucceeded,
        onUsePinInstead = viewModel::usePinInsteadOfBiometric,
        onBiometricCancelled = viewModel::cancelBiometricAuthentication,
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

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val newTab = visibleTabs.getOrElse(pagerState.currentPage) { state.currentTab }
            if (state.currentTab != newTab) {
                viewModel.selectTab(newTab)
            }
        }
    }

    TutorialOverlay(
        state = state,
        onFallbackCompleted = {
            viewModel.onTutorialFallbackCompleted()
            // A tour appena concluso il valore delle notifiche è chiaro: è il momento
            // giusto per chiedere il permesso, con la spiegazione del perché.
            maybeAskNotificationsPermission()
        },
        onAdvancePhase = viewModel::advanceTutorialPhase,
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
            viewModel.onTutorialFinish(keepSample)
            maybeAskNotificationsPermission()
        },
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            if (!(state.readerChapter != null && isReaderFullscreen)) {
                AppTopBar(
                    state = state,
                    visibleTab = visiblePagerTab,
                    onBack = viewModel::handleBack,
                    onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                    onOpenSettings = viewModel::openSettings,
                    onReaderBrightnessChange = viewModel::setReaderBrightness,
                    onSelectReadingMode = viewModel::setReaderReadingMode,
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
                ReaderScreen(
                    chapter = state.readerChapter,
                    previousChapter = state.readerPreviousChapter,
                    nextChapter = state.readerNextChapter,
                    pages = state.readerPages,
                    isLoading = state.isLoadingReader,
                    readingMode = state.readerReadingMode,
                    doubleTapZoomEnabled = state.settings.doubleTapZoomEnabled,
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
                )
            }
            Screen.DiscoverGenre -> {
                DiscoverGenreScreen(
                    discovery = state.discovery,
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
                    onExport = { createBackupLauncher.launch("manga-downloader-backup.json") },
                    onImportMerge = {
                        backupImportMode = BackupRestoreMode.MERGE
                        importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    onImportReplace = { showReplaceBackupConfirm = true },
                )
            }
            Screen.Feedback -> {
                ReportProblemScreen(
                    padding = innerPadding,
                    onResult = { ok ->
                        scope.launch {
                            if (ok) {
                                viewModel.closeFeedback()
                                snackbarHostState.showSnackbar("Segnalazione inviata. Grazie!")
                            } else {
                                snackbarHostState.showSnackbar(
                                    if (!FeedbackReporter.isConfigured()) {
                                        "Segnalazioni non configurate in questa build"
                                    } else {
                                        "Invio non riuscito. Controlla la connessione e riprova."
                                    },
                                )
                            }
                        }
                    },
                )
            }
            Screen.Settings -> {
                SettingsScreen(
                    settings = state.settings,
                    isBiometricAvailable = state.isBiometricAvailable,
                    isParentalAuthInProgress = state.isParentalAuthInProgress,
                    notificationsPermissionGranted = notificationsPermissionGranted,
                    aniListViewerName = state.aniList.viewer?.name,
                    isAniListConnecting = state.aniList.isConnecting,
                    padding = innerPadding,
                    onSelectThemeMode = viewModel::setThemeMode,
                    onToggleDynamicColor = viewModel::setUseDynamicColor,
                    onRestartTutorial = viewModel::restartTutorial,
                    onConnectAniList = {
                        scope.launch {
                            if (!AniListAuth.isConfigured()) {
                                snackbarHostState.showSnackbar(
                                    "Collegamento AniList non configurato in questa build",
                                )
                                return@launch
                            }
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, AniListAuth.authorizationUrl().toUri()),
                                )
                            } catch (_: ActivityNotFoundException) {
                                snackbarHostState.showSnackbar("Nessun browser disponibile")
                            }
                        }
                    },
                    onDisconnectAniList = viewModel::disconnectAniList,
                    onToggleAniListSync = viewModel::setAniListSyncEnabled,
                    onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
                    onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
                    onBatchChange = viewModel::setAutoDownloadBatchSize,
                    onToggleSmartCleanup = viewModel::setSmartCleanupEnabled,
                    onSmartCleanupKeepChange = viewModel::setSmartCleanupKeepPreviousChapters,
                    onToggleStreamingReader = viewModel::setStreamingReaderEnabled,
                    onSelectReadingMode = viewModel::setReadingMode,
                    onSelectReaderPageSpacing = viewModel::setReaderPageSpacing,
                    onToggleDoubleTapZoom = viewModel::setDoubleTapZoomEnabled,
                    onToggleKeepScreenOn = viewModel::setKeepScreenOnEnabled,
                    onToggleShowHomeTab = viewModel::setShowHomeTab,
                    onToggleParentalControl = viewModel::setParentalControlEnabled,
                    onRequestChangeParentalPin = viewModel::requestChangeParentalPin,
                    onToggleParentalBiometric = viewModel::setParentalBiometricEnabled,
                    onToggleLabs = viewModel::setLabsEnabled,
                    onToggleDownloadDevUpdates = viewModel::setDownloadDevUpdates,
                    onToggleHighResImages = viewModel::setHighResImages,
                    onTogglePrivacyBrightness = viewModel::setPrivacyBrightnessEnabled,
                    onToggleAllowLandscapeRotation = viewModel::setAllowLandscapeRotation,
                    onToggleFavoriteNotifications = { enabled ->
                        if (enabled && !notificationsPermissionGranted) {
                            // L'attivazione vera avviene nel callback del launcher,
                            // solo a permesso concesso: niente switch ON "a vuoto".
                            favoriteNotificationsPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        } else {
                            viewModel.setFavoriteNotificationsEnabled(enabled)
                        }
                    },
                    onOpenStorageManager = viewModel::openStorageManager,
                    onOpenBackup = viewModel::openBackup,
                    onOpenReportProblem = viewModel::openFeedback,
                    appVersion = BuildConfig.VERSION_NAME,
                    onOpenChangelog = viewModel::openChangelog,
                )
            }
            Screen.Changelog -> {
                ChangelogScreen(padding = innerPadding)
            }
            Screen.Detail -> if (selectedManga != null) {
                val downloadedChapterKeys = remember(selectedManga, state.library) {
                    LibraryMatching.downloadedChapterKeys(selectedManga, state.library)
                }
                val readChapterIds = remember(selectedManga, state.library, state.selectedMangaReadChapterIds) {
                    state.selectedMangaReadChapterIds +
                        LibraryMatching.downloadedReadChapterIds(selectedManga, state.library)
                }
                val aniListTracking = remember(selectedManga, state.aniList.trackings) {
                    MangaSourceCatalog.identityKeyOrNull(
                        selectedManga.sourceId,
                        selectedManga.mangaUrl,
                        selectedManga.title,
                    )?.let { state.aniList.trackings[it] }
                }
                DetailScreen(
                    details = selectedManga,
                    isLoading = state.isLoadingDetails,
                    padding = innerPadding,
                    downloadedChapterKeys = downloadedChapterKeys,
                    readChapterIds = readChapterIds,
                    streamingReaderEnabled = state.settings.streamingReaderEnabled,
                    autoDownloadEnabled = state.settings.autoDownloadEnabled,
                    showAniListTracking = state.aniList.viewer != null,
                    aniListTracking = aniListTracking,
                    onLinkAniList = viewModel::openAniListMatch,
                    onOpenAniListTracker = viewModel::openAniListTracker,
                    onStart = onStartDownload,
                    onOpenStreamingChapter = viewModel::openStreamingReader,
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
                            onOpenUpdate = viewModel::openMangaFromUpdate,
                            onOpenAllUpdates = viewModel::openUpdates,
                            onOpenFavorite = { favorite ->
                                viewModel.selectManga(favorite.toSearchResult())
                            },
                            onOpenAllFavorites = {
                                viewModel.selectTab(AppTab.FAVORITES)
                                scope.launch { pagerState.animateScrollToPage(state.tabPageIndex(AppTab.FAVORITES)) }
                            },
                            onOpenHistory = viewModel::openHistory,
                            onOpenSeries = viewModel::selectDownloadedSeries,
                            onPickDiscover = viewModel::onPickAniListManga,
                            onShowDiscoverInfo = viewModel::showDiscoveryInfo,
                            onDismissDiscoverInfo = viewModel::dismissDiscoveryInfo,
                            onLoadDiscover = viewModel::loadDiscovery,
                            onOpenGenre = viewModel::openDiscoverGenre,
                            onSearchFirst = goToSearchTab,
                            onStartTutorial = {
                                viewModel.onTutorialWelcomeStart()
                                // Il tour interattivo parte dalla tab Cerca (primo spotlight sulla
                                // barra di ricerca): portaci l'utente, rispettando il lock parentale.
                                goToSearchTab()
                            },
                            onDismissTutorial = viewModel::onTutorialWelcomeSkip,
                            onMoveBlock = viewModel::moveHomeBlock,
                            onSetBlockHidden = viewModel::setHomeBlockHidden,
                        )
                        AppTab.SEARCH -> SearchScreen(
                            state = state,
                            padding = innerPadding,
                            onQueryChange = viewModel::onQueryChange,
                            onClearRecentSearches = viewModel::clearRecentSearches,
                            onRefresh = viewModel::submitSearch,
                            onSelect = viewModel::selectManga,
                            onToggleFavorite = viewModel::toggleFavoriteFromResult,
                            onShowInfo = viewModel::showMangaInfo,
                            onDismissInfo = viewModel::dismissMangaInfo,
                            onSelectLanguage = viewModel::selectLanguageSearch,
                            onSelectAllSources = viewModel::selectAllSourcesSearch,
                        )
                        AppTab.FAVORITES -> FavoritesScreen(
                            favorites = state.favorites,
                            query = state.favoritesQuery,
                            filterReadingState = state.favoriteFilterReadingState,
                            sort = state.settings.favoriteSort,
                            statusByKey = state.favoriteStatusByKey,
                            seenByKey = state.favoriteSeenStates,
                            readingStateByKey = favoriteReadingStates,
                            padding = innerPadding,
                            onQueryChange = viewModel::onFavoritesQueryChange,
                            onSelect = { favorite ->
                                viewModel.selectManga(favorite.toSearchResult())
                            },
                            onBrowse = goToSearchTab,
                            onSelectSort = viewModel::setFavoriteSort,
                            onSelectReadingState = viewModel::setFavoriteFilterReadingState,
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
                            onStopDownloads = {
                                workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
                            },
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
                importBackupLauncher.launch(arrayOf("application/json", "*/*"))
            },
        )
    }

    // Crash del run precedente: la segnalazione parte da sola in background, senza
    // disturbare l'utente con stack trace incomprensibili (scelta deliberata). Se l'invio
    // fallisce il report resta su disco e si ritenta al prossimo avvio.
    LaunchedEffect(lastCrashReport) {
        val report = lastCrashReport ?: return@LaunchedEffect
        if (!FeedbackReporter.isConfigured()) {
            CrashReporter.clearLastCrash(appContext)
            lastCrashReport = null
            return@LaunchedEffect
        }
        val sent = FeedbackReporter.sendCrashReport(appContext, report)
        if (sent) {
            CrashReporter.clearLastCrash(appContext)
        }
        lastCrashReport = null
    }

    state.parentalPinSetupState?.let { setupState ->
        ParentalPinSetupDialog(
            state = setupState,
            onPinChange = { viewModel.onParentalPinSetupChange(pin = it) },
            onConfirmPinChange = { viewModel.onParentalPinSetupChange(confirmPin = it) },
            onDismiss = viewModel::dismissParentalPinSetup,
            onConfirm = viewModel::confirmParentalPinSetup,
        )
    }

    state.parentalPinEntryState?.let { pinEntryState ->
        ParentalPinEntryDialog(
            state = pinEntryState,
            onPinChange = viewModel::onParentalPinEntryChange,
            onDismiss = viewModel::dismissParentalPinEntry,
            onConfirm = viewModel::confirmParentalPinEntry,
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
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, tracking.siteUrl().toUri()),
                        )
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbarHostState.showSnackbar("Nessun browser disponibile") }
                    }
                },
                onDismiss = viewModel::dismissAniListTracker,
            )
        }
}

private fun readerPrivacyDimAlpha(enabled: Boolean, brightness: Float): Float {
    if (!enabled) return 0f
    return (1f - brightness.coerceIn(0f, 1f)) * ReaderPrivacyMaxDimAlpha
}

private const val ReaderPrivacyMaxDimAlpha = 0.86f

/** Permanenza massima delle snackbar con azione prima di sparire da sole (come le Short). */
private const val SnackbarWithActionTimeoutMs = 4_000L

/**
 * Come [SnackbarHostState.showSnackbar], ma le snackbar con azione spariscono da sole dopo
 * [SnackbarWithActionTimeoutMs], allineate ai 4s delle Short: il default Material 3 con
 * `actionLabel` è `Indefinite` (restano finché non le tocchi). Allo scadere il risultato
 * è [SnackbarResult.Dismissed], come per uno swipe. Senza azione, comportamento
 * standard (Short).
 */
internal suspend fun SnackbarHostState.showAutoDismissSnackbar(
    message: String,
    actionLabel: String? = null,
): SnackbarResult {
    if (actionLabel == null) {
        return showSnackbar(message)
    }
    return withTimeoutOrNull(SnackbarWithActionTimeoutMs) {
        showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
}

