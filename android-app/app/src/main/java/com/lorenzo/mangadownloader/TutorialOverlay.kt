package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class TutorialAnchor {
    SEARCH_TAB,
    FAVORITES_TAB,
    LIBRARY_TAB,
    OVERFLOW,
    SEARCH_BAR,
    SEARCH_RESULT_FIRST,
    DETAIL_FAVORITE,
    DETAIL_DOWNLOAD,
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
    val activeBubble = remember(phase) { activeInteractiveBubble(phase) }
    val isOverlayActive = activeBubble != null && shouldShowSpotlight(phase, state)

    val targetBoundsState = remember { mutableStateOf<Rect?>(null) }
    val anchorRecorder = remember(activeBubble?.anchor) {
        { anchor: TutorialAnchor ->
            if (anchor == activeBubble?.anchor) {
                Modifier.onGloballyPositioned { coords ->
                    targetBoundsState.value = coords.boundsInRoot()
                }
            } else {
                Modifier
            }
        }
    }
    LaunchedEffect(activeBubble?.anchor) {
        targetBoundsState.value = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTutorialAnchor provides anchorRecorder) {
            content()
        }

        val bubbleToShow = if (isOverlayActive) activeBubble else null
        if (bubbleToShow != null) {
            val bounds = targetBoundsState.value
            if (bounds != null) {
                TutorialSpotlight(
                    targetBounds = bounds,
                    bubble = bubbleToShow,
                    onAdvance = nextPhaseAdvance(phase, onAdvancePhase),
                    onSkip = { onFinish(false) },
                )
            } else {
                TutorialFullscreenBubble(
                    bubble = bubbleToShow,
                    onAdvance = nextPhaseAdvance(phase, onAdvancePhase),
                    onSkip = { onFinish(false) },
                )
            }
        }
    }

    InteractivePhaseObservers(state = state, onAdvancePhase = onAdvancePhase)

    if (phase == TutorialPhase.Welcome) {
        WelcomeTutorialDialog(onSkip = onWelcomeSkip, onStart = onWelcomeStart)
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
    val showAdvanceButton: Boolean,
)

private fun activeInteractiveBubble(phase: TutorialPhase): TutorialBubbleContent? {
    return when (phase) {
        TutorialPhase.AwaitingSearchBar -> TutorialBubbleContent(
            anchor = TutorialAnchor.SEARCH_BAR,
            title = "La barra di ricerca",
            description = "Da qui cerchi i manga sui server supportati. Per il tutorial ho già scritto 'One Piece' per te.",
            showAdvanceButton = true,
        )
        TutorialPhase.AwaitingResultTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.SEARCH_RESULT_FIRST,
            title = "Apri One Piece",
            description = "Tocca la copertina evidenziata per vedere capitoli e dettagli.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingFavorite -> TutorialBubbleContent(
            anchor = TutorialAnchor.DETAIL_FAVORITE,
            title = "Salvalo nei preferiti",
            description = "Tocca la stella per aggiungerlo alla lista dei tuoi preferiti.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingDownload -> TutorialBubbleContent(
            anchor = TutorialAnchor.DETAIL_DOWNLOAD,
            title = "Da qui si scarica",
            description = "Questo pulsante apre il menu di download (tutto, oppure un range di capitoli). Il primo capitolo è già pronto in background.",
            showAdvanceButton = true,
        )
        TutorialPhase.AwaitingFavoritesTab -> TutorialBubbleContent(
            anchor = TutorialAnchor.FAVORITES_TAB,
            title = "Tab Preferiti",
            description = "Qui ritrovi tutti i manga che hai salvato. Tocca per visitare la sezione.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingLibraryTab -> TutorialBubbleContent(
            anchor = TutorialAnchor.LIBRARY_TAB,
            title = "Tab Libreria",
            description = "Qui trovi i manga che hai scaricato, leggibili offline. Tocca per aprirla.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingSeriesTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.LIBRARY_SERIES_FIRST,
            title = "Ecco One Piece scaricato",
            description = "Tocca la card per vedere i capitoli scaricati.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingChapterTap -> TutorialBubbleContent(
            anchor = TutorialAnchor.DOWNLOADED_CHAPTER_FIRST,
            title = "Apri il capitolo",
            description = "Tocca per leggerlo nel Reader. Quando hai finito, torna indietro per concludere il tutorial.",
            showAdvanceButton = false,
        )
        TutorialPhase.AwaitingOverflow -> TutorialBubbleContent(
            anchor = TutorialAnchor.OVERFLOW,
            title = "Server e Impostazioni",
            description = "Da qui cambi sorgente (Mangapill, Hasta Team, Manga World) e apri le Impostazioni.",
            showAdvanceButton = true,
        )
        TutorialPhase.FallbackShowcase -> TutorialBubbleContent(
            anchor = TutorialAnchor.SEARCH_TAB,
            title = "Tutorial semplificato",
            description = "Le funzioni di rete non sono disponibili. Comunque, ecco le sezioni principali: Cerca, Preferiti e Libreria sono i tre tab in basso. Da Impostazioni → Labs puoi rivedere il tutorial.",
            showAdvanceButton = true,
        )
        else -> null
    }
}

