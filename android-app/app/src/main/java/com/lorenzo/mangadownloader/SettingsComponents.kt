package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Card di una sezione delle impostazioni: intestazione (icona + titolo) e contenuto. Le sezioni
 * raggruppano più impostazioni affini (separate da [SettingsDivider]) per tenere la schermata
 * compatta. Standardizza padding, forma e colori coerenti col resto dell'app (vedi `appCardColors`).
 */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

/** Separatore tra due impostazioni nella stessa [SettingsSection]. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Piccola etichetta per un sotto-gruppo (es. un gruppo di radio) dentro una sezione. */
@Composable
private fun SettingsSubheader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** Riga impostazione con titolo, descrizione e interruttore. */
@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// --- Contenuti (senza card): le card sono composte in SettingsScreen raggruppando per tema. ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModeContent(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
) {
    val options = listOf(
        Triple(ThemeMode.AUTO, Icons.Default.BrightnessAuto, "Auto"),
        Triple(ThemeMode.LIGHT, Icons.Default.LightMode, "Chiaro"),
        Triple(ThemeMode.DARK, Icons.Default.DarkMode, "Scuro"),
    )
    Column {
        SettingsSubheader("Tema")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, icon, label) ->
                SegmentedButton(
                    selected = currentMode == mode,
                    onClick = { onSelectMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingRow(
            title = "Colori dinamici",
            description = "Usa i colori del sistema (Material You)",
            checked = useDynamicColor,
            onCheckedChange = onToggleDynamicColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingModeContent(
    currentMode: ReadingMode,
    onSelectMode: (ReadingMode) -> Unit,
) {
    Column {
        SettingsSubheader("Modalità di lettura")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReadingMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = currentMode == mode,
                    onClick = { onSelectMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ReadingMode.entries.size),
                    label = { Text(mode.shortLabel) },
                )
            }
        }
        Text(
            text = "Vale per i nuovi manga. Puoi cambiarla per la singola serie dal lettore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun DoubleTapZoomContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Doppio tap per zoomare",
        description = "Tocca due volte una pagina per ingrandirla. Lo zoom con due dita resta sempre attivo.",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun StreamingReaderContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Leggi senza scaricare",
        description = "Apri i capitoli in streaming, scaricandoli solo temporaneamente",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun AutoDownloadContent(
    enabled: Boolean,
    triggerChapters: Int,
    batchSize: Int,
    onToggle: (Boolean) -> Unit,
    onTriggerChange: (Int) -> Unit,
    onBatchChange: (Int) -> Unit,
) {
    Column {
        SettingRow(
            title = "Auto-download",
            description = "Scarica i nuovi capitoli quando ti avvicini alla fine",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        if (enabled) {
            Spacer(modifier = Modifier.height(16.dp))
            NumberSettingField(
                label = "Capitoli rimanenti prima di scaricare",
                value = triggerChapters,
                enabled = enabled,
                onValueChange = onTriggerChange,
            )
            Spacer(modifier = Modifier.height(12.dp))
            NumberSettingField(
                label = "Quanti capitoli scaricare ogni volta",
                value = batchSize,
                enabled = enabled,
                onValueChange = onBatchChange,
            )
        }
    }
}

@Composable
fun SmartCleanupContent(
    enabled: Boolean,
    keepPrevious: Int,
    onToggle: (Boolean) -> Unit,
    onKeepChange: (Int) -> Unit,
) {
    Column {
        SettingRow(
            title = "Pulizia intelligente",
            description = "Elimina i capitoli letti, tenendone solo alcuni prima di quello in lettura",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        if (enabled) {
            Spacer(modifier = Modifier.height(16.dp))
            NumberSettingField(
                label = "Capitoli precedenti da mantenere",
                value = keepPrevious,
                enabled = enabled,
                onValueChange = onKeepChange,
            )
        }
    }
}

/** Riga azione (apre un'altra schermata): titolo, descrizione e tap sull'intera riga. */
@Composable
fun StorageManagerContent(
    onOpenStorageManager: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenStorageManager),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Gestisci memoria",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Vedi quanto spazio occupa ogni manga ed elimina ciò che non ti serve",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Riga azione che apre la schermata Backup e ripristino. */
@Composable
fun BackupContent(
    onOpenBackup: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenBackup),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Backup,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Backup e ripristino",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Esporta o importa preferiti e impostazioni in un file",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DiscoveryContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Scopri (AniList)",
        description = "Mostra la scheda Scopri per esplorare tendenze e generi da AniList",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun FavoriteNotificationsContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Notifiche preferiti",
        description = "Ricevi una notifica quando esce un nuovo capitolo dei tuoi preferiti",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun ParentalControlContent(
    parentalControlEnabled: Boolean,
    biometricEnabled: Boolean,
    isBiometricAvailable: Boolean,
    isAuthInProgress: Boolean,
    onToggleParental: (Boolean) -> Unit,
    onRequestChangePin: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
) {
    Column {
        SettingRow(
            title = "Controllo genitori",
            description = "Richiedi un PIN per accedere alla ricerca e ai contenuti",
            checked = parentalControlEnabled,
            onCheckedChange = onToggleParental,
        )
        if (parentalControlEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRequestChangePin) {
                Text("Cambia PIN")
            }
            if (isBiometricAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingRow(
                    title = "Sblocco biometrico",
                    description = "Usa l'impronta o il volto per sbloccare",
                    checked = biometricEnabled,
                    onCheckedChange = onToggleBiometric,
                )
            }
        }
    }
}

@Composable
fun LabsContent(
    labsEnabled: Boolean,
    downloadDevUpdates: Boolean,
    highResImages: Boolean,
    privacyBrightnessEnabled: Boolean,
    allowLandscapeRotation: Boolean,
    onToggleLabs: (Boolean) -> Unit,
    onToggleDownloadDevUpdates: (Boolean) -> Unit,
    onToggleHighResImages: (Boolean) -> Unit,
    onTogglePrivacyBrightness: (Boolean) -> Unit,
    onToggleAllowLandscapeRotation: (Boolean) -> Unit,
) {
    Column {
        SettingRow(
            title = "Abilita funzioni sperimentali",
            description = "Mostra opzioni in fase di test, potrebbero non funzionare",
            checked = labsEnabled,
            onCheckedChange = onToggleLabs,
        )
        if (labsEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            SettingRow(
                title = "Scarica aggiornamenti di sviluppo",
                description = "Ricevi le versioni preview (dev) dell'app",
                checked = downloadDevUpdates,
                onCheckedChange = onToggleDownloadDevUpdates,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingRow(
                title = "Immagini ad alta risoluzione",
                description = "Scarica le pagine alla massima qualità (file più grandi)",
                checked = highResImages,
                onCheckedChange = onToggleHighResImages,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingRow(
                title = "Luminosità lettore (privacy)",
                description = "Riduci la luminosità nel lettore per leggere in privato",
                checked = privacyBrightnessEnabled,
                onCheckedChange = onTogglePrivacyBrightness,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingRow(
                title = "Rotazione schermo",
                description = "Permetti la rotazione in orizzontale (sperimentale)",
                checked = allowLandscapeRotation,
                onCheckedChange = onToggleAllowLandscapeRotation,
            )
        }
    }
}
