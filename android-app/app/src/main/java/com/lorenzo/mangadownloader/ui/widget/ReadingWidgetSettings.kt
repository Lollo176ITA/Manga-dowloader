package com.lorenzo.mangadownloader.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lorenzo.mangadownloader.ui.settings.SettingsActionRow

/**
 * Widget "Continua a leggere": chi non sa che esiste non lo va a cercare tra i widget del
 * telefono, quindi si offre da qui. Lo stato ("già nella Home") si rilegge a ogni ritorno
 * sull'app, perché l'aggiunta avviene nel dialog del launcher.
 */
@Composable
fun ReadingWidgetSettingsContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val placed = remember(refreshTick) { ReadingWidgetPinning.isPlaced(context) }
    val canPin = remember { ReadingWidgetPinning.canRequestPin(context) }
    val description = when {
        placed -> "È già nella schermata Home. Riprendi l'ultima lettura con un tocco."
        canPin -> "Una striscia 4×1 nella schermata Home con l'ultima lettura: un tocco la riapre."
        else -> "Tieni premuto su uno spazio vuoto della schermata Home, scegli Widget e cerca MangApp."
    }
    SettingsActionRow(
        title = if (canPin && !placed) "Aggiungi il widget alla Home" else "Widget \"Continua a leggere\"",
        description = description,
        onClick = if (canPin && !placed) {
            { ReadingWidgetPinning.requestPin(context) }
        } else {
            null
        },
    )
}
