package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun LibrarySeriesCard(
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                model = series?.coverFile ?: downloadStatus?.coverUrl,
                title = row.title,
                modifier = Modifier
                    .width(84.dp)
                    .height(118.dp)
                    .clip(MaterialTheme.shapes.large),
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
                    Spacer(modifier = Modifier.height(6.dp))
                    SeriesDownloadSummary(
                        status = status,
                        onStopDownloads = onStopDownloads,
                    )
                }
            }
            Column(
                modifier = Modifier.width(56.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (series != null) {
                    LibraryReadProgressRing(
                        readCount = series.readChapterCount(),
                        totalCount = series.totalChapterCount,
                    )
                }
                if (series != null) {
                    Box(
                        modifier = Modifier.width(48.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
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
    }

    if (showInfoDialog && series != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(series.title) },
            text = { Text(buildSeriesInfoText(series)) },
            shape = MaterialTheme.shapes.extraLarge,
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
            shape = MaterialTheme.shapes.extraLarge,
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
