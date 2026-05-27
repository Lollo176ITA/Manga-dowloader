package com.lorenzo.mangadownloader

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isBiometricAvailable: Boolean,
    isParentalAuthInProgress: Boolean,
    padding: PaddingValues,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleAutoDownload: (Boolean) -> Unit,
    onTriggerChange: (Int) -> Unit,
    onBatchChange: (Int) -> Unit,
    onToggleSmartCleanup: (Boolean) -> Unit,
    onSmartCleanupKeepChange: (Int) -> Unit,
    onToggleStreamingReader: (Boolean) -> Unit,
    onSelectReadingMode: (ReadingMode) -> Unit,
    onToggleParentalControl: (Boolean) -> Unit,
    onRequestChangeParentalPin: () -> Unit,
    onToggleParentalBiometric: (Boolean) -> Unit,
    onToggleLabs: (Boolean) -> Unit,
    onToggleDownloadDevUpdates: (Boolean) -> Unit,
    onToggleHighResImages: (Boolean) -> Unit,
    onTogglePrivacyBrightness: (Boolean) -> Unit,
    onToggleAllowLandscapeRotation: (Boolean) -> Unit,
    onOpenStorageManager: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSection(
            title = "Aspetto",
            icon = Icons.Default.Palette,
        ) {
            ThemeModePicker(
                selected = settings.themeMode,
                onSelect = onSelectThemeMode,
            )
            SettingsDivider()
            DynamicColorRow(
                checked = settings.useDynamicColor,
                onCheckedChange = onToggleDynamicColor,
            )
        }

        SettingsSection(
            title = "Download automatico",
            icon = Icons.Default.Download,
        ) {
            SettingsSwitchRow(
                title = "Scarica capitoli successivi automaticamente",
                description = "Quando leggi gli ultimi capitoli scaricati, scarica automaticamente i successivi",
                checked = settings.autoDownloadEnabled,
                onCheckedChange = onToggleAutoDownload,
            )
            SettingsDivider()
            SettingsNumberRow(
                label = "Capitoli rimanenti per attivare il download",
                value = settings.autoDownloadTriggerChapters,
                enabled = settings.autoDownloadEnabled,
                onValueChange = onTriggerChange,
            )
            SettingsNumberRow(
                label = "Capitoli da scaricare ogni volta",
                value = settings.autoDownloadBatchSize,
                enabled = settings.autoDownloadEnabled,
                onValueChange = onBatchChange,
            )
        }

        SettingsSection(
            title = "Libera memoria intelligente",
            icon = Icons.Default.CleaningServices,
        ) {
            SettingsSwitchRow(
                title = "Elimina i capitoli letti più vecchi",
                description = "Quando apri un capitolo, mantiene solo gli ultimi capitoli precedenti che scegli tu",
                checked = settings.smartCleanupEnabled,
                onCheckedChange = onToggleSmartCleanup,
            )
            SettingsDivider()
            SettingsNumberRow(
                label = "Capitoli precedenti da mantenere",
                value = settings.smartCleanupKeepPreviousChapters,
                enabled = settings.smartCleanupEnabled,
                onValueChange = onSmartCleanupKeepChange,
            )
        }

        SettingsSection(
            title = "Memoria",
            icon = Icons.Default.Storage,
        ) {
            SettingsActionRow(
                title = "Gestisci memoria",
                description = "Vedi quanto spazio occupa ogni manga ed elimina quelli che non ti servono più.",
                icon = Icons.Default.PieChart,
                onClick = onOpenStorageManager,
            )
        }

        SettingsSection(
            title = "Modalità di lettura",
            icon = Icons.AutoMirrored.Filled.MenuBook,
        ) {
            ReadingModePicker(
                selected = settings.readingMode,
                onSelect = onSelectReadingMode,
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Apri capitoli in streaming",
                description = "Nel dettaglio manga, il tap su un capitolo apre il reader online invece del download.",
                checked = settings.streamingReaderEnabled,
                onCheckedChange = onToggleStreamingReader,
            )
        }

        SettingsSection(
            title = "Parental control",
            icon = Icons.Default.Lock,
        ) {
            SettingsSwitchRow(
                title = "Proteggi la ricerca con PIN",
                description = if (settings.parentalControlEnabled) {
                    if (settings.parentalPinConfigured) {
                        "PIN configurato. Cerca richiede autenticazione a ogni accesso."
                    } else {
                        "Attivo ma PIN non ancora configurato."
                    }
                } else {
                    "Disattivato di default."
                },
                checked = settings.parentalControlEnabled,
                switchEnabled = !isParentalAuthInProgress,
                onCheckedChange = onToggleParentalControl,
            )
            if (settings.parentalControlEnabled && settings.parentalPinConfigured && isBiometricAvailable) {
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Usa anche la biometria",
                    description = "Puoi usare impronta o volto, con PIN sempre disponibile come fallback.",
                    checked = settings.parentalBiometricEnabled,
                    switchEnabled = !isParentalAuthInProgress,
                    onCheckedChange = onToggleParentalBiometric,
                )
            }
            if (settings.parentalControlEnabled && settings.parentalPinConfigured) {
                SettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isParentalAuthInProgress,
                        shape = MaterialTheme.shapes.large,
                        onClick = onRequestChangeParentalPin,
                    ) {
                        Text("Cambia PIN")
                    }
                }
            }
        }

        SettingsSection(
            title = "Labs",
            icon = Icons.Default.Science,
        ) {
            SettingsSwitchRow(
                title = "Funzionalità in sviluppo",
                description = "Mostra opzioni sperimentali. Possono cambiare o sparire nelle prossime versioni.",
                checked = settings.labsEnabled,
                onCheckedChange = onToggleLabs,
            )
            if (settings.labsEnabled) {
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Scarica versioni dev",
                    description = "Include le preview pubblicate dal branch dev quando controlli gli aggiornamenti.",
                    checked = settings.downloadDevUpdates,
                    onCheckedChange = onToggleDownloadDevUpdates,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Immagini full-res",
                    description = "Scarica le pagine alla risoluzione massima quando la fonte le serve ridimensionate (es. VyManga). Usa più dati e spazio.",
                    checked = settings.highResImages,
                    onCheckedChange = onToggleHighResImages,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Luminosità",
                    description = "Mostra nel reader un controllo sole per ridurre la luminosità",
                    checked = settings.privacyBrightnessEnabled,
                    onCheckedChange = onTogglePrivacyBrightness,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Consenti rotazione orizzontale",
                    description = "Disattivata di default: l'app resta in verticale. Se la attivi puoi ruotare lo schermo, ma l'impaginazione della lettura potrebbe rompersi.",
                    checked = settings.allowLandscapeRotation,
                    onCheckedChange = onToggleAllowLandscapeRotation,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        Triple(ThemeMode.AUTO, Icons.Default.BrightnessAuto, "Auto"),
        Triple(ThemeMode.LIGHT, Icons.Default.LightMode, "Chiaro"),
        Triple(ThemeMode.DARK, Icons.Default.DarkMode, "Scuro"),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tema",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, icon, label) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingModePicker(
    selected: ReadingMode,
    onSelect: (ReadingMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Predefinita",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ReadingMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ReadingMode.entries.size,
                    ),
                    label = { Text(mode.shortLabel) },
                )
            }
        }
        Text(
            text = "Vale per i nuovi manga. Puoi cambiare modalità per la singola serie dal reader: la scelta viene ricordata.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DynamicColorRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val description = if (available) {
        "Usa i colori del wallpaper. Disattivalo per la palette più leggibile dell'app."
    } else {
        "Disponibile da Android 12."
    }
    ListItem(
        headlineContent = {
            Text(
                text = "Colori dinamici",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(
                checked = checked && available,
                enabled = available,
                onCheckedChange = onCheckedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
