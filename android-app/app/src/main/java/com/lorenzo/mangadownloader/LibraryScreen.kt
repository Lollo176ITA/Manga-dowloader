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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val hasActiveDownloads = remember(downloadStatuses) {
        downloadStatuses.values.any(::isActiveDownload)
    }

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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        AppLoadingIndicator(modifier = Modifier.padding(top = 24.dp))
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
                                    onStopDownloads = onStopDownloads,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hasActiveDownloads) {
            ExtendedFloatingActionButton(
                onClick = onStopDownloads,
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
}

private fun isActiveDownload(status: SeriesDownloadStatus): Boolean {
    return status.state == androidx.work.WorkInfo.State.RUNNING ||
        status.state == androidx.work.WorkInfo.State.ENQUEUED ||
        status.state == androidx.work.WorkInfo.State.BLOCKED
}
