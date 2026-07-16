package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pagina Statistiche (dal "Vedi tutto" del blocco Home): il quadro completo delle letture.
 * Tutto è derivato da memoria ([ReadChapterMemory]) e diario ([ReadingDayStats]) persistenti,
 * quindi i numeri non calano eliminando i download. Stateless: consuma lo stato e delega.
 */
@Composable
fun StatsScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onOpenSeries: (DownloadedSeries) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val memory = state.readingMemory
    val diary = state.readingDiary
    val stats = remember(state.library, state.favorites, memory) {
        computeHomeStats(state.library, state.favorites.size, memory)
    }
    val week = remember(diary) { diaryTotalsBetween(diary, today.minusDays(6), today) }
    val month = remember(diary) { diaryTotalsBetween(diary, today.withDayOfMonth(1), today) }
    val streak = remember(diary) { currentReadingStreak(diary, today) }
    val longestStreak = remember(diary) { longestReadingStreak(diary) }
    val bestDay = remember(diary) { bestReadingDay(diary) }
    val lastWeek = remember(diary) { lastDiaryDays(diary, days = 7, today = today) }
    val topSeries = remember(memory, state.library) { topReadSeries(memory, state.library) }
    val bySource = remember(memory) { chaptersReadBySource(memory) }

    if (stats.isEmpty() && diary.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.BarChart,
            title = "Ancora nessun numero",
            description = "Leggi qualche capitolo e qui troverai statistiche, andamento e record.",
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsTile(formatStatNumber(stats.chaptersRead), "Capitoli letti", Modifier.weight(1f))
                    StatsTile(formatStatNumber(stats.pagesRead), "Pagine lette", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsTile(formatStatNumber(stats.seriesReadCount), "Serie lette", Modifier.weight(1f))
                    StatsTile(formatStatNumber(stats.seriesCount), "Serie in libreria", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatsTile(
                        value = if (streak > 0) "$streak 🔥" else "0",
                        label = if (streak == 1) "Giorno di fila" else "Giorni di fila",
                        modifier = Modifier.weight(1f),
                    )
                    StatsTile(formatStatNumber(stats.favoritesCount), "Preferiti", Modifier.weight(1f))
                }
            }
        }

        item(key = "week-header") { StatsSectionTitle("Questa settimana") }
        item(key = "week") {
            StatsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PeriodSummary(
                        label = "Ultimi 7 giorni",
                        stats = week,
                        modifier = Modifier.weight(1f),
                    )
                    PeriodSummary(
                        label = "Questo mese",
                        stats = month,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                WeekBarChart(days = lastWeek)
            }
        }

        item(key = "heatmap-header") { StatsSectionTitle("Ultimi 3 mesi") }
        item(key = "heatmap") {
            StatsCard {
                ReadingHeatmap(diary = diary, today = today)
            }
        }

        if (bestDay != null || longestStreak > 0) {
            item(key = "records-header") { StatsSectionTitle("Record personali") }
            item(key = "records") {
                StatsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bestDay?.let { (day, dayStats) ->
                            RecordRow(
                                label = "Giornata migliore",
                                value = "${chapterCountLabel(dayStats.chaptersRead)} · ${day.italianDate()}",
                            )
                        }
                        if (longestStreak > 0) {
                            RecordRow(
                                label = "Streak più lungo",
                                value = if (longestStreak == 1) "1 giorno" else "$longestStreak giorni",
                            )
                        }
                        diary.maxByOrNull { it.value.pagesRead }
                            ?.takeIf { it.value.pagesRead > 0 }
                            ?.let { (dayKey, dayStats) ->
                                RecordRow(
                                    label = "Più pagine in un giorno",
                                    value = "${formatStatNumber(dayStats.pagesRead)} pagine" +
                                        (diaryDayOf(dayKey)?.let { " · ${it.italianDate()}" } ?: ""),
                                )
                            }
                    }
                }
            }
        }

        if (topSeries.isNotEmpty()) {
            item(key = "top-header") { StatsSectionTitle("Serie più lette") }
            item(key = "top") {
                StatsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        topSeries.forEachIndexed { index, entry ->
                            TopSeriesRow(
                                position = index + 1,
                                entry = entry,
                                onOpen = entry.series?.let { series -> { onOpenSeries(series) } },
                            )
                        }
                    }
                }
            }
        }

        if (bySource.isNotEmpty()) {
            item(key = "sources-header") { StatsSectionTitle("Capitoli letti per fonte") }
            item(key = "sources") {
                StatsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bySource.forEach { (sourceId, count) ->
                            RecordRow(
                                label = if (sourceId.isBlank()) {
                                    "Altre letture"
                                } else {
                                    MangaSourceCatalog.shortDisplayName(sourceId)
                                },
                                value = chapterCountLabel(count),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun chapterCountLabel(count: Int): String =
    if (count == 1) "1 capitolo" else "${formatStatNumber(count)} capitoli"

private fun LocalDate.italianDate(): String =
    format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN))

@Composable
private fun StatsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun StatsTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodSummary(label: String, stats: ReadingDayStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = chapterCountLabel(stats.chaptersRead),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${formatStatNumber(stats.pagesRead)} pagine",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Barre degli ultimi 7 giorni (capitoli; con soli avanzamenti pagina la barra è minima). */
@Composable
fun WeekBarChart(
    days: List<Pair<LocalDate, ReadingDayStats>>,
    modifier: Modifier = Modifier,
    barMaxHeight: Int = 64,
) {
    val maxChapters = days.maxOfOrNull { it.second.chaptersRead } ?: 0
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (day, stats) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (stats.chaptersRead > 0) {
                    Text(
                        text = "${stats.chaptersRead}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                val height = when {
                    stats.chaptersRead > 0 && maxChapters > 0 ->
                        (barMaxHeight * stats.chaptersRead / maxChapters).coerceAtLeast(8)
                    stats.hasActivity -> 6
                    else -> 3
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (stats.hasActivity) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ITALIAN),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Heatmap stile GitHub delle ultime ~12 settimane: una colonna per settimana (lun→dom),
 * intensità in base ai capitoli del giorno (le sole pagine valgono come attività minima).
 */
@Composable
private fun ReadingHeatmap(
    diary: Map<String, ReadingDayStats>,
    today: LocalDate,
    weeks: Int = 12,
) {
    val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(weeks) { weekIndex ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { dayIndex ->
                    val day = firstMonday.plusWeeks(weekIndex.toLong()).plusDays(dayIndex.toLong())
                    val stats = diary[day.toString()]
                    val color = when {
                        day.isAfter(today) -> androidx.compose.ui.graphics.Color.Transparent
                        stats == null || !stats.hasActivity ->
                            MaterialTheme.colorScheme.surfaceVariant
                        stats.chaptersRead >= 5 -> MaterialTheme.colorScheme.primary
                        stats.chaptersRead >= 2 ->
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TopSeriesRow(
    position: Int,
    entry: SeriesReadCount,
    onOpen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier.clickable(onClick = onOpen, onClickLabel = "Apri la serie")
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp),
        )
        CoverImage(
            model = entry.series?.coverFile,
            title = entry.title,
            modifier = Modifier
                .size(width = 34.dp, height = 48.dp)
                .clip(MaterialTheme.shapes.small),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chapterCountLabel(entry.chaptersRead),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
