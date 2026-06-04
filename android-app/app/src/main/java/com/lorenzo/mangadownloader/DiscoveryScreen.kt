package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Tab "Scopri": vetrina AniList (solo metadati). Mostra caroselli per tendenze/più votati/novità
 * e un filtro per genere. Toccando un titolo si passa al "ponte" verso le fonti reali (vedi
 * [MangaViewModel.onPickAniListManga]): da AniList non si scarica direttamente.
 */
@Composable
fun DiscoveryScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onLoad: () -> Unit,
    onSelectGenre: (String?) -> Unit,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
    onDismissInfo: () -> Unit,
) {
    val discovery = state.discovery

    LaunchedEffect(Unit) { onLoad() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        GenreChips(
            selectedGenre = discovery.selectedGenre,
            onSelectGenre = onSelectGenre,
        )

        when {
            discovery.selectedGenre != null -> {
                GenreResults(
                    genre = discovery.selectedGenre,
                    results = discovery.genreResults,
                    isLoading = discovery.isLoadingGenre,
                    errorMessage = discovery.genreError,
                    onPick = onPick,
                    onShowInfo = onShowInfo,
                    onRetry = { onSelectGenre(discovery.selectedGenre) },
                )
            }
            discovery.isLoadingSections && !discovery.loaded -> FullScreenLoading()
            discovery.sectionsError != null && !discovery.loaded -> EmptyState(
                icon = Icons.Default.Explore,
                title = "Impossibile caricare",
                description = discovery.sectionsError,
                actionLabel = "Riprova",
                onAction = onLoad,
            )
            else -> DiscoverySections(
                discovery = discovery,
                onPick = onPick,
                onShowInfo = onShowInfo,
            )
        }
    }

    discovery.info?.let { manga ->
        AniListInfoDialog(manga = manga, onDismiss = onDismissInfo)
    }
}

@Composable
private fun GenreChips(
    selectedGenre: String?,
    onSelectGenre: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedGenre == null,
                onClick = { onSelectGenre(null) },
                label = { Text("Tutti") },
            )
        }
        items(ANILIST_GENRES, key = { it }) { genre ->
            FilterChip(
                selected = selectedGenre == genre,
                onClick = { onSelectGenre(genre) },
                label = { Text(genre) },
            )
        }
    }
}

@Composable
private fun DiscoverySections(
    discovery: DiscoveryUiState,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        section("Tendenze", discovery.trending, onPick, onShowInfo)
        section("Più votati", discovery.topRated, onPick, onShowInfo)
        section("Novità", discovery.newest, onPick, onShowInfo)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<AniListManga>,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    item(key = "row-$title") {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { manga ->
                DiscoveryCard(
                    manga = manga,
                    modifier = Modifier.width(124.dp),
                    onClick = { onPick(manga) },
                    onShowInfo = { onShowInfo(manga) },
                )
            }
        }
    }
}

@Composable
private fun GenreResults(
    genre: String,
    results: List<AniListManga>,
    isLoading: Boolean,
    errorMessage: String?,
    onPick: (AniListManga) -> Unit,
    onShowInfo: (AniListManga) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        isLoading -> FullScreenLoading()
        errorMessage != null -> EmptyState(
            icon = Icons.Default.Explore,
            title = "Impossibile caricare \"$genre\"",
            description = errorMessage,
            actionLabel = "Riprova",
            onAction = onRetry,
        )
        results.isEmpty() -> EmptyState(
            icon = Icons.Default.Explore,
            title = "Nessun risultato",
            description = "Nessun manga trovato per il genere \"$genre\".",
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.id }) { manga ->
                DiscoveryCard(
                    manga = manga,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(manga) },
                    onShowInfo = { onShowInfo(manga) },
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    manga: AniListManga,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onShowInfo: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = appCardColors(),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    model = manga.coverUrl,
                    title = manga.displayTitle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.extraLarge),
                )
                DiscoveryInfoBadge(
                    onClick = onShowInfo,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
                manga.averageScore?.let { score ->
                    ScoreBadge(
                        score = score,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
            Text(
                text = manga.displayTitle(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DiscoveryInfoBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(36.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Informazioni manga",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ScoreBadge(
    score: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = "Valutazione $score percento" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.ThumbUp,
            contentDescription = null,
            tint = FavoriteYellow,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "$score%",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AniListInfoDialog(
    manga: AniListManga,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(manga.displayTitle()) },
        text = {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                manga.status.displayLabel()?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (manga.genres.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(manga.genres, key = { it }) { genre ->
                            AssistChip(onClick = {}, label = { Text(genre) })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = manga.description?.takeIf { it.isNotBlank() } ?: "Trama non disponibile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
    )
}
