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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    onOpenSeries: (DownloadedSeries) -> Unit,
    onPickDiscover: (AniListManga) -> Unit,
    onShowDiscoverInfo: (AniListManga) -> Unit,
    onDismissDiscoverInfo: () -> Unit,
    onLoadDiscover: () -> Unit,
    onOpenGenre: (DiscoverGenre) -> Unit,
    onSearchFirst: () -> Unit,
    onStartTutorial: () -> Unit,
    onDismissTutorial: () -> Unit,
    onMoveBlock: (HomeBlock, Boolean) -> Unit,
    onSetBlockHidden: (HomeBlock, Boolean) -> Unit,
) {
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
    val stats = remember(state.library, state.favorites) {
        computeHomeStats(state.library, state.favorites.size)
    }
    val readingHistory = remember(state.library) {
        computeReadingHistory(state.library, limit = 10)
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
        HomeBlock.STATS -> stats.isEmpty()
        HomeBlock.HISTORY -> readingHistory.isEmpty()
        HomeBlock.TO_FINISH -> seriesToFinish.isEmpty()
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
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (editMode) 12.dp else 16.dp),
    ) {
        if (editMode) {
            // Modalità modifica: solo la lista dei blocchi come card, niente contenuti.
            item(key = "edit-hint") {
                Text(
                    text = "Sposta i blocchi con le frecce, tocca l'occhio per nasconderli.",
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
                        onMove = onMoveBlock,
                        onSetHidden = onSetBlockHidden,
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
                            HomeCarousel(recentUpdates) { event ->
                                HomeUpdateChip(event = event, onClick = { onOpenUpdate(event) })
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
                                    onClick = { onOpenFavorite(favorite) },
                                )
                            }
                        }
                    }

                    HomeBlock.DISCOVER -> {
                        item(key = "b-discover-header") {
                            HomeSectionTitle(
                                title = "Scopri",
                                leadingIcon = Icons.Filled.Explore,
                            )
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

                    HomeBlock.STATS -> item(key = "b-stats") {
                        HomeSection(title = "Statistiche") {
                            HomeStatsGrid(stats = stats, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    HomeBlock.HISTORY -> item(key = "b-history") {
                        HomeSection(
                            title = "Letti di recente",
                            trailingActionLabel = "Vedi tutto",
                            onTrailingAction = onOpenHistory,
                        ) {
                            HomeCarousel(readingHistory) { entry ->
                                HomeHistoryChip(item = entry, onClick = { onResume(entry.chapter) })
                            }
                        }
                    }

                    HomeBlock.TO_FINISH -> item(key = "b-tofinish") {
                        HomeSection(title = "Da finire") {
                            HomeCarousel(seriesToFinish) { entry ->
                                HomeToFinishTile(
                                    item = entry,
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
 * (o "Nascosto") + frecce di riordino + occhio per nascondere. I blocchi nascosti restano in
 * lista, attenuati, così si possono riattivare.
 */
@Composable
private fun HomeBlockEditRow(
    block: HomeBlock,
    index: Int,
    lastIndex: Int,
    hidden: Boolean,
    onMove: (HomeBlock, Boolean) -> Unit,
    onSetHidden: (HomeBlock, Boolean) -> Unit,
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
) {
    Card(
        modifier = modifier
            .width(200.dp)
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
) {
    Column(
        modifier = modifier
            .width(104.dp)
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

/** Griglia 2×2 delle statistiche di lettura (calcoli in [computeHomeStats]). */
@Composable
private fun HomeStatsGrid(stats: HomeStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                value = formatStatNumber(stats.seriesCount),
                label = "Serie in libreria",
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = formatStatNumber(stats.chaptersRead),
                label = "Capitoli letti",
                icon = Icons.Filled.Done,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                value = formatStatNumber(stats.pagesRead),
                label = "Pagine lette",
                icon = Icons.Outlined.AutoStories,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = formatStatNumber(stats.favoritesCount),
                label = "Preferiti",
                icon = Icons.Filled.Star,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    icon: ImageVector,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(8.dp))
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

/** Chip di un capitolo letto di recente (carosello "Letti di recente"): come [HomeUpdateChip]. */
@Composable
private fun HomeHistoryChip(
    item: ReadingHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = item.chapter
    Card(
        modifier = modifier
            .width(200.dp)
            .clickable(onClick = onClick, onClickLabel = "Riapri il capitolo"),
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
                model = item.series.coverFile,
                title = item.series.title,
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.series.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapter.title.ifBlank { "Capitolo ${chapter.numberText}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
) {
    Column(
        modifier = modifier
            .width(104.dp)
            .clickable(onClick = onClick, onClickLabel = "Apri la serie")
            .semantics(mergeDescendants = true) {
                stateDescription = "${item.unreadCount} capitoli da leggere"
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
                    .padding(horizontal = 7.dp, vertical = 3.dp),
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
    HomeBlock.STATS -> Icons.Filled.BarChart
    HomeBlock.HISTORY -> Icons.Filled.History
    HomeBlock.TO_FINISH -> Icons.Filled.Checklist
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
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Icon(
                imageVector = genre.icon(),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = genre.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Icona per genere: mapping UI, tenuto fuori dall'enum puro. */
private fun DiscoverGenre.icon(): ImageVector = when (this) {
    DiscoverGenre.ACTION -> Icons.Filled.FlashOn
    DiscoverGenre.ADVENTURE -> Icons.Filled.Explore
    DiscoverGenre.COMEDY -> Icons.Filled.EmojiEmotions
    DiscoverGenre.DRAMA -> Icons.Filled.TheaterComedy
    DiscoverGenre.FANTASY -> Icons.Filled.AutoAwesome
    DiscoverGenre.HORROR -> Icons.Filled.NightsStay
    DiscoverGenre.MYSTERY -> Icons.Filled.QuestionMark
    DiscoverGenre.ROMANCE -> Icons.Filled.Favorite
    DiscoverGenre.SCI_FI -> Icons.Filled.RocketLaunch
    DiscoverGenre.SLICE_OF_LIFE -> Icons.Filled.LocalCafe
    DiscoverGenre.SPORTS -> Icons.Filled.SportsSoccer
    DiscoverGenre.SUPERNATURAL -> Icons.Filled.AutoFixHigh
}
