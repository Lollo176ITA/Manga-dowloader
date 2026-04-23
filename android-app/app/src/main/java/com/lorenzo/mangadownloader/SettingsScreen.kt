package com.lorenzo.mangadownloader

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
    onToggleParentalControl: (Boolean) -> Unit,
    onRequestChangeParentalPin: () -> Unit,
    onToggleParentalBiometric: (Boolean) -> Unit,
    onToggleLabs: (Boolean) -> Unit,
    onSelectAutoReaderSpeed: (AutoReaderSpeed) -> Unit,
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
                    "Disattivato di default. Preferiti e Libreria restano sempre liberi."
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
                AutoReaderSettings(
                    selectedSpeed = settings.autoReaderSpeed,
                    onSelectSpeed = onSelectAutoReaderSpeed,
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

@Composable
private fun DynamicColorRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val description = if (available) {
        "Adatta la palette ai colori del tuo wallpaper (Material You)."
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

@Composable
private fun AutoReaderSettings(
    selectedSpeed: AutoReaderSpeed,
    onSelectSpeed: (AutoReaderSpeed) -> Unit,
) {
    val speeds = AutoReaderSpeed.values()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Reader automatico",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = "Si ferma su ogni pagina per un po', poi scorre a quella successiva. Tiene lo schermo acceso. Puoi sempre scrollare a mano.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selectedSpeed.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = selectedSpeed.pauseLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val currentIndex = speeds.indexOf(selectedSpeed).coerceAtLeast(0)
        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { v ->
                val idx = v.toInt().coerceIn(0, speeds.lastIndex)
                if (speeds[idx] != selectedSpeed) onSelectSpeed(speeds[idx])
            },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = speeds.size - 2,
        )
    }
}

private fun AutoReaderSpeed.displayLabel(): String = when (this) {
    AutoReaderSpeed.OFF -> "Off"
    AutoReaderSpeed.CALM -> "Calmo"
    AutoReaderSpeed.NORMAL -> "Normale"
    AutoReaderSpeed.FAST -> "Veloce"
}

private fun AutoReaderSpeed.pauseLabel(): String = when (this) {
    AutoReaderSpeed.OFF -> "Disattivato"
    else -> "${pauseSeconds}s per pagina"
}
