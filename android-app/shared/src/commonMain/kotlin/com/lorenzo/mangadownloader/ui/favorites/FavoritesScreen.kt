package com.lorenzo.mangadownloader.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.anilist.UnmatchedAniListFavorite
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.model.canonicalKey
import com.lorenzo.mangadownloader.data.store.FavoriteSeenState
import com.lorenzo.mangadownloader.data.store.FavoriteSourceNotice
import com.lorenzo.mangadownloader.domain.series.FavoriteReadingState
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves
import com.lorenzo.mangadownloader.domain.series.FavoriteSort
import com.lorenzo.mangadownloader.domain.series.filterFavorites
import com.lorenzo.mangadownloader.domain.series.filterFavoritesByShelf
import com.lorenzo.mangadownloader.domain.series.sortFavorites
import com.lorenzo.mangadownloader.ui.components.EmptyState
import com.lorenzo.mangadownloader.ui.components.FavoriteActionsDialog
import com.lorenzo.mangadownloader.ui.components.FavoriteCard
import com.lorenzo.mangadownloader.ui.components.MangaPosterCard
import com.lorenzo.mangadownloader.ui.components.SearchField
import com.lorenzo.mangadownloader.ui.components.icon

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
    shelves: FavoriteShelves = FavoriteShelves(),
    filterShelfId: String? = null,
    onSelectShelf: (String?) -> Unit = {},
    onCreateShelf: (String) -> String? = { null },
    onRenameShelf: (String, String) -> Boolean = { _, _ -> false },
    onDeleteShelf: (String) -> Unit = {},
    onSetShelves: (FavoriteManga, Set<String>) -> Unit = { _, _ -> },
    // Favourites AniList che nessuna fonte espone: il tap ne rifà la ricerca.
    unmatchedAniList: List<UnmatchedAniListFavorite> = emptyList(),
    onPickUnmatched: (UnmatchedAniListFavorite) -> Unit = {},
) {
    val displayed = remember(
        favorites, query, sort, statusByKey, seenByKey, filterReadingState, readingStateByKey,
        shelves, filterShelfId,
    ) {
        sortFavorites(
            filterFavoritesByShelf(
                filterFavorites(favorites, query, filterReadingState, readingStateByKey),
                filterShelfId,
                shelves,
            ),
            sort,
            statusByKey,
            seenByKey,
        )
    }
    val shelfCounts = remember(shelves, favorites) { shelves.countsIn(favorites) }

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<FavoriteManga?>(null) }
    var shelvesFor by remember { mutableStateOf<FavoriteManga?>(null) }
    var showShelvesManager by remember { mutableStateOf(false) }
    var unmatchedExpanded by rememberSaveable { mutableStateOf(false) }
    // Non sono preferiti dell'app: con un filtro attivo non c'entrano, con una ricerca sì.
    val unmatchedShown = remember(unmatchedAniList, query, filterReadingState, filterShelfId) {
        if (filterReadingState != null || filterShelfId != null) {
            emptyList()
        } else {
            unmatchedAniList.filter { query.isBlank() || it.displayTitle().contains(query.trim(), ignoreCase = true) }
        }
    }

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
                            contentDescription = "Ordina preferiti e scaffali",
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
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Gestisci scaffali") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
                            onClick = {
                                sortMenuExpanded = false
                                showShelvesManager = true
                            },
                        )
                    }
                }
            }
            // Gli scaffali dell'utente, solo se ne ha creati: chi non li usa non vede una
            // riga in più. Qui lo scroll orizzontale serve, i nomi li sceglie l'utente.
            if (shelves.shelves.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shelves.shelves, key = { it.id }) { shelf ->
                            FilterChip(
                                selected = filterShelfId == shelf.id,
                                onClick = { onSelectShelf(shelf.id.takeIf { filterShelfId != it }) },
                                label = {
                                    Text("${shelf.name} · ${shelfCounts[shelf.id] ?: 0}", maxLines = 1)
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showShelvesManager = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Gestisci scaffali")
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
                            onSelectShelf(null)
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
                        if (unmatchedShown.isNotEmpty()) {
                            item(key = "unmatched-header", span = { GridItemSpan(maxLineSpan) }) {
                                UnmatchedAniListHeader(
                                    count = unmatchedShown.size,
                                    expanded = unmatchedExpanded,
                                    onToggle = { unmatchedExpanded = !unmatchedExpanded },
                                )
                            }
                            if (unmatchedExpanded) {
                                items(unmatchedShown, key = { "anilist-${it.id}" }) { entry ->
                                    MangaPosterCard(
                                        coverModel = entry.coverUrl,
                                        title = entry.displayTitle(),
                                        onClick = { onPickUnmatched(entry) },
                                        onClickLabel = "Cerca sulle fonti",
                                        cardStateDescription = "Senza scan",
                                    )
                                }
                            }
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
            shelvesSummary = shelves.shelfIdsOf(favorite)
                .mapNotNull { shelves.shelf(it)?.name }
                .joinToString(", ")
                .ifEmpty { "Su nessuno scaffale" },
            onEditShelves = {
                shelvesFor = favorite
                actionsFor = null
            },
        )
    }

    shelvesFor?.let { favorite ->
        ShelfPickerDialog(
            favoriteTitle = favorite.title,
            shelves = shelves.shelves,
            initiallySelected = shelves.shelfIdsOf(favorite),
            onCreateShelf = onCreateShelf,
            onConfirm = { ids ->
                onSetShelves(favorite, ids)
                shelvesFor = null
            },
            onDismiss = { shelvesFor = null },
        )
    }

    if (showShelvesManager) {
        ShelvesManageDialog(
            shelves = shelves.shelves,
            countsByShelf = shelfCounts,
            onCreateShelf = onCreateShelf,
            onRenameShelf = onRenameShelf,
            onDeleteShelf = onDeleteShelf,
            onDismiss = { showShelvesManager = false },
        )
    }
}

/**
 * Intestazione del gruppo "Senza scan": spiega perché quei titoli non sono tra i preferiti
 * (nessuna fonte li ha) e cosa fa il tocco, che è insieme spiegazione e rimedio.
 */
@Composable
private fun UnmatchedAniListHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Senza scan · $count",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Comprimi" else "Espandi",
            )
        }
        Text(
            text = "Preferiti del tuo account AniList che nessuna fonte attiva ha. " +
                "Tocca un titolo per cercarlo di nuovo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
