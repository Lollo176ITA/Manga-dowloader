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
 * vengono dalla memoria persistente, quindi restano anche per i capitoli eliminati.
 *
 * Tap su un capitolo ancora scaricato = riapri nel reader; su uno letto in streaming =
 * riaprilo online passando dalla scheda della serie. Resta non cliccabile solo ciò che non è
 * più raggiungibile: capitoli non più scaricati e letture streaming registrate prima che se
 * ne annotasse l'indirizzo. Stateless.
 */
@Composable
fun HistoryScreen(
    memory: Map<String, ReadChapterMemory>,
    library: List<DownloadedSeries>,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
    onResumeStreamingChapter: (ReadChapterMemory) -> Unit,
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
                    HistoryRow(
                        item = item,
                        onOpen = onOpenChapter,
                        onResumeStreaming = onResumeStreamingChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: ReadingHistoryItem,
    onOpen: (DownloadedChapter) -> Unit,
    onResumeStreaming: (ReadChapterMemory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = item.chapter
    val memory = item.memory
    val isStreaming = isStreamingMemoryPath(item.relativePath)
    MangaRowCard(
        coverModel = item.series?.coverFile,
        title = memory.seriesTitle,
        modifier = modifier,
        subtitle = memory.chapterLabel,
        caption = when {
            chapter != null -> memory.progressLabel()
            isStreaming -> "${memory.progressLabel()} · letto in streaming"
            else -> "${memory.progressLabel()} · non più scaricato"
        },
        onClick = when {
            chapter != null -> {
                { onOpen(chapter) }
            }
            isStreaming && memory.canReopenStreaming() -> {
                { onResumeStreaming(memory) }
            }
            // Capitolo non più scaricato, o lettura registrata prima che si annotasse
            // l'indirizzo: non c'è niente da riaprire, e una riga che non reagisce è
            // meglio di una che porta altrove.
            else -> null
        },
        onClickLabel = "Riapri il capitolo",
    )
}
