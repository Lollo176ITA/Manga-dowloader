package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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

/**
 * Riga impostazione con titolo, interruttore e — quando serve — una descrizione: [description]
 * è opzionale, così le impostazioni che si spiegano da sole restano su una riga sola.
 */
@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    /** Avviso in colore d'errore sotto la descrizione (es. una fonte che non risponde). */
    warning: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Senza descrizione il testo da solo non arriva ai 48dp di touch target.
            .heightIn(min = 48.dp)
            // L'intera riga commuta l'interruttore (non solo il piccolo Switch a destra)
            // e TalkBack la annuncia come un unico elemento "titolo, descrizione, attivato".
            // Lo Switch resta senza handler proprio: un solo nodo interattivo per riga.
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

/**
 * Riga impostazione senza interruttore: apre un'altra schermata ([onClick] valorizzato) oppure
 * mostra solo un'informazione. [description] è opzionale, come in [SettingRow].
 *
 * Stesso bordo sinistro di [SettingRow]: prima ognuna di queste righe si apriva con uno
 * spaziatore da 12dp che le rientrava rispetto a quelle con lo switch, e dentro la stessa
 * card la differenza si leggeva come due tipi di elemento diversi.
 */
@Composable
private fun SettingsActionRow(
    title: String,
    onClick: (() -> Unit)? = null,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Senza descrizione il testo da solo non arriva ai 48dp di touch target.
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
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
            checked = useDynamicColor,
            onCheckedChange = onToggleDynamicColor,
        )
    }
}

/** Densità globale delle card (poster e righe manga in tutta l'app), stile selettore tema. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDensityContent(
    currentDensity: CardDensity,
    onSelectDensity: (CardDensity) -> Unit,
) {
    Column {
        SettingsSubheader("Dimensione card home")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CardDensity.entries.forEachIndexed { index, density ->
                SegmentedButton(
                    selected = currentDensity == density,
                    onClick = { onSelectDensity(density) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CardDensity.entries.size),
                    label = { Text(density.label) },
                )
            }
        }
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

/**
 * Come trattare le pagine doppie. Sta subito sotto alla modalità di lettura perché è da
 * quella che dipende l'ordine delle due metà: nella modalità occidentale si legge prima la
 * sinistra, altrove prima la destra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpreadPageModeContent(
    currentMode: SpreadPageMode,
    onSelectMode: (SpreadPageMode) -> Unit,
) {
    Column {
        SettingsSubheader("Pagine doppie")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SpreadPageMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = currentMode == mode,
                    onClick = { onSelectMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SpreadPageMode.entries.size,
                    ),
                    label = { Text(mode.shortLabel) },
                )
            }
        }
        Text(
            text = when (currentMode) {
                SpreadPageMode.FIT ->
                    "Le facciate affiancate restano in un'unica immagine, rimpicciolita per starci."
                SpreadPageMode.SPLIT ->
                    "Le facciate affiancate diventano due pagine, nell'ordine di lettura giusto."
                SpreadPageMode.ROTATE ->
                    "Le facciate affiancate vengono ruotate: si leggono girando il telefono, " +
                        "che resta bloccato in verticale."
            } + " Puoi cambiarla per la singola serie dal lettore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPageSpacingContent(
    currentDp: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(
        0 to "Zero",
        4 to "Poco",
        DEFAULT_READER_PAGE_SPACING_DP to "Medio",
        16 to "Tanto",
    )
    Column {
        SettingsSubheader("Spazio tra le pagine")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (dp, label) ->
                SegmentedButton(
                    selected = currentDp == dp,
                    onClick = { onSelect(dp) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
fun DoubleTapZoomContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Doppio tap per zoomare",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun KeepScreenOnContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Mantieni schermo acceso",
        checked = enabled,
        onCheckedChange = onToggle,
    )
}

@Composable
fun ShowHomeTabContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingRow(
        title = "Mostra la tab Home",
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
    SettingsActionRow(
        title = "Gestisci memoria",
        description = "Vedi quanto spazio occupa ogni manga ed elimina ciò che non ti serve",
        onClick = onOpenStorageManager,
    )
}

/** Sezione "Informazioni": versione installata + accesso alle Novità (changelog). */
@Composable
fun InfoContent(
    appVersion: String,
    onOpenChangelog: () -> Unit,
) {
    Column {
        SettingsActionRow(
            title = "Versione",
            description = "MangApp $appVersion",
        )
        SettingsDivider()
        SettingsActionRow(
            title = "Novità",
            description = "Scopri cosa è cambiato nelle ultime versioni",
            onClick = onOpenChangelog,
        )
    }
}

/** Riga azione che apre la schermata Backup e ripristino. */
@Composable
fun BackupContent(
    onOpenBackup: () -> Unit,
) {
    SettingsActionRow(
        title = "Backup e ripristino",
        description = "Esporta o importa preferiti e impostazioni in un file",
        onClick = onOpenBackup,
    )
}

