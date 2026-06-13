package com.lorenzo.mangadownloader

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (MangaSearchResult) -> Unit,
    onToggleFavorite: (MangaSearchResult) -> Unit,
    onShowInfo: (MangaSearchResult) -> Unit,
    onDismissInfo: () -> Unit,
    onSelectSource: (String) -> Unit,
    onSelectAllSources: () -> Unit,
) {
    val trimmed = state.query.trim()
    val searchConfig = MangaSourceCatalog.searchConfig(state.settings.searchSourceId)
    val pullState = rememberPullToRefreshState()

    val tutorialAnchorFor = LocalTutorialAnchor.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = state.query,
            placeholder = "Cerca manga",
            onValueChange = onQueryChange,
            modifier = tutorialAnchorFor(TutorialAnchor.SEARCH_BAR),
            // Invio dalla tastiera = conferma subito: bypassa il debounce e libera lo schermo.
            onSearch = onRefresh,
        )

        // Fonte attiva sempre visibile e cambiabile a 1 tap (prima: 4 tap via overflow→dialog).
        // "Tutte" attiva la ricerca aggregata, la stessa del ponte AniList della tab Scopri.
        SourceFilterChips(
            selectedSourceId = state.settings.searchSourceId,
            allSourcesActive = state.bridgeSearchActive,
            onSelectSource = onSelectSource,
            onSelectAllSources = onSelectAllSources,
        )

        PullToRefreshBox(
            isRefreshing = state.isSearching && state.results.isNotEmpty(),
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isSearching && state.results.isEmpty() -> {
                        FullScreenLoading()
                    }
                    // Errore di rete/fonte con lista vuota: stato dedicato con Riprova,
                    // non un falso "Nessun risultato" (stesso pattern della tab Scopri).
                    state.searchError != null && state.results.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Default.CloudOff,
                            title = "Ricerca non riuscita",
                            description = state.searchError,
                            actionLabel = "Riprova",
                            onAction = onRefresh,
                        )
                    }
                    state.results.isNotEmpty() -> {
                        val anchorFor = LocalTutorialAnchor.current
                        val firstKey = MangaSourceCatalog.identityKey(
                            state.results.first().sourceId,
                            state.results.first().mangaUrl,
                        )
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (state.bridgeSearchActive) {
                                Text(
                                    text = "${state.results.size} risultati da tutte le fonti",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                                )
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(
                                    state.results,
                                    key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                                ) { result ->
                                    val resultKey = MangaSourceCatalog.identityKey(
                                        result.sourceId,
                                        result.mangaUrl,
                                    )
                                    val cardModifier = if (resultKey == firstKey) {
                                        anchorFor(TutorialAnchor.SEARCH_RESULT_FIRST)
                                    } else {
                                        Modifier
                                    }
                                    Box(modifier = cardModifier) {
                                        ResultCard(
                                            result = result,
                                            isFavorite = resultKey in state.favoriteMangaKeys,
                                            onClick = { onSelect(result) },
                                            onToggleFavorite = { onToggleFavorite(result) },
                                            onShowInfo = { onShowInfo(result) },
                                            // In aggregata lo stesso titolo arriva da più fonti:
                                            // il badge le rende distinguibili senza aprire il dettaglio.
                                            sourceLabel = if (state.bridgeSearchActive) {
                                                MangaSourceCatalog.shortDisplayName(result.sourceId)
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    trimmed.isEmpty() -> {
                        when {
                            !state.bridgeSearchActive && searchConfig.showAllOnEmptyQuery -> EmptyState(
                                icon = Icons.Default.SearchOff,
                                title = "Nessun risultato",
                            )
                            state.recentSearches.isNotEmpty() -> RecentSearches(
                                queries = state.recentSearches,
                                onPick = onQueryChange,
                                onClear = onClearRecentSearches,
                            )
                            else -> EmptyState(
                                icon = Icons.Default.Search,
                                title = "Cerca un manga",
                                description = "Digita il titolo nella barra qui sopra per trovare manga da leggere o scaricare.",
                            )
                        }
                    }
                    // Il minimo di caratteri vale per la fonte singola: l'aggregata parte
                    // con qualunque query non vuota.
                    !state.bridgeSearchActive && trimmed.length < searchConfig.minQueryLength -> {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = if (searchConfig.minQueryLength == 1) {
                                "Digita almeno 1 carattere"
                            } else {
                                "Digita almeno ${searchConfig.minQueryLength} caratteri"
                            },
                        )
                    }
                    state.bridgeSearchActive -> {
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "Nessun risultato",
                            description = "Nessun manga trovato per \"$trimmed\" su nessuna fonte. " +
                                "Prova con un altro titolo.",
                            actionLabel = "Cancella ricerca",
                            onAction = { onQueryChange("") },
                        )
                    }
                    else -> {
                        // Vicolo cieco più frequente della ricerca: il titolo magari esiste su
                        // un'altra fonte. La CTA primaria estende la ricerca a tutte in 1 tap.
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "Nessun risultato",
                            description = "Nessun manga trovato per \"$trimmed\" su " +
                                "${MangaSourceCatalog.shortDisplayName(state.settings.searchSourceId)}.",
                            actionLabel = "Cerca su tutte le fonti",
                            onAction = onSelectAllSources,
                            secondaryActionLabel = "Cancella ricerca",
                            onSecondaryAction = { onQueryChange("") },
                        )
                    }
                }
            }
        }
    }

    state.mangaInfoDialog?.let { info ->
        MangaInfoDialog(
            info = info,
            onDismiss = onDismissInfo,
        )
    }
}

/**
 * Riga di FilterChip per lo scope della ricerca: "Tutte" (aggregata su ogni fonte) più una
 * chip per fonte. Stesso pattern dei GenreChips della tab Scopri.
 */
@Composable
private fun SourceFilterChips(
    selectedSourceId: String,
    allSourcesActive: Boolean,
    onSelectSource: (String) -> Unit,
    onSelectAllSources: () -> Unit,
) {
    val resolvedSourceId = MangaSourceCatalog.resolveSourceId(selectedSourceId)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = allSourcesActive,
                onClick = onSelectAllSources,
                label = { Text("Tutte") },
            )
        }
        items(MangaSourceCatalog.descriptors, key = { it.id }) { descriptor ->
            FilterChip(
                selected = !allSourcesActive && resolvedSourceId == descriptor.id,
                onClick = { onSelectSource(descriptor.id) },
                label = { Text(descriptor.shortName) },
            )
        }
    }
}

@Composable
private fun MangaInfoDialog(
    info: MangaInfoDialogState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    info.isLoading -> {
                        AppLoadingIndicator(modifier = Modifier.padding(vertical = 16.dp))
                        Text("Caricamento trama...")
                    }
                    !info.errorMessage.isNullOrBlank() -> {
                        Text(info.errorMessage)
                    }
                    info.description.isNullOrBlank() -> {
                        Text("Trama non disponibile.")
                    }
                    else -> {
                        Text(info.description)
                    }
                }
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
