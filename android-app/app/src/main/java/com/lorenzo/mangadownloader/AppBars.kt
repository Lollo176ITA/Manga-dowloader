package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Slider
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    state: MangaUiState,
    visibleTab: AppTab,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSource: (String) -> Unit,
    onReaderBrightnessChange: (Float) -> Unit,
    onEnterReaderFullscreen: () -> Unit,
) {
    val anchorFor = LocalTutorialAnchor.current
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
    var brightnessExpanded by remember(readerChapter?.relativePath) { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverSelectorExpanded by remember { mutableStateOf(false) }

    val inDetail = selectedManga != null
    val inSeries = state.currentTab == AppTab.LIBRARY && selectedSeries != null
    val showOverflow = readerChapter == null &&
        !state.showSettings &&
        !inDetail &&
        !inSeries

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            if (readerChapter != null && state.settings.privacyBrightnessEnabled) {
                ReaderBrightnessAction(
                    brightness = state.settings.readerBrightness,
                    expanded = brightnessExpanded,
                    onExpandedChange = { brightnessExpanded = it },
                    onBrightnessChange = onReaderBrightnessChange,
                )
            }

            if (readerChapter != null) {
                IconButton(onClick = onEnterReaderFullscreen) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Schermo intero",
                    )
                }
            }

            if (selectedManga != null) {
                val isFavorite = MangaSourceCatalog.identityKey(
                    selectedManga.sourceId,
                    selectedManga.mangaUrl,
                ) in state.favoriteMangaKeys
                FavoriteToggleAction(
                    isFavorite = isFavorite,
                    onToggle = onToggleFavorite,
                    modifier = anchorFor(TutorialAnchor.DETAIL_FAVORITE),
                )
            }

            if (showOverflow) {
                Box {
                    IconButton(
                        onClick = { overflowExpanded = true },
                        modifier = anchorFor(TutorialAnchor.OVERFLOW),
                    ) {
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
            shape = MaterialTheme.shapes.extraLarge,
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
private fun ReaderBrightnessAction(
    brightness: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onBrightnessChange: (Float) -> Unit,
) {
    Box {
        FilledIconToggleButton(
            checked = expanded,
            onCheckedChange = onExpandedChange,
            shape = if (expanded) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.LightMode,
                contentDescription = "Regola luminosità",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Luminosità",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${(brightness.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = brightness.coerceIn(0f, 1f),
                    onValueChange = onBrightnessChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FavoriteToggleAction(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconToggleButton(
        checked = isFavorite,
        onCheckedChange = { onToggle() },
        modifier = modifier,
        shape = if (isFavorite) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = FavoriteYellow,
        ),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
        )
    }
}

@Composable
fun AppBottomBar(
    currentTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    val anchorFor = LocalTutorialAnchor.current
    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AppTabEntry(
            tab = AppTab.SEARCH,
            selected = currentTab == AppTab.SEARCH,
            icon = Icons.Default.Search,
            label = "Cerca",
            onSelect = onSelect,
            anchorModifier = anchorFor(TutorialAnchor.SEARCH_TAB),
        )
        AppTabEntry(
            tab = AppTab.FAVORITES,
            selected = currentTab == AppTab.FAVORITES,
            icon = Icons.Default.Star,
            label = "Preferiti",
            onSelect = onSelect,
            anchorModifier = anchorFor(TutorialAnchor.FAVORITES_TAB),
        )
        AppTabEntry(
            tab = AppTab.LIBRARY,
            selected = currentTab == AppTab.LIBRARY,
            icon = Icons.AutoMirrored.Filled.LibraryBooks,
            label = "Libreria",
            onSelect = onSelect,
            anchorModifier = anchorFor(TutorialAnchor.LIBRARY_TAB),
        )
    }
}

@Composable
private fun AppTabEntry(
    tab: AppTab,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onSelect: (AppTab) -> Unit,
    anchorModifier: Modifier = Modifier,
) {
    ShortNavigationBarItem(
        modifier = anchorModifier,
        selected = selected,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}