/** Riga azione che apre la schermata "Aiutaci a migliorare" (segnalazioni e proposte). */
@Composable
fun ReportProblemContent(
    onOpenReportProblem: () -> Unit,
) {
    SettingsActionRow(
        title = "Aiutaci a migliorare",
        description = "Segnala un bug, proponi una funzionalità o lascia un feedback " +
            "(con foto o vocale)",
        onClick = onOpenReportProblem,
    )
}

/** Riga azione "Rivedi il tutorial": rilancia il tour guidato di benvenuto. */
@Composable
fun RestartTutorialContent(onRestart: () -> Unit) {
    SettingsActionRow(
        title = "Rivedi il tutorial",
        onClick = onRestart,
    )
}

/**
 * Account AniList nella sezione App: collegamento (OAuth nel browser), stato "Connesso come…",
 * toggle del sync automatico e disconnessione. Il matching delle singole serie vive nel
 * dettaglio del manga (vedi `AniListTrackingRow`).
 */
@Composable
fun AniListAccountContent(
    viewerName: String?,
    isConnecting: Boolean,
    syncEnabled: Boolean,
    favoritesSyncEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onToggleFavoritesSync: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Account AniList",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when {
                        isConnecting -> "Collegamento in corso…"
                        viewerName != null -> "Connesso come $viewerName"
                        else -> "Collega il tuo account per segnare su AniList ciò che leggi"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (viewerName == null) {
                TextButton(onClick = onConnect, enabled = !isConnecting) {
                    Text("Connetti")
                }
            } else {
                TextButton(onClick = onDisconnect) {
                    Text("Disconnetti")
                }
            }
        }
        if (viewerName != null) {
            SettingsDivider()
            SettingRow(
                title = "Sincronizza lettura",
                description = "A fine capitolo aggiorna stato e progresso sulla tua lista AniList",
                checked = syncEnabled,
                onCheckedChange = onToggleSync,
            )
            SettingsDivider()
            SettingRow(
                title = "Sincronizza preferiti",
                description = "Unisce i preferiti dell'app e quelli di AniList. " +
                    "Non rimuove mai niente da nessuna delle due parti.",
                checked = favoritesSyncEnabled,
                onCheckedChange = onToggleFavoritesSync,
            )
        }
    }
}

@Composable
fun FavoriteNotificationsContent(
    enabled: Boolean,
    notificationsPermissionGranted: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column {
        SettingRow(
            title = "Notifiche preferiti",
            description = "Ricevi una notifica quando esce un nuovo capitolo dei tuoi preferiti",
            checked = enabled,
            onCheckedChange = onToggle,
        )
        // Permesso revocato a posteriori dalle impostazioni di sistema: senza questo avviso
        // il toggle ON sarebbe un fallimento silenzioso (nessuna notifica arriverà mai).
        if (enabled && !notificationsPermissionGranted) {
            Text(
                text = "Permesso notifiche disattivato: non riceverai avvisi. " +
                    "Riattivalo dalle impostazioni di sistema.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
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
                title = "Luminosità lettore",
                description = "Riduci la luminosità nel lettore",
                checked = privacyBrightnessEnabled,
                onCheckedChange = onTogglePrivacyBrightness,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingRow(
                title = "Rotazione schermo",
                description = "Permetti la rotazione in orizzontale",
                checked = allowLandscapeRotation,
                onCheckedChange = onToggleAllowLandscapeRotation,
            )
        }
    }
}

/**
 * Elenco delle fonti registrate con uno switch ciascuna (sezione impostazioni "Fonti").
 *
 * Una fonte che non risponde resta **accesa** e mostra un avviso: l'app la salta da sola
 * finché è giù ([SourceHealth]) e la riprende quando torna. Spegnerle al posto dell'utente
 * significherebbe lasciargliela spenta anche dopo, senza che nessuno glielo dica.
 */
@Composable
fun SourceTogglesContent(
    disabledSourceIds: Set<String>,
    sourceHealth: Map<String, SourceReachability>,
    onToggle: (String, Boolean) -> Unit,
) {
    // L'ora si legge una volta per composizione: serve solo a dire "da quanto", non è un orologio.
    val warnings = remember(sourceHealth) {
        sourceUnreachableLabels(sourceHealth, System.currentTimeMillis())
    }
    Column {
        MangaSourceCatalog.descriptors.forEachIndexed { index, descriptor ->
            if (index > 0) SettingsDivider()
            SettingRow(
                title = descriptor.displayName,
                description = "Lingua: ${descriptor.language.displayName}",
                warning = warnings[descriptor.id],
                checked = descriptor.id !in disabledSourceIds,
                onCheckedChange = { enabled -> onToggle(descriptor.id, enabled) },
            )
        }
    }
}
