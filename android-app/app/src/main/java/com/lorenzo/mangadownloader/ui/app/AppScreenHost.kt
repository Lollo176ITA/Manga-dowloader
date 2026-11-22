package com.lorenzo.mangadownloader.ui.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.app.Screen
import com.lorenzo.mangadownloader.app.currentScreen
import com.lorenzo.mangadownloader.app.hidesAdultContent
import com.lorenzo.mangadownloader.app.withoutAdultContent
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.report.FeedbackReporter
import com.lorenzo.mangadownloader.domain.series.FavoriteReadingState
import com.lorenzo.mangadownloader.domain.series.LibraryMatching
import com.lorenzo.mangadownloader.ui.detail.DetailScreen
import com.lorenzo.mangadownloader.ui.history.HistoryScreen
import com.lorenzo.mangadownloader.ui.history.StatsScreen
import com.lorenzo.mangadownloader.ui.home.DiscoverGenreScreen
import com.lorenzo.mangadownloader.ui.info.ChangelogScreen
import com.lorenzo.mangadownloader.ui.info.ReportProblemScreen
import com.lorenzo.mangadownloader.ui.library.DownloadedSeriesScreen
import com.lorenzo.mangadownloader.ui.library.SeriesDownloadStatus
import com.lorenzo.mangadownloader.ui.reader.ReaderScreen
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode
import com.lorenzo.mangadownloader.ui.settings.BackupScreen
import com.lorenzo.mangadownloader.ui.settings.StorageScreen
import com.lorenzo.mangadownloader.ui.updates.UpdatesScreen
import kotlinx.coroutines.launch

/**
 * La schermata corrente (vedi [currentScreen]) coi suoi collegamenti al ViewModel. Le tre più
 * cablate stanno in file propri: [AppTabsPager], [SettingsRoute] e, qui sotto, [DetailRoute].
 */
@Composable
internal fun AppScreenHost(
    state: MangaUiState,
    viewModel: MangaViewModel,
    padding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    pagerState: PagerState,
    showPager: Boolean,
    readerFullscreen: Boolean,
    onToggleReaderFullscreen: () -> Unit,
    homeEditMode: Boolean,
    favoriteReadingStates: Map<String, FavoriteReadingState>,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    notifications: NotificationPermissions,
    backupActions: BackupActions,
    onStartDownload: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
    goToSearchTab: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val selectedManga = state.selected
    val selectedSeries = state.selectedDownloadedSeries

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
                navBarVisible = !readerFullscreen,
                padding = padding,
                initialPageIndex = state.readerInitialPageIndex,
                onOpenPrevious = viewModel::openPreviousReaderChapter,
                onOpenNext = viewModel::openNextReaderChapter,
                onPageVisible = viewModel::saveReaderPagePosition,
                onToggleFullscreen = onToggleReaderFullscreen,
                onRetry = viewModel::retryReaderLoad,
            )
        }
        Screen.StorageManager -> {
            StorageScreen(
                library = state.library,
                padding = padding,
                onOpenSeries = viewModel::selectDownloadedSeries,
                onDeleteSeries = viewModel::deleteDownloadedSeries,
                onDeleteReadChapters = viewModel::deleteReadChapters,
            )
        }
        Screen.Updates -> {
            UpdatesScreen(
                events = state.favoriteUpdates,
                padding = padding,
                onSelect = viewModel::openMangaFromUpdate,
                onBrowse = goToSearchTab,
            )
        }
        Screen.History -> {
            HistoryScreen(
                memory = state.readingMemory,
                library = state.library,
                padding = padding,
                onOpenChapter = viewModel::openReader,
                onResumeStreamingChapter = viewModel::resumeStreamingChapter,
            )
        }
        Screen.Stats -> {
            StatsScreen(
                state = state,
                padding = padding,
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
                padding = padding,
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
                padding = padding,
                onExport = backupActions.onExport,
                onImportMerge = backupActions.onImportMerge,
                onImportReplace = backupActions.onImportReplace,
            )
        }
        Screen.Feedback -> {
            ReportProblemScreen(
                padding = padding,
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
            SettingsRoute(
                state = state,
                viewModel = viewModel,
                padding = padding,
                snackbarHostState = snackbarHostState,
                notifications = notifications,
            )
        }
        Screen.Changelog -> {
            ChangelogScreen(padding = padding)
        }
        Screen.Detail -> if (selectedManga != null) {
            DetailRoute(
                state = state,
                details = selectedManga,
                viewModel = viewModel,
                padding = padding,
                onStartDownload = onStartDownload,
            )
        }
        Screen.DownloadedSeries -> if (selectedSeries != null) {
            DownloadedSeriesScreen(
                series = selectedSeries,
                padding = padding,
                onOpenChapter = viewModel::openReader,
                onDeleteChapter = viewModel::deleteDownloadedChapter,
                onSetChapterRead = viewModel::setChapterRead,
                onMarkReadUpTo = viewModel::markChaptersReadUpTo,
            )
        }
        Screen.Tabs -> {
            AppTabsPager(
                state = state,
                viewModel = viewModel,
                padding = padding,
                snackbarHostState = snackbarHostState,
                pagerState = pagerState,
                userScrollEnabled = showPager,
                homeEditMode = homeEditMode,
                favoriteReadingStates = favoriteReadingStates,
                downloadStatuses = downloadStatuses,
                goToSearchTab = goToSearchTab,
            )
        }
    }
}

@Composable
private fun DetailRoute(
    state: MangaUiState,
    details: MangaDetails,
    viewModel: MangaViewModel,
    padding: PaddingValues,
    onStartDownload: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
) {
    val linkBindings = state.selectedSeriesLink?.sources.orEmpty()
    val downloadedChapterKeys = remember(details, state.library, linkBindings) {
        LibraryMatching.downloadedChapterKeys(details, state.library, linkBindings)
    }
    val readChapterIds = remember(details, state.library, state.selectedMangaReadChapterIds, linkBindings) {
        state.selectedMangaReadChapterIds +
            LibraryMatching.downloadedReadChapterIds(details, state.library, linkBindings)
    }
    val aniListTracking = remember(details, state.aniList.trackings, state.selectedSeriesKey) {
        state.selectedSeriesKey?.let { state.aniList.trackings[it] }
    }
    DetailScreen(
        details = details,
        isLoading = state.isLoadingDetails,
        padding = padding,
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
