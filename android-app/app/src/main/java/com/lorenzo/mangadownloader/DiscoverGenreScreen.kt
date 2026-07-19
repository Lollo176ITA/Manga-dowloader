package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pagina di un genere Scopri: griglia dei manga popolari del genere (AniList, metadati-only).
 * Il tap fa lo stesso "ponte" verso la ricerca aggregata del blocco Scopri; l'info dialog
 * riusa [AniListInfoDialog] via `discovery.info`.
 */
@Composable
fun DiscoverGenreScreen(
    discovery: DiscoveryUiState,
    padding: PaddingValues,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
    onDismissInfo: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        discovery.isLoadingGenre -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            AppLoadingIndicator()
        }

        discovery.genreError != null -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = discovery.genreError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("Riprova") }
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(discovery.genreResults, key = { it.id }) { manga ->
                DiscoveryCard(
                    manga = manga,
                    fillWidth = true,
                    onClick = { onPick(manga) },
                    onShowInfo = { onShowInfo(manga) },
                )
            }
        }
    }

    discovery.info?.let { manga ->
        AniListInfoDialog(manga = manga, onDismiss = onDismissInfo)
    }
}
