package com.lorenzo.mangadownloader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

data class SeriesDownloadStatus(
    val sourceId: String,
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
    val searchConfig = MangaSourceCatalog.searchConfig(state.settings.searchSourceId)

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
                        items(
                            state.results,
                            key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                        ) { result ->
                            ResultCard(
                                result = result,
                                isFavorite = MangaSourceCatalog.identityKey(
                                    result.sourceId,
                                    result.mangaUrl,
                                ) in state.favoriteMangaKeys,
                                onClick = { onSelect(result) },
                                onToggleFavorite = { onToggleFavorite(result) },
                            )
                        }
                    }
                }
                trimmed.isEmpty() -> {
                    EmptyStateText(
                        text = if (searchConfig.showAllOnEmptyQuery) {
                            "Nessun risultato"
                        } else {
                            "Digita per cercare"
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                trimmed.length < searchConfig.minQueryLength -> {
                    EmptyStateText(
                        text = if (searchConfig.minQueryLength == 1) {
                            "Digita almeno 1 carattere"
                        } else {
                            "Digita almeno ${searchConfig.minQueryLength} caratteri"
                        },
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
                        items(
                            filtered,
                            key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                        ) { favorite ->
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
    isBiometricAvailable: Boolean,
    isParentalAuthInProgress: Boolean,
    padding: PaddingValues,
    onToggleAutoDownload: (Boolean) -> Unit,
    onTriggerChange: (Int) -> Unit,
    onBatchChange: (Int) -> Unit,
    onToggleSmartCleanup: (Boolean) -> Unit,
    onSmartCleanupKeepChange: (Int) -> Unit,
    onToggleParentalControl: (Boolean) -> Unit,
    onRequestChangeParentalPin: () -> Unit,
    onToggleParentalBiometric: (Boolean) -> Unit,
    onToggleLabs: (Boolean) -> Unit,
    onSelectAutoReaderSpeed: (AutoReaderSpeed) -> Unit,
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

        Text(
            text = "Libera memoria intelligente",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Elimina i capitoli letti più vecchi",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Quando apri un capitolo, mantiene solo gli ultimi capitoli precedenti che scegli tu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.smartCleanupEnabled,
                onCheckedChange = onToggleSmartCleanup,
            )
        }
        NumberSettingField(
            label = "Capitoli precedenti da mantenere",
            value = settings.smartCleanupKeepPreviousChapters,
            enabled = settings.smartCleanupEnabled,
            onValueChange = onSmartCleanupKeepChange,
        )

        Text(
            text = "Parental control",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Proteggi la ricerca con PIN",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (settings.parentalControlEnabled) {
                        if (settings.parentalPinConfigured) {
                            "PIN configurato. Cerca richiede autenticazione a ogni accesso."
                        } else {
                            "Attivo ma PIN non ancora configurato."
                        }
                    } else {
                        "Disattivato di default. Preferiti e Libreria restano sempre liberi."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.parentalControlEnabled,
                enabled = !isParentalAuthInProgress,
                onCheckedChange = onToggleParentalControl,
            )
        }

        if (settings.parentalControlEnabled && settings.parentalPinConfigured && isBiometricAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Usa anche la biometria",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Puoi usare impronta o volto, con PIN sempre disponibile come fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.parentalBiometricEnabled,
                    enabled = !isParentalAuthInProgress,
                    onCheckedChange = onToggleParentalBiometric,
                )
            }
        }

        if (settings.parentalControlEnabled && settings.parentalPinConfigured) {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isParentalAuthInProgress,
                onClick = onRequestChangeParentalPin,
            ) {
                Text("Cambia PIN")
            }
        }

        Text(
            text = "Labs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Funzionalità in sviluppo",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Mostra opzioni sperimentali. Possono cambiare o sparire nelle prossime versioni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.labsEnabled,
                onCheckedChange = onToggleLabs,
            )
        }

        if (settings.labsEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Reader automatico",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Scorre la pagina automaticamente verso il basso. Tiene lo schermo acceso. Puoi sempre scrollare a mano.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoReaderSpeed.values().forEach { speed ->
                        FilterChip(
                            selected = settings.autoReaderSpeed == speed,
                            onClick = { onSelectAutoReaderSpeed(speed) },
                            label = { Text(speed.displayLabel()) },
                        )
                    }
                }
                if (settings.autoReaderSpeed == AutoReaderSpeed.SMART) {
                    Text(
                        text = "Intelligente analizza il testo di ogni pagina con ML Kit (on-device) per regolare la velocità: pagine dense vanno più lente, pagine vuote più veloci.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun AutoReaderSpeed.displayLabel(): String = when (this) {
    AutoReaderSpeed.OFF -> "Off"
    AutoReaderSpeed.CALM -> "Calmo"
    AutoReaderSpeed.NORMAL -> "Normale"
    AutoReaderSpeed.FAST -> "Veloce"
    AutoReaderSpeed.SMART -> "Intelligente"
}

@Composable
fun DownloadedSeriesScreen(
    series: DownloadedSeries,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
    onDeleteChapter: (DownloadedChapter) -> Unit,
) {
    val isFullyRead = series.isFullyRead()
    var chapterPendingDelete by remember { mutableStateOf<DownloadedChapter?>(null) }
    val firstChapter = remember(series) { series.chapters.firstOrNull() }
    val resumeChapter = remember(series) { series.resumeChapter() }

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

        LazyColumn(
            modifier = Modifier.weight(1f),
        ) {
            items(series.chapters, key = { it.relativePath }) { chapter ->
                DownloadedChapterRow(
                    chapter = chapter,
                    onOpen = { onOpenChapter(chapter) },
                    onDelete = { chapterPendingDelete = chapter },
                )
            }
        }

        DownloadedSeriesActionBar(
            readCount = series.readChapterCount(),
            totalCount = series.totalChapterCount,
            firstChapter = firstChapter,
            resumeChapter = resumeChapter,
            onOpenChapter = onOpenChapter,
        )
    }

    chapterPendingDelete?.let { chapter ->
        DeleteChapterDialog(
            chapterTitle = chapter.title,
            onDismiss = { chapterPendingDelete = null },
            onConfirm = {
                chapterPendingDelete = null
                onDeleteChapter(chapter)
            },
        )
    }
}

