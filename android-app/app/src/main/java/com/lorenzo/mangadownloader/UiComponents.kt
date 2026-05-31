package com.lorenzo.mangadownloader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil.compose.AsyncImage

/**
 * Colori standard delle card "contenitore" dell'app (sfondo `surfaceContainerHigh`).
 * Punto unico per il token colore: usato da `ResultCard`/`FavoriteCard`/`LibrarySeriesCard`/
 * `SettingsSection`/`StorageHeader`. La `shape` resta scelta dal singolo call site.
 */
@Composable
fun appCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

/**
 * Indicatore di caricamento a tutta pagina, centrato in alto. Stato di "loading" condiviso
 * tra le schermate (ricerca, libreria, archivio, dettaglio).
 */
@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AppLoadingIndicator(modifier = Modifier.padding(top = 24.dp))
    }
}

@Composable
fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        placeholder = { Text(placeholder) },
        singleLine = true,
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Pulisci")
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
fun CoverImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
) {
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Stato vuoto coerente con le linee guida M3: icona + titolo + (opzionale) descrizione
 * e una sola CTA che porta al passo successivo, invece di un semplice testo "Nessun…".
 * Trasforma un vicolo cieco in un'azione chiara.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (!description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onAction, shape = MaterialTheme.shapes.large) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Elenco delle ricerche recenti: l'utente tocca una voce per rilanciare quella ricerca
 * senza ridigitarla (meno passaggi). Mostrato quando la barra di ricerca è vuota.
 */
@Composable
fun RecentSearches(
    queries: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ricerche recenti",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text("Cancella")
            }
        }
        queries.forEach { query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(query) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SeriesHeader(
    coverModel: Any?,
    title: String,
    subtitle: String,
    status: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onDownloadAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            model = coverModel,
            title = title,
            modifier = Modifier
                .width(104.dp)
                .height(156.dp)
                .clip(MaterialTheme.shapes.extraLarge),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!status.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        onDownloadAll?.let { downloadAll ->
            IconButton(onClick = downloadAll) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Scarica tutto il manga",
                )
            }
        }
    }
}

@Composable
fun ResultCard(
    result: MangaSearchResult,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowInfo: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = appCardColors(),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    model = result.coverUrl,
                    title = result.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.extraLarge),
                )
                InfoBadge(
                    onClick = onShowInfo,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
                FavoriteToggleBadge(
                    isFavorite = isFavorite,
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            Text(
                text = result.title,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(36.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Informazioni manga",
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteToggleBadge(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isFavorite) 1.05f else 1f,
        label = "favoriteBadgeScale",
    )
    FilledIconToggleButton(
        checked = isFavorite,
        onCheckedChange = { onClick() },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = if (isFavorite) MaterialTheme.shapes.medium else MaterialTheme.shapes.extraLarge,
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = FavoriteYellow,
        ),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteCard(
    favorite: FavoriteManga,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
        shape = MaterialTheme.shapes.extraLarge,
        colors = appCardColors(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            CoverImage(
                model = favorite.coverUrl,
                title = favorite.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.large),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = favorite.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = FavoriteYellow,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun ChapterRow(
    chapter: ChapterEntry,
    isDownloaded: Boolean = false,
    isRead: Boolean = false,
    onClick: () -> Unit,
) {
    val containerColor = if (isDownloaded || isRead) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter.displayShortLabel(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (isDownloaded || isRead) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Capitolo scaricato",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (isRead) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Capitolo letto",
                            tint = ReadGreen,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadedChapterRow(
    chapter: DownloadedChapter,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val containerColor = if (chapter.isRead) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onOpen),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    chapter.isReaderCompleted() -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completato",
                            tint = ReadGreen,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    chapter.hasReaderProgress() -> {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = chapter.readerProgressDescription(),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Elimina capitolo",
                )
            }
        }
    }
}

@Composable
fun ScrollToBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.loweredElevation(),
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardDoubleArrowDown,
            contentDescription = "Vai in fondo",
        )
    }
}

@Composable
fun ReaderChapterNavigationRow(
    previousChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onOpenPrevious,
            enabled = previousChapter != null,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Precedente")
        }
        TextButton(
            onClick = onOpenNext,
            enabled = nextChapter != null,
        ) {
            Text("Successivo")
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

@Composable
fun DownloadedSeriesActionBar(
    readCount: Int,
    totalCount: Int,
    firstChapter: DownloadedChapter?,
    resumeChapter: DownloadedChapter?,
    onOpenChapter: (DownloadedChapter) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    totalCount == 0 -> "Nessun capitolo disponibile"
                    readCount == 0 -> "$totalCount capitoli scaricati"
                    readCount >= totalCount -> "Completato · $readCount / $totalCount capitoli"
                    else -> "$readCount / $totalCount capitoli letti"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { firstChapter?.let(onOpenChapter) },
                    enabled = firstChapter != null,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Inizio")
                }
                FilledTonalButton(
                    onClick = { resumeChapter?.let(onOpenChapter) },
                    enabled = resumeChapter != null,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Riprendi")
                }
            }
        }
    }
}

@Composable
fun NumberSettingField(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            val digits = newText.filter(Char::isDigit).take(3)
            text = digits
            digits.toIntOrNull()?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
fun SeriesActionsMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onShowInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(onClick = onExpand) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Azioni manga",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("Info") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = onShowInfo,
            )
            DropdownMenuItem(
                text = { Text("Elimina") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = onDelete,
            )
        }
    }
}

@Composable
fun SeriesDownloadSummary(
    status: SeriesDownloadStatus,
    onStopDownloads: () -> Unit,
) {
    val supporting = when {
        status.state == WorkInfo.State.RUNNING && status.totalChapters > 0 ->
            "${status.doneChapters} / ${status.totalChapters} capitoli"
        status.state == WorkInfo.State.ENQUEUED || status.state == WorkInfo.State.BLOCKED ->
            if (status.requestCount > 1) "In coda (${status.requestCount})" else "In coda"
        else -> null
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            supporting?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.state == WorkInfo.State.RUNNING) {
                Spacer(modifier = Modifier.height(4.dp))
                DownloadProgressIndicator(
                    doneChapters = status.doneChapters,
                    totalChapters = status.totalChapters,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onStopDownloads,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(32.dp),
            shape = MaterialTheme.shapes.medium,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Ferma download",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