private fun nextPhaseAdvance(
    phase: TutorialPhase,
    onAdvancePhase: (TutorialPhase, TutorialPhase) -> Unit,
): (() -> Unit)? {
    val next = when (phase) {
        TutorialPhase.AwaitingSearchBar -> TutorialPhase.AwaitingResultTap
        TutorialPhase.AwaitingDownload -> TutorialPhase.AwaitingFavoritesTab
        TutorialPhase.AwaitingOverflow -> TutorialPhase.Closing
        TutorialPhase.FallbackShowcase -> TutorialPhase.FallbackClosing
        else -> null
    }
    return next?.let { target -> { onAdvancePhase(phase, target) } }
}

private fun shouldShowSpotlight(phase: TutorialPhase, state: MangaUiState): Boolean {
    val onMainPager = state.selected == null &&
        !state.showSettings &&
        state.readerChapter == null &&
        state.selectedDownloadedSeries == null
    return when (phase) {
        TutorialPhase.AwaitingSearchBar ->
            onMainPager && state.currentTab == AppTab.SEARCH
        TutorialPhase.AwaitingResultTap ->
            onMainPager && state.currentTab == AppTab.SEARCH && state.results.isNotEmpty()
        TutorialPhase.AwaitingFavorite -> state.selected != null
        TutorialPhase.AwaitingDownload -> state.selected != null
        TutorialPhase.AwaitingFavoritesTab -> onMainPager
        TutorialPhase.AwaitingLibraryTab -> onMainPager
        TutorialPhase.AwaitingSeriesTap ->
            onMainPager && state.currentTab == AppTab.LIBRARY && state.library.isNotEmpty()
        TutorialPhase.AwaitingChapterTap -> state.selectedDownloadedSeries != null
        TutorialPhase.AwaitingOverflow -> onMainPager
        TutorialPhase.FallbackShowcase -> onMainPager
        else -> false
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
            onAdvancePhase(TutorialPhase.AwaitingFavorite, TutorialPhase.AwaitingDownload)
        }
    }
    LaunchedEffect(phase, state.currentTab, state.selected) {
        if (phase == TutorialPhase.AwaitingFavoritesTab &&
            state.currentTab == AppTab.FAVORITES &&
            state.selected == null
        ) {
            onAdvancePhase(TutorialPhase.AwaitingFavoritesTab, TutorialPhase.AwaitingLibraryTab)
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
            onAdvancePhase(TutorialPhase.InReader, TutorialPhase.AwaitingOverflow)
        }
    }
}

