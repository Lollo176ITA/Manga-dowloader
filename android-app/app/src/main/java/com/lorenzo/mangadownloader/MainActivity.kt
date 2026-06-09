package com.lorenzo.mangadownloader

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MangaDownloaderApp()
        }
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
@Composable
private fun MangaDownloaderAppContent(
    state: MangaUiState,
    viewModel: MangaViewModel,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val appContext = remember(context) { context.applicationContext }
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val activeWorkInfos = remember(workInfos) { workInfos.filter { it.isActiveDownload() } }
    val runningOrQueuedWork = activeWorkInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: activeWorkInfos.firstOrNull()
    val latestDone = runningOrQueuedWork?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val terminalWorkKey = remember(workInfos) {
        workInfos
            .filter { it.isTerminalDownload() }
            .map { "${it.id}:${it.state}" }
            .sorted()
            .joinToString("|")
    }
    val downloadStatuses = remember(activeWorkInfos) { buildSeriesDownloadStatuses(activeWorkInfos) }
    // Etichette di lettura automatiche dei preferiti, derivate dalla libreria scaricata.
    val favoriteReadingStates = remember(state.favorites, state.library) {
        favoriteReadingStatesByKey(state.favorites, state.library)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lastCrashReport by remember {
        mutableStateOf(CrashReporter.readLastCrash(appContext))
    }
    var lastForcedChapterProgressKey by remember { mutableStateOf<String?>(null) }
    var lastForcedTerminalWorkKey by remember { mutableStateOf("") }

    // Refresh the library only on coarse, infrequent transitions: when a chapter
    // completes (latestDone changes), when a worker terminates, or when the set
    // of active downloads grows/shrinks. Chapter completion and terminal work
    // bypass the TTL cache so newly written files are visible immediately.
    LaunchedEffect(
        runningOrQueuedWork?.id,
        runningOrQueuedWork?.state,
        latestDone,
        terminalWorkKey,
        activeWorkInfos.size,
    ) {
        val chapterProgressKey = runningOrQueuedWork
            ?.id
            ?.takeIf { latestDone > 0 }
            ?.let { "$it:$latestDone" }
        val chapterCompleted = chapterProgressKey != null &&
            chapterProgressKey != lastForcedChapterProgressKey
        val workerTerminated = terminalWorkKey.isNotBlank() &&
            terminalWorkKey != lastForcedTerminalWorkKey

        if (chapterCompleted) {
            lastForcedChapterProgressKey = chapterProgressKey
        }
        if (workerTerminated) {
            lastForcedTerminalWorkKey = terminalWorkKey
        }

        viewModel.refreshLibrary(forceRefresh = chapterCompleted || workerTerminated)
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        scope.launch {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

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

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.checkForAppUpdate()
    }

    // Blocca l'app in verticale di default. Solo se l'utente attiva esplicitamente
    // il flag nelle impostazioni consentiamo la rotazione (a rischio impaginazione).
    LaunchedEffect(activity, state.settings.allowLandscapeRotation) {
        activity?.requestedOrientation = if (state.settings.allowLandscapeRotation) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(state.biometricPromptRequest?.requestId) {
        val request = state.biometricPromptRequest ?: return@LaunchedEffect
        val hostActivity = activity
        if (hostActivity == null) {
            viewModel.cancelBiometricAuthentication(
                request.requestId,
                "Biometria non disponibile su questo dispositivo",
            )
            return@LaunchedEffect
        }

        val prompt = BiometricPrompt(
            hostActivity,
            ContextCompat.getMainExecutor(hostActivity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onBiometricAuthenticationSucceeded(request.requestId)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            viewModel.usePinInsteadOfBiometric(request.requestId)
                        }
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> {
                            viewModel.cancelBiometricAuthentication(request.requestId)
                        }
                        else -> {
                            viewModel.cancelBiometricAuthentication(
                                request.requestId,
                                errString.toString(),
                            )
                        }
                    }
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(request.title)
            .setSubtitle(request.subtitle)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Usa PIN")
            .build()

        prompt.authenticate(promptInfo)
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
                    snackbarHostState.showSnackbar(
                        if (startChapter.url == endChapter.url) {
                            "Download aggiunto in coda: ${startChapter.displayLabel()}"
                        } else {
                            "Download aggiunto in coda: ${startChapter.displayLabel()} - ${endChapter.displayLabel()}"
                        },
                    )
                }
            } catch (exc: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(exc.message ?: "Impossibile avviare il download")
                }
            }
        }
    }

    val visibleTabs = state.visibleTabs()
    // pageCount letto live dal pager (la lambda persiste): rememberUpdatedState evita di
    // catturare un conteggio stantio quando la tab Scopri viene attivata/disattivata.
    val visibleTabCount by rememberUpdatedState(visibleTabs.size)
    val pagerState = rememberPagerState(
        initialPage = state.tabPageIndex(state.currentTab),
        pageCount = { visibleTabCount },
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
        if (visiblePagerTab == AppTab.FAVORITES) {
            viewModel.refreshUpdatesFeed()
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

    // Vero schermo intero: quando il reader è in fullscreen nascondiamo anche le barre
    // di sistema (status + navigation), così la pagina occupa davvero tutto lo schermo.
    // Si esce con un tap (toggle) o con lo swipe dal bordo, che le ripristina da solo.
    val view = LocalView.current
    val readerImmersive = state.readerChapter != null && isReaderFullscreen
    LaunchedEffect(readerImmersive, view) {
        if (view.isInEditMode) return@LaunchedEffect
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (readerImmersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

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
        onWelcomeStart = viewModel::onTutorialWelcomeStart,
        onWelcomeSkip = viewModel::onTutorialWelcomeSkip,
        onFallbackCompleted = viewModel::onTutorialFallbackCompleted,
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
                TutorialAnchor.OVERFLOW,
                TutorialAnchor.DETAIL_DOWNLOAD -> Unit
            }
        },
        onFinish = viewModel::onTutorialFinish,
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
                    onSelectSource = viewModel::selectSearchSource,
                    onReaderBrightnessChange = viewModel::setReaderBrightness,
                    onSelectReadingMode = viewModel::setReaderReadingMode,
                    unseenUpdatesCount = unseenCount(state.favoriteUpdates),
                    onOpenUpdates = viewModel::openUpdates,
                )
            }
        },
        bottomBar = {
            if (showPager) {
                AppBottomBar(
                    currentTab = visiblePagerTab,
                    showDiscovery = state.settings.discoveryEnabled,
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
                    navBarVisible = !isReaderFullscreen,
                    padding = innerPadding,
                    initialPageIndex = state.readerInitialPageIndex,
                    onOpenPrevious = viewModel::openPreviousReaderChapter,
                    onOpenNext = viewModel::openNextReaderChapter,
                    onPageVisible = viewModel::saveReaderPagePosition,
                    onToggleFullscreen = { isReaderFullscreen = !isReaderFullscreen },
                )
            }
            Screen.StorageManager -> {
                StorageScreen(
                    library = state.library,
                    padding = innerPadding,
                    onDeleteSeries = viewModel::deleteDownloadedSeries,
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
            Screen.Settings -> {
                SettingsScreen(
                    settings = state.settings,
                    isBiometricAvailable = state.isBiometricAvailable,
                    isParentalAuthInProgress = state.isParentalAuthInProgress,
                    padding = innerPadding,
                    onSelectThemeMode = viewModel::setThemeMode,
                    onToggleDynamicColor = viewModel::setUseDynamicColor,
                    onToggleDiscovery = viewModel::setDiscoveryEnabled,
                    onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
                    onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
                    onBatchChange = viewModel::setAutoDownloadBatchSize,
                    onToggleSmartCleanup = viewModel::setSmartCleanupEnabled,
                    onSmartCleanupKeepChange = viewModel::setSmartCleanupKeepPreviousChapters,
                    onToggleStreamingReader = viewModel::setStreamingReaderEnabled,
                    onSelectReadingMode = viewModel::setReadingMode,
                    onToggleDoubleTapZoom = viewModel::setDoubleTapZoomEnabled,
                    onToggleParentalControl = viewModel::setParentalControlEnabled,
                    onRequestChangeParentalPin = viewModel::requestChangeParentalPin,
                    onToggleParentalBiometric = viewModel::setParentalBiometricEnabled,
                    onToggleLabs = viewModel::setLabsEnabled,
                    onToggleDownloadDevUpdates = viewModel::setDownloadDevUpdates,
                    onToggleHighResImages = viewModel::setHighResImages,
                    onTogglePrivacyBrightness = viewModel::setPrivacyBrightnessEnabled,
                    onToggleAllowLandscapeRotation = viewModel::setAllowLandscapeRotation,
                    onToggleFavoriteNotifications = { enabled ->
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setFavoriteNotificationsEnabled(enabled)
                    },
                    onOpenStorageManager = viewModel::openStorageManager,
                    onOpenBackup = viewModel::openBackup,
                )
            }
            Screen.Detail -> if (selectedManga != null) {
                val downloadedChapterKeys = remember(selectedManga, state.library) {
                    LibraryMatching.downloadedChapterKeys(selectedManga, state.library)
                }
                val readChapterIds = remember(selectedManga, state.library, state.selectedMangaReadChapterIds) {
                    state.selectedMangaReadChapterIds +
                        LibraryMatching.downloadedReadChapterIds(selectedManga, state.library)
                }
                DetailScreen(
                    details = selectedManga,
                    isLoading = state.isLoadingDetails,
                    padding = innerPadding,
                    downloadedChapterKeys = downloadedChapterKeys,
                    readChapterIds = readChapterIds,
                    streamingReaderEnabled = state.settings.streamingReaderEnabled,
                    autoDownloadEnabled = state.settings.autoDownloadEnabled,
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
                )
            }
            Screen.Tabs -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = showPager,
                ) { page ->
                    when (visibleTabs.getOrElse(page) { AppTab.SEARCH }) {
                        AppTab.DISCOVERY -> DiscoveryScreen(
                            state = state,
                            padding = innerPadding,
                            onLoad = viewModel::loadDiscovery,
                            onSelectGenre = viewModel::selectDiscoveryGenre,
                            onPick = viewModel::onPickAniListManga,
                            onShowInfo = viewModel::showDiscoveryInfo,
                            onDismissInfo = viewModel::dismissDiscoveryInfo,
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
                        )
                        AppTab.FAVORITES -> FavoritesScreen(
                            favorites = state.favorites,
                            query = state.favoritesQuery,
                            categories = state.favoriteCategories,
                            assignments = state.favoriteCategoryAssignments,
                            filterCategoryId = state.favoriteFilterCategoryId,
                            sort = state.settings.favoriteSort,
                            statusByKey = state.favoriteStatusByKey,
                            seenByKey = state.favoriteSeenStates,
                            readingStateByKey = favoriteReadingStates,
                            padding = innerPadding,
                            onQueryChange = viewModel::onFavoritesQueryChange,
                            onSelect = { favorite ->
                                viewModel.selectManga(
                                    MangaSearchResult(
                                        sourceId = favorite.sourceId,
                                        title = favorite.title,
                                        mangaUrl = favorite.mangaUrl,
                                        coverUrl = favorite.coverUrl,
                                    ),
                                )
                            },
                            onBrowse = goToSearchTab,
                            onSelectSort = viewModel::setFavoriteSort,
                            onSelectCategory = viewModel::setFavoriteFilterCategory,
                            onAssignCategory = viewModel::assignFavoriteCategory,
                            onAddCategory = viewModel::addFavoriteCategory,
                            onRenameCategory = viewModel::renameFavoriteCategory,
                            onRemoveCategory = viewModel::removeFavoriteCategory,
                            onReadNow = viewModel::readNowFromFavorite,
                        )
                        AppTab.LIBRARY -> LibraryScreen(
                            state = state,
                            downloadStatuses = downloadStatuses,
                            padding = innerPadding,
                            onOpenSeries = viewModel::selectDownloadedSeries,
                            onDeleteSeries = viewModel::deleteDownloadedSeries,
                            onQueryChange = viewModel::onLibraryQueryChange,
                            onBrowse = goToSearchTab,
                            onStopDownloads = {
                                workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
                            },
                            onResume = viewModel::openReader,
                        )
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

    lastCrashReport?.let { report ->
        CrashReportDialog(
            report = report,
            crashPath = remember(appContext) { CrashReporter.crashFilePath(appContext).orEmpty() },
            onDismiss = {
                CrashReporter.clearLastCrash(appContext)
                lastCrashReport = null
            },
        )
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
}

private fun WorkInfo.isActiveDownload(): Boolean {
    return state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.ENQUEUED ||
        state == WorkInfo.State.BLOCKED
}

private fun WorkInfo.isTerminalDownload(): Boolean {
    return state == WorkInfo.State.SUCCEEDED ||
        state == WorkInfo.State.FAILED ||
        state == WorkInfo.State.CANCELLED
}

private fun readerPrivacyDimAlpha(enabled: Boolean, brightness: Float): Float {
    if (!enabled) return 0f
    return (1f - brightness.coerceIn(0f, 1f)) * ReaderPrivacyMaxDimAlpha
}

private const val ReaderPrivacyMaxDimAlpha = 0.86f

