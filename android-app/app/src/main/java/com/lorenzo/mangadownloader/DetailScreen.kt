package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(
    details: MangaDetails,
    isLoading: Boolean,
    padding: PaddingValues,
    onStart: (MangaDetails, ChapterEntry, ChapterEntry) -> Unit,
) {
    var pendingStart by remember { mutableStateOf<ChapterEntry?>(null) }
    var pendingEnd by remember { mutableStateOf<ChapterEntry?>(null) }
    var endMenuExpanded by remember { mutableStateOf(false) }
    var downloadAllMenuExpanded by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val chapters = details.chapters
    val hasChapters = chapters.isNotEmpty()

    val startAll: () -> Unit = {
        if (hasChapters) onStart(details, chapters.first(), chapters.last())
    }
    val startLastN: (Int) -> Unit = { n ->
        if (hasChapters) {
            val lastN = chapters.takeLast(n.coerceAtLeast(1))
            onStart(details, lastN.first(), lastN.last())
        }
    }
    val startSelectRange: () -> Unit = {
        if (hasChapters) {
            pendingStart = chapters.first()
            pendingEnd = chapters.last()
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
                subtitle = "${chapters.size} capitoli disponibili",
            )

            if (hasChapters) {
                DownloadAllSplitButton(
                    onDownloadAll = startAll,
                    menuExpanded = downloadAllMenuExpanded,
                    onMenuExpandedChange = { downloadAllMenuExpanded = it },
                    onPickRange = {
                        downloadAllMenuExpanded = false
                        startSelectRange()
                    },
                    onDownloadLast = { n ->
                        downloadAllMenuExpanded = false
                        startLastN(n)
                    },
                )
            }

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
                    items(chapters, key = { it.url }) { chapter ->
                        ChapterRow(chapter = chapter) {
                            pendingStart = chapter
                            pendingEnd = chapter
                            endMenuExpanded = false
                        }
                    }
                }
            }
        }

        if (hasChapters) {
            DetailFabMenu(
                expanded = fabMenuExpanded,
                onExpandedChange = { fabMenuExpanded = it },
                onDownloadAll = {
                    fabMenuExpanded = false
                    startAll()
                },
                onPickRange = {
                    fabMenuExpanded = false
                    startSelectRange()
                },
                onScrollToBottom = {
                    fabMenuExpanded = false
                    scope.launch {
                        listState.animateScrollToItem(chapters.lastIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }

    pendingStart?.let { startChapter ->
        val endOptions = remember(chapters, startChapter.url) {
            val startIndex = chapters.indexOfFirst { it.url == startChapter.url }
            if (startIndex >= 0) chapters.subList(startIndex, chapters.size)
            else listOf(startChapter)
        }
        DownloadRangeDialog(
            startChapter = startChapter,
            endChapter = pendingEnd ?: startChapter,
            endOptions = endOptions,
            endMenuExpanded = endMenuExpanded,
            onDismiss = {
                pendingStart = null
                pendingEnd = null
                endMenuExpanded = false
            },
            onOpenEndMenu = { endMenuExpanded = true },
            onDismissEndMenu = { endMenuExpanded = false },
            onSelectEnd = { chapter ->
                pendingEnd = chapter
                endMenuExpanded = false
            },
            onConfirm = { endChapter ->
                pendingStart = null
                pendingEnd = null
                endMenuExpanded = false
                onStart(details, startChapter, endChapter)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadAllSplitButton(
    onDownloadAll: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onPickRange: () -> Unit,
    onDownloadLast: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(onClick = onDownloadAll) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scarica tutto")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = menuExpanded,
                    onCheckedChange = onMenuExpandedChange,
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Altre opzioni di download",
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Scarica selezione...") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null)
                            },
                            onClick = onPickRange,
                        )
                        DropdownMenuItem(
                            text = { Text("Scarica gli ultimi 10") },
                            onClick = { onDownloadLast(10) },
                        )
                        DropdownMenuItem(
                            text = { Text("Scarica gli ultimi 20") },
                            onClick = { onDownloadLast(20) },
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDownloadAll: () -> Unit,
    onPickRange: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.Close else Icons.Default.Tune,
                    contentDescription = if (expanded) "Chiudi menu" else "Azioni",
                )
            }
        },
        modifier = modifier,
    ) {
        FloatingActionButtonMenuItem(
            onClick = onDownloadAll,
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            text = { Text("Scarica tutto") },
        )
        FloatingActionButtonMenuItem(
            onClick = onPickRange,
            icon = { Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null) },
            text = { Text("Scarica intervallo") },
        )
        FloatingActionButtonMenuItem(
            onClick = onScrollToBottom,
            icon = { Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = null) },
            text = { Text("Vai in fondo") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadRangeDialog(
    startChapter: ChapterEntry,
    endChapter: ChapterEntry,
    endOptions: List<ChapterEntry>,
    endMenuExpanded: Boolean,
    onDismiss: () -> Unit,
    onOpenEndMenu: () -> Unit,
    onDismissEndMenu: () -> Unit,
    onSelectEnd: (ChapterEntry) -> Unit,
    onConfirm: (ChapterEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleziona intervallo download") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = "Capitolo ${startChapter.displayNumber()}",
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Da") },
                    readOnly = true,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                ExposedDropdownMenuBox(
                    expanded = endMenuExpanded,
                    onExpandedChange = { if (it) onOpenEndMenu() else onDismissEndMenu() },
                ) {
                    OutlinedTextField(
                        value = "Capitolo ${endChapter.displayNumber()}",
                        onValueChange = {},
                        modifier = Modifier
                            .menuAnchor()
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
                                text = { Text("Capitolo ${candidate.displayNumber()}") },
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
