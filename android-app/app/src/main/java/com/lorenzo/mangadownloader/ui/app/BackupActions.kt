package com.lorenzo.mangadownloader.ui.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.data.backup.BackupRestoreMode
import com.lorenzo.mangadownloader.ui.components.ConfirmationDialog

internal class BackupActions(
    val onExport: () -> Unit,
    val onImportMerge: () -> Unit,
    val onImportReplace: () -> Unit,
)

/**
 * Esporta e importa il backup con lo Storage Access Framework. La sostituzione passa da una
 * conferma esplicita, mostrata da qui.
 */
@Composable
internal fun rememberBackupActions(viewModel: MangaViewModel): BackupActions {
    // rememberSaveable: la scelta MERGE/REPLACE deve sopravvivere a una morte del processo
    // mentre il selettore di file SAF è in primo piano (l'enum è Serializable).
    var importMode by rememberSaveable { mutableStateOf(BackupRestoreMode.MERGE) }
    var showReplaceConfirm by rememberSaveable { mutableStateOf(false) }
    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it, importMode) } }

    if (showReplaceConfirm) {
        ConfirmationDialog(
            title = "Sostituisci dati",
            text = "I preferiti e le impostazioni attuali verranno rimpiazzati con quelli del backup. Continuare?",
            confirmLabel = "Sostituisci",
            onDismiss = { showReplaceConfirm = false },
            onConfirm = {
                showReplaceConfirm = false
                importMode = BackupRestoreMode.REPLACE
                importLauncher.launch(BACKUP_MIME_TYPES)
            },
        )
    }

    return BackupActions(
        onExport = { createLauncher.launch("manga-downloader-backup.json") },
        onImportMerge = {
            importMode = BackupRestoreMode.MERGE
            importLauncher.launch(BACKUP_MIME_TYPES)
        },
        onImportReplace = { showReplaceConfirm = true },
    )
}

private val BACKUP_MIME_TYPES = arrayOf("application/json", "*/*")
