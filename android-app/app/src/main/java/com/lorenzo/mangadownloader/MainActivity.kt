package com.lorenzo.mangadownloader

import android.Manifest
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
            MangaDownloaderTheme {
                MangaDownloaderApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val appContext = remember(context) { context.applicationContext }
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val activeWorkInfos = remember(workInfos) { workInfos.filter { it.isActiveDownload() } }
    val runningOrQueuedWork = activeWorkInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: activeWorkInfos.firstOrNull()
    val latestMessage = runningOrQueuedWork?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val latestDone = runningOrQueuedWork?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val downloadStatuses = remember(activeWorkInfos) { buildSeriesDownloadStatuses(activeWorkInfos) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var lastCrashReport by remember {
        mutableStateOf(CrashReporter.readLastCrash(appContext))
    }

    LaunchedEffect(
        runningOrQueuedWork?.id,
        runningOrQueuedWork?.state,
        latestDone,
        latestMessage,
        activeWorkInfos.size,
    ) {
        viewModel.refreshLibrary()
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

    val onStartDownload: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit = { details, startChapter, endChapter ->
        val firstUrl = startChapter.url.trim()
        val lastUrl = endChapter.url.trim()
        if (firstUrl.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("URL capitolo non valido")
            }
        } else {
            try {
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
                            "Download aggiunto in coda: capitolo ${startChapter.displayNumber()}"
                        } else {
                            "Download aggiunto in coda: ${startChapter.displayNumber()}-${endChapter.displayNumber()}"
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

    val pagerState = rememberPagerState(
        initialPage = state.currentTab.ordinal,
        pageCount = { AppTab.entries.size },
    )
    val showPager = state.readerChapter == null &&
        !state.showSettings &&
        state.selected == null &&
        state.selectedDownloadedSeries == null
    val visiblePagerTab = when {
        !showPager -> state.currentTab
        pagerState.isScrollInProgress -> AppTab.entries[pagerState.targetPage]
        else -> AppTab.entries[pagerState.currentPage]
    }
    val canHandleBack = state.canHandleBack()

    BackHandler(enabled = canHandleBack) {
        state.handleBack(viewModel)
    }

    LaunchedEffect(
        state.currentTab,
        state.pendingSearchAccessReturnTab,
        showPager,
        pagerState.currentPage,
        pagerState.isScrollInProgress,
    ) {
        if (
            showPager &&
            !pagerState.isScrollInProgress &&
            state.pendingSearchAccessReturnTab == null &&
            pagerState.currentPage != state.currentTab.ordinal
        ) {
            pagerState.animateScrollToPage(state.currentTab.ordinal)
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val newTab = AppTab.entries[pagerState.currentPage]
            if (state.currentTab != newTab) {
                viewModel.selectTab(newTab)
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                state = state,
                visibleTab = visiblePagerTab,
                onBack = { state.handleBack(viewModel) },
                onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                onOpenSettings = viewModel::openSettings,
                onOpenSearchSource = viewModel::openSearchSourceDialog,
            )
        },
        bottomBar = {
            if (showPager) {
                AppBottomBar(
                    currentTab = visiblePagerTab,
                    onSelect = viewModel::selectTab,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val selectedManga = state.selected
        val selectedSeries = state.selectedDownloadedSeries

        when {
            state.readerChapter != null -> {
                ReaderScreen(
                    chapter = state.readerChapter,
                    previousChapter = state.readerPreviousChapter,
                    nextChapter = state.readerNextChapter,
                    pages = state.readerPages,
                    isLoading = state.isLoadingReader,
                    padding = innerPadding,
                    onOpenPrevious = viewModel::openPreviousReaderChapter,
                    onOpenNext = viewModel::openNextReaderChapter,
                )
            }
            state.showSettings -> {
                SettingsScreen(
                    settings = state.settings,
                    isBiometricAvailable = state.isBiometricAvailable,
                    isParentalAuthInProgress = state.isParentalAuthInProgress,
                    padding = innerPadding,
                    onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
                    onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
                    onBatchChange = viewModel::setAutoDownloadBatchSize,
                    onToggleSmartCleanup = viewModel::setSmartCleanupEnabled,
                    onSmartCleanupKeepChange = viewModel::setSmartCleanupKeepPreviousChapters,
                    onToggleParentalControl = viewModel::setParentalControlEnabled,
                    onRequestChangeParentalPin = viewModel::requestChangeParentalPin,
                    onToggleParentalBiometric = viewModel::setParentalBiometricEnabled,
                )
            }
            selectedManga != null -> {
                DetailScreen(
                    details = selectedManga,
                    isLoading = state.isLoadingDetails,
                    padding = innerPadding,
                    onStart = onStartDownload,
                )
            }
            state.currentTab == AppTab.LIBRARY && selectedSeries != null -> {
                DownloadedSeriesScreen(
                    series = selectedSeries,
                    padding = innerPadding,
                    onOpenChapter = viewModel::openReader,
                    onDeleteChapter = viewModel::deleteDownloadedChapter,
                )
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = showPager,
                    beyondBoundsPageCount = 1,
                ) { page ->
                    when (AppTab.entries[page]) {
                        AppTab.SEARCH -> SearchScreen(
                            state = state,
                            padding = innerPadding,
                            onQueryChange = viewModel::onQueryChange,
                            onSelect = viewModel::selectManga,
                            onToggleFavorite = viewModel::toggleFavoriteFromResult,
                        )
                        AppTab.FAVORITES -> FavoritesScreen(
                            favorites = state.favorites,
                            query = state.favoritesQuery,
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
                        )
                        AppTab.LIBRARY -> LibraryScreen(
                            state = state,
                            downloadStatuses = downloadStatuses,
                            padding = innerPadding,
                            onOpenSeries = viewModel::selectDownloadedSeries,
                            onDeleteSeries = viewModel::deleteDownloadedSeries,
                            onQueryChange = viewModel::onLibraryQueryChange,
                            onStopDownloads = {
                                workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
                            },
                        )
                    }
                }
            }
        }
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

    if (state.showSearchSourceDialog) {
        SearchSourceDialog(
            selectedSourceId = state.settings.searchSourceId,
            sources = MangaSourceCatalog.descriptors,
            onDismiss = viewModel::dismissSearchSourceDialog,
            onSelect = viewModel::selectSearchSource,
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

private fun MangaUiState.canHandleBack(): Boolean {
    return readerChapter != null ||
        showSettings ||
        selected != null ||
        (currentTab == AppTab.LIBRARY && selectedDownloadedSeries != null)
}

private fun MangaUiState.handleBack(viewModel: MangaViewModel) {
    when {
        readerChapter != null -> viewModel.closeReader()
        showSettings -> viewModel.closeSettings()
        selected != null -> viewModel.clearSelection()
        currentTab == AppTab.LIBRARY && selectedDownloadedSeries != null ->
            viewModel.clearDownloadedSelection()
    }
}
