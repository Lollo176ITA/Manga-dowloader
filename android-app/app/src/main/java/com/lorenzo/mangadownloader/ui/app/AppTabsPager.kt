package com.lorenzo.mangadownloader.ui.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.app.AppTab
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.app.unmatchedAniListFavoritesToShow
import com.lorenzo.mangadownloader.app.visibleTabs
import com.lorenzo.mangadownloader.data.model.toSearchResult
import com.lorenzo.mangadownloader.domain.series.FavoriteReadingState
import com.lorenzo.mangadownloader.showAutoDismissSnackbar
import com.lorenzo.mangadownloader.ui.favorites.FavoritesScreen
import com.lorenzo.mangadownloader.ui.home.HomeScreen
import com.lorenzo.mangadownloader.ui.library.LibraryScreen
import com.lorenzo.mangadownloader.ui.library.SeriesDownloadStatus
import com.lorenzo.mangadownloader.ui.search.SearchLockedContent
import com.lorenzo.mangadownloader.ui.search.SearchScreen
import kotlinx.coroutines.launch

/** Le tab principali (Home · Cerca · Preferiti · Libreria) dentro il pager a scorrimento. */
@Composable
internal fun AppTabsPager(
    state: MangaUiState,
    viewModel: MangaViewModel,
    padding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    pagerState: PagerState,
    userScrollEnabled: Boolean,
    homeEditMode: Boolean,
    favoriteReadingStates: Map<String, FavoriteReadingState>,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    goToSearchTab: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visibleTabs = state.visibleTabs()
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = userScrollEnabled,
    ) { page ->
        when (visibleTabs.getOrElse(page) { AppTab.SEARCH }) {
            AppTab.HOME -> HomeScreen(
                state = state,
                editMode = homeEditMode,
                padding = padding,
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
                    padding = padding,
                    onUnlock = { viewModel.selectTab(AppTab.SEARCH) },
                )
            } else {
                SearchScreen(
                    state = state,
                    padding = padding,
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
                padding = padding,
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
                padding = padding,
                onOpenSeries = viewModel::selectDownloadedSeries,
                onDeleteSeries = viewModel::deleteDownloadedSeries,
                onDeleteReadChapters = viewModel::deleteReadChapters,
                onQueryChange = viewModel::onLibraryQueryChange,
                onBrowse = goToSearchTab,
                onStopDownloads = { DownloadWorker.stopAll(context) },
                onStopSeriesDownload = { status ->
                    DownloadWorker.stopWork(context, status.workIds)
                },
                onResume = viewModel::openReader,
                onSelectSort = viewModel::setLibrarySort,
                onMarkAllRead = viewModel::markAllChaptersRead,
            )
        }
    }
}
