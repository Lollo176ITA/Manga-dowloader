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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.canopas.lib.showcase.IntroShowcase
import com.canopas.lib.showcase.component.ShowcaseStyle
import com.canopas.lib.showcase.component.rememberIntroShowcaseState

enum class TutorialAnchor {
    SEARCH_TAB,
    FAVORITES_TAB,
    LIBRARY_TAB,
    OVERFLOW,
    SEARCH_RESULT_FIRST,
    DETAIL_FAVORITE,
    LIBRARY_SERIES_FIRST,
    DOWNLOADED_CHAPTER_FIRST,
}

val LocalTutorialAnchor = compositionLocalOf<(TutorialAnchor) -> Modifier> { { Modifier } }

@Composable
fun TutorialOverlay(
    state: MangaUiState,
    onWelcomeStart: () -> Unit,
    onWelcomeSkip: () -> Unit,
    onFallbackCompleted: () -> Unit,
    onAdvancePhase: (from: TutorialPhase, to: TutorialPhase) -> Unit,
    onFinish: (keepSample: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val phase = state.tutorialState.phase
    val showcaseState = rememberIntroShowcaseState()
    val style = TutorialShowcaseStyle()

    val onMainPager = state.selected == null &&
        !state.showSettings &&
        state.readerChapter == null &&
        state.selectedDownloadedSeries == null
    val showShowcase = when (phase) {
        TutorialPhase.FallbackShowcase -> onMainPager
        TutorialPhase.AwaitingResultTap -> onMainPager && state.results.isNotEmpty()
        TutorialPhase.AwaitingFavorite -> state.selected != null
        TutorialPhase.AwaitingLibraryTab -> onMainPager
        TutorialPhase.AwaitingSeriesTap ->
            onMainPager && state.currentTab == AppTab.LIBRARY && state.library.isNotEmpty()
        TutorialPhase.AwaitingChapterTap -> state.selectedDownloadedSeries != null
        else -> false
    }

    IntroShowcase(
        showIntroShowCase = showShowcase,
        dismissOnClickOutside = false,
        onShowCaseCompleted = {
            if (phase == TutorialPhase.FallbackShowcase) {
                onAdvancePhase(TutorialPhase.FallbackShowcase, TutorialPhase.FallbackClosing)
            }
        },
        state = showcaseState,
    ) {
        val fallbackSearch = Modifier.introShowCaseTarget(
            index = 0,
            style = style,
            content = {
                TutorialBubble(
                    title = "Cerca",
                    description = "Qui cerchi i manga sui server supportati. Tocca un risultato per vedere capitoli e dettagli.",
                )
            },
        )
        val fallbackFavorites = Modifier.introShowCaseTarget(
            index = 1,
            style = style,
            content = {
                TutorialBubble(
                    title = "Preferiti",
                    description = "I manga che salvi dalla schermata di dettaglio finiscono qui per ritrovarli al volo.",
                )
            },
        )
        val fallbackLibrary = Modifier.introShowCaseTarget(
            index = 2,
            style = style,
            content = {
                TutorialBubble(
                    title = "Libreria",
                    description = "Tutti i capitoli che hai scaricato. Si leggono offline senza connessione.",
                )
            },
        )
        val fallbackOverflow = Modifier.introShowCaseTarget(
            index = 3,
            style = style,
            content = {
                TutorialBubble(
                    title = "Server e Impostazioni",
                    description = "Da qui cambi sorgente (Mangapill, Hasta Team, Manga World) e apri le Impostazioni.",
                )
            },
        )
        val activeBubble: TutorialBubbleContent? = activeInteractiveBubble(phase)
        val activeModifier = activeBubble?.let { bubble ->
            Modifier.introShowCaseTarget(
                index = 0,
                style = style,
                content = { TutorialBubble(title = bubble.title, description = bubble.description) },
            )
        }

        val isFallback = phase == TutorialPhase.FallbackShowcase
        val anchorFor: (TutorialAnchor) -> Modifier = { anchor ->
            when {
                isFallback -> when (anchor) {
                    TutorialAnchor.SEARCH_TAB -> fallbackSearch
                    TutorialAnchor.FAVORITES_TAB -> fallbackFavorites
                    TutorialAnchor.LIBRARY_TAB -> fallbackLibrary
                    TutorialAnchor.OVERFLOW -> fallbackOverflow
                    else -> Modifier
                }
                activeBubble != null && anchor == activeBubble.anchor -> activeModifier ?: Modifier
                else -> Modifier
            }
        }

        CompositionLocalProvider(LocalTutorialAnchor provides anchorFor) {
            content()
        }
    }

    InteractivePhaseObservers(state = state, onAdvancePhase = onAdvancePhase)

    if (phase == TutorialPhase.Welcome) {
        WelcomeTutorialDialog(
            onSkip = onWelcomeSkip,
            onStart = onWelcomeStart,
        )
    }

    if (phase == TutorialPhase.Preloading) {
        PreloadingTutorialDialog()
    }

    if (phase == TutorialPhase.Closing) {
        ClosingTutorialDialog(
            onKeep = { onFinish(true) },
            onDelete = { onFinish(false) },
        )
    }

    if (phase == TutorialPhase.FallbackClosing) {
        FallbackClosingTutorialDialog(onDismiss = onFallbackCompleted)
    }
}

private data class TutorialBubbleContent(
    val anchor: TutorialAnchor,
    val title: String,
    val description: String,
)

private fun activeInteractiveBubble(phase: TutorialPhase): TutorialBubbleContent? {
    return when (phase) {
        TutorialPhase.AwaitingResultTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.SEARCH_RESULT_FIRST,
            title = "Ecco One Piece",
            description = "Ho cercato 'One Piece' per te. Tocca il primo risultato per vedere capitoli e dettagli.",
        )
        TutorialPhase.AwaitingFavorite -> TutorialBubbleContent(
            anchor = TutorialAnchor.DETAIL_FAVORITE,
            title = "Salvalo nei preferiti",
            description = "Tocca la stella per aggiungerlo. Il capitolo 1 si scarica intanto in background. Quando hai finito, torna indietro per andare in Libreria.",
        )
        TutorialPhase.AwaitingLibraryTab -> TutorialBubbleContent(
            anchor = TutorialAnchor.LIBRARY_TAB,
            title = "Apri la Libreria",
            description = "Qui trovi i manga scaricati, leggibili offline.",
        )
        TutorialPhase.AwaitingSeriesTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.LIBRARY_SERIES_FIRST,
            title = "Ecco One Piece",
            description = "Tocca per vedere i capitoli scaricati.",
        )
        TutorialPhase.AwaitingChapterTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.DOWNLOADED_CHAPTER_FIRST,
            title = "Apri il capitolo",
            description = "Tocca un capitolo per leggerlo nel Reader.",
        )
        else -> null
    }
}

