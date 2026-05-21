package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
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
    onStart: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
    onOpenStreamingChapter: (MangaDetails, ChapterEntry) -> Unit,
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

    val startAll: () -> Unit = {
        if (hasChapters) onStart(details, chapters.first(), chapters.last())
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
            )

            if (isLoading && chapters.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    AppLoadingIndicator(modifier = Modifier.padding(top = 24.dp))
                }
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

            // Azioni di download in basso a destra. La primaria ("Scarica tutto") è un
            // Extended FAB sempre visibile ed etichettato: un solo tocco, nessun menu da
            // aprire. L'intervallo resta accessibile come azione secondaria affiancata.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = startSelectRange,
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                        contentDescription = "Scarica un intervallo di capitoli",
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = startAll,
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text("Scarica tutto") },
                    modifier = tutorialAnchorFor(TutorialAnchor.DETAIL_DOWNLOAD),
                )
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
        DownloadRangeDialog(
            startChapter = startChapter,
            endChapter = selectedEnd,
            startOptions = chapters,
            endOptions = endOptions,
            startMenuExpanded = startMenuExpanded,
            endMenuExpanded = endMenuExpanded,
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

private fun ChapterEntry.isRead(readChapterIds: Set<String>): Boolean {
    return DownloadStorage.stableChapterId(this) in readChapterIds
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRangeDialog(
    startChapter: ChapterEntry,
    endChapter: ChapterEntry,
    startOptions: List<ChapterEntry>,
    endOptions: List<ChapterEntry>,
    startMenuExpanded: Boolean,
    endMenuExpanded: Boolean,
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(endChapter) }) {
                Text("Avvia")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}
