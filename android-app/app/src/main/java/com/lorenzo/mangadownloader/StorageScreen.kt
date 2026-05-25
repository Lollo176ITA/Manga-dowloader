package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class StorageSortOrder { SIZE_DESC, SIZE_ASC, NAME }

data class SeriesStorageInfo(
    val series: DownloadedSeries,
    val sizeBytes: Long,
    val color: Color,
)

@Composable
fun StorageScreen(
    library: List<DownloadedSeries>,
    padding: PaddingValues,
    onDeleteSeries: (DownloadedSeries) -> Unit,
) {
    // Le dimensioni si leggono dal filesystem: fuori dal main thread per non
    // bloccare la UI. Si ricalcola quando la libreria cambia (es. dopo un'eliminazione).
    val storageItems by produceState<List<SeriesStorageInfo>?>(initialValue = null, library) {
        value = withContext(Dispatchers.IO) {
            library.map { series ->
                SeriesStorageInfo(
                    series = series,
                    sizeBytes = series.storageSizeBytes(),
                    color = seriesColor(series.colorKey()),
                )
            }
        }
    }

    var sortOrder by remember { mutableStateOf(StorageSortOrder.SIZE_DESC) }
    var pendingDeletion by remember { mutableStateOf<DownloadedSeries?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        when {
            storageItems == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AppLoadingIndicator(modifier = Modifier.padding(top = 24.dp))
                }
            }
            storageItems.isNullOrEmpty() -> {
                EmptyState(
                    icon = Icons.Default.Storage,
                    title = "Nessun manga in memoria",
                    description = "Quando scarichi dei manga, qui vedrai quanto spazio occupano e potrai liberarlo.",
                )
            }
            else -> {
                val items = storageItems.orEmpty()
                val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }
                val barSegments = remember(items) { items.sortedByDescending { it.sizeBytes } }
                val sortedItems = remember(items, sortOrder) { items.sortedByOrder(sortOrder) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        StorageHeader(
                            totalBytes = totalBytes,
                            seriesCount = items.size,
                            segments = barSegments,
                        )
                    }
                    item(key = "sort") {
                        StorageSortPicker(
                            selected = sortOrder,
                            onSelect = { sortOrder = it },
                        )
                    }
                    items(sortedItems, key = { it.series.directory.absolutePath }) { info ->
                        StorageSeriesRow(
                            info = info,
                            totalBytes = totalBytes,
                            onDelete = { pendingDeletion = info.series },
                        )
                    }
                }
            }
        }
    }

    pendingDeletion?.let { series ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Elimina manga") },
            text = { Text("Vuoi eliminare ${series.title} dalla memoria del telefono? I capitoli scaricati verranno rimossi.") },
            shape = MaterialTheme.shapes.extraLarge,
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                    onDeleteSeries(series)
                }) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text("Annulla")
                }
            },
        )
    }
}

@Composable
private fun StorageHeader(
    totalBytes: Long,
    seriesCount: Int,
    segments: List<SeriesStorageInfo>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Spazio occupato",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatBytes(totalBytes),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (seriesCount == 1) "1 manga" else "$seriesCount manga",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            StorageBar(segments = segments, totalBytes = totalBytes)
        }
    }
}

@Composable
private fun StorageBar(
    segments: List<SeriesStorageInfo>,
    totalBytes: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(MaterialTheme.shapes.large),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            // Le serie minuscole restano comunque visibili con una quota minima.
            val fraction = if (totalBytes > 0) {
                (segment.sizeBytes.toFloat() / totalBytes).coerceAtLeast(0.015f)
            } else {
                1f / segments.size
            }
            Box(
                modifier = Modifier
                    .weight(fraction)
                    .fillMaxHeight()
                    .background(segment.color),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageSortPicker(
    selected: StorageSortOrder,
    onSelect: (StorageSortOrder) -> Unit,
) {
    val options = listOf(
        StorageSortOrder.SIZE_DESC to "Più grandi",
        StorageSortOrder.SIZE_ASC to "Più piccoli",
        StorageSortOrder.NAME to "Nome",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (order, label) ->
            SegmentedButton(
                selected = selected == order,
                onClick = { onSelect(order) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun StorageSeriesRow(
    info: SeriesStorageInfo,
    totalBytes: Long,
    onDelete: () -> Unit,
) {
    val series = info.series
    val percent = if (totalBytes > 0) {
        (info.sizeBytes.toDouble() / totalBytes * 100).coerceIn(0.0, 100.0)
    } else {
        0.0
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Striscia colorata: stesso colore del segmento di questo manga nella barra.
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(info.color),
            )
            Spacer(modifier = Modifier.width(12.dp))
            CoverImage(
                model = series.coverFile,
                title = series.title,
                modifier = Modifier
                    .width(44.dp)
                    .height(60.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${series.chapters.size} capitoli · ${String.format(Locale.US, "%.0f", percent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatBytes(info.sizeBytes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Elimina ${series.title}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun List<SeriesStorageInfo>.sortedByOrder(order: StorageSortOrder): List<SeriesStorageInfo> {
    return when (order) {
        StorageSortOrder.SIZE_DESC -> sortedByDescending { it.sizeBytes }
        StorageSortOrder.SIZE_ASC -> sortedBy { it.sizeBytes }
        StorageSortOrder.NAME -> sortedBy { it.series.title.lowercase(Locale.US) }
    }
}

internal fun DownloadedSeries.storageSizeBytes(): Long {
    return directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)
}

private fun DownloadedSeries.colorKey(): String = directory.name

/**
 * Colore categorico stabile per manga: la stessa serie ottiene sempre lo stesso
 * colore, così il segmento nella barra e la riga in lista si corrispondono. I
 * token M3 non offrono N tinte distinte, quindi qui generiamo un colore HSL
 * deterministico calibrato per restare leggibile in light e dark.
 */
internal fun seriesColor(key: String): Color {
    val hue = ((key.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
    return Color.hsl(hue = hue, saturation = 0.55f, lightness = 0.55f)
}
