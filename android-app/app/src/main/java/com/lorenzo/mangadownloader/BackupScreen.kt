package com.lorenzo.mangadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Schermata "Backup": esporta/importa preferiti e impostazioni in un file JSON via Storage Access
 * Framework. La conferma per la sostituzione (REPLACE) è gestita dal chiamante (MainActivity).
 */
@Composable
fun BackupScreen(
    padding: PaddingValues,
    onExport: () -> Unit,
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Il backup include preferiti, descrizioni, ricerche recenti e impostazioni. " +
                "Non include i progressi di lettura né i capitoli scaricati: restano legati a questo dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BackupActionCard(
            icon = Icons.Default.Backup,
            title = "Esporta backup",
            description = "Salva un file .json con i tuoi dati",
            onClick = onExport,
        )
        BackupActionCard(
            icon = Icons.Default.Download,
            title = "Importa e unisci",
            description = "Aggiunge i dati del file mantenendo i tuoi attuali",
            onClick = onImportMerge,
        )
        BackupActionCard(
            icon = Icons.Default.Restore,
            title = "Sostituisci con un backup",
            description = "Rimpiazza preferiti e impostazioni con quelli del file",
            tint = MaterialTheme.colorScheme.error,
            onClick = onImportReplace,
        )
    }
}

@Composable
private fun BackupActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
