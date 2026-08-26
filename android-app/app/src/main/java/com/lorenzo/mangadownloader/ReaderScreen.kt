package com.lorenzo.mangadownloader

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun ReaderScreen(
    chapter: ReaderChapter?,
    previousChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    pages: List<ReaderPage>,
    isLoading: Boolean,
    readingMode: ReadingMode,
    doubleTapZoomEnabled: Boolean,
    spreadRotation: SpreadRotation?,
    pageSpacing: Dp,
    navBarVisible: Boolean,
    padding: PaddingValues,
    initialPageIndex: Int,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
) {
    // In modalità a pagine il salto di capitolo vive solo nella barra in basso, nascosta in
    // fullscreen: quando il pager si assesta sull'ultima pagina la barra riappare da sola,
    // così il passaggio al capitolo successivo non richiede tap + tap.
    var pagedAtLastPage by remember(chapter?.relativePath, readingMode) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = chapter?.relativePath,
            transitionSpec = {
                val slideSpec = spring<IntOffset>(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
                (slideInHorizontally(animationSpec = slideSpec) { full -> full } +
                    fadeIn(animationSpec = tween(220)))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = slideSpec) { full -> -full } +
                            fadeOut(animationSpec = tween(180)),
                    )
            },
            label = "readerChapterTransition",
        ) { targetChapterPath ->
            ReaderContent(
                chapterKey = targetChapterPath,
                chapter = chapter,
                previousChapter = previousChapter,
                nextChapter = nextChapter,
                pages = pages,
                isLoading = isLoading,
                readingMode = readingMode,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                spreadRotation = spreadRotation,
                pageSpacing = pageSpacing,
                padding = padding,
                initialPageIndex = initialPageIndex,
                resumeButtonVisible = navBarVisible,
                onOpenPrevious = onOpenPrevious,
                onOpenNext = onOpenNext,
                onPageVisible = onPageVisible,
                onToggleFullscreen = onToggleFullscreen,
                onRetry = onRetry,
                onAtLastPageChange = { pagedAtLastPage = it },
            )
        }

        // Barra di navigazione capitoli fissa in basso: resta sopra al contenuto del
        // reader (sia scroll verticale che a pagine) e segue lo stato del fullscreen
        // tramite [navBarVisible], così con un tap immersivo sparisce insieme alle barre.
        // Sull'ultima pagina del pager riappare anche in fullscreen (pagedAtLastPage).
        ReaderBottomNavBar(
            visible = (navBarVisible || pagedAtLastPage) &&
                chapter != null &&
                !isLoading &&
                (previousChapter != null || nextChapter != null),
            currentTitle = chapter?.title.orEmpty(),
            previousChapter = previousChapter,
            nextChapter = nextChapter,
            onOpenPrevious = onOpenPrevious,
            onOpenNext = onOpenNext,
            contentPadding = padding,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReaderBottomNavBar(
    visible: Boolean,
    currentTitle: String,
    previousChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            // Frecce ai lati + titolo del capitolo al centro: il titolo basta a chiarire
            // che questi controlli cambiano il capitolo, non scorrono le pagine.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onOpenPrevious,
                    enabled = previousChapter != null,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Capitolo precedente",
                    )
                }
                Text(
                    text = currentTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                IconButton(
                    onClick = onOpenNext,
                    enabled = nextChapter != null,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Capitolo successivo",
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(
    chapterKey: String?,
    chapter: ReaderChapter?,
    previousChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    pages: List<ReaderPage>,
    isLoading: Boolean,
    readingMode: ReadingMode,
    doubleTapZoomEnabled: Boolean,
    spreadRotation: SpreadRotation?,
    pageSpacing: Dp,
    padding: PaddingValues,
    initialPageIndex: Int,
    resumeButtonVisible: Boolean,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onAtLastPageChange: (Boolean) -> Unit,
) {
    // Il pulsante "riprendi" deve galleggiare sopra la barra capitoli quando questa
    // è presente (stessa condizione di ReaderBottomNavBar: esiste un prev/next).
    val resumeButtonBottomPadding =
        if (previousChapter != null || nextChapter != null) 80.dp else 16.dp

    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                AppLoadingIndicator()
            }
        }
        chapter == null || pages.isEmpty() -> {
            EmptyState(
                icon = Icons.Default.BrokenImage,
                title = "Nessuna pagina disponibile",
                description = "Potrebbe essere un problema di rete o della fonte.",
                actionLabel = if (chapter != null) "Riprova" else null,
                onAction = if (chapter != null) onRetry else null,
                modifier = Modifier.padding(padding),
            )
        }
        readingMode.isPaged -> {
            PagedReader(
                chapterKey = chapterKey,
                chapter = chapter,
                pages = pages,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                spreadRotation = spreadRotation,
                pageSpacing = pageSpacing,
                rightToLeft = readingMode.isRightToLeft,
                padding = padding,
                initialPageIndex = initialPageIndex,
                resumeButtonVisible = resumeButtonVisible,
                resumeButtonBottomPadding = resumeButtonBottomPadding,
                onPageVisible = onPageVisible,
                onToggleFullscreen = onToggleFullscreen,
                onAtLastPageChange = onAtLastPageChange,
            )
        }
        else -> {
            VerticalReader(
                chapterKey = chapterKey,
                chapter = chapter,
                previousChapter = previousChapter,
                nextChapter = nextChapter,
                pages = pages,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                spreadRotation = spreadRotation,
                pageSpacing = pageSpacing,
                padding = padding,
                initialPageIndex = initialPageIndex,
                resumeButtonVisible = resumeButtonVisible,
                resumeButtonBottomPadding = resumeButtonBottomPadding,
                onOpenPrevious = onOpenPrevious,
                onOpenNext = onOpenNext,
                onPageVisible = onPageVisible,
                onToggleFullscreen = onToggleFullscreen,
            )
        }
    }
}

