package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Pagina Cronologia (da "Vedi tutto" del blocco Letti di recente): tutti i capitoli con un
 * timestamp di lettura, raggruppati per giorno ("Oggi", "Ieri", data estesa). Tap = riapri il
 * capitolo nel reader. Stateless: consuma la libreria e delega al callback.
 */
@Composable
fun HistoryScreen(
    library: List<DownloadedSeries>,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
) {
    val history = remember(library) { computeReadingHistory(library) }
    val now = System.currentTimeMillis()
    val groups = remember(history) {
        history.groupBy { historyDayLabel(it.chapter.lastReadAtMillis ?: 0L, now) }
    }

    if (history.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.History,
            title = "Nessuna lettura registrata",
            description = "Qui troverai i capitoli che leggi, giorno per giorno.",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (dayLabel, items) ->
            item(key = "day-$dayLabel") {
                Text(
                    text = dayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            items.forEach { item ->
                item(key = "h-${item.chapter.relativePath}") {
                    HistoryRow(item = item, onOpen = { onOpenChapter(item.chapter) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: ReadingHistoryItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = item.chapter
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen, onClickLabel = "Riapri il capitolo"),
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                model = item.series.coverFile,
                title = item.series.title,
                modifier = Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.series.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapter.title.ifBlank { "Capitolo ${chapter.numberText}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val idx = chapter.readerPageIndex
                val count = chapter.readerPageCount
                Text(
                    text = when {
                        chapter.isRead -> "Completato"
                        idx != null && count != null && count > 0 -> "pagina ${idx + 1} di $count"
                        else -> "In corso"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
