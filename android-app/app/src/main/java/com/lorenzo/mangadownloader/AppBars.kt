package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    state: MangaUiState,
    visibleTab: AppTab,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearchSource: () -> Unit,
    onOpenParentalControl: () -> Unit,
) {
    val readerChapter = state.readerChapter
    val selectedManga = state.selected
    val selectedSeries = state.selectedDownloadedSeries
    val showBack = readerChapter != null ||
        state.showSettings ||
        selectedManga != null ||
        (state.currentTab == AppTab.LIBRARY && selectedSeries != null)
    val title = when {
        state.showSettings -> "Impostazioni"
        readerChapter != null -> readerChapter.title
        selectedManga != null -> selectedManga.title
        state.currentTab == AppTab.LIBRARY && selectedSeries != null -> selectedSeries.title
        visibleTab == AppTab.SEARCH -> "Cerca"
        visibleTab == AppTab.FAVORITES -> "Preferiti"
        visibleTab == AppTab.LIBRARY -> "Libreria"
        else -> "Manga Downloader"
    }
    var overflowExpanded by remember { mutableStateOf(false) }

    val inDetail = selectedManga != null
    val inSeries = state.currentTab == AppTab.LIBRARY && selectedSeries != null
    val showOverflow = state.readerChapter == null &&
        !state.showSettings &&
        !inDetail &&
        !inSeries

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Indietro",
                    )
                }
            }
        },
        actions = {
            if (inDetail && selectedManga != null) {
                val isFavorite = MangaSourceCatalog.identityKey(
                    selectedManga.sourceId,
                    selectedManga.mangaUrl,
                ) in state.favoriteMangaKeys
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                        tint = if (isFavorite) FavoriteYellow else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (showOverflow) {
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Altre azioni",
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        if (visibleTab == AppTab.SEARCH) {
                            DropdownMenuItem(
                                text = { Text("Server") },
                                leadingIcon = {
                                    Icon(Icons.Default.Storage, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenSearchSource()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Impostazioni") },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            },
                            onClick = {
                                overflowExpanded = false
                                onOpenSettings()
                            },
                        )
                        if (state.settings.parentalControlEnabled) {
                            DropdownMenuItem(
                                text = { Text("Parental control") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenParentalControl()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun AppBottomBar(
    currentTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = currentTab == AppTab.SEARCH,
            onClick = { onSelect(AppTab.SEARCH) },
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Cerca") },
        )
        NavigationBarItem(
            selected = currentTab == AppTab.FAVORITES,
            onClick = { onSelect(AppTab.FAVORITES) },
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = { Text("Preferiti") },
        )
        NavigationBarItem(
            selected = currentTab == AppTab.LIBRARY,
            onClick = { onSelect(AppTab.LIBRARY) },
            icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
            label = { Text("Libreria") },
        )
    }
}