@Composable
private fun InteractivePhaseObservers(
    state: MangaUiState,
    onAdvancePhase: (from: TutorialPhase, to: TutorialPhase) -> Unit,
) {
    val phase = state.tutorialState.phase
    val sample = state.tutorialState.sample

    LaunchedEffect(phase, state.selected) {
        if (phase == TutorialPhase.AwaitingResultTap && state.selected != null) {
            onAdvancePhase(TutorialPhase.AwaitingResultTap, TutorialPhase.AwaitingFavorite)
        }
    }

    LaunchedEffect(phase, state.favoriteMangaKeys, sample) {
        if (phase != TutorialPhase.AwaitingFavorite || sample == null) return@LaunchedEffect
        val key = MangaSourceCatalog.identityKey(sample.sourceId, sample.mangaUrl)
        if (key in state.favoriteMangaKeys) {
            onAdvancePhase(TutorialPhase.AwaitingFavorite, TutorialPhase.AwaitingLibraryTab)
        }
    }

    LaunchedEffect(phase, state.currentTab, state.selected) {
        if (phase == TutorialPhase.AwaitingLibraryTab &&
            state.currentTab == AppTab.LIBRARY &&
            state.selected == null
        ) {
            onAdvancePhase(TutorialPhase.AwaitingLibraryTab, TutorialPhase.AwaitingSeriesTap)
        }
    }

    LaunchedEffect(phase, state.selectedDownloadedSeries) {
        if (phase == TutorialPhase.AwaitingSeriesTap && state.selectedDownloadedSeries != null) {
            onAdvancePhase(TutorialPhase.AwaitingSeriesTap, TutorialPhase.AwaitingChapterTap)
        }
    }

    LaunchedEffect(phase, state.readerChapter) {
        if (phase == TutorialPhase.AwaitingChapterTap && state.readerChapter != null) {
            onAdvancePhase(TutorialPhase.AwaitingChapterTap, TutorialPhase.InReader)
        }
    }

    LaunchedEffect(phase, state.readerChapter) {
        if (phase == TutorialPhase.InReader && state.readerChapter == null) {
            onAdvancePhase(TutorialPhase.InReader, TutorialPhase.Closing)
        }
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
                "Ti accompagno in un giro guidato: cerchiamo One Piece insieme, lo salvi nei preferiti e " +
                    "lo apri nel Reader. Il primo capitolo lo scarico io in background per te.",
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
private fun PreloadingTutorialDialog() {
    AlertDialog(
        onDismissRequest = { },
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            CircularProgressIndicator()
        },
        title = { Text("Preparazione in corso") },
        text = {
            Text(
                "Sto cercando One Piece e avviando il download del primo capitolo. " +
                    "Bastano pochi secondi.",
            )
        },
        confirmButton = { },
    )
}

@Composable
private fun ClosingTutorialDialog(onKeep: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Tutorial finito!") },
        text = {
            Column {
                Text(
                    "Bonus: nel Reader hai a disposizione questi gesti.",
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
                        text = "Vuoi tenere One Piece in libreria, o eliminarlo dato che era solo di prova?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep) { Text("Tieni One Piece") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("Elimina") }
        },
    )
}

@Composable
private fun FallbackClosingTutorialDialog(onDismiss: () -> Unit) {
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
                    "Non sono riuscito a preparare il tutorial interattivo (ricerca/download non disponibili). " +
                        "Per ora ecco i gesti del Reader:",
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
                        text = "Da Impostazioni → Labs puoi rivedere il tutorial in qualsiasi momento.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
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
