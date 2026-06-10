package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Schermata "Novità": mostra il changelog (bundlato negli assets) raggruppato per giorno e
 * per categoria, saltando le voci tecniche ("Interno"). Sola lettura, aperta dalle impostazioni.
 */
@Composable
fun ChangelogScreen(padding: PaddingValues) {
    val context = LocalContext.current
    // Il file è minuscolo: lettura sincrona dall'asset in composizione, senza I/O thread.
    val days = remember(context) {
        val markdown = runCatching {
            context.assets.open(CHANGELOG_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        parseChangelog(markdown, includeInternal = false)
    }

    if (days.isEmpty()) {
        EmptyState(
            icon = Icons.Default.NewReleases,
            title = "Nessuna novità da mostrare",
            description = "Le novità delle prossime versioni appariranno qui.",
            modifier = Modifier.padding(padding),
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(days, key = { it.isoDate }) { day ->
            ChangelogDayCard(day)
        }
    }
}

@Composable
private fun ChangelogDayCard(day: ChangelogDay) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = formatChangelogDate(day.isoDate),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            day.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = section.category,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    section.entries.forEach { entry ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CHANGELOG_ASSET_NAME = "CHANGELOG.md"