@Composable
private fun TutorialSpotlight(
    targetBounds: Rect,
    bubble: TutorialBubbleContent,
    onAdvance: (() -> Unit)?,
    onSkip: () -> Unit,
) {
    val density = LocalDensity.current
    val scrim = Color.Black.copy(alpha = 0.85f)
    val padPx = with(density) { 6.dp.toPx() }
    val cornerRadius = 12.dp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val parentWidthPx = constraints.maxWidth.toFloat()
        val parentHeightPx = constraints.maxHeight.toFloat()
        val left = (targetBounds.left - padPx).coerceAtLeast(0f)
        val top = (targetBounds.top - padPx).coerceAtLeast(0f)
        val right = (targetBounds.right + padPx).coerceAtMost(parentWidthPx)
        val bottom = (targetBounds.bottom + padPx).coerceAtMost(parentHeightPx)
        val leftDp = with(density) { left.toDp() }
        val topDp = with(density) { top.toDp() }
        val rightDp = with(density) { right.toDp() }
        val bottomDp = with(density) { bottom.toDp() }
        val maxWidthDp = maxWidth
        val maxHeightDp = maxHeight

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topDp)
                .background(scrim)
                .pointerInput(Unit) { detectTapGestures { } },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeightDp - bottomDp)
                .offset(y = bottomDp)
                .background(scrim)
                .pointerInput(Unit) { detectTapGestures { } },
        )
        Box(
            modifier = Modifier
                .width(leftDp)
                .height(bottomDp - topDp)
                .offset(y = topDp)
                .background(scrim)
                .pointerInput(Unit) { detectTapGestures { } },
        )
        Box(
            modifier = Modifier
                .width(maxWidthDp - rightDp)
                .height(bottomDp - topDp)
                .offset(x = rightDp, y = topDp)
                .background(scrim)
                .pointerInput(Unit) { detectTapGestures { } },
        )

        Box(
            modifier = Modifier
                .width(rightDp - leftDp)
                .height(bottomDp - topDp)
                .offset(x = leftDp, y = topDp)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(cornerRadius),
                ),
        )
        if (bubble.showAdvanceButton) {
            Box(
                modifier = Modifier
                    .width(rightDp - leftDp)
                    .height(bottomDp - topDp)
                    .offset(x = leftDp, y = topDp)
                    .pointerInput(Unit) { detectTapGestures { } },
            )
        }

        val bubbleBelow = targetBounds.center.y < parentHeightPx / 2f
        val bubbleAlign = if (bubbleBelow) Alignment.TopStart else Alignment.BottomStart
        val bubbleOffsetY = if (bubbleBelow) {
            bottomDp + 12.dp
        } else {
            -(maxHeightDp - topDp + 12.dp)
        }
        Box(
            modifier = Modifier
                .align(bubbleAlign)
                .offset(y = bubbleOffsetY)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            TutorialBubbleCard(
                bubble = bubble,
                onAdvance = onAdvance,
                onSkip = onSkip,
            )
        }
    }
}

@Composable
private fun TutorialFullscreenBubble(
    bubble: TutorialBubbleContent,
    onAdvance: (() -> Unit)?,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 480.dp),
        ) {
            TutorialBubbleCard(
                bubble = bubble,
                onAdvance = onAdvance,
                onSkip = onSkip,
            )
        }
    }
}

@Composable
private fun TutorialBubbleCard(
    bubble: TutorialBubbleContent,
    onAdvance: (() -> Unit)?,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = bubble.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bubble.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSkip) {
                    Text("Esci dal tutorial")
                }
                if (bubble.showAdvanceButton && onAdvance != null) {
                    FilledTonalButton(onClick = onAdvance) {
                        Text("Avanti")
                    }
                } else {
                    Spacer(modifier = Modifier.size(0.dp))
                }
            }
        }
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
                "Ti accompagno in un giro guidato. Cerchiamo One Piece, lo metti nei preferiti, " +
                    "vediamo come si scarica, visitiamo Preferiti e Libreria, leggiamo un capitolo " +
                    "e finiamo dal menu Server/Impostazioni. Il primo capitolo lo scarico io in background.",
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
                    Spacer(modifier = Modifier.heightIn(min = 0.dp))
                    Spacer(modifier = Modifier.size(8.dp))
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
        title = { Text("Tutorial chiuso") },
        text = {
            Column {
                Text(
                    "Quando vorrai rifarlo con la modalità interattiva (con One Piece), riprovalo da " +
                        "Impostazioni → Labs → Rispiega tutorial.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ReaderHintRow(
                    icon = Icons.Default.ZoomIn,
                    text = "Pinch per zoomare nel Reader.",
                )
                ReaderHintRow(
                    icon = Icons.Default.SwipeVertical,
                    text = "Scroll verticale per sfogliare le pagine.",
                )
                ReaderHintRow(
                    icon = Icons.Default.Fullscreen,
                    text = "Tap fullscreen per nascondere le barre.",
                )
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
