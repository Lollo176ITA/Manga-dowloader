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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Stop
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(DownloadWorker.UNIQUE_WORK_NAME)
        .observeAsState(emptyList())
    val latestWork = workInfos.maxByOrNull { it.id.toString() }
    val latestDone = latestWork?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appContext = remember(context) { context.applicationContext }
    var lastCrashReport by remember {
        mutableStateOf(CrashReporter.readLastCrash(appContext))
    }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showDeleteSeriesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(latestWork?.id, latestWork?.state, latestDone) {
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

    val onStartDownload: (ChapterEntry) -> Unit = { chapter ->
        val firstUrl = chapter.url.trim()
        if (firstUrl.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("URL capitolo non valido")
            }
        } else {
            try {
                DownloadWorker.enqueue(appContext, firstUrl)
                viewModel.selectTab(AppTab.DOWNLOADS)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Download avviato dal capitolo ${chapter.displayNumber()}",
                    )
                }
            } catch (exc: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(exc.message ?: "Impossibile avviare il download")
                }
            }
        }
    }

    val isDownloadActive = latestWork?.state == WorkInfo.State.RUNNING ||
        latestWork?.state == WorkInfo.State.ENQUEUED ||
        latestWork?.state == WorkInfo.State.BLOCKED

    val showBottomBar = state.readerChapter == null
    val canHandleBack = state.readerChapter != null ||
        state.selectedChapterPaths.isNotEmpty() ||
        (state.currentTab == AppTab.SEARCH && state.selected != null) ||
        (state.currentTab == AppTab.DOWNLOADS && state.selectedDownloadedSeries != null)

    val handleBack: () -> Unit = {
        when {
            state.readerChapter != null -> viewModel.closeReader()
            state.selectedChapterPaths.isNotEmpty() -> viewModel.clearChapterSelection()
            state.currentTab == AppTab.DOWNLOADS && state.selectedDownloadedSeries != null ->
                viewModel.clearDownloadedSelection()
            state.currentTab == AppTab.SEARCH && state.selected != null ->
                viewModel.clearSelection()
        }
    }

    BackHandler(enabled = canHandleBack, onBack = handleBack)

    Scaffold(
        topBar = {
            AppTopBar(
                state = state,
                onBack = handleBack,
                onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                onDeleteSelected = { showDeleteSelectedDialog = true },
                onDeleteSeries = { showDeleteSeriesDialog = true },
            )
        },
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentTab = state.currentTab,
                    onSelect = viewModel::selectTab,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
            state.currentTab == AppTab.SEARCH -> {
                val selected = state.selected
                if (selected == null) {
                    SearchScreen(
                        state = state,
                        padding = innerPadding,
                        onQueryChange = viewModel::onQueryChange,
                        onSelect = viewModel::selectManga,
                    )
                } else {
                    DetailScreen(
                        details = selected,
                        isLoading = state.isLoadingDetails,
                        padding = innerPadding,
                        onStart = onStartDownload,
                    )
                }
            }
            else -> {
                val selectedSeries = state.selectedDownloadedSeries
                if (selectedSeries == null) {
                    DownloadsScreen(
                        state = state,
                        padding = innerPadding,
                        status = latestWork,
                        isDownloadActive = isDownloadActive,
                        onStopDownload = { workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME) },
                        onOpenSeries = viewModel::selectDownloadedSeries,
                        onDeleteSeries = viewModel::deleteDownloadedSeries,
                    )
                } else {
                    DownloadedSeriesScreen(
                        series = selectedSeries,
                        selectedChapterPaths = state.selectedChapterPaths,
                        padding = innerPadding,
                        onOpenChapter = viewModel::openReader,
                        onToggleChapterSelection = viewModel::toggleChapterSelection,
                        onStartChapterSelection = viewModel::startChapterSelection,
                    )
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
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteSeries: () -> Unit,
) {
    val showBack = state.readerChapter != null ||
        state.selectedChapterPaths.isNotEmpty() ||
        (state.currentTab == AppTab.SEARCH && state.selected != null) ||
        (state.currentTab == AppTab.DOWNLOADS && state.selectedDownloadedSeries != null)
    val title = when {
        state.selectedChapterPaths.isNotEmpty() -> "${state.selectedChapterPaths.size} selezionati"
        state.readerChapter != null -> state.readerChapter.title
        state.currentTab == AppTab.SEARCH && state.selected != null -> state.selected.title
        state.currentTab == AppTab.DOWNLOADS && state.selectedDownloadedSeries != null ->
            state.selectedDownloadedSeries.title
        state.currentTab == AppTab.SEARCH -> "Manga Downloader"
        else -> "Download"
    }
    val barColor = MaterialTheme.colorScheme.surface

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
            val selectedManga = state.selected
            if (state.currentTab == AppTab.SEARCH && selectedManga != null) {
                val isFavorite = selectedManga.mangaUrl in state.favoriteMangaUrls
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                        tint = if (isFavorite) FavoriteYellow else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else if (state.currentTab == AppTab.DOWNLOADS &&
                state.readerChapter == null &&
                state.selectedDownloadedSeries != null
            ) {
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
            selected = currentTab == AppTab.DOWNLOADS,
            onClick = { onSelect(AppTab.DOWNLOADS) },
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
            label = { Text("Download") },
        )
    }
}

@Composable
private fun SearchScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (MangaSearchResult) -> Unit,
) {
    val trimmed = state.query.trim()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("Cerca manga") },
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Pulisci")
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
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
                            ResultCard(result = result, onClick = { onSelect(result) })
                        }
                    }
                }
                trimmed.isEmpty() && state.favorites.isNotEmpty() -> {
                    FavoritesSection(
                        favorites = state.favorites,
                        modifier = Modifier.fillMaxSize(),
                        onSelect = { favorite ->
                            onSelect(
                                MangaSearchResult(
                                    title = favorite.title,
                                    mangaUrl = favorite.mangaUrl,
                                    coverUrl = favorite.coverUrl,
                                ),
                            )
                        },
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
private fun ResultCard(result: MangaSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            CoverImage(
                model = result.coverUrl,
                title = result.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
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
private fun FavoritesSection(
    favorites: List<FavoriteManga>,
    modifier: Modifier = Modifier,
    onSelect: (FavoriteManga) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("favorites-header") {
            Text(
                text = "Preferiti",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(favorites, key = { it.mangaUrl }) { favorite ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(favorite) },
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(
                        model = favorite.coverUrl,
                        title = favorite.title,
                        modifier = Modifier
                            .width(64.dp)
                            .height(92.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = favorite.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Apri manga",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = FavoriteYellow,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(
    details: MangaDetails,
    isLoading: Boolean,
    padding: PaddingValues,
    onStart: (ChapterEntry) -> Unit,
) {
    var pending by remember { mutableStateOf<ChapterEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SeriesHeader(
            coverModel = details.coverUrl,
            title = details.title,
            subtitle = "${details.chapters.size} capitoli disponibili",
        )

        if (isLoading && details.chapters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(details.chapters.reversed(), key = { it.url }) { chapter ->
                    ChapterRow(chapter = chapter, onClick = { pending = chapter })
                }
            }
        }
    }

    pending?.let { chapter ->
        val label = chapter.displayNumber()
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Capitolo $label") },
            text = { Text("Scarica da questo capitolo in poi?") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onStart(chapter)
                }) { Text("Avvia") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun DownloadsScreen(
    state: MangaUiState,
    padding: PaddingValues,
    status: WorkInfo?,
    isDownloadActive: Boolean,
    onStopDownload: () -> Unit,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onDeleteSeries: (DownloadedSeries) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        DownloadStatusStrip(
            status = status,
            isActive = isDownloadActive,
            onStop = onStopDownload,
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
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.library, key = { it.directory.absolutePath }) { series ->
                        DownloadedSeriesCard(
                            series = series,
                            onClick = { onOpenSeries(series) },
                            onDelete = { onDeleteSeries(series) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedSeriesCard(
    series: DownloadedSeries,
    onClick: () -> Unit,
    onDelete: () -> Unit,
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

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(series.chapters.reversed(), key = { it.relativePath }) { chapter ->
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
            Spacer(modifier = Modifier.height(8.dp))
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

@Composable
private fun DownloadStatusStrip(
    status: WorkInfo?,
    isActive: Boolean,
    onStop: () -> Unit,
) {
    val progressMessage = status?.progress?.getString(DownloadWorker.PROGRESS_MESSAGE)
    val visibleMessage = progressMessage
    val done = status?.progress?.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1) ?: -1
    val total = status?.progress?.getInt(DownloadWorker.PROGRESS_TOTAL_CHAPTERS, -1) ?: -1

    if (!isActive) {
        return
    }

    val fraction = if (done >= 0 && total > 0) done.toFloat() / total.toFloat() else null
    val title = visibleMessage ?: "Download in corso"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Ferma download",
                        )
                    }
                }
            }
            if (fraction != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$done / $total",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
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