@Composable
private fun DownloadedSeriesActionBar(
    readCount: Int,
    totalCount: Int,
    firstChapter: DownloadedChapter?,
    resumeChapter: DownloadedChapter?,
    onOpenChapter: (DownloadedChapter) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    totalCount == 0 -> "Nessun capitolo disponibile"
                    readCount == 0 -> "$totalCount capitoli scaricati"
                    readCount >= totalCount -> "Hai letto tutti i $totalCount capitoli"
                    else -> "$readCount / $totalCount capitoli letti"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { firstChapter?.let(onOpenChapter) },
                    enabled = firstChapter != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Inizio")
                }
                FilledTonalButton(
                    onClick = { resumeChapter?.let(onOpenChapter) },
                    enabled = resumeChapter != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Riprendi")
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
    autoReaderSpeed: AutoReaderSpeed,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
) {
    val minScale = 1f
    val maxScale = 4f
    var readerScale by remember(chapter?.relativePath) { mutableStateOf(minScale) }
    var readerOffsetX by remember(chapter?.relativePath) { mutableStateOf(0f) }
    var readerOffsetY by remember(chapter?.relativePath) { mutableStateOf(0f) }
    var viewportSize by remember(chapter?.relativePath) { mutableStateOf(IntSize.Zero) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val analyzer = remember { PageReadingAnalyzer(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { analyzer.close() }
    }

    DisposableEffect(autoReaderSpeed) {
        val window = (view.context as? Activity)?.window
        if (autoReaderSpeed != AutoReaderSpeed.OFF) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(autoReaderSpeed, chapter?.relativePath) {
        if (autoReaderSpeed == AutoReaderSpeed.OFF || chapter == null) return@LaunchedEffect
        val fixedPxPerSecond = with(density) { autoReaderSpeed.dpPerSecond.dp.toPx() }
        val fallbackPxPerSecond = with(density) { AutoReaderSpeed.NORMAL.dpPerSecond.dp.toPx() }
        if (autoReaderSpeed == AutoReaderSpeed.SMART) {
            analyzer.prefetch(pages, startIndex = 0)
        }
        var lastFrame = 0L
        var residual = 0f
        var lastPrefetchedFrom = -1
        while (true) {
            withFrameNanos { now ->
                if (lastFrame != 0L) {
                    val deltaSec = (now - lastFrame) / 1_000_000_000f
                    val pxPerSecond = if (autoReaderSpeed == AutoReaderSpeed.SMART) {
                        val info = listState.layoutInfo
                        val visiblePage = info.visibleItemsInfo.firstOrNull { item ->
                            val k = item.key
                            k != "reader-nav-top" && k != "reader-nav-bottom" && k is String
                        }
                        val pageIndex = visiblePage
                            ?.let { vp -> pages.indexOfFirst { it.absolutePath == vp.key } }
                            ?: -1
                        if (pageIndex >= 0 && pageIndex > lastPrefetchedFrom) {
                            analyzer.prefetch(pages, startIndex = pageIndex)
                            lastPrefetchedFrom = pageIndex
                        }
                        val pageHeightPx = visiblePage?.size?.toFloat() ?: 0f
                        val timeMs = if (pageIndex >= 0) analyzer.cachedTimeMs(pages[pageIndex]) else null
                        if (timeMs != null && pageHeightPx > 0f) {
                            pageHeightPx / (timeMs / 1000f)
                        } else {
                            fallbackPxPerSecond
                        }
                    } else {
                        fixedPxPerSecond
                    }
                    val moveF = pxPerSecond * deltaSec + residual
                    val moveInt = moveF.toInt()
                    residual = moveF - moveInt
                    if (moveInt > 0) {
                        coroutineScope.launch { listState.scrollBy(moveInt.toFloat()) }
                    }
                }
                lastFrame = now
            }
        }
    }

    fun clampOffsets(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Pair<Float, Float> {
        val maxX = ((viewportSize.width * (scale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportSize.height * (scale - 1f)) / 2f).coerceAtLeast(0f)
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (readerScale * zoomChange).coerceIn(minScale, maxScale)
        if (nextScale <= minScale) {
            readerScale = minScale
            readerOffsetX = 0f
            readerOffsetY = 0f
        } else {
            val (clampedX, clampedY) = clampOffsets(
                scale = nextScale,
                offsetX = readerOffsetX + panChange.x,
                offsetY = readerOffsetY + panChange.y,
            )
            readerScale = nextScale
            readerOffsetX = clampedX
            readerOffsetY = clampedY
        }
    }

    LaunchedEffect(chapter?.relativePath) {
        readerScale = minScale
        readerOffsetX = 0f
        readerOffsetY = 0f
        viewportSize = IntSize.Zero
    }

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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(if (readerScale > minScale) Color.Black else Color.Transparent)
                    .clipToBounds()
                    .transformable(state = transformableState)
                    .pointerInput(chapter.relativePath, readerScale) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (readerScale > minScale) {
                                    readerScale = minScale
                                    readerOffsetX = 0f
                                    readerOffsetY = 0f
                                } else {
                                    readerScale = 2f
                                }
                            },
                        )
                    }
                    .onSizeChanged { size ->
                        viewportSize = size
                        val (clampedX, clampedY) = clampOffsets(
                            scale = readerScale,
                            offsetX = readerOffsetX,
                            offsetY = readerOffsetY,
                        )
                        readerOffsetX = clampedX
                        readerOffsetY = clampedY
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = readerScale
                            scaleY = readerScale
                            translationX = readerOffsetX
                            translationY = readerOffsetY
                        },
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
                minLines = 2,
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
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
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
                        text = it.readProgressLabel(),
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
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
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

@Composable
private fun DownloadedChapterRow(
    chapter: DownloadedChapter,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (chapter.isRead) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Letto",
                    tint = ReadGreen,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(22.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Elimina capitolo",
                )
            }
        }
    }
}

