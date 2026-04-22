package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    state: MangaUiState,
    visibleTab: AppTab,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSource: (String) -> Unit,
) {
    val resolvedSourceId = remember(state.settings.searchSourceId) {
        MangaSourceCatalog.resolveSourceId(state.settings.searchSourceId)
    }
    val selectedSourceName = remember(resolvedSourceId) {
        MangaSourceCatalog.displayName(resolvedSourceId)
    }
    val selectedSourceShortName = remember(resolvedSourceId) {
        MangaSourceCatalog.shortDisplayName(resolvedSourceId)
    }
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
    var showServerDialog by remember { mutableStateOf(false) }
    var serverSelectorExpanded by remember { mutableStateOf(false) }

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showOverflow) {
                    Text(
                        text = selectedSourceShortName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                        DropdownMenuItem(
                            text = { Text("Server: $selectedSourceShortName") },
                            leadingIcon = {
                                Icon(Icons.Default.Storage, contentDescription = null)
                            },
                            onClick = {
                                overflowExpanded = false
                                showServerDialog = true
                            },
                        )
                        HorizontalDivider()
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
                    }
                }
            }
        },
    )

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = {
                serverSelectorExpanded = false
                showServerDialog = false
            },
            title = { Text("Seleziona server") },
            text = {
                ExposedDropdownMenuBox(
                    expanded = serverSelectorExpanded,
                    onExpandedChange = { serverSelectorExpanded = !serverSelectorExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    OutlinedTextField(
                        value = selectedSourceName,
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Server") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Storage, contentDescription = null)
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverSelectorExpanded)
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    ExposedDropdownMenu(
                        expanded = serverSelectorExpanded,
                        onDismissRequest = { serverSelectorExpanded = false },
                    ) {
                        MangaSourceCatalog.descriptors.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.displayName) },
                                onClick = {
                                    serverSelectorExpanded = false
                                    onSelectSource(source.id)
                                    showServerDialog = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    serverSelectorExpanded = false
                    showServerDialog = false
                }) {
                    Text("Chiudi")
                }
            },
        )
    }
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
