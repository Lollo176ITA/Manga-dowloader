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
    onSelectLanguage: (MangaSourceLanguage) -> Unit,
    onSelectAllSources: () -> Unit,
) {
    val trimmed = state.query.trim()
    val scope = state.settings.searchScope
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

        // Ambito sempre visibile e cambiabile a 1 tap. L'utente sceglie per lingua
        // (Tutte · Italiano · English), non per server: i nomi delle fonti non dicono
        // nulla a chi non le conosce. Le chip delle fonti singole compaiono solo con
        // l'impostazione "Mostra fonti singole" attiva.
        SearchScopeChips(
            scope = scope,
            selectedSourceId = state.settings.searchSourceId,
            showIndividualSources = state.settings.showIndividualSources,
            onSelectSource = onSelectSource,
            onSelectLanguage = onSelectLanguage,
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
                            if (state.aggregatedSearchActive) {
                                Text(
                                    text = "${state.results.size} risultati ${scope.resultsCaption()}",
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
                                            // il badge le rende distinguibili senza aprire il
                                            // dettaglio (due edizioni diverse, non un doppione).
                                            sourceLabel = if (state.aggregatedSearchActive) {
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
                            !state.aggregatedSearchActive && searchConfig.showAllOnEmptyQuery -> EmptyState(
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
                    !state.aggregatedSearchActive && trimmed.length < searchConfig.minQueryLength -> {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = if (searchConfig.minQueryLength == 1) {
                                "Digita almeno 1 carattere"
                            } else {
                                "Digita almeno ${searchConfig.minQueryLength} caratteri"
                            },
                        )
                    }
                    scope == SearchScope.ALL -> {
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
                        // Vicolo cieco più frequente della ricerca: il titolo magari esiste
                        // in un'altra lingua o su un'altra fonte. La CTA primaria estende
                        // la ricerca a tutte in 1 tap.
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "Nessun risultato",
                            description = "Nessun manga trovato per \"$trimmed\" " +
                                "${scope.emptyResultsPlace(state.settings.searchSourceId)}.",
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

/** Complemento per la caption "N risultati …" della ricerca aggregata. */
private fun SearchScope.resultsCaption(): String = when (this) {
    SearchScope.ALL -> "da tutte le fonti"
    SearchScope.ITA -> "dalle fonti in italiano"
    SearchScope.ENG -> "dalle fonti in inglese"
    SearchScope.SOURCE -> ""
}

/** Complemento di luogo per lo stato vuoto "Nessun manga trovato per \"q\" …". */
private fun SearchScope.emptyResultsPlace(selectedSourceId: String): String = when (this) {
    SearchScope.ITA -> "sulle fonti in italiano"
    SearchScope.ENG -> "sulle fonti in inglese"
    else -> "su ${MangaSourceCatalog.shortDisplayName(selectedSourceId)}"
}

/**
 * Riga di FilterChip per l'ambito della ricerca: "Tutte" più una chip per lingua
 * (Italiano/English), che attivano la ricerca aggregata sulle fonti corrispondenti.
 * Con "Mostra fonti singole" attivo si aggiungono le chip delle fonti dell'ambito
 * corrente (tutte, o solo quelle della lingua scelta). Stesso pattern dei GenreChips
 * della tab Scopri.
 */
@Composable
private fun SearchScopeChips(
    scope: SearchScope,
    selectedSourceId: String,
    showIndividualSources: Boolean,
    onSelectSource: (String) -> Unit,
    onSelectLanguage: (MangaSourceLanguage) -> Unit,
    onSelectAllSources: () -> Unit,
) {
    val resolvedSourceId = MangaSourceCatalog.resolveSourceId(selectedSourceId)
    // Con una fonte singola attiva, le fonti mostrate restano quelle della sua lingua.
    val visibleSources = when {
        !showIndividualSources -> emptyList()
        scope == SearchScope.SOURCE ->
            MangaSourceCatalog.descriptorsForScope(
                SearchScope.forLanguage(MangaSourceCatalog.languageOf(resolvedSourceId)),
            )
        else -> MangaSourceCatalog.descriptorsForScope(scope)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = scope == SearchScope.ALL,
                onClick = onSelectAllSources,
                label = { Text("Tutte") },
            )
        }
        items(MangaSourceLanguage.entries, key = { "lang_${it.name}" }) { language ->
            FilterChip(
                selected = scope.language == language,
                onClick = { onSelectLanguage(language) },
                label = { Text(language.displayName) },
            )
        }
        items(visibleSources, key = { it.id }) { descriptor ->
            FilterChip(
                selected = scope == SearchScope.SOURCE && resolvedSourceId == descriptor.id,
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