@Composable
private fun VerticalReader(
    chapterKey: String?,
    chapter: ReaderChapter,
    previousChapter: ReaderChapter?,
    nextChapter: ReaderChapter?,
    pages: List<ReaderPage>,
    doubleTapZoomEnabled: Boolean,
    spreadRotation: SpreadRotation?,
    pageSpacing: Dp,
    padding: PaddingValues,
    initialPageIndex: Int,
    resumeButtonVisible: Boolean,
    resumeButtonBottomPadding: Dp,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val minScale = 1f
    val maxScale = 4f
    var readerScale by remember(chapterKey) { mutableFloatStateOf(minScale) }
    var readerOffsetX by remember(chapterKey) { mutableFloatStateOf(0f) }
    var readerOffsetY by remember(chapterKey) { mutableFloatStateOf(0f) }
    var viewportSize by remember(chapterKey) { mutableStateOf(IntSize.Zero) }
    var restoreComplete by remember(chapterKey) { mutableStateOf(false) }
    var hasReaderMovedAfterRestore by remember(chapterKey) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Pagina attualmente in vista e pagina più avanzata raggiunta nel capitolo: la
    // differenza tra le due pilota il pulsante "riprendi" (torna dove ero arrivato).
    var currentPageIndex by remember(chapterKey) { mutableIntStateOf(0) }
    var furthestPageIndex by remember(chapterKey) { mutableIntStateOf(0) }
    val zoomFlingDecay = remember { exponentialDecay<Float>() }
    val readerScope = rememberCoroutineScope()
    var zoomFlingJob by remember(chapterKey) { mutableStateOf<Job?>(null) }
    val context = LocalContext.current

    DisposableEffect(chapterKey) {
        onDispose { zoomFlingJob?.cancel() }
    }

    LaunchedEffect(chapterKey, pages.size) {
        if (pages.isEmpty()) return@LaunchedEffect

        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (restoreComplete && scrolling) {
                    hasReaderMovedAfterRestore = true
                }
            }
    }

    LaunchedEffect(chapterKey, pages.size, initialPageIndex) {
        if (pages.isEmpty()) return@LaunchedEffect

        val restoredPageIndex = initialPageIndex.coerceIn(0, pages.lastIndex)
        listState.scrollToItem(restoredPageIndex + ReaderPageItemOffset)
        restoreComplete = true
        currentPageIndex = restoredPageIndex
        furthestPageIndex = maxOf(furthestPageIndex, restoredPageIndex)

        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visiblePages = layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val pageIndex = item.index - ReaderPageItemOffset
                if (pageIndex !in pages.indices) {
                    null
                } else {
                    val visiblePixels = (
                        minOf(item.offset + item.size, layoutInfo.viewportEndOffset) -
                            maxOf(item.offset, layoutInfo.viewportStartOffset)
                        ).coerceAtLeast(0)
                    if (visiblePixels > 0) {
                        Triple(pageIndex, visiblePixels, item.size)
                    } else {
                        null
                    }
                }
            }
            val furthestMostlyVisiblePage = visiblePages
                .filter { (_, visiblePixels, itemSize) -> itemSize > 0 && visiblePixels * 2 >= itemSize }
                .maxOfOrNull { (pageIndex, _, _) -> pageIndex }
            val dominantVisiblePage = visiblePages
                .maxByOrNull { (_, visiblePixels) -> visiblePixels }
                ?.first
            val reachedPageIndex = furthestMostlyVisiblePage
                ?: dominantVisiblePage
                ?: restoredPageIndex
            reachedPageIndex to hasReaderMovedAfterRestore
        }
            .distinctUntilChanged()
            .collect { (reachedPageIndex, allowCompletion) ->
                currentPageIndex = reachedPageIndex
                if (reachedPageIndex > furthestPageIndex) {
                    furthestPageIndex = reachedPageIndex
                }
                onPageVisible(reachedPageIndex, pages.size, allowCompletion)
                prefetchReaderPages(context, pages, reachedPageIndex, spreadRotation)
            }
    }

    fun clampOffsets(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Pair<Float, Float> {
        val maxX = ((viewportSize.width * (scale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportSize.height * (scale - 1f)) / 2f).coerceAtLeast(0f)
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }

    fun applyZoomedOffset(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        val (clampedX, clampedY) = clampOffsets(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
        )
        readerOffsetX = clampedX
        readerOffsetY = clampedY
    }

    fun applyZoomPanDelta(scale: Float, panChange: Offset) {
        if (panChange == Offset.Zero) return
        hasReaderMovedAfterRestore = true
        val previousOffsetY = readerOffsetY
        val (clampedX, clampedY) = clampOffsets(
            scale = scale,
            offsetX = readerOffsetX + panChange.x,
            offsetY = readerOffsetY + panChange.y,
        )
        readerOffsetX = clampedX
        readerOffsetY = clampedY

        if (panChange.y != 0f) {
            val consumedByViewportPan = clampedY - previousOffsetY
            val remainingPanY = panChange.y - consumedByViewportPan
            if (remainingPanY != 0f) {
                listState.dispatchRawDelta(-remainingPanY / scale)
            }
        }
    }

    fun settleToMinScale(offsetY: Float) {
        if (offsetY != 0f) {
            listState.dispatchRawDelta(-offsetY)
            hasReaderMovedAfterRestore = true
        }
        readerScale = minScale
        readerOffsetX = 0f
        readerOffsetY = 0f
    }

    fun startZoomFling(scale: Float, velocity: Offset) {
        zoomFlingJob?.cancel()
        val velocityMagnitude = hypot(velocity.x, velocity.y)
        if (scale <= minScale || velocityMagnitude < MinZoomFlingVelocityPxPerSecond) {
            return
        }

        zoomFlingJob = readerScope.launch {
            val direction = velocity / velocityMagnitude
            var previousDistance = 0f
            AnimationState(
                initialValue = 0f,
                initialVelocity = velocityMagnitude,
            ).animateDecay(zoomFlingDecay) {
                if (readerScale <= minScale) {
                    cancelAnimation()
                    return@animateDecay
                }
                val distanceDelta = value - previousDistance
                previousDistance = value
                applyZoomPanDelta(
                    scale = readerScale,
                    panChange = direction * distanceDelta,
                )
                val movingHorizontally = abs(velocity.x) >= MinZoomFlingVelocityPxPerSecond &&
                    readerOffsetX != 0f
                val movingVertically = abs(velocity.y) >= MinZoomFlingVelocityPxPerSecond ||
                    readerOffsetY != 0f
                if (!movingHorizontally && !movingVertically) {
                    cancelAnimation()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(if (readerScale > minScale) Color.Black else Color.Transparent)
            .clipToBounds()
            .pointerInput(chapterKey) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    zoomFlingJob?.cancel()
                    var velocityTracker: VelocityTracker? = if (readerScale > minScale) {
                        VelocityTracker().apply {
                            addPosition(firstDown.uptimeMillis, firstDown.position)
                        }
                    } else {
                        null
                    }
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val pressedChanges = event.changes.filter { it.pressed }
                        if (pressedChanges.size >= 2) {
                            velocityTracker = null
                            zoomFlingJob?.cancel()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val nextScale = (readerScale * zoomChange)
                                    .coerceIn(minScale, maxScale)
                                val effectiveZoomChange = nextScale / readerScale
                                val centroid = event.calculateCentroid()
                                val viewportCenter = Offset(
                                    x = viewportSize.width / 2f,
                                    y = viewportSize.height / 2f,
                                )
                                val zoomFocus = centroid - viewportCenter
                                val targetOffsetX =
                                    readerOffsetX * effectiveZoomChange +
                                        zoomFocus.x * (1f - effectiveZoomChange) +
                                        panChange.x
                                val targetOffsetY =
                                    readerOffsetY * effectiveZoomChange +
                                        zoomFocus.y * (1f - effectiveZoomChange) +
                                        panChange.y
                                if (nextScale <= minScale) {
                                    settleToMinScale(targetOffsetY)
                                } else {
                                    readerScale = nextScale
                                    applyZoomedOffset(
                                        scale = nextScale,
                                        offsetX = targetOffsetX,
                                        offsetY = targetOffsetY,
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } else if (readerScale > minScale && pressedChanges.size == 1) {
                            val change = pressedChanges.first()
                            val panChange = change.positionChange()
                            val tracker = velocityTracker ?: VelocityTracker().also {
                                velocityTracker = it
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (panChange != Offset.Zero) {
                                applyZoomPanDelta(readerScale, panChange)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    velocityTracker
                        ?.calculateVelocity()
                        ?.let { velocity ->
                            startZoomFling(
                                scale = readerScale,
                                velocity = Offset(velocity.x, velocity.y),
                            )
                        }
                }
            }
            .pointerInput(chapterKey) {
                detectTapGestures(
                    onTap = { onToggleFullscreen() },
                    // Quando il doppio-tap è disattivato passiamo null: così onTap non
                    // attende l'eventuale secondo tap e la barra risponde subito.
                    onDoubleTap = if (!doubleTapZoomEnabled) {
                        null
                    } else {
                        { tapOffset ->
                            if (readerScale > minScale) {
                                readerScale = minScale
                                readerOffsetX = 0f
                                readerOffsetY = 0f
                            } else {
                                val nextScale = 2f
                                val zoomChange = nextScale / readerScale
                                val viewportCenter = Offset(
                                    x = viewportSize.width / 2f,
                                    y = viewportSize.height / 2f,
                                )
                                val zoomFocus = tapOffset - viewportCenter
                                val targetOffsetX =
                                    readerOffsetX * zoomChange +
                                        zoomFocus.x * (1f - zoomChange)
                                val targetOffsetY =
                                    readerOffsetY * zoomChange +
                                        zoomFocus.y * (1f - zoomChange)
                                readerScale = nextScale
                                applyZoomedOffset(
                                    scale = nextScale,
                                    offsetX = targetOffsetX,
                                    offsetY = targetOffsetY,
                                )
                            }
                        }
                    },
                )
            }
            .onSizeChanged { size ->
                viewportSize = size
                val (clampedX, clampedY) = clampOffsets(
                    scale = readerScale,
                    offsetX = readerOffsetX,
                    offsetY = readerOffsetY,
                )
                readerOffsetX = clampedX
                readerOffsetY = clampedY
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = readerScale
                    scaleY = readerScale
                    translationX = readerOffsetX
                    translationY = readerOffsetY
                },
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            item("reader-nav-top") {
                Box(modifier = Modifier.padding(bottom = pageSpacing)) {
                    ReaderChapterNavigationRow(
                        previousChapter = previousChapter,
                        nextChapter = nextChapter,
                        onOpenPrevious = onOpenPrevious,
                        onOpenNext = onOpenNext,
                    )
                }
            }
            itemsIndexed(pages, key = { _, page -> page.stableKey }) { index, page ->
                val previousGroup = pages.getOrNull(index - 1)?.persistedTallPageGroupKey()
                val currentGroup = page.persistedTallPageGroupKey()
                val continuesTallPage = currentGroup != null && currentGroup == previousGroup
                ReaderPageImage(
                    page = page,
                    contentDescription = chapter.title,
                    contentScale = ContentScale.FillWidth,
                    spreadRotation = spreadRotation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index > 0 && !continuesTallPage) pageSpacing else 0.dp),
                )
            }
            item("reader-nav-bottom") {
                Box(modifier = Modifier.padding(top = pageSpacing)) {
                    ReaderChapterNavigationRow(
                        previousChapter = previousChapter,
                        nextChapter = nextChapter,
                        onOpenPrevious = onOpenPrevious,
                        onOpenNext = onOpenNext,
                    )
                }
            }
        }

        // Pulsante "riprendi": compare quando si risale di qualche pagina (per esempio
        // per ricontrollare un nome) e riporta alla pagina più avanzata raggiunta.
        ReaderResumeButton(
            visible = resumeButtonVisible &&
                furthestPageIndex - currentPageIndex >= ReaderResumeMinPagesBehind,
            targetPageNumber = furthestPageIndex + 1,
            icon = Icons.Default.KeyboardDoubleArrowDown,
            bottomPadding = resumeButtonBottomPadding,
            onClick = {
                readerScope.launch {
                    listState.animateScrollToItem(furthestPageIndex + ReaderPageItemOffset)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    chapterKey: String?,
    chapter: ReaderChapter,
    pages: List<ReaderPage>,
    doubleTapZoomEnabled: Boolean,
    spreadRotation: SpreadRotation?,
    pageSpacing: Dp,
    rightToLeft: Boolean,
    padding: PaddingValues,
    initialPageIndex: Int,
    resumeButtonVisible: Boolean,
    resumeButtonBottomPadding: Dp,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    onAtLastPageChange: (Boolean) -> Unit,
) {
    // Solo le pagine reali: niente più "pagine di navigazione" all'inizio/fine. Il salto
    // al capitolo precedente/successivo vive nella barra fissa in basso (ReaderBottomNavBar),
    // così lo swipe resta dedicato alle pagine e l'indicatore "x/y" non si confonde col capitolo.
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    var hasMoved by remember(chapterKey) { mutableStateOf(false) }
    val context = LocalContext.current
    // Pagina più avanzata raggiunta nel capitolo: pilota il pulsante "riprendi"
    // quando si torna indietro di qualche pagina.
    var furthestPageIndex by remember(chapterKey) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, pages.lastIndex))
    }
    val resumeScope = rememberCoroutineScope()

    LaunchedEffect(chapterKey) {
        snapshotFlow { pagerState.currentPage }
            .drop(1)
            .collect { hasMoved = true }
    }

    LaunchedEffect(chapterKey, pages.size) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                val pageIndex = settled.coerceIn(0, pages.lastIndex)
                if (pageIndex > furthestPageIndex) {
                    furthestPageIndex = pageIndex
                }
                onPageVisible(pageIndex, pages.size, hasMoved)
                onAtLastPageChange(pageIndex == pages.lastIndex)
                prefetchReaderPages(context, pages, pageIndex, spreadRotation)
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black),
    ) {
        // In modalità manga forziamo il layout RTL solo sul pager: così pagina 1 sta a
        // destra e lo swipe procede da destra verso sinistra. Le immagini e l'indicatore
        // "x/y" (fuori da questo provider) restano in layout normale, quindi non si
        // specchiano. Le coordinate dei gesti di zoom/pan sono in pixel, indipendenti dalla
        // direzione, quindi ZoomablePage funziona identico nelle due modalità.
        val pagerLayoutDirection = if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides pagerLayoutDirection) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Visibile solo durante lo swipe: separa otticamente le pagine come in un volume.
                pageSpacing = pageSpacing,
                key = { index -> pages[index].stableKey },
                // La pagina successiva è già composta (e la sua immagine in caricamento)
                // prima dello swipe: in streaming evita la pagina nera a ogni cambio.
                beyondViewportPageCount = 1,
            ) { index ->
                ZoomablePage(
                    page = pages[index],
                    contentDescription = chapter.title,
                    doubleTapZoomEnabled = doubleTapZoomEnabled,
                    spreadRotation = spreadRotation,
                    onToggleFullscreen = onToggleFullscreen,
                )
            }
        }

        // Indicatore di pagina discreto: appare al cambio pagina e svanisce da solo.
        // Sta in alto, separato dalla barra capitolo in basso: così "x/y" è chiaramente
        // la pagina e non si sovrappone alla navigazione capitoli.
        ReaderPageIndicator(
            currentPageIndex = pagerState.currentPage,
            pageCount = pages.size,
            isScrolling = pagerState.isScrollInProgress,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Pulsante "riprendi": compare quando si sfoglia indietro di qualche pagina
        // e riporta alla pagina più avanzata raggiunta. In modalità manga (RTL)
        // "avanti" è visivamente verso sinistra, quindi la freccia viene specchiata.
        ReaderResumeButton(
            visible = resumeButtonVisible &&
                furthestPageIndex - pagerState.currentPage >= ReaderResumeMinPagesBehind,
            targetPageNumber = furthestPageIndex + 1,
            icon = Icons.Default.KeyboardDoubleArrowRight,
            mirrorIcon = rightToLeft,
            bottomPadding = resumeButtonBottomPadding,
            onClick = {
                resumeScope.launch { pagerState.animateScrollToPage(furthestPageIndex) }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun BoxScope.ReaderPageIndicator(
    currentPageIndex: Int,
    pageCount: Int,
    isScrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val visiblePage = currentPageIndex in 0 until pageCount
    var visible by remember { mutableStateOf(true) }

    // Mostra l'indicatore quando cambi pagina o stai scorrendo, poi nascondilo dopo
    // una breve pausa di inattività.
    LaunchedEffect(currentPageIndex, isScrolling) {
        if (!visiblePage) return@LaunchedEffect
        visible = true
        if (!isScrolling) {
            delay(1400)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && visiblePage,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.padding(top = 20.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text = "${currentPageIndex + 1} / $pageCount",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Pulsante flottante "riprendi lettura", in basso a destra: compare quando l'utente
 * risale/torna indietro di almeno [ReaderResumeMinPagesBehind] pagine rispetto al punto
 * più avanzato raggiunto nel capitolo (per esempio per ricontrollare un nome) e con un
 * tap lo riporta a quella pagina. Solo icona (la doppia freccia già usata dalla lista
 * capitoli nel dettaglio); segue lo stato del fullscreen come le altre barre.
 */
@Composable
private fun ReaderResumeButton(
    visible: Boolean,
    targetPageNumber: Int,
    icon: ImageVector,
    bottomPadding: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mirrorIcon: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.padding(end = 16.dp, bottom = bottomPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Torna a pagina $targetPageNumber",
                modifier = Modifier.graphicsLayer { scaleX = if (mirrorIcon) -1f else 1f },
            )
        }
    }
}

@Composable
private fun ZoomablePage(
    page: ReaderPage,
    contentDescription: String,
    doubleTapZoomEnabled: Boolean,
    spreadRotation: SpreadRotation?,
    onToggleFullscreen: () -> Unit,
) {
    val minScale = 1f
    val maxScale = 4f
    var scale by remember(page.stableKey) { mutableFloatStateOf(minScale) }
    var offsetX by remember(page.stableKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(page.stableKey) { mutableFloatStateOf(0f) }
    var viewportSize by remember(page.stableKey) { mutableStateOf(IntSize.Zero) }

    fun clamp() {
        val maxX = ((viewportSize.width * (scale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((viewportSize.height * (scale - 1f)) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(page.stableKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val pressedChanges = event.changes.filter { it.pressed }
                        if (pressedChanges.size >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val nextScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                                val effective = nextScale / scale
                                val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                                val focus = event.calculateCentroid() - center
                                offsetX = offsetX * effective + focus.x * (1f - effective) + panChange.x
                                offsetY = offsetY * effective + focus.y * (1f - effective) + panChange.y
                                scale = nextScale
                                if (scale <= minScale) {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                clamp()
                                event.changes.forEach { it.consume() }
                            }
                        } else if (scale > minScale && pressedChanges.size == 1) {
                            // Pan dentro la pagina ingrandita: consuma così il pager non sfoglia.
                            val change = pressedChanges.first()
                            val panChange = change.positionChange()
                            if (panChange != Offset.Zero) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                                clamp()
                                change.consume()
                            }
                        }
                        // Un dito a scala 1: non consumiamo → lo swipe orizzontale va al pager.
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(page.stableKey) {
                detectTapGestures(
                    onTap = { onToggleFullscreen() },
                    // Con doppio-tap disattivato passiamo null così il tap singolo
                    // (toggle barra) non resta in attesa del secondo tap.
                    onDoubleTap = if (!doubleTapZoomEnabled) {
                        null
                    } else {
                        { tapOffset ->
                            if (scale > minScale) {
                                scale = minScale
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2f
                                val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                                val focus = tapOffset - center
                                offsetX = -focus.x
                                offsetY = -focus.y
                                clamp()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        ReaderPageImage(
            page = page,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            spreadRotation = spreadRotation,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

/**
 * Pagina del reader con feedback di stato: spinner finché l'immagine carica e, in caso
 * di fallimento, una card "Tocca per riprovare" — così una pagina remota fallita non
 * collassa ad altezza zero sparendo dal flusso verticale, né resta schermata nera muta
 * in modalità a pagine. Il retry incrementa un contatore che entra nella richiesta Coil
 * come parametro: la chiave nuova forza un vero nuovo tentativo di rete. Per le pagine
 * locali con origine remota nota, il retry riscarica la pagina invece di rileggere il
 * file rotto (vedi [readerImageRequest]).
 *
 * Prima di mostrare la card, un fallimento passa dal recupero "striscia webtoon"
 * ([decodeTallReaderPageChunks]): le pagine più alte del limite texture della GPU
 * non sono decodificabili intere, ma spezzate a blocchi si mostrano senza problemi.
 */
@Composable
private fun ReaderPageImage(
    page: ReaderPage,
    contentDescription: String,
    contentScale: ContentScale,
    spreadRotation: SpreadRotation?,
    modifier: Modifier = Modifier,
) {
    var retryAttempt by remember(page.stableKey) { mutableIntStateOf(0) }
    var tallPageChunks by remember(page.stableKey) { mutableStateOf<List<ImageBitmap>?>(null) }
    val context = LocalContext.current

    val chunks = tallPageChunks
    if (chunks != null) {
        DisposableEffect(chunks) {
            onDispose {
                chunks.forEach { chunk ->
                    val bitmap = chunk.asAndroidBitmap()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
        TallReaderPageStrip(
            chunks = chunks,
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }

    val model = remember(page.stableKey, retryAttempt, spreadRotation) {
        readerImageRequest(context, page, retryAttempt, spreadRotation)
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ReaderPagePlaceholderMinHeight),
                contentAlignment = Alignment.Center,
            ) {
                AppLoadingIndicator()
            }
        },
        error = {
            // Tentativo di recupero a blocchi (una volta per caricamento fallito):
            // finché è in corso mostra lo spinner, la card compare solo se anche
            // questo non produce nulla.
            var tallAttemptDone by remember(page.stableKey, retryAttempt) { mutableStateOf(false) }
            LaunchedEffect(page.stableKey, retryAttempt) {
                if (!tallAttemptDone) {
                    tallPageChunks = decodeTallReaderPageChunks(context, page)
                    tallAttemptDone = true
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ReaderPagePlaceholderMinHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (!tallAttemptDone) {
                    AppLoadingIndicator()
                } else {
                    Surface(
                        onClick = { retryAttempt++ },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Pagina non caricata",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Tocca per riprovare",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * Richiesta immagine per Coil. Le pagine locali sono semplici file; quelle remote
 * (streaming) portano il Referer della loro fonte, così l'hotlink protection dei
 * vari siti non blocca le immagini (prima ricevevano un Referer mangapill fisso).
 * [retryAttempt] > 0 entra nella cache key per distinguere il retry dalla richiesta fallita.
 *
 * Recupero pagine locali: una pagina locale col file sparito/vuoto, o che ha già fallito
 * un caricamento (retry richiesto), viene richiesta dal suo URL d'origine quando lo
 * conosciamo — rileggere lo stesso file rotto non la farebbe mai ricomparire.
 */
private fun readerImageRequest(
    context: Context,
    page: ReaderPage,
    retryAttempt: Int = 0,
    spreadRotation: SpreadRotation? = null,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
    when (page) {
        is ReaderPage.Local -> {
            val remote = page.remote
            if (
                remote != null &&
                page.remoteSegmentIndex == null &&
                (retryAttempt > 0 || page.isFileBroken)
            ) {
                builder
                    .data(remote.url)
                    .httpHeaders(NetworkHeaders.Builder().set("Referer", remote.referer).build())
            } else {
                builder.data(page.file)
            }
        }
        is ReaderPage.Remote -> builder
            .data(page.url)
            .httpHeaders(NetworkHeaders.Builder().set("Referer", page.referer).build())
    }
    // Pagine doppie: o si mostra una metà per volta (deciso a monte, sull'elenco pagine)
    // oppure si ruota tutto il capitolo. Le due cose si escludono a vicenda.
    val half = (page as? ReaderPage.Local)?.half
    when {
        half != null -> builder.transformations(SpreadHalfTransformation(half))
        spreadRotation != null -> builder.transformations(SpreadRotateTransformation(spreadRotation))
    }
    if (retryAttempt > 0) {
        builder.memoryCacheKeyExtra("retry", retryAttempt.toString())
    }
    return builder.build()
}

/**
 * Scalda la cache Coil (memoria + disco) delle pagine remote successive a [fromIndex]:
 * allo swipe/scroll l'immagine è già pronta invece di una pagina nera che scarica.
 * Best-effort: le pagine locali non ne hanno bisogno, le richieste duplicate le
 * deduplica Coil tramite cache.
 */
private fun prefetchReaderPages(
    context: Context,
    pages: List<ReaderPage>,
    fromIndex: Int,
    spreadRotation: SpreadRotation?,
) {
    val first = fromIndex + 1
    val last = (fromIndex + ReaderPrefetchPagesAhead).coerceAtMost(pages.lastIndex)
    for (index in first..last) {
        val page = pages.getOrNull(index) as? ReaderPage.Remote ?: continue
        context.imageLoader.enqueue(
            // Stessa richiesta che farà la pagina quando toccherà a lei, rotazione compresa:
            // con parametri diversi la cache non verrebbe riusata e il prefetch sarebbe sprecato.
            readerImageRequest(context, page, spreadRotation = spreadRotation),
        )
    }
}

private const val ReaderPageItemOffset = 1
private const val MinZoomFlingVelocityPxPerSecond = 120f
private const val ReaderPrefetchPagesAhead = 3

// Ingombro delle pagine non ancora caricate (spinner/errore): evita item ad altezza
// zero in verticale e dà un bersaglio visibile al "tocca per riprovare".
private val ReaderPagePlaceholderMinHeight = 360.dp

// Di quante pagine bisogna tornare indietro rispetto al punto più avanzato
// raggiunto prima che compaia il pulsante "riprendi".
private const val ReaderResumeMinPagesBehind = 2
