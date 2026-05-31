package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

@Composable
fun FavoritesScreen(
    favorites: List<FavoriteManga>,
    query: String,
    categories: List<FavoriteCategory>,
    assignments: Map<String, String>,
    filterCategoryId: String?,
    sort: FavoriteSort,
    statusByKey: Map<String, MangaPublicationStatus>,
    seenByKey: Map<String, FavoriteSeenState>,
    readingStateByKey: Map<String, FavoriteReadingState>,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (FavoriteManga) -> Unit,
    onBrowse: () -> Unit,
    onSelectSort: (FavoriteSort) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onAssignCategory: (String, String?) -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onReadNow: (FavoriteManga) -> Unit,
) {
    val displayed = remember(favorites, query, filterCategoryId, assignments, sort, statusByKey, seenByKey) {
        sortFavorites(
            filterFavorites(favorites, query, filterCategoryId, assignments),
            sort,
            statusByKey,
            seenByKey,
        )
    }
    val counts = remember(favorites, assignments) { categoryCounts(favorites, assignments) }

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showManageCategories by remember { mutableStateOf(false) }
    var categoryPickerFor by remember { mutableStateOf<FavoriteManga?>(null) }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    item {
                        FilterChip(
                            selected = filterCategoryId == null,
                            onClick = { onSelectCategory(null) },
                            label = { Text("Tutti (${counts[null] ?: 0})") },
                        )
                    }
                    categories.sortedBy { it.order }.forEach { category ->
                        item(key = category.id) {
                            FilterChip(
                                selected = filterCategoryId == category.id,
                                onClick = { onSelectCategory(category.id) },
                                label = { Text("${category.name} (${counts[category.id] ?: 0})") },
                            )
                        }
                    }
                    val uncategorized = counts[UNCATEGORIZED_CATEGORY_ID] ?: 0
                    if (uncategorized > 0) {
                        item {
                            FilterChip(
                                selected = filterCategoryId == UNCATEGORIZED_CATEGORY_ID,
                                onClick = { onSelectCategory(UNCATEGORIZED_CATEGORY_ID) },
                                label = { Text("Senza cartella ($uncategorized)") },
                            )
                        }
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
                IconButton(onClick = { showManageCategories = true }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Gestisci cartelle",
                    )
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
                            onSelectCategory(null)
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
                            key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                        ) { favorite ->
                            FavoriteCard(
                                favorite = favorite,
                                onClick = { onSelect(favorite) },
                                onLongClick = { actionsFor = favorite },
                                onMoreActions = { actionsFor = favorite },
                                readingState = readingStateByKey[
                                    MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl),
                                ],
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
            onRead = {
                onReadNow(favorite)
                actionsFor = null
            },
            onMoveToFolder = {
                categoryPickerFor = favorite
                actionsFor = null
            },
            onDismiss = { actionsFor = null },
        )
    }

    categoryPickerFor?.let { favorite ->
        val key = MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl)
        CategoryPickerDialog(
            categories = categories,
            currentCategoryId = assignments[key],
            onSelect = { categoryId ->
                onAssignCategory(key, categoryId)
                categoryPickerFor = null
            },
            onDismiss = { categoryPickerFor = null },
        )
    }

    if (showManageCategories) {
        CategoryManagerDialog(
            categories = categories,
            onAdd = onAddCategory,
            onRename = onRenameCategory,
            onRemove = onRemoveCategory,
            onDismiss = { showManageCategories = false },
        )
    }
}
