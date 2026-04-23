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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FavoritesScreen(
    favorites: List<FavoriteManga>,
    query: String,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSelect: (FavoriteManga) -> Unit,
) {
    val filtered = remember(favorites, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) favorites
        else favorites.filter { it.title.contains(trimmed, ignoreCase = true) }
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

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                favorites.isEmpty() -> {
                    EmptyStateText(
                        text = "Nessun preferito",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                filtered.isEmpty() -> {
                    EmptyStateText(
                        text = "Nessun preferito corrisponde",
                        modifier = Modifier.align(Alignment.TopCenter),
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
                            filtered,
                            key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                        ) { favorite ->
                            FavoriteCard(
                                favorite = favorite,
                                onClick = { onSelect(favorite) },
                            )
                        }
                    }
                }
            }
        }
    }
}
