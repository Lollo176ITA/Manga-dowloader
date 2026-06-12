package com.lorenzo.mangadownloader

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BrokenImage
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
import androidx.compose.ui.graphics.graphicsLayer
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    pageSpacing: Dp,
    navBarVisible: Boolean,
    padding: PaddingValues,
    initialPageIndex: Int,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
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
                pageSpacing = pageSpacing,
                padding = padding,
                initialPageIndex = initialPageIndex,
                onOpenPrevious = onOpenPrevious,
                onOpenNext = onOpenNext,
                onPageVisible = onPageVisible,
                onToggleFullscreen = onToggleFullscreen,
            )
        }

        // Barra di navigazione capitoli fissa in basso: resta sopra al contenuto del
        // reader (sia scroll verticale che a pagine) e segue lo stato del fullscreen
        // tramite [navBarVisible], così con un tap immersivo sparisce insieme alle barre.
        ReaderBottomNavBar(
            visible = navBarVisible &&
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
    pageSpacing: Dp,
    padding: PaddingValues,
    initialPageIndex: Int,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
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
                modifier = Modifier.padding(padding),
            )
        }
        readingMode.isPaged -> {
            PagedReader(
                chapterKey = chapterKey,
                chapter = chapter,
                pages = pages,
                doubleTapZoomEnabled = doubleTapZoomEnabled,
                pageSpacing = pageSpacing,
                rightToLeft = readingMode.isRightToLeft,
                padding = padding,
                initialPageIndex = initialPageIndex,
                onPageVisible = onPageVisible,
                onToggleFullscreen = onToggleFullscreen,
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
                pageSpacing = pageSpacing,
                padding = padding,
                initialPageIndex = initialPageIndex,
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
    pageSpacing: Dp,
    padding: PaddingValues,
    initialPageIndex: Int,
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
    val zoomFlingDecay = remember { exponentialDecay<Float>() }
    val zoomFlingScope = rememberCoroutineScope()
    var zoomFlingJob by remember(chapterKey) { mutableStateOf<Job?>(null) }

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
                onPageVisible(reachedPageIndex, pages.size, allowCompletion)
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

        zoomFlingJob = zoomFlingScope.launch {
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
            verticalArrangement = Arrangement.spacedBy(pageSpacing),
        ) {
            item("reader-nav-top") {
                ReaderChapterNavigationRow(
                    previousChapter = previousChapter,
                    nextChapter = nextChapter,
                    onOpenPrevious = onOpenPrevious,
                    onOpenNext = onOpenNext,
                )
            }
            items(pages, key = { it.stableKey }) { page ->
                AsyncImage(
                    model = rememberReaderImageModel(page),
                    contentDescription = chapter.title,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            item("reader-nav-bottom") {
                ReaderChapterNavigationRow(
                    previousChapter = previousChapter,
                    nextChapter = nextChapter,
                    onOpenPrevious = onOpenPrevious,
                    onOpenNext = onOpenNext,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    chapterKey: String?,
    chapter: ReaderChapter,
    pages: List<ReaderPage>,
    doubleTapZoomEnabled: Boolean,
    pageSpacing: Dp,
    rightToLeft: Boolean,
    padding: PaddingValues,
    initialPageIndex: Int,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    // Solo le pagine reali: niente più "pagine di navigazione" all'inizio/fine. Il salto
    // al capitolo precedente/successivo vive nella barra fissa in basso (ReaderBottomNavBar),
    // così lo swipe resta dedicato alle pagine e l'indicatore "x/y" non si confonde col capitolo.
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    var hasMoved by remember(chapterKey) { mutableStateOf(false) }

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
                onPageVisible(pageIndex, pages.size, hasMoved)
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
            ) { index ->
                ZoomablePage(
                    page = pages[index],
                    contentDescription = chapter.title,
                    doubleTapZoomEnabled = doubleTapZoomEnabled,
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

@Composable
private fun ZoomablePage(
    page: ReaderPage,
    contentDescription: String,
    doubleTapZoomEnabled: Boolean,
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
        AsyncImage(
            model = rememberReaderImageModel(page),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Modello immagine per Coil. Le pagine locali sono semplici file; quelle remote
 * (streaming) portano il Referer della loro fonte, così l'hotlink protection dei
 * vari siti non blocca le immagini (prima ricevevano un Referer mangapill fisso).
 */
@Composable
private fun rememberReaderImageModel(page: ReaderPage): Any {
    val context = LocalContext.current
    return when (page) {
        is ReaderPage.Local -> page.file
        is ReaderPage.Remote -> remember(page.url, page.referer) {
            ImageRequest.Builder(context)
                .data(page.url)
                .setHeader("Referer", page.referer)
                .build()
        }
    }
}

private const val ReaderPageItemOffset = 1
private const val MinZoomFlingVelocityPxPerSecond = 120f
