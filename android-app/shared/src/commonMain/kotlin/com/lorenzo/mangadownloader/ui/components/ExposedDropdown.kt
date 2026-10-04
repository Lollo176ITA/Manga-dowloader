package com.lorenzo.mangadownloader.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.runtime.Composable

/**
 * Il menu di un `ExposedDropdownMenuBox`. Esiste perché le due material3 in gioco lo dichiarano in
 * modo diverso (membro dello scope nel binario Compose Multiplatform, estensione da importare in
 * quello Android più recente): ogni piattaforma lo chiama contro la propria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
expect fun ExposedDropdownMenuBoxScope.AppExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
)