fun buildSeriesDownloadStatuses(workInfos: List<WorkInfo>): Map<String, SeriesDownloadStatus> {
    val sorted = workInfos.sortedBy { statePriority(it.state) }
    val grouped = linkedMapOf<String, MutableList<WorkInfo>>()

    for (workInfo in sorted) {
        val sourceId = workInfo.progress.getString(DownloadWorker.PROGRESS_SOURCE_ID)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: workInfo.tagValue(DownloadWorker.TAG_SOURCE_ID_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
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
        val key = downloadSeriesKey(sourceId = sourceId, mangaUrl = mangaUrl, title = title) ?: continue
        grouped.getOrPut(key) { mutableListOf() } += workInfo
    }

    return grouped.mapValues { (_, entries) ->
        val workInfo = entries.first()
        SeriesDownloadStatus(
            sourceId = workInfo.progress.getString(DownloadWorker.PROGRESS_SOURCE_ID)
                ?: workInfo.tagValue(DownloadWorker.TAG_SOURCE_ID_PREFIX)
                ?: MangaSourceCatalog.resolveSourceId(
                    null,
                    workInfo.progress.getString(DownloadWorker.PROGRESS_MANGA_URL)
                        ?: workInfo.tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX),
                ),
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
    val primaryKey = downloadSeriesKey(series.sourceId, series.mangaUrl, series.title)
    if (primaryKey != null) {
        downloadStatuses[primaryKey]?.let { return it }
    }
    return downloadSeriesKey(series.sourceId, null, series.title)?.let(downloadStatuses::get)
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
        downloadSeriesKey(series.sourceId, series.mangaUrl, series.title)
            ?.takeIf { status != null }
            ?.let(usedStatusKeys::add)
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
        compareBy<LibraryRowItem> { it.title.lowercase() },
    )
}

private fun downloadSeriesKey(
    sourceId: String?,
    mangaUrl: String?,
    title: String?,
): String? {
    return MangaSourceCatalog.identityKeyOrNull(sourceId, mangaUrl, title)
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
    return totalChapterCount > 0 && readChapterCount() >= totalChapterCount
}

private fun DownloadedSeries.resumeChapter(): DownloadedChapter? {
    return chapters.firstOrNull { !it.isRead } ?: chapters.lastOrNull()
}

private fun DownloadedSeries.readChapterCount(): Int {
    return readChapterIds.size.coerceAtMost(totalChapterCount.coerceAtLeast(0))
}

private fun DownloadedSeries.readProgressPercent(): Int {
    if (totalChapterCount <= 0) return 0
    return ((readChapterCount() * 100f) / totalChapterCount.toFloat()).toInt()
}

private fun DownloadedSeries.readProgressLabel(): String {
    val readCount = readChapterCount()
    return when {
        totalChapterCount <= 0 -> "$readCount letti"
        readCount >= totalChapterCount -> "100% letto · $readCount / $totalChapterCount"
        else -> "${readProgressPercent()}% letto · $readCount / $totalChapterCount"
    }
}

private fun buildSeriesInfoText(series: DownloadedSeries): String {
    val totalSizeBytes = series.directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)
    return buildString {
        appendLine("Capitoli scaricati: ${series.chapters.size}")
        appendLine("Capitoli totali: ${series.totalChapterCount}")
        appendLine("Progresso: ${series.readProgressLabel()}")
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
