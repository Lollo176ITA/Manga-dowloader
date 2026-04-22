package com.lorenzo.mangadownloader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

data class SeriesDownloadStatus(
    val seriesTitle: String?,
    val mangaUrl: String?,
    val coverUrl: String?,
    val message: String?,
    val doneChapters: Int,
    val totalChapters: Int,
    val state: WorkInfo.State,
    val requestCount: Int,
)

data class LibraryRowItem(
    val key: String,
    val title: String,
    val series: DownloadedSeries?,
    val downloadStatus: SeriesDownloadStatus?,
)

@Composable
fun SearchScreen(
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
                trimmed.length < 3 -> {
                    EmptyStateText(
                        text = "Digita almeno 3 caratteri",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                else -> {
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
fun FavoritesScreen(
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
                            FavoriteCard(
                                favorite = favorite,
                                onClick = { onSelect(favorite) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
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
            onDownloadAll = details.chapters.takeIf { it.isNotEmpty() }?.let { chapters ->
                { onStart(details, chapters.first(), chapters.last()) }
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
                        ChapterRow(chapter = chapter) {
                            pendingStart = chapter
                            pendingEnd = chapter
                            endMenuExpanded = false
                        }
                    }
                }
                if (details.chapters.isNotEmpty()) {
                    ScrollToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        scope.launch {
                            listState.animateScrollToItem(details.chapters.lastIndex)
                        }
                    }
                }
            }
        }
    }

    pendingStart?.let { startChapter ->
        val endOptions = remember(details.chapters, startChapter.url) {
            val startIndex = details.chapters.indexOfFirst { it.url == startChapter.url }
            if (startIndex >= 0) details.chapters.subList(startIndex, details.chapters.size)
            else listOf(startChapter)
        }
        DownloadRangeDialog(
            startChapter = startChapter,
            endChapter = pendingEnd ?: startChapter,
            endOptions = endOptions,
            endMenuExpanded = endMenuExpanded,
            onDismiss = {
                pendingStart = null
                pendingEnd = null
                endMenuExpanded = false
            },
            onOpenEndMenu = { endMenuExpanded = true },
            onDismissEndMenu = { endMenuExpanded = false },
            onSelectEnd = { chapter ->
                pendingEnd = chapter
                endMenuExpanded = false
            },
            onConfirm = { endChapter ->
                pendingStart = null
                pendingEnd = null
                endMenuExpanded = false
                onStart(details, startChapter, endChapter)
            },
        )
    }
}

@Composable
fun LibraryScreen(
    state: MangaUiState,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    padding: PaddingValues,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onDeleteSeries: (DownloadedSeries) -> Unit,
    onQueryChange: (String) -> Unit,
    onStopDownloads: () -> Unit,
) {
    val rows = remember(state.library, state.libraryQuery, downloadStatuses) {
        buildLibraryRowItems(
            library = state.library,
            downloadStatuses = downloadStatuses,
            query = state.libraryQuery.trim(),
        )
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
            state.isLoadingLibrary && rows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                }
            }
            rows.isEmpty() && state.library.isEmpty() && downloadStatuses.isEmpty() -> {
                EmptyStateText(
                    text = "Nessun manga scaricato",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            rows.isEmpty() -> {
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
                    items(rows, key = { it.key }) { row ->
                        LibrarySeriesCard(
                            row = row,
                            onClick = { row.series?.let(onOpenSeries) },
                            onDelete = { row.series?.let(onDeleteSeries) },
                            onStopDownloads = onStopDownloads,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
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
fun DownloadedSeriesScreen(
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
                ) {
                    scope.launch {
                        listState.animateScrollToItem(series.chapters.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderScreen(
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
private fun FavoriteCard(
    favorite: FavoriteManga,
    onClick: () -> Unit,
) {
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

@Composable
private fun DownloadRangeDialog(
    startChapter: ChapterEntry,
    endChapter: ChapterEntry,
    endOptions: List<ChapterEntry>,
    endMenuExpanded: Boolean,
    onDismiss: () -> Unit,
    onOpenEndMenu: () -> Unit,
    onDismissEndMenu: () -> Unit,
    onSelectEnd: (ChapterEntry) -> Unit,
    onConfirm: (ChapterEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleziona intervallo download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = "Capitolo ${startChapter.displayNumber()}",
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Da") },
                    readOnly = true,
                    singleLine = true,
                )
                Box {
                    OutlinedTextField(
                        value = "Capitolo ${endChapter.displayNumber()}",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        label = { Text("A") },
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(onClick = onOpenEndMenu),
                    )
                    DropdownMenu(
                        expanded = endMenuExpanded,
                        onDismissRequest = onDismissEndMenu,
                    ) {
                        endOptions.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text("Capitolo ${candidate.displayNumber()}") },
                                onClick = { onSelectEnd(candidate) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(endChapter) }) {
                Text("Avvia")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
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
            val digits = newText.filter(Char::isDigit).take(3)
            text = digits
            digits.toIntOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
    )
}

@Composable
private fun LibrarySeriesCard(
    row: LibraryRowItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onStopDownloads: () -> Unit,
) {
    val series = row.series
    val downloadStatus = row.downloadStatus
    val isFullyRead = series?.isFullyRead() == true
    var menuExpanded by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = if (series != null) {
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                model = series?.coverFile ?: downloadStatus?.coverUrl,
                title = row.title,
                modifier = Modifier
                    .width(78.dp)
                    .height(110.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        series != null -> "${series.chapters.size} capitoli scaricati"
                        downloadStatus?.totalChapters?.let { it > 0 } == true -> "Download avviato"
                        else -> "In coda"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                series?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isFullyRead) "Letto" else "${it.chapters.count { chapter -> chapter.isRead }} letti",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFullyRead) ReadGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                downloadStatus?.let { status ->
                    Spacer(modifier = Modifier.height(10.dp))
                    SeriesDownloadSummary(
                        status = status,
                        onStopDownloads = onStopDownloads,
                    )
                }
            }
            if (series != null) {
                SeriesActionsMenu(
                    expanded = menuExpanded,
                    onExpand = { menuExpanded = true },
                    onDismiss = { menuExpanded = false },
                    onShowInfo = {
                        menuExpanded = false
                        showInfoDialog = true
                    },
                    onDelete = {
                        menuExpanded = false
                        showDeleteDialog = true
                    },
                )
            }
        }
    }

    if (showInfoDialog && series != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(series.title) },
            text = { Text(buildSeriesInfoText(series)) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Chiudi")
                }
            },
        )
    }

    if (showDeleteDialog && series != null) {
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
private fun SeriesActionsMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onShowInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(onClick = onExpand) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Azioni manga",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("Info") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = onShowInfo,
            )
            DropdownMenuItem(
                text = { Text("Elimina") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = onDelete,
            )
        }
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
        WorkInfo.State.RUNNING -> "Download in corso"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            if (status.requestCount > 1) "In coda (${status.requestCount})" else "In coda"
        else -> "Download"
    }
    val supporting = when {
        fraction != null -> "${status.doneChapters} / ${status.totalChapters} capitoli"
        status.totalChapters > 0 -> "0 / ${status.totalChapters} capitoli"
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
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onStopDownloads,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Ferma download",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            supporting?.let { text ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun ChapterRow(
    chapter: ChapterEntry,
    onClick: () -> Unit,
) {
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

fun buildSeriesDownloadStatuses(workInfos: List<WorkInfo>): Map<String, SeriesDownloadStatus> {
    val sorted = workInfos.sortedBy { statePriority(it.state) }
    val grouped = linkedMapOf<String, MutableList<WorkInfo>>()

    for (workInfo in sorted) {
        val mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val title = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_SERIES_TITLE_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val key = downloadSeriesKey(mangaUrl = mangaUrl, title = title) ?: continue
        grouped.getOrPut(key) { mutableListOf() } += workInfo
    }

    return grouped.mapValues { (_, entries) ->
        val workInfo = entries.first()
        SeriesDownloadStatus(
            seriesTitle = workInfo.progress.getString(DownloadWorker.PROGRESS_SERIES_TITLE)
                ?: workInfo.tagValue(DownloadWorker.TAG_SERIES_TITLE_PREFIX),
            mangaUrl = workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
                ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX),
            coverUrl = workInfo.tagValue(DownloadWorker.TAG_COVER_URL_PREFIX),
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
    return downloadSeriesKey(null, series.title)?.let(downloadStatuses::get)
}

private fun buildLibraryRowItems(
    library: List<DownloadedSeries>,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    query: String,
): List<LibraryRowItem> {
    val rows = mutableListOf<LibraryRowItem>()
    val usedStatusKeys = linkedSetOf<String>()

    library.forEach { series ->
        val status = downloadStatusForSeries(downloadStatuses, series)
        downloadSeriesKey(series.mangaUrl, series.title)?.takeIf { status != null }?.let(usedStatusKeys::add)
        if (query.isBlank() || series.title.contains(query, ignoreCase = true)) {
            rows += LibraryRowItem(
                key = "series:${series.directory.absolutePath}",
                title = series.title,
                series = series,
                downloadStatus = status,
            )
        }
    }

    downloadStatuses.forEach { (key, status) ->
        if (key in usedStatusKeys) {
            return@forEach
        }
        val title = status.seriesTitle?.takeIf(String::isNotBlank) ?: return@forEach
        if (query.isNotBlank() && !title.contains(query, ignoreCase = true)) {
            return@forEach
        }
        rows += LibraryRowItem(
            key = "pending:$key",
            title = title,
            series = null,
            downloadStatus = status,
        )
    }

    return rows.sortedWith(
        compareByDescending<LibraryRowItem> { it.series == null && it.downloadStatus != null }
            .thenBy { it.title.lowercase() },
    )
}

private fun downloadSeriesKey(mangaUrl: String?, title: String?): String? {
    val normalizedUrl = mangaUrl?.trim()?.takeIf(String::isNotBlank)
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
    return title?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let { "title:$it" }
}

private fun statePriority(state: WorkInfo.State): Int {
    return when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED -> 1
        WorkInfo.State.BLOCKED -> 2
        else -> 3
    }
}

private fun WorkInfo.tagValue(prefix: String): String? {
    return tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
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

private fun DownloadedSeries.isFullyRead(): Boolean {
    return chapters.isNotEmpty() && chapters.all { it.isRead }
}

private fun buildSeriesInfoText(series: DownloadedSeries): String {
    val totalSizeBytes = series.directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)
    val readCount = series.chapters.count { it.isRead }
    return buildString {
        appendLine("Capitoli: ${series.chapters.size}")
        appendLine("Letti: $readCount")
        appendLine("Dimensione: ${formatBytes(totalSizeBytes)}")
        appendLine("Percorso: ${series.directory.absolutePath}")
        series.mangaUrl?.takeIf(String::isNotBlank)?.let { url ->
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

val ReadGreen = Color(0xFF2E7D32)
val FavoriteYellow = Color(0xFFF4B400)
