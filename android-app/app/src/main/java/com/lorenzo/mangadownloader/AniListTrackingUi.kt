package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * UI del tracking AniList nel dettaglio del manga: la riga sotto l'header (collega / stato
 * attuale), il dialog di matching serie→media e il dialog di modifica stato/progresso/voto.
 * Lo stato vive in [AniListUiState]; qui solo presentazione e callback verso il ViewModel.
 */

/**
 * Riga compatta sotto il [SeriesHeader]: se la serie non è collegata invita al matching,
 * altrimenti riassume l'entry ("In lettura · 12/120") e apre il tracker al tap.
 * Va mostrata solo con l'account AniList collegato.
 */
@Composable
fun AniListTrackingRow(
    tracking: AniListTracking?,
    onLink: () -> Unit,
    onOpenTracker: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (tracking == null) onLink else onOpenTracker)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (tracking == null) {
                Icons.AutoMirrored.Filled.PlaylistAdd
            } else {
                Icons.AutoMirrored.Filled.PlaylistAddCheck
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (tracking == null) {
            Text(
                text = "Collega ad AniList",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = buildString {
                    append(tracking.status?.label ?: "Sulla tua lista")
                    append(" · ")
                    append(tracking.progress)
                    append("/")
                    append(tracking.totalChapters?.toString() ?: "?")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Matching serie→AniList: ricerca per titolo (pre-compilata col titolo della serie) e lista
 * di candidati con copertina; il tap su un candidato conferma il collegamento.
 */
@Composable
fun AniListMatchDialog(
    match: AniListMatchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (AniListManga) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!match.isLinking) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Collega ad AniList") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = match.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Titolo su AniList") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        IconButton(onClick = onSearch, enabled = !match.isLinking) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Cerca su AniList",
                            )
                        }
                    },
                )
                when {
                    match.isLoading || match.isLinking -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppLoadingIndicator()
                    }
                    match.errorMessage != null -> Text(
                        text = match.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    match.candidates.isEmpty() -> Text(
                        text = "Nessun risultato su AniList. Prova ad accorciare o tradurre il titolo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(match.candidates, key = { it.id }) { candidate ->
                            AniListCandidateRow(
                                candidate = candidate,
                                onClick = { onSelect(candidate) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !match.isLinking) {
                Text("Annulla")
            }
        },
    )
}

@Composable
private fun AniListCandidateRow(
    candidate: AniListManga,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            model = candidate.coverUrl,
            title = candidate.displayTitle(),
            modifier = Modifier
                .size(width = 44.dp, height = 62.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.displayTitle(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            val meta = buildList {
                candidate.format?.let { add(it.replace('_', ' ')) }
                candidate.chapters?.let { add("$it cap.") }
                candidate.averageScore?.let { add("$it%") }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Modifica manuale dell'entry AniList: stato, progresso (capitoli letti) e voto nel formato
 * dell'account. "Salva" fa una sola mutation; "Scollega" rimuove solo il legame locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListTrackerDialog(
    tracking: AniListTracking,
    scoreFormat: AniListScoreFormat,
    isSaving: Boolean,
    onSave: (AniListListStatus, Int, Double?) -> Unit,
    onUnlink: () -> Unit,
    onOpenOnSite: () -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember(tracking) {
        mutableStateOf(tracking.status ?: AniListListStatus.CURRENT)
    }
    var statusMenuExpanded by remember { mutableStateOf(false) }
    var progressText by remember(tracking) { mutableStateOf(tracking.progress.toString()) }
    var score by remember(tracking, scoreFormat) {
        mutableStateOf((tracking.score ?: 0.0).toFloat().coerceIn(0f, scoreFormat.maxValue))
    }

    val progress = progressText.toIntOrNull()?.coerceAtLeast(0)
    val scoreStep = when (scoreFormat) {
        AniListScoreFormat.POINT_100 -> 5f
        AniListScoreFormat.POINT_10_DECIMAL -> 0.5f
        else -> 1f
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(tracking.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExposedDropdownMenuBox(
                    expanded = statusMenuExpanded,
                    onExpandedChange = { statusMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = status.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Stato") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false },
                    ) {
                        AniListListStatus.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    status = option
                                    statusMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { progressText = ((progress ?: 0) - 1).coerceAtLeast(0).toString() },
                        enabled = (progress ?: 0) > 0,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Un capitolo in meno",
                        )
                    }
                    OutlinedTextField(
                        value = progressText,
                        onValueChange = { text ->
                            progressText = text.filter(Char::isDigit).take(5)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Capitoli letti") },
                        suffix = { Text("/ ${tracking.totalChapters?.toString() ?: "?"}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = progress == null,
                    )
                    IconButton(
                        onClick = { progressText = ((progress ?: 0) + 1).toString() },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Un capitolo in più",
                        )
                    }
                }

                Column {
                    Text(
                        text = if (score <= 0f) {
                            "Voto: nessuno"
                        } else {
                            "Voto: ${scoreFormat.displayValue(score.toDouble())}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = score,
                        onValueChange = { value ->
                            // Aggancia il valore al passo del formato voto dell'account.
                            score = (Math.round(value / scoreStep) * scoreStep)
                                .coerceIn(0f, scoreFormat.maxValue)
                        },
                        valueRange = 0f..scoreFormat.maxValue,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenOnSite, enabled = !isSaving) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apri su AniList")
                    }
                    TextButton(onClick = onUnlink, enabled = !isSaving) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scollega", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        status,
                        progress ?: tracking.progress,
                        score.toDouble().takeIf { it > 0.0 },
                    )
                },
                enabled = !isSaving && progress != null,
            ) {
                Text(if (isSaving) "Salvataggio…" else "Salva")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Annulla")
            }
        },
    )
}
