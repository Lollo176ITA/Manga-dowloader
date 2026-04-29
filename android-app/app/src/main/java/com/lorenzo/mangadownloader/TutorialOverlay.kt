package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.luminos.BackPressBehavior
import io.luminos.CoachmarkConfig
import io.luminos.CoachmarkHost
import io.luminos.CoachmarkTarget
import io.luminos.ConnectorEndStyle
import io.luminos.ConnectorStyle
import io.luminos.CutoutShape
import io.luminos.HighlightAnimation
import io.luminos.ScrimTapBehavior
import io.luminos.TargetTapBehavior
import io.luminos.TooltipPosition
import io.luminos.coachmarkTarget
import io.luminos.rememberCoachmarkController
import kotlinx.coroutines.delay

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
    READER_FULLSCREEN,
}

val LocalTutorialAnchor = compositionLocalOf<(TutorialAnchor) -> Modifier> { { Modifier } }

@Composable
fun TutorialOverlay(
    state: MangaUiState,
    onWelcomeStart: () -> Unit,
    onWelcomeSkip: () -> Unit,
    onFallbackCompleted: () -> Unit,
    onAdvancePhase: (from: TutorialPhase, to: TutorialPhase) -> Unit,
    onTargetTap: (TutorialAnchor) -> Unit,
    onFinish: (keepSample: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val phase = state.tutorialState.phase
    val activeTarget = remember(phase) { activeInteractiveTarget(phase) }
    val isCoachmarkActive = activeTarget != null && shouldShowSpotlight(phase, state)
    val controller = rememberCoachmarkController()

    var handledTargetActionKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(phase) {
        handledTargetActionKey = null
    }

    fun handleTargetAction(targetId: String) {
        val target = activeTarget ?: return
        if (!target.handlesTargetTap || target.anchor.coachmarkId != targetId) return

        val actionKey = "${phase.name}:$targetId"
        if (handledTargetActionKey == actionKey) return
        handledTargetActionKey = actionKey
        onTargetTap(target.anchor)
    }

    LaunchedEffect(phase, isCoachmarkActive) {
        if (!isCoachmarkActive) {
            controller.dismiss()
            return@LaunchedEffect
        }
        val target = requireNotNull(activeTarget)

        // Give Compose one frame to publish the target bounds registered by Modifier.coachmarkTarget.
        delay(120)
        controller.show(target.toCoachmarkTarget())
    }

    LaunchedEffect(phase, state.readerChapter?.relativePath) {
        if (phase != TutorialPhase.InReader || state.readerChapter == null) return@LaunchedEffect
        delay(4500)
        onTargetTap(TutorialAnchor.READER_FULLSCREEN)
    }

    val anchorRecorder: (TutorialAnchor) -> Modifier = remember(controller) {
        { anchor -> Modifier.coachmarkTarget(controller, anchor.coachmarkId) }
    }

    CoachmarkHost(
        controller = controller,
        config = CoachmarkConfig(
            scrimTapBehavior = ScrimTapBehavior.NONE,
            backPressBehavior = BackPressBehavior.DISMISS,
            showProgressIndicator = false,
            showTooltipCard = true,
            showSkipButton = false,
            highlightAnimation = HighlightAnimation.PULSE,
            tooltipCornerRadius = 20.dp,
            ctaMinHeight = 44.dp,
        ),
        onStepCompleted = { _, targetId ->
            handleTargetAction(targetId)
            val next = activeTarget?.advanceOnCompleted
            if (next != null) {
                onAdvancePhase(phase, next)
            }
        },
        onTargetTap = { targetId ->
            handleTargetAction(targetId)
        },
    ) {
        CompositionLocalProvider(LocalTutorialAnchor provides anchorRecorder) {
            content()
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

private data class TutorialTargetContent(
    val anchor: TutorialAnchor,
    val title: String,
    val description: String,
    val ctaText: String,
    val shape: CutoutShape,
    val targetTapBehavior: TargetTapBehavior,
    val handlesTargetTap: Boolean,
    val advanceOnCompleted: TutorialPhase? = null,
)

private fun activeInteractiveTarget(phase: TutorialPhase): TutorialTargetContent? {
    return when (phase) {
        TutorialPhase.AwaitingSearchBar -> TutorialTargetContent(
            anchor = TutorialAnchor.SEARCH_BAR,
            title = "La ricerca",
            description = "Qui cerchi i manga sui server supportati. Per il tutorial ho gia preparato One Piece.",
            ctaText = "Continua",
            shape = CutoutShape.RoundedRect(cornerRadius = 16.dp, padding = 8.dp),
            targetTapBehavior = TargetTapBehavior.PASS_THROUGH,
            handlesTargetTap = false,
            advanceOnCompleted = TutorialPhase.AwaitingResultTap,
        )
        TutorialPhase.AwaitingResultTap -> TutorialTargetContent(
            anchor = TutorialAnchor.SEARCH_RESULT_FIRST,
            title = "Apri One Piece",
            description = "Tocca la copertina evidenziata per vedere dettagli e capitoli.",
            ctaText = "Apri",
            shape = CutoutShape.RoundedRect(cornerRadius = 16.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingFavorite -> TutorialTargetContent(
            anchor = TutorialAnchor.DETAIL_FAVORITE,
            title = "Salvalo nei preferiti",
            description = "Aggiungilo alla tua lista, cosi lo ritrovi subito dopo.",
            ctaText = "Aggiungi",
            shape = CutoutShape.Circle(radiusPadding = 10.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingDownload -> TutorialTargetContent(
            anchor = TutorialAnchor.DETAIL_DOWNLOAD,
            title = "Download",
            description = "Da questo pulsante scegli se scaricare tutto o solo un range di capitoli. Il primo capitolo demo e gia in preparazione.",
            ctaText = "Ho capito",
            shape = CutoutShape.Circle(radiusPadding = 12.dp),
            targetTapBehavior = TargetTapBehavior.PASS_THROUGH,
            handlesTargetTap = false,
            advanceOnCompleted = TutorialPhase.AwaitingFavoritesTab,
        )
        TutorialPhase.AwaitingFavoritesTab -> TutorialTargetContent(
            anchor = TutorialAnchor.FAVORITES_TAB,
            title = "Preferiti",
            description = "Qui trovi i manga salvati. Tocca il tab per aprire la sezione.",
            ctaText = "Apri Preferiti",
            shape = CutoutShape.RoundedRect(cornerRadius = 18.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingLibraryTab -> TutorialTargetContent(
            anchor = TutorialAnchor.LIBRARY_TAB,
            title = "Libreria",
            description = "Qui trovi i manga scaricati e leggibili offline.",
            ctaText = "Apri Libreria",
            shape = CutoutShape.RoundedRect(cornerRadius = 18.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingSeriesTap -> TutorialTargetContent(
            anchor = TutorialAnchor.LIBRARY_SERIES_FIRST,
            title = "Manga scaricato",
            description = "Apri la scheda per vedere i capitoli disponibili offline.",
            ctaText = "Apri",
            shape = CutoutShape.RoundedRect(cornerRadius = 16.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingChapterTap -> TutorialTargetContent(
            anchor = TutorialAnchor.DOWNLOADED_CHAPTER_FIRST,
            title = "Apri il capitolo",
            description = "Tocca il capitolo per entrare nel Reader. Dopo una breve anteprima torno indietro io.",
            ctaText = "Leggi",
            shape = CutoutShape.RoundedRect(cornerRadius = 12.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.InReader -> TutorialTargetContent(
            anchor = TutorialAnchor.READER_FULLSCREEN,
            title = "Reader",
            description = "Qui leggi le pagine offline. Puoi scorrere in verticale, fare pinch per zoomare e usare lo schermo intero.",
            ctaText = "Torna al tour",
            shape = CutoutShape.Circle(radiusPadding = 10.dp),
            targetTapBehavior = TargetTapBehavior.BOTH,
            handlesTargetTap = true,
        )
        TutorialPhase.AwaitingOverflow -> TutorialTargetContent(
            anchor = TutorialAnchor.OVERFLOW,
            title = "Menu e server",
            description = "Da qui puoi cambiare server di ricerca e aprire le impostazioni. Il tutorial termina qui.",
            ctaText = "Finisci",
            shape = CutoutShape.Circle(radiusPadding = 10.dp),
            targetTapBehavior = TargetTapBehavior.PASS_THROUGH,
            handlesTargetTap = false,
            advanceOnCompleted = TutorialPhase.Closing,
        )
        TutorialPhase.FallbackShowcase -> TutorialTargetContent(
            anchor = TutorialAnchor.SEARCH_TAB,
            title = "Tour rapido",
            description = "La rete non e disponibile. Le sezioni principali sono Cerca, Preferiti e Libreria; puoi rivedere il tutorial da Impostazioni, Labs.",
            ctaText = "Chiudi",
            shape = CutoutShape.RoundedRect(cornerRadius = 18.dp, padding = 6.dp),
            targetTapBehavior = TargetTapBehavior.PASS_THROUGH,
            handlesTargetTap = false,
            advanceOnCompleted = TutorialPhase.FallbackClosing,
        )
        else -> null
    }
}

private fun TutorialTargetContent.toCoachmarkTarget(): CoachmarkTarget {
    return CoachmarkTarget(
        id = anchor.coachmarkId,
        title = title,
        description = description,
        shape = shape,
        tooltipPosition = TooltipPosition.AUTO,
        connectorStyle = ConnectorStyle.AUTO,
        connectorEndStyle = ConnectorEndStyle.DOT,
        ctaText = ctaText,
        showProgressIndicator = false,
        highlightAnimation = HighlightAnimation.PULSE,
        targetTapBehavior = targetTapBehavior,
    )
}

private val TutorialAnchor.coachmarkId: String
    get() = name.lowercase()

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
        TutorialPhase.AwaitingDownload -> state.selected?.chapters?.isNotEmpty() == true
        TutorialPhase.AwaitingFavoritesTab -> onMainPager
        TutorialPhase.AwaitingLibraryTab -> onMainPager
        TutorialPhase.AwaitingSeriesTap ->
            onMainPager && state.currentTab == AppTab.LIBRARY && state.library.isNotEmpty()
        TutorialPhase.AwaitingChapterTap -> state.selectedDownloadedSeries != null
        TutorialPhase.InReader -> state.readerChapter != null
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
                    "vediamo come si scarica, visitiamo Preferiti e Libreria, apriamo il Reader " +
                    "e finiamo dal cambio server. Il primo capitolo lo scarico io in background.",
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
                    "Quando vorrai rifarlo con la modalita interattiva, riprovalo da " +
                        "Impostazioni, Labs, Rispiega tutorial.",
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
