package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Tab Home: il centro dell'app. Saluto + card onboarding (solo al primo avvio) + i blocchi
 * contenuto ([HomeBlock]) nell'ordine/visibilità scelti dall'utente, con una modalità modifica
 * (frecce su/giù e mostra/nascondi). Stateless/hoisted: legge [MangaUiState] e delega tutto ai
 * callback. Il blocco Scopri è escluso del tutto sotto controllo parentale.
 */
@Composable
fun HomeScreen(
    state: MangaUiState,
    padding: PaddingValues,
    onResume: (DownloadedChapter) -> Unit,
    onOpenUpdate: (FavoriteUpdateEvent) -> Unit,
    onOpenAllUpdates: () -> Unit,
    onOpenFavorite: (FavoriteManga) -> Unit,
    onOpenAllFavorites: () -> Unit,
    onPickDiscover: (AniListManga) -> Unit,
    onShowDiscoverInfo: (AniListManga) -> Unit,
    onDismissDiscoverInfo: () -> Unit,
    onLoadDiscover: () -> Unit,
    onSearchFirst: () -> Unit,
    onStartTutorial: () -> Unit,
    onDismissTutorial: () -> Unit,
    onMoveBlock: (HomeBlock, Boolean) -> Unit,
    onSetBlockHidden: (HomeBlock, Boolean) -> Unit,
) {
    var editMode by rememberSaveable { mutableStateOf(false) }
    val settings = state.settings

    val blocks = remember(settings.homeBlockOrder, settings.parentalControlEnabled) {
        reconcileHomeBlocks(settings.homeBlockOrder)
            .filter { it != HomeBlock.DISCOVER || !settings.parentalControlEnabled }
    }
    val hidden = settings.hiddenHomeBlocks
    val continueItem = remember(state.library) {
        computeContinueReading(state.library, limit = 1).firstOrNull()
    }
    val recentUpdates = remember(state.favoriteUpdates) {
        state.favoriteUpdates.sortedByDescending { it.timestampMillis }.take(5)
    }
    val recentFavorites = remember(state.favorites) { state.favorites.take(12) }
    val discovery = state.discovery
    // Il blocco Scopri "ha qualcosa da mostrare" se ci sono risultati, sta caricando, oppure c'è
    // un errore (in errore mostriamo un retry invece di far sparire il blocco).
    val discoverHasContent = discovery.trending.isNotEmpty() ||
        discovery.topRated.isNotEmpty() ||
        discovery.newest.isNotEmpty() ||
        discovery.isLoadingSections ||
        discovery.sectionsError != null
    // L'utente ha già dei contenuti? Considera anche la libreria scaricata: chi ha download non
    // deve vedere l'empty state "Inizia la tua collezione".
    val hasAnyContent = continueItem != null ||
        state.favorites.isNotEmpty() ||
        state.favoriteUpdates.isNotEmpty() ||
        state.library.isNotEmpty()

    fun isBlockEmpty(block: HomeBlock): Boolean = when (block) {
        HomeBlock.RESUME -> continueItem == null
        HomeBlock.FAVORITE_UPDATES -> state.favoriteUpdates.isEmpty()
        HomeBlock.RECENT_FAVORITES -> state.favorites.isEmpty()
        HomeBlock.DISCOVER -> !discoverHasContent
    }

    // Carica Scopri solo se il blocco è presente E non nascosto: evita fetch AniList sprecati
    // quando l'utente ha nascosto il blocco.
    if (HomeBlock.DISCOVER in blocks && HomeBlock.DISCOVER !in hidden) {
        LaunchedEffect(Unit) { onLoadDiscover() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "greeting") {
            HomeGreeting(editMode = editMode, onToggleEdit = { editMode = !editMode })
        }

        if (state.tutorialState.phase == TutorialPhase.Welcome) {
            item(key = "onboarding") {
                HomeOnboardingCard(
                    onStart = onStartTutorial,
                    onDismiss = onDismissTutorial,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (!hasAnyContent && !editMode) {
            item(key = "empty") {
                HomeEmptyStateCard(
                    onSearchFirst = onSearchFirst,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        blocks.forEachIndexed { index, block ->
            val isHidden = block in hidden
            val showsPlaceholder = isHidden || isBlockEmpty(block)
            // Fuori dalla modalità modifica i blocchi nascosti o vuoti non occupano spazio.
            if (!editMode && showsPlaceholder) return@forEachIndexed

            if (editMode) {
                item(key = "ctrl-${block.name}") {
                    HomeBlockControls(
                        block = block,
                        index = index,
                        lastIndex = blocks.lastIndex,
                        hidden = isHidden,
                        onMove = onMoveBlock,
                        onSetHidden = onSetBlockHidden,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (showsPlaceholder) {
                // Solo in modalità modifica: segnaposto per riordinare/riattivare il blocco.
                item(key = "ph-${block.name}") {
                    HomeBlockPlaceholder(
                        block = block,
                        hidden = isHidden,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                return@forEachIndexed
            }

            when (block) {
                HomeBlock.RESUME -> item(key = "b-resume") {
                    HomeResumeCard(
                        item = continueItem!!,
                        onResume = onResume,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                HomeBlock.FAVORITE_UPDATES -> item(key = "b-updates") {
                    HomeSection(
                        title = "Novità dai preferiti",
                        trailingActionLabel = "Vedi tutte",
                        onTrailingAction = onOpenAllUpdates,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recentUpdates.forEach { event ->
                                FavoriteUpdateRow(event = event, onClick = { onOpenUpdate(event) })
                            }
                        }
                    }
                }

                HomeBlock.RECENT_FAVORITES -> item(key = "b-favorites") {
                    HomeSection(
                        title = "Preferiti recenti",
                        trailingActionLabel = "Vedi tutti",
                        onTrailingAction = onOpenAllFavorites,
                    ) {
                        HomeCarousel(recentFavorites) { favorite ->
                            Box(modifier = Modifier.width(120.dp)) {
                                FavoriteCard(favorite = favorite, onClick = { onOpenFavorite(favorite) })
                            }
                        }
                    }
                }

                HomeBlock.DISCOVER -> {
                    item(key = "b-discover-header") { HomeSectionTitle(title = "Scopri") }
                    val hasSections = discovery.trending.isNotEmpty() ||
                        discovery.topRated.isNotEmpty() ||
                        discovery.newest.isNotEmpty()
                    val sectionsError = discovery.sectionsError
                    when {
                        hasSections -> {
                            discoverySection("Tendenze", discovery.trending, onPickDiscover, onShowDiscoverInfo)
                            discoverySection("Più votati", discovery.topRated, onPickDiscover, onShowDiscoverInfo)
                            discoverySection("Novità", discovery.newest, onPickDiscover, onShowDiscoverInfo)
                        }
                        discovery.isLoadingSections -> item(key = "b-discover-loading") { HomeDiscoverLoading() }
                        sectionsError != null -> item(key = "b-discover-error") {
                            HomeDiscoverError(message = sectionsError, onRetry = onLoadDiscover)
                        }
                    }
                }
            }
        }
    }

    discovery.info?.let { manga ->
        AniListInfoDialog(manga = manga, onDismiss = onDismissDiscoverInfo)
    }
}

@Composable
private fun HomeGreeting(editMode: Boolean, onToggleEdit: () -> Unit) {
    // Ricalcolato a ogni ricomposizione (niente remember) così il saluto non resta stantio oltre
    // i confini orari mentre l'app è in foreground.
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = homeGreeting(hour),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = onToggleEdit) {
            Icon(
                imageVector = if (editMode) Icons.Filled.Done else Icons.Filled.Edit,
                contentDescription = if (editMode) "Fine modifica Home" else "Personalizza la Home",
            )
        }
    }
}

@Composable
private fun HomeBlockControls(
    block: HomeBlock,
    index: Int,
    lastIndex: Int,
    hidden: Boolean,
    onMove: (HomeBlock, Boolean) -> Unit,
    onSetHidden: (HomeBlock, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = block.displayName(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(block, true) }, enabled = index > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Sposta su: ${block.displayName()}")
        }
        IconButton(onClick = { onMove(block, false) }, enabled = index < lastIndex) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Sposta giù: ${block.displayName()}")
        }
        IconButton(onClick = { onSetHidden(block, !hidden) }) {
            Icon(
                imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (hidden) "Mostra: ${block.displayName()}" else "Nascondi: ${block.displayName()}",
            )
        }
    }
}

@Composable
private fun HomeBlockPlaceholder(block: HomeBlock, hidden: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = appCardColors(),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = if (hidden) "${block.displayName()} · nascosto" else "${block.displayName()} · vuoto",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun HomeEmptyStateCard(onSearchFirst: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = appCardColors(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Inizia la tua collezione",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Cerca il tuo primo manga da leggere e scaricare.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSearchFirst) { Text("Cerca il primo manga") }
        }
    }
}

@Composable
private fun HomeDiscoverLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HomeDiscoverError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("Riprova") }
    }
}

private fun HomeBlock.displayName(): String = when (this) {
    HomeBlock.RESUME -> "Continua a leggere"
    HomeBlock.FAVORITE_UPDATES -> "Novità dai preferiti"
    HomeBlock.RECENT_FAVORITES -> "Preferiti recenti"
    HomeBlock.DISCOVER -> "Scopri"
}
