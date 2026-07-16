package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pagina Cronologia (da "Vedi tutto" del blocco Letti di recente): tutti i capitoli con un
 * timestamp di lettura, raggruppati per giorno ("Oggi", "Ieri", data estesa). Le letture
 * vengono dalla memoria persistente, quindi restano anche per i capitoli eliminati (riga non
 * cliccabile). Tap su un capitolo ancora scaricato = riapri nel reader. Stateless.
 */
@Composable
fun HistoryScreen(
    memory: Map<String, ReadChapterMemory>,
    library: List<DownloadedSeries>,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
) {
    val history = remember(memory, library) { computeReadingHistory(memory, library) }
    // Raggruppamento NON memoizzato: "Oggi"/"Ieri" dipendono dall'orologio, e una schermata
    // rimasta aperta oltre la mezzanotte deve rietichettare i gruppi alla ricomposizione.
    val now = System.currentTimeMillis()
    val groups = history.groupBy { historyDayLabel(it.memory.lastReadAtMillis, now) }

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
                item(key = "h-${item.relativePath}") {
                    HistoryRow(item = item, onOpen = onOpenChapter)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: ReadingHistoryItem,
    onOpen: (DownloadedChapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = item.chapter
    MangaRowCard(
        coverModel = item.series?.coverFile,
        title = item.memory.seriesTitle,
        modifier = modifier,
        subtitle = item.memory.chapterLabel,
        caption = when {
            chapter != null -> item.memory.progressLabel()
            isStreamingMemoryPath(item.relativePath) ->
                "${item.memory.progressLabel()} · letto in streaming"
            else -> "${item.memory.progressLabel()} · non più scaricato"
        },
        onClick = chapter?.let { { onOpen(it) } },
        onClickLabel = "Riapri il capitolo",
    )
}
