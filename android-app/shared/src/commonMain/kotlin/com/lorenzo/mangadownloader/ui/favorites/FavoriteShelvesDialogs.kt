package com.lorenzo.mangadownloader.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lorenzo.mangadownloader.domain.series.FavoriteShelf
import com.lorenzo.mangadownloader.domain.series.MAX_SHELF_NAME_LENGTH
import com.lorenzo.mangadownloader.ui.components.ConfirmationDialog

/**
 * Nome di uno scaffale, nuovo o da rinominare. [onConfirm] restituisce `false` se il nome non
 * va bene (vuoto o già usato): il dialog resta aperto e lo dice.
 */
@Composable
internal fun ShelfNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var rejected by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(MAX_SHELF_NAME_LENGTH)
                    rejected = false
                },
                singleLine = true,
                label = { Text("Nome") },
                isError = rejected,
                supportingText = if (rejected) {
                    { Text("Scegli un nome non vuoto e non già usato") }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (!onConfirm(name)) rejected = true },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}

/** Sceglie gli scaffali di un preferito; da qui si può anche crearne uno al volo. */
@Composable
internal fun ShelfPickerDialog(
    favoriteTitle: String,
    shelves: List<FavoriteShelf>,
    initiallySelected: Set<String>,
    onCreateShelf: (String) -> String?,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scaffali") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = favoriteTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                shelves.forEach { shelf ->
                    val checked = shelf.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - shelf.id else selected + shelf.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(shelf.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                }
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nuovo scaffale")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
    if (creating) {
        ShelfNameDialog(
            title = "Nuovo scaffale",
            initialName = "",
            confirmLabel = "Crea",
            onConfirm = { name ->
                val id = onCreateShelf(name) ?: return@ShelfNameDialog false
                // Chi crea uno scaffale da qui ci vuole mettere proprio questo preferito.
                selected = selected + id
                creating = false
                true
            },
            onDismiss = { creating = false },
        )
    }
}

/** Elenco degli scaffali con rinomina ed eliminazione. */
@Composable
internal fun ShelvesManageDialog(
    shelves: List<FavoriteShelf>,
    countsByShelf: Map<String, Int>,
    onCreateShelf: (String) -> String?,
    onRenameShelf: (String, String) -> Boolean,
    onDeleteShelf: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FavoriteShelf?>(null) }
    var deleting by remember { mutableStateOf<FavoriteShelf?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scaffali") },
        shape = MaterialTheme.shapes.extraLarge,
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (shelves.isEmpty()) {
                    Text(
                        text = "Gli scaffali raccolgono i preferiti come vuoi tu. " +
                            "Un preferito può stare su più scaffali.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shelves.forEach { shelf ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shelf.name, style = MaterialTheme.typography.bodyLarge)
                            val count = countsByShelf[shelf.id] ?: 0
                            Text(
                                text = if (count == 1) "1 preferito" else "$count preferiti",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { renaming = shelf }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rinomina ${shelf.name}")
                        }
                        IconButton(onClick = { deleting = shelf }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Elimina ${shelf.name}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Nuovo scaffale")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        },
    )
    if (creating) {
        ShelfNameDialog(
            title = "Nuovo scaffale",
            initialName = "",
            confirmLabel = "Crea",
            onConfirm = { name ->
                (onCreateShelf(name) != null).also { if (it) creating = false }
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { shelf ->
        ShelfNameDialog(
            title = "Rinomina scaffale",
            initialName = shelf.name,
            confirmLabel = "Rinomina",
            onConfirm = { name ->
                onRenameShelf(shelf.id, name).also { if (it) renaming = null }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { shelf ->
        ConfirmationDialog(
            title = "Elimina scaffale",
            text = "Eliminare \"${shelf.name}\"? I preferiti restano, perdono solo questa etichetta.",
            confirmLabel = "Elimina",
            onDismiss = { deleting = null },
            onConfirm = {
                onDeleteShelf(shelf.id)
                deleting = null
            },
        )
    }
}
