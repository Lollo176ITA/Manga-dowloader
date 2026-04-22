package com.lorenzo.mangadownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

private data class SeriesDownloadStatus(
    val seriesTitle: String?,
    val mangaUrl: String?,
    val message: String?,
    val doneChapters: Int,
    val totalChapters: Int,
    val state: WorkInfo.State,
    val requestCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val activeWorkInfos = workInfos.filter { workInfo ->
        workInfo.state == WorkInfo.State.RUNNING ||
            workInfo.state == WorkInfo.State.ENQUEUED ||
            workInfo.state == WorkInfo.State.BLOCKED
    }
    val statusWork = activeWorkInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: activeWorkInfos.firstOrNull()
    val latestMessage = statusWork?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val latestDone = statusWork?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val downloadStatuses = remember(activeWorkInfos) { buildSeriesDownloadStatuses(activeWorkInfos) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appContext = remember(context) { context.applicationContext }
    var lastCrashReport by remember {
        mutableStateOf(CrashReporter.readLastCrash(appContext))
    }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showDeleteSeriesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(statusWork?.id, statusWork?.state, latestDone, latestMessage, activeWorkInfos.size) {
        viewModel.refreshLibrary()
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (!msg.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.dismissError()
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

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
                    seriesTitle = details.title,
                    mangaUrl = details.mangaUrl,
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
    val showBottomBar = showPager
    val canHandleBack = state.readerChapter != null ||
        state.showSettings ||
        state.selected != null ||
        state.selectedChapterPaths.isNotEmpty() ||
        (state.currentTab == AppTab.LIBRARY && state.selectedDownloadedSeries != null)

    val handleBack: () -> Unit = {
        when {
            state.readerChapter != null -> viewModel.closeReader()
            state.showSettings -> viewModel.closeSettings()
            state.selected != null -> viewModel.clearSelection()
            state.selectedChapterPaths.isNotEmpty() -> viewModel.clearChapterSelection()
            state.currentTab == AppTab.LIBRARY && state.selectedDownloadedSeries != null ->
                viewModel.clearDownloadedSelection()
        }
    }

    BackHandler(enabled = canHandleBack, onBack = handleBack)
    LaunchedEffect(state.currentTab) {
        if (pagerState.currentPage != state.currentTab.ordinal) {
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
                onBack = handleBack,
                onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                onDeleteSelected = { showDeleteSelectedDialog = true },
                onDeleteSeries = { showDeleteSeriesDialog = true },
                onOpenSettings = viewModel::openSettings,
            )
        },
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentTab = visiblePagerTab,
                    onSelect = viewModel::selectTab,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val activeReaderChapter = state.readerChapter
        val activeDetail = state.selected
        val activeSeries = state.selectedDownloadedSeries
        when {
            activeReaderChapter != null -> {
                ReaderScreen(
                    chapter = activeReaderChapter,
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
                    padding = innerPadding,
                    onToggleAutoDownload = viewModel::setAutoDownloadEnabled,
                    onTriggerChange = viewModel::setAutoDownloadTriggerChapters,
                    onBatchChange = viewModel::setAutoDownloadBatchSize,
                )
            }
            activeDetail != null -> {
                DetailScreen(
                    details = activeDetail,
                    isLoading = state.isLoadingDetails,
                    padding = innerPadding,
                    onStart = onStartDownload,
                )
            }
            state.currentTab == AppTab.LIBRARY && activeSeries != null -> {
                DownloadedSeriesScreen(
                    series = activeSeries,
                    selectedChapterPaths = state.selectedChapterPaths,
                    padding = innerPadding,
                    onOpenChapter = viewModel::openReader,
                    onToggleChapterSelection = viewModel::toggleChapterSelection,
                    onStartChapterSelection = viewModel::startChapterSelection,
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

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Elimina capitoli") },
            text = {
                Text("Vuoi eliminare ${state.selectedChapterPaths.size} capitoli selezionati?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelectedDialog = false
                    viewModel.deleteSelectedChapters()
                }) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    val selectedSeriesForDelete = state.selectedDownloadedSeries
    if (showDeleteSeriesDialog && selectedSeriesForDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteSeriesDialog = false },
            title = { Text("Elimina manga") },
            text = {
                Text("Vuoi eliminare ${selectedSeriesForDelete.title} con tutti i capitoli scaricati?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSeriesDialog = false
                    viewModel.deleteDownloadedSeries()
                }) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSeriesDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    lastCrashReport?.let { report ->
        val crashPath = remember(appContext) { CrashReporter.crashFilePath(appContext).orEmpty() }
        AlertDialog(
            onDismissRequest = {
                CrashReporter.clearLastCrash(appContext)
                lastCrashReport = null
            },
            title = { Text("Ultimo crash rilevato") },
            text = {
                Text(
                    text = buildString {
                        append(report.lineSequence().take(10).joinToString("\n"))
                        if (crashPath.isNotBlank()) {
                            append("\n\nFile: $crashPath")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CrashReporter.clearLastCrash(appContext)
                    lastCrashReport = null
                }) { Text("Chiudi") }
            },
        )
    }

    state.availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = {
                if (!state.isInstallingUpdate) {
                    viewModel.dismissAvailableUpdate()
                }
            },
            title = { Text("Aggiornamento disponibile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("È disponibile la versione ${update.versionName}.")
                    if (state.isInstallingUpdate) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Scaricamento e apertura installer...")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.installAvailableUpdate() },
                    enabled = !state.isInstallingUpdate,
                ) {
                    Text("Aggiorna")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissAvailableUpdate() },
                    enabled = !state.isInstallingUpdate,
                ) {
                    Text("Più tardi")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    state: MangaUiState,
    visibleTab: AppTab,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteSeries: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val readerChapter = state.readerChapter
    val selectedManga = state.selected
    val selectedSeries = state.selectedDownloadedSeries
    val showBack = readerChapter != null ||
        state.showSettings ||
        selectedManga != null ||
        state.selectedChapterPaths.isNotEmpty() ||
        (state.currentTab == AppTab.LIBRARY && selectedSeries != null)
    val title = when {
        state.showSettings -> "Impostazioni"
        state.selectedChapterPaths.isNotEmpty() -> "${state.selectedChapterPaths.size} selezionati"
        readerChapter != null -> readerChapter.title
        selectedManga != null -> selectedManga.title
        state.currentTab == AppTab.LIBRARY && selectedSeries != null -> selectedSeries.title
        visibleTab == AppTab.SEARCH -> "Cerca"
        visibleTab == AppTab.FAVORITES -> "Preferiti"
        visibleTab == AppTab.LIBRARY -> "Libreria"
        else -> "Manga Downloader"
    }
    val barColor = MaterialTheme.colorScheme.surface
    var overflowExpanded by remember { mutableStateOf(false) }

    val inDetail = selectedManga != null
    val inSeries = state.currentTab == AppTab.LIBRARY && selectedSeries != null
    val showOverflow = state.readerChapter == null &&
        !state.showSettings &&
        state.selectedChapterPaths.isEmpty() &&
        !inDetail &&
        !inSeries

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = barColor,
        ),
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Indietro",
                    )
                }
            }
        },
        actions = {
            if (inDetail && selectedManga != null) {
                val isFavorite = selectedManga.mangaUrl in state.favoriteMangaUrls
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                        tint = if (isFavorite) FavoriteYellow else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else if (inSeries && readerChapter == null) {
                if (state.selectedChapterPaths.isNotEmpty()) {
                    IconButton(onClick = onDeleteSelected) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Elimina capitoli selezionati",
                        )
                    }
                } else {
                    IconButton(onClick = onDeleteSeries) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Elimina manga",
                        )
                    }
                }
            }

            if (showOverflow) {
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Altre azioni",
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Impostazioni") },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            },
                            onClick = {
                                overflowExpanded = false
                                onOpenSettings()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AppBottomBar(
    currentTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.surface
    NavigationBar(containerColor = barColor) {
        NavigationBarItem(
            selected = currentTab == AppTab.SEARCH,
            onClick = { onSelect(AppTab.SEARCH) },
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Cerca") },
        )
        NavigationBarItem(
            selected = currentTab == AppTab.FAVORITES,
            onClick = { onSelect(AppTab.FAVORITES) },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = { Text("Preferiti") },
        )
        NavigationBarItem(
            selected = currentTab == AppTab.LIBRARY,
            onClick = { onSelect(AppTab.LIBRARY) },
            icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            label = { Text("Libreria") },
        )
    }
}

@Composable
private fun SearchScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (MangaSearchResult) -> Unit,
    onToggleFavorite: (MangaSearchResult) -> Unit,
) {
    val trimmed = state.query.trim()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = state.query,
            placeholder = "Cerca manga",
            onValueChange = onQueryChange,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isSearching -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )
                }
                state.results.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.results, key = { it.mangaUrl }) { result ->
                            ResultCard(
                                result = result,
                                isFavorite = result.mangaUrl in state.favoriteMangaUrls,
                                onClick = { onSelect(result) },
                                onToggleFavorite = { onToggleFavorite(result) },
                            )
                        }
                    }
                }
                trimmed.isEmpty() -> {
                    EmptyStateText(
                        text = "Digita per cercare",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                trimmed.isNotEmpty() && trimmed.length < 3 -> {
                    EmptyStateText(
                        text = "Digita almeno 3 caratteri",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                trimmed.length >= 3 && !state.isSearching -> {
                    EmptyStateText(
                        text = "Nessun risultato",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        placeholder = { Text(placeholder) },
        singleLine = true,
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Pulisci")
                }
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun ResultCard(
    result: MangaSearchResult,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    model = result.coverUrl,
                    title = result.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                )
                FavoriteToggleBadge(
                    isFavorite = isFavorite,
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            Text(
                text = result.title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavoriteToggleBadge(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier
            .size(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                tint = if (isFavorite) FavoriteYellow else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FavoritesScreen(
    favorites: List<FavoriteManga>,
    query: String,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (FavoriteManga) -> Unit,
) {
    val filtered = remember(favorites, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) favorites
        else favorites.filter { it.title.contains(trimmed, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = query,
            placeholder = "Cerca nei preferiti",
            onValueChange = onQueryChange,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                favorites.isEmpty() -> {
                    EmptyStateText(
                        text = "Nessun preferito",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                filtered.isEmpty() -> {
                    EmptyStateText(
                        text = "Nessun preferito corrisponde",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filtered, key = { it.mangaUrl }) { favorite ->
                            FavoriteCard(favorite = favorite, onClick = { onSelect(favorite) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(favorite: FavoriteManga, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            CoverImage(
                model = favorite.coverUrl,
                title = favorite.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.medium),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = favorite.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = FavoriteYellow,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    details: MangaDetails,
    isLoading: Boolean,
    padding: PaddingValues,
    onStart: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
) {
    var pendingStart by remember { mutableStateOf<ChapterEntry?>(null) }
    var pendingEnd by remember { mutableStateOf<ChapterEntry?>(null) }
    var endMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SeriesHeader(
            coverModel = details.coverUrl,
            title = details.title,
            subtitle = "${details.chapters.size} capitoli disponibili",
            onDownloadAll = if (details.chapters.isNotEmpty()) {
                { onStart(details, details.chapters.first(), details.chapters.last()) }
            } else {
                null
            },
        )

        if (isLoading && details.chapters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(details.chapters, key = { it.url }) { chapter ->
                        ChapterRow(
                            chapter = chapter,
                            onClick = {
                                pendingStart = chapter
                                pendingEnd = chapter
                                endMenuExpanded = false
                            },
                        )
                    }
                }
                if (details.chapters.isNotEmpty()) {
                    ScrollToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(details.chapters.lastIndex)
                            }
                        },
                    )
                }
            }
        }
    }

    val dialogStart = pendingStart
    if (dialogStart != null) {
        val endOptions = remember(details.chapters, dialogStart.url) {
            val startIndex = details.chapters.indexOfFirst { it.url == dialogStart.url }
            if (startIndex >= 0) details.chapters.subList(startIndex, details.chapters.size) else listOf(dialogStart)
        }
        val dialogEnd = pendingEnd ?: dialogStart
        AlertDialog(
            onDismissRequest = {
                pendingStart = null
                pendingEnd = null
                endMenuExpanded = false
            },
            title = { Text("Seleziona intervallo download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = "Capitolo ${dialogStart.displayNumber()}",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Da") },
                        readOnly = true,
                        singleLine = true,
                    )
                    Box {
                        OutlinedTextField(
                            value = "Capitolo ${dialogEnd.displayNumber()}",
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            singleLine = true,
                            label = { Text("A") },
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { endMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = endMenuExpanded,
                            onDismissRequest = { endMenuExpanded = false },
                        ) {
                            endOptions.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text("Capitolo ${candidate.displayNumber()}") },
                                    onClick = {
                                        pendingEnd = candidate
                                        endMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalEnd = pendingEnd ?: dialogStart
                    pendingStart = null
                    pendingEnd = null
                    endMenuExpanded = false
                    onStart(details, dialogStart, finalEnd)
                }) { Text("Avvia") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingStart = null
                    pendingEnd = null
                    endMenuExpanded = false
                }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun LibraryScreen(
    state: MangaUiState,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    padding: PaddingValues,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onDeleteSeries: (DownloadedSeries) -> Unit,
    onQueryChange: (String) -> Unit,
    onStopDownloads: () -> Unit,
) {
    val filtered = remember(state.library, state.libraryQuery) {
        val trimmed = state.libraryQuery.trim()
        if (trimmed.isBlank()) state.library
        else state.library.filter { it.title.contains(trimmed, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = state.libraryQuery,
            placeholder = "Cerca nella libreria",
            onValueChange = onQueryChange,
        )

        when {
            state.isLoadingLibrary && state.library.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                }
            }
            state.library.isEmpty() -> {
                EmptyStateText(
                    text = "Nessun manga scaricato",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            filtered.isEmpty() -> {
                EmptyStateText(
                    text = "Nessun manga corrisponde",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.directory.absolutePath }) { series ->
                        DownloadedSeriesCard(
                            series = series,
                            downloadStatus = downloadStatusForSeries(downloadStatuses, series),
                            onClick = { onOpenSeries(series) },
                            onDelete = { onDeleteSeries(series) },
                            onStopDownloads = onStopDownloads,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    padding: PaddingValues,
    onToggleAutoDownload: (Boolean) -> Unit,
    onTriggerChange: (Int) -> Unit,
    onBatchChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Download automatico",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Scarica capitoli successivi automaticamente",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Quando leggi gli ultimi capitoli scaricati, scarica automaticamente i successivi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.autoDownloadEnabled,
                onCheckedChange = onToggleAutoDownload,
            )
        }
        NumberSettingField(
            label = "Capitoli rimanenti per attivare il download",
            value = settings.autoDownloadTriggerChapters,
            enabled = settings.autoDownloadEnabled,
            onValueChange = onTriggerChange,
        )
        NumberSettingField(
            label = "Capitoli da scaricare ogni volta",
            value = settings.autoDownloadBatchSize,
            enabled = settings.autoDownloadEnabled,
            onValueChange = onBatchChange,
        )
    }
}

@Composable
private fun NumberSettingField(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val digits = newText.filter { it.isDigit() }.take(3)
            text = digits
            digits.toIntOrNull()?.let { onValueChange(it) }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
    )
}

@Composable
private fun DownloadedSeriesCard(
    series: DownloadedSeries,
    downloadStatus: SeriesDownloadStatus?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onStopDownloads: () -> Unit,
) {
    val isFullyRead = series.isFullyRead()
    var menuExpanded by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                model = series.coverFile,
                title = series.title,
                modifier = Modifier
                    .width(78.dp)
                    .height(110.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${series.chapters.size} capitoli scaricati",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isFullyRead) "Letto" else "${series.chapters.count { it.isRead }} letti",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFullyRead) ReadGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                downloadStatus?.let { status ->
                    Spacer(modifier = Modifier.height(10.dp))
                    SeriesDownloadSummary(
                        status = status,
                        onStopDownloads = onStopDownloads,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Azioni manga",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Info") },
                        leadingIcon = {
                            Icon(Icons.Default.Info, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            showInfoDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Elimina") },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(series.title) },
            text = {
                Text(buildSeriesInfoText(series))
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Chiudi")
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Elimina manga") },
            text = { Text("Vuoi eliminare ${series.title} dalla memoria del telefono?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }
}

@Composable
private fun SeriesDownloadSummary(
    status: SeriesDownloadStatus,
    onStopDownloads: () -> Unit,
) {
    val fraction = if (status.doneChapters >= 0 && status.totalChapters > 0) {
        status.doneChapters.toFloat() / status.totalChapters.toFloat()
    } else {
        null
    }
    val title = when (status.state) {
        WorkInfo.State.RUNNING -> status.message ?: "Download in corso"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            if (status.requestCount > 1) "In coda (${status.requestCount})" else "In coda"
        else -> status.message ?: "Download"
    }
    val supporting = when {
        status.state == WorkInfo.State.RUNNING && fraction != null ->
            "${status.doneChapters} / ${status.totalChapters} capitoli"
        status.state == WorkInfo.State.RUNNING -> "Preparazione download"
        !status.message.isNullOrBlank() -> status.message
        else -> null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    supporting?.let { text ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = onStopDownloads) {
                    Text("Ferma coda")
                }
            }
            if (status.state == WorkInfo.State.RUNNING) {
                Spacer(modifier = Modifier.height(8.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DownloadedSeriesScreen(
    series: DownloadedSeries,
    selectedChapterPaths: Set<String>,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
    onToggleChapterSelection: (DownloadedChapter) -> Unit,
    onStartChapterSelection: (DownloadedChapter) -> Unit,
) {
    val selectionMode = selectedChapterPaths.isNotEmpty()
    val isFullyRead = series.isFullyRead()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SeriesHeader(
            coverModel = series.coverFile,
            title = series.title,
            subtitle = "${series.chapters.size} capitoli scaricati",
            status = if (isFullyRead) "Letto" else null,
            statusColor = ReadGreen,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                items(series.chapters, key = { it.relativePath }) { chapter ->
                    DownloadedChapterRow(
                        chapter = chapter,
                        isSelected = chapter.relativePath in selectedChapterPaths,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                onToggleChapterSelection(chapter)
                            } else {
                                onOpenChapter(chapter)
                            }
                        },
                        onLongClick = { onStartChapterSelection(chapter) },
                    )
                }
            }
            if (series.chapters.isNotEmpty()) {
                ScrollToBottomButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(series.chapters.lastIndex)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    chapter: DownloadedChapter?,
    previousChapter: DownloadedChapter?,
    nextChapter: DownloadedChapter?,
    pages: List<File>,
    isLoading: Boolean,
    padding: PaddingValues,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        chapter == null || pages.isEmpty() -> {
            EmptyStateText(
                text = "Nessuna pagina disponibile",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("reader-nav-top") {
                    ReaderChapterNavigationRow(
                        previousChapter = previousChapter,
                        nextChapter = nextChapter,
                        onOpenPrevious = onOpenPrevious,
                        onOpenNext = onOpenNext,
                    )
                }
                items(pages, key = { it.absolutePath }) { page ->
                    AsyncImage(
                        model = page,
                        contentDescription = chapter.title,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                item("reader-nav-bottom") {
                    ReaderChapterNavigationRow(
                        previousChapter = previousChapter,
                        nextChapter = nextChapter,
                        onOpenPrevious = onOpenPrevious,
                        onOpenNext = onOpenNext,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderChapterNavigationRow(
    previousChapter: DownloadedChapter?,
    nextChapter: DownloadedChapter?,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onOpenPrevious,
            enabled = previousChapter != null,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Precedente")
        }
        TextButton(
            onClick = onOpenNext,
            enabled = nextChapter != null,
        ) {
            Text("Successivo")
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SeriesHeader(
    coverModel: Any?,
    title: String,
    subtitle: String,
    status: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onDownloadAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            model = coverModel,
            title = title,
            modifier = Modifier
                .width(96.dp)
                .height(144.dp)
                .clip(MaterialTheme.shapes.medium),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!status.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        onDownloadAll?.let { downloadAll ->
            IconButton(onClick = downloadAll) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Scarica tutto il manga",
                )
            }
        }
    }
}

@Composable
private fun ScrollToBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = modifier,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardDoubleArrowDown,
            contentDescription = "Vai in fondo",
            tint = Color.White,
        )
    }
}

@Composable
private fun CoverImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
) {
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChapterRow(chapter: ChapterEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "Capitolo ${chapter.displayNumber()}",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedChapterRow(
    chapter: DownloadedChapter,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (chapter.isRead) "Letto" else "Non letto",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chapter.isRead) ReadGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                )
            } else if (chapter.isRead) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Letto",
                    tint = ReadGreen,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun buildSeriesDownloadStatuses(workInfos: List<WorkInfo>): Map<String, SeriesDownloadStatus> {
    val sorted = workInfos.sortedBy { statePriority(it.state) }
    val grouped = linkedMapOf<String, MutableList<WorkInfo>>()
    for (workInfo in sorted) {
        val mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: workInfo.inputData.getString(DownloadWorker.INPUT_MANGA_URL)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val title = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: workInfo.inputData.getString(DownloadWorker.INPUT_SERIES_TITLE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val key = downloadSeriesKey(mangaUrl = mangaUrl, title = title) ?: continue
        grouped.getOrPut(key) { mutableListOf() } += workInfo
    }

    return grouped.mapValues { (_, entries) ->
        val workInfo = entries.first()
        SeriesDownloadStatus(
            seriesTitle = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
                ?: workInfo.inputData.getString(DownloadWorker.INPUT_SERIES_TITLE),
            mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
                ?: workInfo.inputData.getString(DownloadWorker.INPUT_MANGA_URL),
            message = workInfo.progress.getString(DownloadWorker.PROGRESS_MESSAGE),
            doneChapters = workInfo.progress.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1),
            totalChapters = workInfo.progress.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1),
            state = workInfo.state,
            requestCount = entries.size,
        )
    }
}

private fun downloadStatusForSeries(
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    series: DownloadedSeries,
): SeriesDownloadStatus? {
    val primaryKey = downloadSeriesKey(series.mangaUrl, series.title)
    if (primaryKey != null) {
        downloadStatuses[primaryKey]?.let { return it }
    }
    val fallbackKey = downloadSeriesKey(null, series.title) ?: return null
    return downloadStatuses[fallbackKey]
}

private fun downloadSeriesKey(mangaUrl: String?, title: String?): String? {
    val normalizedUrl = mangaUrl?.trim()?.takeIf { it.isNotBlank() }
    if (normalizedUrl != null) {
        val mangaMatch = Regex("""^https?://mangapill\.com/manga/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(normalizedUrl)
        if (mangaMatch != null) {
            return "url:https://mangapill.com/manga/${mangaMatch.groupValues[1]}"
        }
        val chapterMatch = Regex("""^https?://mangapill\.com/chapters/([^/]+)/""", RegexOption.IGNORE_CASE)
            .find(normalizedUrl)
        if (chapterMatch != null) {
            val mangaId = chapterMatch.groupValues[1].substringBefore('-')
            if (mangaId.isNotBlank()) {
                return "url:https://mangapill.com/manga/$mangaId"
            }
        }
        return "url:$normalizedUrl"
    }
    val normalizedTitle = title?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    return normalizedTitle?.let { "title:$it" }
}

private fun statePriority(state: WorkInfo.State): Int {
    return when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED -> 1
        WorkInfo.State.BLOCKED -> 2
        else -> 3
    }
}

@Composable
private fun EmptyStateText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DownloadedSeries.isFullyRead(): Boolean = chapters.isNotEmpty() && chapters.all { it.isRead }

private fun buildSeriesInfoText(series: DownloadedSeries): String {
    val totalSizeBytes = series.directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
    val readCount = series.chapters.count { it.isRead }
    return buildString {
        appendLine("Capitoli: ${series.chapters.size}")
        appendLine("Letti: $readCount")
        appendLine("Dimensione: ${formatBytes(totalSizeBytes)}")
        appendLine("Percorso: ${series.directory.absolutePath}")
        series.mangaUrl?.takeIf { it.isNotBlank() }?.let { url ->
            appendLine("Sorgente: $url")
        }
    }.trim()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

private val ReadGreen = Color(0xFF2E7D32)
private val FavoriteYellow = Color(0xFFF4B400)
