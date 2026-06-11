package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

@Composable
fun DeleteChapterDialog(
    chapterTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmationDialog(
        title = "Elimina capitolo",
        text = "Vuoi eliminare $chapterTitle?",
        confirmLabel = "Elimina",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * Conferma per "Elimina capitoli letti": anticipa il beneficio (quanti capitoli e quanto
 * spazio liberato) e rassicura che il progresso non si perde. [freedBytes] è la somma delle
 * dimensioni dei capitoli letti, calcolata dal chiamante.
 */
@Composable
fun DeleteReadChaptersDialog(
    seriesTitle: String,
    readCount: Int,
    freedBytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val capitoli = if (readCount == 1) "1 capitolo letto" else "$readCount capitoli letti"
    ConfirmationDialog(
        title = "Elimina capitoli letti",
        text = "Eliminerai $capitoli di $seriesTitle, liberando ${formatBytes(freedBytes)}. " +
            "Restano segnati come letti e potrai riscaricarli; il capitolo in corso e quelli " +
            "non ancora letti non vengono toccati.",
        confirmLabel = "Elimina letti",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
fun CrashReportDialog(
    report: String,
    crashPath: String,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canSend = FeedbackReporter.isConfigured()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ultimo crash rilevato") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Text(
                text = buildString {
                    append(report.lineSequence().take(10).joinToString("\n"))
                    if (crashPath.isNotBlank()) {
                        append("\n\nFile: $crashPath")
                    }
                },
            )
        },
        confirmButton = {
            if (canSend) {
                TextButton(onClick = onSend) {
                    Text("Invia segnalazione")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Chiudi")
                }
            }
        },
        dismissButton = if (canSend) {
            { TextButton(onClick = onDismiss) { Text("Chiudi") } }
        } else {
            null
        },
    )
}

@Composable
fun AvailableUpdateDialog(
    update: AppUpdateInfo,
    isInstalling: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val releaseNotes = update.releaseNotes
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    AlertDialog(
        onDismissRequest = {
            if (!isInstalling) {
                onDismiss()
            }
        },
        title = { Text("Aggiornamento disponibile") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(scrollState),
            ) {
                Text("È disponibile la versione ${update.versionName}")
                if (releaseNotes.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Novità",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            releaseNotes.forEach { note ->
                                Text(
                                    text = "• ${note.removePrefix("-").removePrefix("•").trim()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                if (isInstalling) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLoadingIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Scaricamento installer...")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isInstalling,
            ) {
                Text("Aggiorna")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isInstalling,
            ) {
                Text("Più tardi")
            }
        },
    )
}

@Composable
fun ParentalPinSetupDialog(
    state: ParentalPinSetupState,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = if (state.mode == ParentalPinSetupMode.CHANGE) {
        "Cambia PIN parental"
    } else {
        "Configura parental control"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scegli un PIN numerico di 6 cifre per proteggere Cerca.")
                PinTextField(
                    value = state.pin,
                    onValueChange = onPinChange,
                    label = "PIN",
                )
                PinTextField(
                    value = state.confirmPin,
                    onValueChange = onConfirmPinChange,
                    label = "Conferma PIN",
                )
                state.errorMessage?.let { message ->
                    Text(text = message)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Salva")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

@Composable
fun ParentalPinEntryDialog(
    state: ParentalPinEntryState,
    onPinChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inserisci PIN") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Inserisci il PIN parental di 6 cifre.")
                PinTextField(
                    value = state.pin,
                    onValueChange = onPinChange,
                    label = "PIN",
                )
                state.errorMessage?.let { message ->
                    Text(text = message)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Conferma")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

/**
 * Dialog di conferma riusabile (titolo + testo + azione di conferma + "Annulla").
 * Usato da [DeleteChapterDialog] e dai dialog di eliminazione serie (libreria, archivio).
 */
@Composable
fun ConfirmationDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        shape = MaterialTheme.shapes.extraLarge,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        },
    )
}

/** Azioni rapide su un preferito (long-press): leggi subito i primi capitoli. */
@Composable
fun FavoriteActionsDialog(
    title: String,
    onRead: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column {
                FavoriteActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = "Leggi",
                    description = null,
                    onClick = onRead,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        },
    )
}

@Composable
private fun FavoriteActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Campo di testo per un PIN numerico (mascherato, tastiera numerica). Riusato dai dialog parental. */
@Composable
private fun PinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}
