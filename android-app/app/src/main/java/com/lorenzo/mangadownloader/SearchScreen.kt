package com.lorenzo.mangadownloader

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (MangaSearchResult) -> Unit,
    onToggleFavorite: (MangaSearchResult) -> Unit,
    onShowInfo: (MangaSearchResult) -> Unit,
    onDismissInfo: () -> Unit,
) {
    val trimmed = state.query.trim()
    val searchConfig = MangaSourceCatalog.searchConfig(state.settings.searchSourceId)
    val pullState = rememberPullToRefreshState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = state.query,
            placeholder = "Cerca manga",
            onValueChange = onQueryChange,
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
                        AppLoadingIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 24.dp),
                        )
                    }
                    state.results.isNotEmpty() -> {
                        val anchorFor = LocalTutorialAnchor.current
                        val firstKey = MangaSourceCatalog.identityKey(
                            state.results.first().sourceId,
                            state.results.first().mangaUrl,
                        )
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
                                    )
                                }
                            }
                        }
                    }
                    trimmed.isEmpty() -> {
                        EmptyStateText(
                            text = if (searchConfig.showAllOnEmptyQuery) {
                                "Nessun risultato"
                            } else {
                                "Digita per cercare"
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    trimmed.length < searchConfig.minQueryLength -> {
                        EmptyStateText(
                            text = if (searchConfig.minQueryLength == 1) {
                                "Digita almeno 1 carattere"
                            } else {
                                "Digita almeno ${searchConfig.minQueryLength} caratteri"
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    else -> {
                        EmptyStateText(
                            text = "Nessun risultato",
                            modifier = Modifier.align(Alignment.TopCenter),
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
