package com.lorenzo.mangadownloader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.data.library.DownloadedChapter
import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.domain.reading.mostRecentInProgressChapter
import com.lorenzo.mangadownloader.ui.components.ConfirmationDialog
import com.lorenzo.mangadownloader.ui.components.EmptyState
import com.lorenzo.mangadownloader.ui.components.FullScreenLoading
import com.lorenzo.mangadownloader.ui.components.SearchField
import com.lorenzo.mangadownloader.ui.components.icon
import com.lorenzo.mangadownloader.ui.home.ContinueReadingCard
import com.lorenzo.mangadownloader.ui.tutorial.LocalTutorialAnchor
import com.lorenzo.mangadownloader.ui.tutorial.TutorialAnchor

@Composable
fun LibraryScreen(
    state: MangaUiState,
    downloadStatuses: Map<String, SeriesDownloadStatus>,
    padding: PaddingValues,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onDeleteSeries: (DownloadedSeries) -> Unit,
    onDeleteReadChapters: (DownloadedSeries) -> Unit,
    onQueryChange: (String) -> Unit,
    onBrowse: () -> Unit,
    onStopDownloads: () -> Unit,
    onStopSeriesDownload: (SeriesDownloadStatus) -> Unit,
    onResume: (DownloadedChapter) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
    onMarkAllRead: (DownloadedSeries) -> Unit,
) {
    val rows = remember(state.library, state.libraryQuery, downloadStatuses, state.settings.librarySort) {
        buildLibraryRowItems(
            library = state.library,
            downloadStatuses = downloadStatuses,
            query = state.libraryQuery.trim(),
            sort = state.settings.librarySort,
        )
    }
    val hasActiveDownloads = remember(downloadStatuses) {
        downloadStatuses.values.any(::isActiveDownload)
    }
    // Il FAB ferma TUTTI i download, lo stop della card solo quella serie. Entrambi con
    // conferma: il pulsante della card è piccolo e un tap cieco interromperebbe un download
    // magari lungo (i capitoli già salvati restano, ma la coda va rifatta a mano).
    var showStopConfirm by remember { mutableStateOf(false) }
    val requestStopDownloads = { showStopConfirm = true }
    var seriesToStop by remember { mutableStateOf<LibraryRowItem?>(null) }
    val activeSeriesCount = remember(downloadStatuses) {
        downloadStatuses.values.count(::isActiveDownload)
    }
    // "Continua a leggere": mostrata solo senza filtro di ricerca attivo.
    val continueItem = remember(state.library) { state.library.mostRecentInProgressChapter() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                value = state.libraryQuery,
                placeholder = "Cerca nella libreria",
                onValueChange = onQueryChange,
            )

            // Ordinamento persistito (come Preferiti e Gestione memoria): prima la lista
            // era solo alfabetica. Visibile solo quando c'è qualcosa da ordinare.
            if (state.library.isNotEmpty()) {
                LibrarySortPicker(
                    selected = state.settings.librarySort,
                    onSelect = onSelectSort,
                )
            }

            when {
                state.isLoadingLibrary && rows.isEmpty() -> {
                    FullScreenLoading()
                }
                rows.isEmpty() && state.library.isEmpty() && downloadStatuses.isEmpty() -> {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.LibraryBooks,
                        title = "Nessun manga scaricato",
                        description = "Cerca un manga e scarica i capitoli per leggerli offline, anche senza connessione.",
                        actionLabel = "Cerca manga",
                        onAction = onBrowse,
                    )
                }
                rows.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "Nessun manga corrisponde",
                        actionLabel = "Cancella ricerca",
                        onAction = { onQueryChange("") },
                    )
                }
                else -> {
                    val anchorFor = LocalTutorialAnchor.current
                    val firstKey = rows.first().key
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = if (hasActiveDownloads) 96.dp else 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (continueItem != null && state.libraryQuery.isBlank()) {
                            item(key = "continue-reading") {
                                ContinueReadingCard(
                                    item = continueItem,
                                    onResume = onResume,
                                )
                            }
                        }
                        items(rows, key = { it.key }) { row ->
                            val rowModifier = if (row.key == firstKey) {
                                anchorFor(TutorialAnchor.LIBRARY_SERIES_FIRST)
                            } else {
                                Modifier
                            }
                            Box(modifier = rowModifier) {
                                LibrarySeriesCard(
                                    row = row,
                                    onClick = { row.series?.let(onOpenSeries) },
                                    onDelete = { row.series?.let(onDeleteSeries) },
                                    onDeleteReadChapters = { row.series?.let(onDeleteReadChapters) },
                                    onStopDownloads = { seriesToStop = row },
                                    onMarkAllRead = { row.series?.let(onMarkAllRead) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hasActiveDownloads) {
            ExtendedFloatingActionButton(
                onClick = requestStopDownloads,
                icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                text = { Text("Ferma download") },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    seriesToStop?.let { row ->
        val status = row.downloadStatus
        ConfirmationDialog(
            title = "Ferma download",
            text = "Vuoi fermare il download di ${row.title}? I capitoli già scaricati restano in libreria.",
            confirmLabel = "Ferma",
            onDismiss = { seriesToStop = null },
            onConfirm = {
                seriesToStop = null
                status?.let(onStopSeriesDownload)
            },
        )
    }

    if (showStopConfirm) {
        val text = if (activeSeriesCount > 1) {
            "Vuoi fermare tutti i download in corso? Riguarda $activeSeriesCount serie in coda."
        } else {
            "Vuoi fermare il download in corso?"
        }
        ConfirmationDialog(
            title = "Ferma download",
            text = text,
            confirmLabel = "Ferma",
            onDismiss = { showStopConfirm = false },
            onConfirm = {
                showStopConfirm = false
                onStopDownloads()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySortPicker(
    selected: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        LibrarySort.entries.forEachIndexed { index, sort ->
            SegmentedButton(
                selected = selected == sort,
                onClick = { onSelect(sort) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = LibrarySort.entries.size,
                ),
                label = { Text(sort.label, maxLines = 1) },
            )
        }
    }
}

private fun isActiveDownload(status: SeriesDownloadStatus): Boolean {
    return status.state == androidx.work.WorkInfo.State.RUNNING ||
        status.state == androidx.work.WorkInfo.State.ENQUEUED ||
        status.state == androidx.work.WorkInfo.State.BLOCKED
}
