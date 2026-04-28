package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.canopas.lib.showcase.IntroShowcase
import com.canopas.lib.showcase.component.ShowcaseStyle
import com.canopas.lib.showcase.component.rememberIntroShowcaseState

enum class TutorialAnchor { SEARCH_TAB, FAVORITES_TAB, LIBRARY_TAB, OVERFLOW }

private enum class TutorialPhase { Welcome, Showcase, Closing, Done }

@Composable
fun TutorialOverlay(
    isActive: Boolean,
    resetKey: Any,
    onCompleted: () -> Unit,
    content: @Composable (anchorFor: (TutorialAnchor) -> Modifier) -> Unit,
) {
    var phase by remember(resetKey) {
        mutableStateOf(if (isActive) TutorialPhase.Welcome else TutorialPhase.Done)
    }
    val showcaseState = rememberIntroShowcaseState()
    val style = TutorialShowcaseStyle()

    IntroShowcase(
        showIntroShowCase = isActive && phase == TutorialPhase.Showcase,
        dismissOnClickOutside = false,
        onShowCaseCompleted = { phase = TutorialPhase.Closing },
        state = showcaseState,
    ) {
        val searchModifier = Modifier.introShowCaseTarget(
            index = 0,
            style = style,
            content = {
                TutorialBubble(
                    title = "Cerca",
                    description = "Qui cerchi i manga sui server supportati. Tocca un risultato per vedere capitoli e dettagli.",
                )
            },
        )
        val favoritesModifier = Modifier.introShowCaseTarget(
            index = 1,
            style = style,
            content = {
                TutorialBubble(
                    title = "Preferiti",
                    description = "I manga che salvi dalla schermata di dettaglio finiscono qui per ritrovarli al volo.",
                )
            },
        )
        val libraryModifier = Modifier.introShowCaseTarget(
            index = 2,
            style = style,
            content = {
                TutorialBubble(
                    title = "Libreria",
                    description = "Tutti i capitoli che hai scaricato. Si leggono offline senza connessione.",
                )
            },
        )
        val overflowModifier = Modifier.introShowCaseTarget(
            index = 3,
            style = style,
            content = {
                TutorialBubble(
                    title = "Server e Impostazioni",
                    description = "Da qui cambi sorgente (Mangapill, Hasta Team, Manga World) e apri le Impostazioni.",
                )
            },
        )
        val anchorFor: (TutorialAnchor) -> Modifier = { anchor ->
            when (anchor) {
                TutorialAnchor.SEARCH_TAB -> searchModifier
                TutorialAnchor.FAVORITES_TAB -> favoritesModifier
                TutorialAnchor.LIBRARY_TAB -> libraryModifier
                TutorialAnchor.OVERFLOW -> overflowModifier
            }
        }
        content(anchorFor)
    }

    if (isActive && phase == TutorialPhase.Welcome) {
        WelcomeTutorialDialog(
            onSkip = {
                phase = TutorialPhase.Done
                onCompleted()
            },
            onStart = { phase = TutorialPhase.Showcase },
        )
    }

    if (isActive && phase == TutorialPhase.Closing) {
        ClosingTutorialDialog(
            onDismiss = {
                phase = TutorialPhase.Done
                onCompleted()
            },
        )
    }
}

@Composable
private fun TutorialShowcaseStyle(): ShowcaseStyle {
    return ShowcaseStyle.Default.copy(
        backgroundColor = MaterialTheme.colorScheme.primary,
        backgroundAlpha = 0.95f,
        targetCircleColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun TutorialBubble(title: String, description: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Tocca l'elemento evidenziato per continuare",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WelcomeTutorialDialog(onSkip: () -> Unit, onStart: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Benvenuto in Manga Downloader") },
        text = {
            Text(
                "Ti faccio fare un giro veloce delle sezioni e di come usare l'app. " +
                    "Bastano una trentina di secondi: ti mostro i tre tab, dove cambiare server " +
                    "e dove trovare le impostazioni.",
            )
        },
        confirmButton = {
            TextButton(onClick = onStart) { Text("Inizia") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Salta") }
        },
    )
}

@Composable
private fun ClosingTutorialDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Quasi fatto!") },
        text = {
            Column {
                Text(
                    "Quando apri un capitolo entri nel Reader. Ecco i gesti che lo fanno volare:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ReaderHintRow(
                    icon = Icons.Default.ZoomIn,
                    text = "Pinch con due dita per zoomare e fare panning sulle pagine.",
                )
                ReaderHintRow(
                    icon = Icons.Default.SwipeVertical,
                    text = "Scroll verticale per sfogliare le pagine in continuo.",
                )
                ReaderHintRow(
                    icon = Icons.Default.Fullscreen,
                    text = "Tocca l'icona schermo intero per nascondere le barre.",
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Da Impostazioni → Labs puoi rivedere questo tutorial in qualsiasi momento.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Inizia ad esplorare") }
        },
    )
}

@Composable
private fun ReaderHintRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
