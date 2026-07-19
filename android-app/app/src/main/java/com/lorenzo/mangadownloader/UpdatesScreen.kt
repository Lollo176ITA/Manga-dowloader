package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Feed in-app "Aggiornamenti": i nuovi capitoli dei preferiti rilevati dal
 * [FavoriteUpdatesWorker], più recenti per primi e raggruppati per giorno. Il tap su una riga
 * apre il dettaglio del manga. Vuoto quando non c'è nulla (o le notifiche sono disattivate).
 */
@Composable
fun UpdatesScreen(
    events: List<FavoriteUpdateEvent>,
    padding: PaddingValues,
    onSelect: (FavoriteUpdateEvent) -> Unit,
    onBrowse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        if (events.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.NotificationsNone,
                title = "Nessun aggiornamento",
                description = "Quando esce un nuovo capitolo di un preferito comparirà qui.",
                actionLabel = "Vai ai preferiti",
                onAction = onBrowse,
            )
            return@Column
        }
        val days = remember(events) { groupEventsByDay(events) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { day ->
                item(key = "day-${day.dayLabel}") {
                    DayHeader(label = day.dayLabel)
                }
                items(
                    day.events,
                    key = { "${it.identityKey}|${it.chapterNumber}|${it.timestampMillis}" },
                ) { event ->
                    FavoriteUpdateRow(event = event, onClick = { onSelect(event) })
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * Riga di un evento del feed "Novità dai preferiti": copertina + titolo + "Nuovo: capitolo" e
 * pallino se non letto. Riusata sia dalla schermata piena [UpdatesScreen] sia dal blocco Home.
 */
@Composable
internal fun FavoriteUpdateRow(
    event: FavoriteUpdateEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MangaRowCard(
        coverModel = event.coverUrl,
        title = event.title,
        modifier = modifier,
        subtitle = "Nuovo: ${event.chapterLabel}",
        onClick = onClick,
        cardStateDescription = if (!event.seen) "Non letto" else null,
        trailing = { if (!event.seen) UnseenDot() },
    )
}
