package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                    UpdateEventRow(event = event, onClick = { onSelect(event) })
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

@Composable
private fun UpdateEventRow(
    event: FavoriteUpdateEvent,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (!event.seen) stateDescription = "Non letto"
            },
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoverImage(
                model = event.coverUrl,
                title = event.title,
                modifier = Modifier
                    .size(width = 46.dp, height = 64.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Nuovo: ${event.chapterLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!event.seen) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
