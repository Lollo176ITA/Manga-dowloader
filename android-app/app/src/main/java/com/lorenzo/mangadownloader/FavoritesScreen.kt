package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteManga>,
    query: String,
    filterReadingState: FavoriteReadingState?,
    sort: FavoriteSort,
    // Tutte indicizzate per SeriesKey (l'identità del preferito), non per fonte.
    statusByKey: Map<String, MangaPublicationStatus>,
    seenByKey: Map<String, FavoriteSeenState>,
    readingStateByKey: Map<String, FavoriteReadingState>,
    noticesByKey: Map<String, FavoriteSourceNotice>,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (FavoriteManga) -> Unit,
    onBrowse: () -> Unit,
    onSelectSort: (FavoriteSort) -> Unit,
    onSelectReadingState: (FavoriteReadingState?) -> Unit,
    onReadNow: (FavoriteManga) -> Unit,
    onRemoveFavorite: (FavoriteManga) -> Unit,
) {
    val displayed = remember(
        favorites, query, sort, statusByKey, seenByKey, filterReadingState, readingStateByKey,
    ) {
        sortFavorites(
            filterFavorites(favorites, query, filterReadingState, readingStateByKey),
            sort,
            statusByKey,
            seenByKey,
        )
    }

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<FavoriteManga?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SearchField(
            value = query,
            placeholder = "Cerca nei preferiti",
            onValueChange = onQueryChange,
        )

        if (favorites.isNotEmpty()) {
            // Una sola riga fissa, niente scroll orizzontale: stato di lettura come filter
            // chip. "Tutti" = nessun filtro; un ri-tap sulla chip attiva la spegne.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filterReadingState == null,
                        onClick = { onSelectReadingState(null) },
                        label = { Text("Tutti", maxLines = 1) },
                    )
                    FavoriteReadingState.entries.forEach { readingState ->
                        FilterChip(
                            selected = filterReadingState == readingState,
                            onClick = {
                                onSelectReadingState(
                                    readingState.takeIf { filterReadingState != readingState },
                                )
                            },
                            label = { Text(readingState.shortLabel, maxLines = 1) },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Ordina preferiti",
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        FavoriteSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.menuLabel) },
                                trailingIcon = if (option == sort) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSelectSort(option)
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                favorites.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.StarBorder,
                        title = "Nessun preferito",
                        description = "Aggiungi un manga ai preferiti toccando la stella nella ricerca.",
                        actionLabel = "Cerca manga",
                        onAction = onBrowse,
                    )
                }
                displayed.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "Nessun preferito corrisponde",
                        actionLabel = "Azzera filtri",
                        onAction = {
                            onQueryChange("")
                            onSelectReadingState(null)
                        },
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            displayed,
                            key = { it.canonicalKey() },
                        ) { favorite ->
                            FavoriteCard(
                                favorite = favorite,
                                onClick = { onSelect(favorite) },
                                onLongClick = { actionsFor = favorite },
                                onMoreActions = { actionsFor = favorite },
                                readingState = readingStateByKey[favorite.canonicalKey()],
                                notice = noticesByKey[favorite.canonicalKey()],
                            )
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { favorite ->
        FavoriteActionsDialog(
            title = favorite.title,
            notice = noticesByKey[favorite.canonicalKey()],
            onRead = {
                onReadNow(favorite)
                actionsFor = null
            },
            onRemoveFromFavorites = {
                onRemoveFavorite(favorite)
                actionsFor = null
            },
            onDismiss = { actionsFor = null },
        )
    }
}
