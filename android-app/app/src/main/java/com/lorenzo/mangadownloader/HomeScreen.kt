package com.lorenzo.mangadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Tab Home: il centro dell'app. Il saluto e l'azione di personalizzazione vivono nella top bar
 * ([AppTopBar]); qui restano la card onboarding (solo al primo avvio) e i blocchi contenuto
 * ([HomeBlock]) nell'ordine/visibilità scelti dall'utente. La modalità modifica ([editMode],
 * comandata dalla top bar) sostituisce i contenuti con una lista di card riordinabili (frecce
 * su/giù e mostra/nascondi), come una schermata dedicata. Stateless/hoisted: legge
 * [MangaUiState] e delega tutto ai callback. Il blocco Scopri è escluso del tutto sotto
 * controllo parentale.
 */
@Composable
fun HomeScreen(
    state: MangaUiState,
    editMode: Boolean,
    padding: PaddingValues,
    onResume: (DownloadedChapter) -> Unit,
    onOpenUpdate: (FavoriteUpdateEvent) -> Unit,
    onOpenAllUpdates: () -> Unit,
    onOpenFavorite: (FavoriteManga) -> Unit,
    onOpenAllFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onPickDiscover: (AniListManga) -> Unit,
    onShowDiscoverInfo: (AniListManga) -> Unit,
    onDismissDiscoverInfo: () -> Unit,
    onLoadDiscover: () -> Unit,
    onLoadRecommendations: () -> Unit,
    onOpenGenre: (DiscoverGenre) -> Unit,
    onSearchFirst: () -> Unit,
    onStartTutorial: () -> Unit,
    onDismissTutorial: () -> Unit,
    onMoveBlock: (HomeBlock, Boolean) -> Unit,
    onSetBlockHidden: (HomeBlock, Boolean) -> Unit,
    onSetBlockSize: (HomeBlock, HomeBlockSize) -> Unit,
) {
    val settings = state.settings
    // Taglia per blocco (S/M/L, default M): decisa dalla modalità modifica.
    fun sizeOf(block: HomeBlock): HomeBlockSize =
        settings.homeBlockSizes[block] ?: HomeBlockSize.MEDIUM

    // Scopri e Consigliati sono vetrine AniList: entrambe escluse sotto controllo parentale.
    val blocks = remember(settings.homeBlockOrder, settings.parentalControlEnabled) {
        reconcileHomeBlocks(settings.homeBlockOrder)
            .filter {
                (it != HomeBlock.DISCOVER && it != HomeBlock.RECOMMENDED) ||
                    !settings.parentalControlEnabled
            }
    }
    val hidden = settings.hiddenHomeBlocks
    val continueItem = remember(state.library) {
        computeContinueReading(state.library, limit = 1).firstOrNull()
    }
    val recentUpdates = remember(state.favoriteUpdates) {
        state.favoriteUpdates.sortedByDescending { it.timestampMillis }.take(5)
    }
    val recentFavorites = remember(state.favorites) { state.favorites.take(12) }
    val stats = remember(state.library, state.favorites, state.readingMemory) {
        computeHomeStats(state.library, state.favorites.size, state.readingMemory)
    }
    val streak = remember(state.readingDiary) {
        currentReadingStreak(state.readingDiary, LocalDate.now())
    }
    val lastWeek = remember(state.readingDiary) {
        lastDiaryDays(state.readingDiary, days = 7, today = LocalDate.now())
    }
    val readingHistory = remember(state.library, state.readingMemory) {
        computeReadingHistory(state.readingMemory, state.library, limit = 10)
    }
    val seriesToFinish = remember(state.library) {
        computeSeriesToFinish(state.library)
    }
    val discovery = state.discovery
    // Il blocco Scopri "ha qualcosa da mostrare" se ci sono risultati, sta caricando, oppure c'è
    // un errore (in errore mostriamo un retry invece di far sparire il blocco).
    val discoverHasContent = discovery.trending.isNotEmpty() ||
        discovery.topRated.isNotEmpty() ||
        discovery.newest.isNotEmpty() ||
        discovery.isLoadingSections ||
        discovery.sectionsError != null
    // Stessa logica per i Consigliati; senza semi (né preferiti né letture) il blocco sparisce.
    val recommendations = state.recommendations
    val recommendedHasContent = recommendations.items.isNotEmpty() ||
        recommendations.isLoading ||
        recommendations.error != null
    // L'utente ha già dei contenuti? Considera anche la libreria scaricata: chi ha download non
    // deve vedere l'empty state "La tua Home si riempie mentre leggi".
    val hasAnyContent = continueItem != null ||
        state.favorites.isNotEmpty() ||
        state.favoriteUpdates.isNotEmpty() ||
        state.library.isNotEmpty()

    fun isBlockEmpty(block: HomeBlock): Boolean = when (block) {
        HomeBlock.RESUME -> continueItem == null
        HomeBlock.FAVORITE_UPDATES -> state.favoriteUpdates.isEmpty()
        HomeBlock.RECENT_FAVORITES -> state.favorites.isEmpty()
        HomeBlock.DISCOVER -> !discoverHasContent
        HomeBlock.RECOMMENDED -> !recommendedHasContent
        HomeBlock.STATS -> stats.isEmpty()
        HomeBlock.HISTORY -> readingHistory.isEmpty()
        HomeBlock.TO_FINISH -> seriesToFinish.isEmpty()
    }

    // Carica Scopri solo se il blocco è presente E non nascosto: evita fetch AniList sprecati
    // quando l'utente ha nascosto il blocco.
    if (HomeBlock.DISCOVER in blocks && HomeBlock.DISCOVER !in hidden) {
        LaunchedEffect(Unit) { onLoadDiscover() }
    }
    if (HomeBlock.RECOMMENDED in blocks && HomeBlock.RECOMMENDED !in hidden) {
        LaunchedEffect(Unit) { onLoadRecommendations() }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (editMode) 12.dp else 16.dp),
    ) {
        if (editMode) {
            // Modalità modifica: solo la lista dei blocchi come card, niente contenuti.
            item(key = "edit-hint") {
                Text(
                    text = "Sposta i blocchi con le frecce, tocca l'occhio per nasconderli, " +
                        "scegli la taglia con S · M · L.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            blocks.forEachIndexed { index, block ->
                item(key = "ctrl-${block.name}") {
                    HomeBlockEditRow(
                        block = block,
                        index = index,
                        lastIndex = blocks.lastIndex,
                        hidden = block in hidden,
                        size = sizeOf(block),
                        onMove = onMoveBlock,
                        onSetHidden = onSetBlockHidden,
                        onSetSize = onSetBlockSize,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        } else {
            if (state.tutorialState.phase == TutorialPhase.Welcome) {
                item(key = "onboarding") {
                    HomeOnboardingCard(
                        onStart = onStartTutorial,
                        onDismiss = onDismissTutorial,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (!hasAnyContent) {
                item(key = "empty") {
                    HomeEmptyState(onSearchFirst = onSearchFirst)
                }
            }

            blocks.forEach { block ->
                // I blocchi nascosti o vuoti non occupano spazio.
                if (block in hidden || isBlockEmpty(block)) return@forEach

                val size = sizeOf(block)
                when (block) {
                    HomeBlock.RESUME -> item(key = "b-resume") {
                        HomeResumeCard(
                            item = continueItem!!,
                            onResume = onResume,
                            compact = size == HomeBlockSize.SMALL,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    HomeBlock.FAVORITE_UPDATES -> item(key = "b-updates") {
                        HomeSection(
                            title = "Novità dai preferiti",
                            trailingActionLabel = "Vedi tutte",
                            onTrailingAction = onOpenAllUpdates,
                        ) {
                            HomeCarousel(recentUpdates) { event ->
                                HomeUpdateChip(
                                    event = event,
                                    width = size.chipWidth(),
                                    onClick = { onOpenUpdate(event) },
                                )
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
                                HomePosterTile(
                                    coverModel = favorite.coverUrl,
                                    title = favorite.title,
                                    width = size.posterWidth(),
                                    onClick = { onOpenFavorite(favorite) },
                                )
                            }
                        }
                    }

                    HomeBlock.DISCOVER -> {
                        item(key = "b-discover-header") {
                            HomeSectionTitle(title = "Scopri")
                        }
                        item(key = "b-discover-genres") {
                            HomeGenreRow(onOpenGenre = onOpenGenre)
                        }
                        val hasSections = discovery.trending.isNotEmpty() ||
                            discovery.topRated.isNotEmpty() ||
                            discovery.newest.isNotEmpty()
                        val sectionsError = discovery.sectionsError
                        when {
                            hasSections -> {
                                val cardWidth = size.discoverCardWidth()
                                discoverySection("Tendenze", discovery.trending, onPickDiscover, onShowDiscoverInfo, cardWidth)
                                discoverySection("Più votati", discovery.topRated, onPickDiscover, onShowDiscoverInfo, cardWidth)
                                discoverySection("Novità", discovery.newest, onPickDiscover, onShowDiscoverInfo, cardWidth)
                            }
                            discovery.isLoadingSections -> item(key = "b-discover-loading") { HomeDiscoverLoading() }
                            sectionsError != null -> item(key = "b-discover-error") {
                                HomeDiscoverError(message = sectionsError, onRetry = onLoadDiscover)
                            }
                        }
                    }

                    HomeBlock.RECOMMENDED -> {
                        item(key = "b-recommended-header") {
                            HomeSectionTitle(title = "Consigliati per te")
                        }
                        when {
                            recommendations.items.isNotEmpty() -> item(key = "b-recommended-row") {
                                HomeCarousel(recommendations.items) { manga ->
                                    DiscoveryCard(
                                        manga = manga,
                                        modifier = Modifier.width(size.discoverCardWidth()),
                                        onClick = { onPickDiscover(manga) },
                                        onShowInfo = { onShowDiscoverInfo(manga) },
                                    )
                                }
                            }
                            recommendations.isLoading -> item(key = "b-recommended-loading") {
                                HomeDiscoverLoading()
                            }
                            recommendations.error != null -> item(key = "b-recommended-error") {
                                HomeDiscoverError(
                                    message = recommendations.error,
                                    onRetry = onLoadRecommendations,
                                )
                            }
                        }
                    }

                    HomeBlock.STATS -> item(key = "b-stats") {
                        HomeSection(
                            title = "Statistiche",
                            trailingActionLabel = "Vedi tutto",
                            onTrailingAction = onOpenStats,
                        ) {
                            when (size) {
                                HomeBlockSize.SMALL -> HomeStatsCompactRow(
                                    stats = stats,
                                    streak = streak,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                HomeBlockSize.MEDIUM -> HomeStatsGrid(
                                    stats = stats,
                                    streak = streak,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                HomeBlockSize.LARGE -> Column(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    HomeStatsGrid(stats = stats, streak = streak)
                                    Card(
                                        shape = MaterialTheme.shapes.large,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ),
                                    ) {
                                        WeekBarChart(
                                            days = lastWeek,
                                            modifier = Modifier.padding(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HomeBlock.HISTORY -> item(key = "b-history") {
                        HomeSection(
                            title = "Letti di recente",
                            trailingActionLabel = "Vedi tutto",
                            onTrailingAction = onOpenHistory,
                        ) {
                            HomeCarousel(readingHistory) { entry ->
                                HomeHistoryChip(
                                    item = entry,
                                    width = size.chipWidth(),
                                    onResume = onResume,
                                )
                            }
                        }
                    }

                    HomeBlock.TO_FINISH -> item(key = "b-tofinish") {
                        HomeSection(title = "Da finire") {
                            HomeCarousel(seriesToFinish) { entry ->
                                HomeToFinishTile(
                                    item = entry,
                                    width = size.posterWidth(),
                                    onClick = { onOpenSeries(entry.series) },
                                )
                            }
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

/**
 * Card di un blocco in modalità modifica (stile mockup): icona del blocco + nome e descrizione
 * (o "Nascosto") + frecce di riordino + occhio per nascondere; sotto, il selettore di taglia
 * S · M · L (nascosto insieme al blocco). I blocchi nascosti restano in lista, attenuati.
 */
@Composable
private fun HomeBlockEditRow(
    block: HomeBlock,
    index: Int,
    lastIndex: Int,
    hidden: Boolean,
    size: HomeBlockSize,
    onMove: (HomeBlock, Boolean) -> Unit,
    onSetHidden: (HomeBlock, Boolean) -> Unit,
    onSetSize: (HomeBlock, HomeBlockSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (hidden) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = block.editIcon(),
                contentDescription = null,
                tint = if (hidden) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = if (hidden) "Nascosto" else block.editDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        if (!hidden) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Taglia",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                HomeBlockSize.entries.forEach { candidate ->
                    FilterChip(
                        selected = size == candidate,
                        onClick = { onSetSize(block, candidate) },
                        label = { Text(candidate.label) },
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Taglia ${candidate.label} per ${block.displayName()}"
                        },
                    )
                }
            }
        }
    }
}

/**
 * Chip compatta di una novità dai preferiti (carosello orizzontale): mini-cover + titolo +
 * capitolo, con il pallino "non letto" in coda. Versione da vetrina della [FavoriteUpdateRow]
 * usata nella schermata Aggiornamenti.
 */
@Composable
private fun HomeUpdateChip(
    event: FavoriteUpdateEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
) {
    Card(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (!event.seen) stateDescription = "Non letto"
            },
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverImage(
                model = event.coverUrl,
                title = event.title,
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = event.chapterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!event.seen) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

/**
 * Tile poster "cover-forward" (stile mockup): solo copertina arrotondata + titolo su una riga,
 * senza card contenitore. Usata per i preferiti recenti.
 */
@Composable
private fun HomePosterTile(
    coverModel: Any?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick, onClickLabel = "Apri"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoverImage(
            model = coverModel,
            title = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.large),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Stato iniziale della Home (stile mockup): non lascia il vuoto, spiega cosa apparirà e offre
 * l'azione chiave — cercare il primo manga.
 */
@Composable
private fun HomeEmptyState(onSearchFirst: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoStories,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "La tua Home si riempie mentre leggi",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Qui troverai il capitolo da riprendere, le novità dei preferiti e i suggerimenti.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSearchFirst, shape = MaterialTheme.shapes.large) {
            Text("Cerca il primo manga")
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
        AppLoadingIndicator()
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

/** Griglia 3×2 delle statistiche di lettura (calcoli in [computeHomeStats] + streak dal diario). */
@Composable
private fun HomeStatsGrid(stats: HomeStats, streak: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                value = formatStatNumber(stats.chaptersRead),
                label = "Capitoli letti",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = formatStatNumber(stats.pagesRead),
                label = "Pagine lette",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                value = formatStatNumber(stats.seriesReadCount),
                label = "Serie lette",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = formatStatNumber(stats.seriesCount),
                label = "Serie in libreria",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                value = if (streak > 0) "$streak 🔥" else "0",
                label = if (streak == 1) "Giorno di fila" else "Giorni di fila",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = formatStatNumber(stats.favoritesCount),
                label = "Preferiti",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Variante compatta (taglia S): una sola card con i tre numeri chiave in riga. */
@Composable
private fun HomeStatsCompactRow(stats: HomeStats, streak: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompactStat(formatStatNumber(stats.chaptersRead), "Capitoli", Modifier.weight(1f))
            CompactStat(formatStatNumber(stats.pagesRead), "Pagine", Modifier.weight(1f))
            CompactStat(if (streak > 0) "$streak 🔥" else "0", "Di fila", Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompactStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Chip di un capitolo letto di recente (carosello "Letti di recente"): come [HomeUpdateChip].
 * Le letture arrivano dalla memoria persistente: un capitolo non più scaricato resta visibile
 * (copertina segnaposto) ma non è cliccabile, non essendoci più nulla da riaprire.
 */
@Composable
private fun HomeHistoryChip(
    item: ReadingHistoryItem,
    onResume: (DownloadedChapter) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
) {
    val chapter = item.chapter
    Card(
        modifier = modifier
            .width(width)
            .then(
                if (chapter != null) {
                    Modifier.clickable(onClick = { onResume(chapter) }, onClickLabel = "Riapri il capitolo")
                } else {
                    Modifier
                },
            ),
        shape = MaterialTheme.shapes.large,
        colors = appCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverImage(
                model = item.series?.coverFile,
                title = item.memory.seriesTitle,
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.memory.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.memory.chapterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.memory.progressLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Poster di una serie "da finire": cover + badge col conteggio dei non letti. */
@Composable
private fun HomeToFinishTile(
    item: SeriesToFinish,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 104.dp,
) {
    val label = if (item.unreadCount == 1) {
        "1 capitolo da leggere"
    } else {
        "${item.unreadCount} capitoli da leggere"
    }
    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick, onClickLabel = "Apri la serie")
            .semantics(mergeDescendants = true) {
                stateDescription = label
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CoverImage(
                model = item.series.coverFile,
                title = item.series.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.large),
            )
            Text(
                text = "${item.unreadCount}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
                    .clearAndSetSemantics {},
            )
        }
        Text(
            text = item.series.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun HomeBlock.displayName(): String = when (this) {
    HomeBlock.RESUME -> "Continua a leggere"
    HomeBlock.FAVORITE_UPDATES -> "Novità dai preferiti"
    HomeBlock.RECENT_FAVORITES -> "Preferiti recenti"
    HomeBlock.DISCOVER -> "Scopri"
    HomeBlock.RECOMMENDED -> "Consigliati per te"
    HomeBlock.STATS -> "Statistiche"
    HomeBlock.HISTORY -> "Letti di recente"
    HomeBlock.TO_FINISH -> "Da finire"
}

/** Sottotitolo del blocco nella modalità modifica. */
private fun HomeBlock.editDescription(): String = when (this) {
    HomeBlock.RESUME -> "Il capitolo da riprendere"
    HomeBlock.FAVORITE_UPDATES -> "Nuovi capitoli usciti"
    HomeBlock.RECENT_FAVORITES -> "Aggiunti di recente"
    HomeBlock.DISCOVER -> "Tendenze da AniList"
    HomeBlock.RECOMMENDED -> "In base a preferiti e letture"
    HomeBlock.STATS -> "I tuoi numeri di lettura"
    HomeBlock.HISTORY -> "Gli ultimi capitoli letti"
    HomeBlock.TO_FINISH -> "Serie con capitoli da leggere"
}

/** Icona identificativa del blocco nella modalità modifica. */
private fun HomeBlock.editIcon(): ImageVector = when (this) {
    HomeBlock.RESUME -> Icons.Filled.PlayCircle
    HomeBlock.FAVORITE_UPDATES -> Icons.Filled.NewReleases
    HomeBlock.RECENT_FAVORITES -> Icons.Filled.Star
    HomeBlock.DISCOVER -> Icons.Filled.Explore
    HomeBlock.RECOMMENDED -> Icons.Filled.AutoAwesome
    HomeBlock.STATS -> Icons.Filled.BarChart
    HomeBlock.HISTORY -> Icons.Filled.History
    HomeBlock.TO_FINISH -> Icons.Filled.Checklist
}

/** Larghezza dei poster (preferiti, da finire) per taglia. */
private fun HomeBlockSize.posterWidth(): Dp = when (this) {
    HomeBlockSize.SMALL -> 88.dp
    HomeBlockSize.MEDIUM -> 104.dp
    HomeBlockSize.LARGE -> 128.dp
}

/** Larghezza delle chip orizzontali (novità, letti di recente) per taglia. */
private fun HomeBlockSize.chipWidth(): Dp = when (this) {
    HomeBlockSize.SMALL -> 168.dp
    HomeBlockSize.MEDIUM -> 200.dp
    HomeBlockSize.LARGE -> 240.dp
}

/** Larghezza delle card AniList (Scopri, Consigliati) per taglia. */
private fun HomeBlockSize.discoverCardWidth(): Dp = when (this) {
    HomeBlockSize.SMALL -> 104.dp
    HomeBlockSize.MEDIUM -> 124.dp
    HomeBlockSize.LARGE -> 148.dp
}

/** Carosello "Esplora per genere": card compatte, colori container ciclici, icona per genere. */
@Composable
private fun HomeGenreRow(onOpenGenre: (DiscoverGenre) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(DiscoverGenre.entries) { index, genre ->
            GenreCard(genre = genre, index = index, onClick = { onOpenGenre(genre) })
        }
    }
}

@Composable
private fun GenreCard(genre: DiscoverGenre, index: Int, onClick: () -> Unit) {
    val (container, content) = when (index % 3) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Card(
        modifier = Modifier.clickable(onClick = onClick, onClickLabel = "Esplora ${genre.label}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Text(
            text = genre.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
