package com.lorenzo.mangadownloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    state: MangaUiState,
    visibleTab: AppTab,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSettings: () -> Unit,
    onReaderBrightnessChange: (Float) -> Unit,
    onSelectReadingMode: (ReadingMode) -> Unit,
    unseenUpdatesCount: Int,
    onOpenUpdates: () -> Unit,
    onMarkAllUpdatesSeen: () -> Unit,
    homeEditMode: Boolean = false,
    onToggleHomeEdit: () -> Unit = {},
) {
    val anchorFor = LocalTutorialAnchor.current
    val readerChapter = state.readerChapter
    val selectedManga = state.selected
    val selectedSeries = state.selectedDownloadedSeries
    // Unica fonte di verità: la schermata in primo piano derivata dallo stato (vedi Screen.kt).
    // Evita di ri-ordinare a mano (e in modo divergente) la priorità delle schermate.
    val screen = state.currentScreen()
    val showBack = screen != Screen.Tabs
    val title = when (screen) {
        Screen.StorageManager -> "Gestisci memoria"
        Screen.Backup -> "Backup"
        Screen.Feedback -> "Aiutaci a migliorare"
        Screen.Changelog -> "Novità"
        Screen.Settings -> "Impostazioni"
        Screen.Updates -> "Aggiornamenti"
        Screen.History -> "Cronologia"
        Screen.DiscoverGenre -> state.discovery.selectedGenre?.label ?: "Scopri"
        Screen.Reader -> readerChapter?.title ?: "Manga Downloader"
        Screen.Detail -> selectedManga?.title ?: "Manga Downloader"
        Screen.DownloadedSeries -> selectedSeries?.title ?: "Manga Downloader"
        Screen.Tabs -> when (visibleTab) {
            // Il saluto è ricalcolato a ogni ricomposizione: non resta stantio oltre i confini
            // orari mentre l'app è in foreground (stesso criterio del vecchio HomeHeader).
            AppTab.HOME -> if (homeEditMode) {
                "Modifica Home"
            } else {
                homeGreeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            }
            AppTab.SEARCH -> "Cerca"
            AppTab.FAVORITES -> "Preferiti"
            AppTab.LIBRARY -> "Libreria"
        }
    }
    var brightnessExpanded by remember(readerChapter?.relativePath) { mutableStateOf(false) }

    // La scelta del server vive nelle chip della tab Cerca (per lingua): in alto a destra
    // resta solo l'accesso diretto alle Impostazioni, senza menu intermedio.
    val showSettingsAction = screen == Screen.Tabs
    val showUpdatesAction = visibleTab == AppTab.FAVORITES && screen == Screen.Tabs

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
            } else {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .padding(start = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_new),
                        contentDescription = "Logo Manga Downloader",
                        modifier = Modifier.size(38.dp),
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
                ReaderModeAction(
                    currentMode = state.readerReadingMode,
                    onSelectMode = onSelectReadingMode,
                    modifier = anchorFor(TutorialAnchor.READER_FULLSCREEN),
                )
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

            // Nel feed Aggiornamenti: azzera i "non visti" senza dover chiudere la schermata.
            if (screen == Screen.Updates && unseenUpdatesCount > 0) {
                IconButton(onClick = onMarkAllUpdatesSeen) {
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = "Segna tutti come visti",
                    )
                }
            }

            if (showUpdatesAction) {
                FavoriteUpdatesAction(
                    unseenCount = unseenUpdatesCount,
                    onClick = onOpenUpdates,
                )
            }

            // Matita della Home: personalizza i blocchi. In modifica diventa la spunta "Fine".
            if (screen == Screen.Tabs && visibleTab == AppTab.HOME) {
                IconButton(onClick = onToggleHomeEdit) {
                    Icon(
                        imageVector = if (homeEditMode) Icons.Filled.Done else Icons.Outlined.Edit,
                        contentDescription = if (homeEditMode) "Fine modifica Home" else "Personalizza la Home",
                    )
                }
            }

            if (showSettingsAction) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = anchorFor(TutorialAnchor.SETTINGS),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Impostazioni",
                    )
                }
            }

        },
    )
}

@Composable
private fun ReaderModeAction(
    currentMode: ReadingMode,
    onSelectMode: (ReadingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = "Modalità di lettura",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ReadingMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.menuLabel) },
                    trailingIcon = if (mode == currentMode) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelectMode(mode)
                    },
                )
            }
        }
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

/** Azione "Aggiornamenti" con badge del numero di nuovi capitoli non visti (tab Preferiti). */
@Composable
private fun FavoriteUpdatesAction(
    unseenCount: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (unseenCount > 0) {
                    Badge { Text(if (unseenCount > 99) "99+" else "$unseenCount") }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = if (unseenCount > 0) {
                    "Aggiornamenti, $unseenCount non letti"
                } else {
                    "Aggiornamenti"
                },
            )
        }
    }
}

@Composable
fun AppBottomBar(
    currentTab: AppTab,
    onSelect: (AppTab) -> Unit,
    favoritesBadgeCount: Int = 0,
) {
    val anchorFor = LocalTutorialAnchor.current
    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AppTabEntry(
            tab = AppTab.HOME,
            selected = currentTab == AppTab.HOME,
            icon = Icons.Default.Home,
            label = "Home",
            onSelect = onSelect,
        )
        AppTabEntry(
            tab = AppTab.SEARCH,
            selected = currentTab == AppTab.SEARCH,
            icon = Icons.Default.Search,
            label = "Cerca",
            onSelect = onSelect,
            modifier = anchorFor(TutorialAnchor.SEARCH_TAB),
        )
        AppTabEntry(
            tab = AppTab.FAVORITES,
            selected = currentTab == AppTab.FAVORITES,
            icon = Icons.Default.Star,
            label = "Preferiti",
            onSelect = onSelect,
            modifier = anchorFor(TutorialAnchor.FAVORITES_TAB),
            // Nuovi capitoli non ancora visti: visibili da qualunque tab, non solo dai Preferiti.
            badgeCount = favoritesBadgeCount,
        )
        AppTabEntry(
            tab = AppTab.LIBRARY,
            selected = currentTab == AppTab.LIBRARY,
            icon = Icons.AutoMirrored.Filled.LibraryBooks,
            label = "Libreria",
            onSelect = onSelect,
            modifier = anchorFor(TutorialAnchor.LIBRARY_TAB),
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
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    ShortNavigationBarItem(
        modifier = modifier,
        selected = selected,
        onClick = { onSelect(tab) },
        icon = {
            if (badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(
                                text = badgeCount.toString(),
                                modifier = Modifier.semantics {
                                    contentDescription = "$badgeCount nuovi capitoli"
                                },
                            )
                        }
                    },
                ) {
                    Icon(icon, contentDescription = null)
                }
            } else {
                Icon(icon, contentDescription = null)
            }
        },
        label = { Text(label) },
    )
}
