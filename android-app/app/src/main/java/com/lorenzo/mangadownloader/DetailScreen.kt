package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    details: MangaDetails,
    isLoading: Boolean,
    padding: PaddingValues,
    downloadedChapterKeys: Set<String>,
    readChapterIds: Set<String>,
    streamingReaderEnabled: Boolean,
    autoDownloadEnabled: Boolean,
    // Selettore fonte: visibile solo per serie con un SeriesLink (multi-fonte).
    showSourceSelector: Boolean,
    sourceOptions: List<SourceOptionUi>,
    onOpenSourceMenu: () -> Unit,
    onSwitchSource: (SourceOptionUi) -> Unit,
    onSearchOtherSources: () -> Unit,
    onUnlinkSource: (String) -> Unit,
    otherSourcesSheet: OtherSourcesUiState?,
    onPickOtherSource: (MangaSearchResult) -> Unit,
    onDismissOtherSources: () -> Unit,
    // Tracking AniList: la riga compare solo con l'account collegato (vedi AniListTrackingRow).
    showAniListTracking: Boolean,
    aniListTracking: AniListTracking?,
    onLinkAniList: () -> Unit,
    onOpenAniListTracker: () -> Unit,
    onStart: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
    onOpenStreamingChapter: (MangaDetails, ChapterEntry) -> Unit,
    onEnableAutoDownload: () -> Unit,
) {
    var pendingStart by remember { mutableStateOf<ChapterEntry?>(null) }
    var pendingEnd by remember { mutableStateOf<ChapterEntry?>(null) }
    var startMenuExpanded by remember { mutableStateOf(false) }
    var endMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val chapters = details.chapters
    val chapterListItems = remember(chapters) { buildChapterListItems(chapters) }
    val hasChapters = chapters.isNotEmpty()
    val isAtListBottom by remember(chapterListItems.size) {
        derivedStateOf {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            visibleItems.isNotEmpty() && visibleItems.last().index >= chapterListItems.lastIndex
        }
    }

    val startSelectRange: () -> Unit = {
        if (hasChapters) {
            pendingStart = chapters.first()
            pendingEnd = chapters.last()
            startMenuExpanded = false
            endMenuExpanded = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SeriesHeader(
                coverModel = details.coverUrl,
                title = details.title,
                subtitle = "${chapters.size} ${readingUnitPlural(chapters)} disponibili",
                status = details.status.displayLabel(),
                statusColor = when (details.status) {
                    MangaPublicationStatus.ONGOING -> MaterialTheme.colorScheme.primary
                    MangaPublicationStatus.COMPLETED -> ReadGreen
                    MangaPublicationStatus.DROPPED -> MaterialTheme.colorScheme.error
                    MangaPublicationStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (showSourceSelector) {
                SourceSelector(
                    activeSourceId = details.sourceId,
                    options = sourceOptions,
                    onOpenMenu = onOpenSourceMenu,
                    onSwitchSource = onSwitchSource,
                    onSearchOtherSources = onSearchOtherSources,
                    onUnlinkSource = onUnlinkSource,
                )
            }

            if (showAniListTracking) {
                AniListTrackingRow(
                    tracking = aniListTracking,
                    onLink = onLinkAniList,
                    onOpenTracker = onOpenAniListTracker,
                )
            }

            if (isLoading && chapters.isEmpty()) {
                FullScreenLoading()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(chapterListItems, key = { it.key }) { item ->
                        when (item) {
                            is ChapterListItem.VolumeHeader -> VolumeHeaderRow(item.title)
                            is ChapterListItem.Chapter -> {
                                val chapter = item.chapter
                                ChapterRow(
                                    chapter = chapter,
                                    isDownloaded = chapter.isDownloaded(downloadedChapterKeys),
                                    isRead = chapter.isRead(readChapterIds),
                                ) {
                                    if (streamingReaderEnabled) {
                                        onOpenStreamingChapter(details, chapter)
                                    } else {
                                        pendingStart = chapter
                                        pendingEnd = chapter
                                        startMenuExpanded = false
                                        endMenuExpanded = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasChapters) {
            val tutorialAnchorFor = LocalTutorialAnchor.current

            // Utility di navigazione lista (vai in cima/fondo): tenuta separata dalle
            // azioni primarie, come da linee guida M3. In basso a sinistra.
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(
                            if (isAtListBottom) 0 else chapterListItems.lastIndex,
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    imageVector = if (isAtListBottom) {
                        Icons.Default.KeyboardDoubleArrowUp
                    } else {
                        Icons.Default.KeyboardDoubleArrowDown
                    },
                    contentDescription = if (isAtListBottom) {
                        "Vai in cima alla lista"
                    } else {
                        "Vai in fondo alla lista"
                    },
                )
            }

            // Azione di download primaria: un solo Extended FAB che apre il selettore di
            // intervallo (la modalità che la maggior parte degli utenti preferisce).
            // L'intervallo è pre-impostato su tutti i capitoli, quindi "scarica tutto"
            // resta a un passo. Niente più due pulsanti separati.
            ExtendedFloatingActionButton(
                onClick = startSelectRange,
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                text = { Text("Scarica…") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .then(tutorialAnchorFor(TutorialAnchor.DETAIL_DOWNLOAD)),
            )
        }
    }

    otherSourcesSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = onDismissOtherSources) {
            Text(
                text = "Altre fonti per questa serie",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            when {
                sheet.isLoading -> AppLoadingIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                )
                sheet.error != null -> Text(
                    text = sheet.error,
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sheet.results.isEmpty() -> Text(
                    text = "Nessun risultato sulle altre fonti.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(
                        sheet.results,
                        key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) },
                    ) { result ->
                        ListItem(
                            modifier = Modifier.clickable { onPickOtherSource(result) },
                            supportingContent = {
                                Text(
                                    MangaSourceCatalog.displayName(result.sourceId) + " · " +
                                        MangaSourceCatalog.languageOf(result.sourceId).displayName,
                                )
                            },
                        ) {
                            Text(result.title)
                        }
                    }
                }
            }
        }
    }

    pendingStart?.let { startChapter ->
        val endOptions = remember(chapters, startChapter.url) {
            val startIndex = chapters.indexOfFirst { it.url == startChapter.url }
            if (startIndex >= 0) chapters.subList(startIndex, chapters.size)
            else listOf(startChapter)
        }
        val selectedEnd = pendingEnd
            ?.takeIf { endChapter -> endOptions.any { it.url == endChapter.url } }
            ?: endOptions.lastOrNull()
            ?: startChapter
        val rangeSummary = remember(chapters, startChapter.url, selectedEnd.url, downloadedChapterKeys) {
            downloadRangeSummary(chapters, startChapter.url, selectedEnd.url, downloadedChapterKeys)
        }
        DownloadRangeDialog(
            startChapter = startChapter,
            endChapter = selectedEnd,
            startOptions = chapters,
            endOptions = endOptions,
            selectedCount = rangeSummary.selectedCount,
            alreadyDownloadedCount = rangeSummary.alreadyDownloadedCount,
            startMenuExpanded = startMenuExpanded,
            endMenuExpanded = endMenuExpanded,
            autoDownloadEnabled = autoDownloadEnabled,
            onEnableAutoDownload = onEnableAutoDownload,
            onDismiss = {
                pendingStart = null
                pendingEnd = null
                startMenuExpanded = false
                endMenuExpanded = false
            },
            onOpenStartMenu = { startMenuExpanded = true },
            onDismissStartMenu = { startMenuExpanded = false },
            onOpenEndMenu = { endMenuExpanded = true },
            onDismissEndMenu = { endMenuExpanded = false },
            onSelectStart = { chapter ->
                val startIndex = chapters.indexOfFirst { it.url == chapter.url }
                val currentEndIndex = pendingEnd?.let { endChapter ->
                    chapters.indexOfFirst { it.url == endChapter.url }
                } ?: -1
                pendingStart = chapter
                if (currentEndIndex < startIndex) {
                    pendingEnd = chapter
                }
                startMenuExpanded = false
            },
            onSelectEnd = { chapter ->
                pendingEnd = chapter
                endMenuExpanded = false
            },
            onConfirm = { endChapter ->
                pendingStart = null
                pendingEnd = null
                startMenuExpanded = false
                endMenuExpanded = false
                onStart(details, startChapter, endChapter)
            },
        )
    }
}

/**
 * Selettore della fonte attiva, sotto il titolo. Aprendo il menu si caricano (lazy) le
 * info comparative per fonte: capitoli disponibili e ultimo uscito. L'ultima voce apre
 * la ricerca su altre fonti non ancora collegate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelector(
    activeSourceId: String,
    options: List<SourceOptionUi>,
    onOpenMenu: () -> Unit,
    onSwitchSource: (SourceOptionUi) -> Unit,
    onSearchOtherSources: () -> Unit,
    onUnlinkSource: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            if (open) onOpenMenu()
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = "${MangaSourceCatalog.displayName(activeSourceId)} · " +
                MangaSourceCatalog.languageOf(activeSourceId).displayName,
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text("Fonte") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.large,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                "${MangaSourceCatalog.displayName(option.sourceId)} · " +
                                    MangaSourceCatalog.languageOf(option.sourceId).displayName,
                            )
                            Text(
                                text = when {
                                    option.isLoading -> "Carico…"
                                    option.hasError -> "Non raggiungibile"
                                    option.chapterCount != null ->
                                        "${option.chapterCount} capitoli" +
                                            (option.lastChapterLabel?.let { " · ultimo: $it" } ?: "")
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (option.hasError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!option.hasError && option.sourceId != activeSourceId) {
                            onSwitchSource(option)
                        }
                    },
                    trailingIcon = if (option.sourceId != activeSourceId && options.size > 1) {
                        {
                            IconButton(onClick = {
                                expanded = false
                                onUnlinkSource(option.sourceId)
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Scollega ${MangaSourceCatalog.displayName(option.sourceId)}",
                                )
                            }
                        }
                    } else {
                        null
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            DropdownMenuItem(
                text = { Text("Cerca su altre fonti…") },
                onClick = {
                    expanded = false
                    onSearchOtherSources()
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}

@Composable
private fun VolumeHeaderRow(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private sealed class ChapterListItem {
    abstract val key: String

    data class VolumeHeader(
        val title: String,
        override val key: String,
    ) : ChapterListItem()

    data class Chapter(
        val chapter: ChapterEntry,
    ) : ChapterListItem() {
        override val key: String = "chapter:${chapter.url}"
    }
}

private fun buildChapterListItems(chapters: List<ChapterEntry>): List<ChapterListItem> {
    val items = mutableListOf<ChapterListItem>()
    var currentVolume: String? = null
    chapters.forEach { chapter ->
        val volume = chapter.volumeText?.trim()?.takeIf(String::isNotBlank)
        if (volume != null && volume != currentVolume) {
            items += ChapterListItem.VolumeHeader(
                title = volume,
                key = "volume:${items.size}:$volume",
            )
            currentVolume = volume
        } else if (volume == null) {
            currentVolume = null
        }
        items += ChapterListItem.Chapter(chapter)
    }
    return items
}

private fun ChapterEntry.isDownloaded(downloadedChapterKeys: Set<String>): Boolean {
    val stableId = DownloadStorage.stableChapterId(this)
    val numberKey = "number:${DownloadStorage.normalizedChapterLabel(displayNumber())}"
    return stableId in downloadedChapterKeys || numberKey in downloadedChapterKeys
}

/** Capitoli selezionati e quanti di questi sono già scaricati, nel range [startUrl]..[endUrl]. */
data class DownloadRangeSummary(val selectedCount: Int, val alreadyDownloadedCount: Int)

/** Funzione pura (testabile): conta i capitoli del range e quanti verrebbero saltati. */
fun downloadRangeSummary(
    chapters: List<ChapterEntry>,
    startUrl: String,
    endUrl: String,
    downloadedChapterKeys: Set<String>,
): DownloadRangeSummary {
    val start = chapters.indexOfFirst { it.url == startUrl }
    val end = chapters.indexOfFirst { it.url == endUrl }
    if (start < 0 || end < 0 || start > end) return DownloadRangeSummary(0, 0)
    val range = chapters.subList(start, end + 1)
    return DownloadRangeSummary(
        selectedCount = range.size,
        alreadyDownloadedCount = range.count { it.isDownloaded(downloadedChapterKeys) },
    )
}

private fun ChapterEntry.isRead(readChapterIds: Set<String>): Boolean {
    val numberKey = "number:${DownloadStorage.normalizedChapterLabel(displayNumber())}"
    return DownloadStorage.stableChapterId(this) in readChapterIds || numberKey in readChapterIds
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRangeDialog(
    startChapter: ChapterEntry,
    endChapter: ChapterEntry,
    startOptions: List<ChapterEntry>,
    endOptions: List<ChapterEntry>,
    selectedCount: Int,
    alreadyDownloadedCount: Int,
    startMenuExpanded: Boolean,
    endMenuExpanded: Boolean,
    autoDownloadEnabled: Boolean,
    onEnableAutoDownload: () -> Unit,
    onDismiss: () -> Unit,
    onOpenStartMenu: () -> Unit,
    onDismissStartMenu: () -> Unit,
    onOpenEndMenu: () -> Unit,
    onDismissEndMenu: () -> Unit,
    onSelectStart: (ChapterEntry) -> Unit,
    onSelectEnd: (ChapterEntry) -> Unit,
    onConfirm: (ChapterEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleziona intervallo download") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = startMenuExpanded,
                    onExpandedChange = { if (it) onOpenStartMenu() else onDismissStartMenu() },
                ) {
                    OutlinedTextField(
                        value = startChapter.displayLabel(),
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Da") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = startMenuExpanded)
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    ExposedDropdownMenu(
                        expanded = startMenuExpanded,
                        onDismissRequest = onDismissStartMenu,
                    ) {
                        startOptions.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.displayLabel()) },
                                onClick = { onSelectStart(candidate) },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = endMenuExpanded,
                    onExpandedChange = { if (it) onOpenEndMenu() else onDismissEndMenu() },
                ) {
                    OutlinedTextField(
                        value = endChapter.displayLabel(),
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        readOnly = true,
                        singleLine = true,
                        label = { Text("A") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = endMenuExpanded)
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    ExposedDropdownMenu(
                        expanded = endMenuExpanded,
                        onDismissRequest = onDismissEndMenu,
                    ) {
                        endOptions.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.displayLabel()) },
                                onClick = { onSelectEnd(candidate) },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                // Riepilogo live: quanti capitoli si scaricheranno davvero (i già presenti
                // vengono saltati dal worker), così la conferma non è più un "Avvia" alla cieca.
                val skipNote = if (alreadyDownloadedCount > 0) {
                    " · $alreadyDownloadedCount già scaricati verranno saltati"
                } else {
                    ""
                }
                Text(
                    text = "$selectedCount capitoli selezionati$skipNote",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!autoDownloadEnabled) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Download automatico",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Scarica da solo i capitoli successivi mentre leggi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            TextButton(onClick = onEnableAutoDownload) {
                                Text("Attiva")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val toDownload = (selectedCount - alreadyDownloadedCount).coerceAtLeast(0)
            TextButton(
                onClick = { onConfirm(endChapter) },
                enabled = toDownload > 0,
            ) {
                Text(if (toDownload > 0) "Scarica $toDownload" else "Già scaricati")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}
