package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    onResume: (DownloadedChapter) -> Unit,
) {
    val rows = remember(state.library, state.libraryQuery, downloadStatuses) {
        buildLibraryRowItems(
            library = state.library,
            downloadStatuses = downloadStatuses,
            query = state.libraryQuery.trim(),
        )
    }
    val hasActiveDownloads = remember(downloadStatuses) {
        downloadStatuses.values.any(::isActiveDownload)
    }
    // Lo stop ferma TUTTI i download (un'unica coda WorkManager), non solo quello toccato:
    // una conferma evita la cancellazione a un tap cieco di una coda magari lunga.
    var showStopConfirm by remember { mutableStateOf(false) }
    val requestStopDownloads = { showStopConfirm = true }
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
                                    onStopDownloads = requestStopDownloads,
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

private fun isActiveDownload(status: SeriesDownloadStatus): Boolean {
    return status.state == androidx.work.WorkInfo.State.RUNNING ||
        status.state == androidx.work.WorkInfo.State.ENQUEUED ||
        status.state == androidx.work.WorkInfo.State.BLOCKED
}
