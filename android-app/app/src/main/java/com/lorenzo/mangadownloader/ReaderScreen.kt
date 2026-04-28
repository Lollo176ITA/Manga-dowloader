package com.lorenzo.mangadownloader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ReaderScreen(
    chapter: DownloadedChapter?,
    previousChapter: DownloadedChapter?,
    nextChapter: DownloadedChapter?,
    pages: List<File>,
    isLoading: Boolean,
    padding: PaddingValues,
    autoReaderSpeed: AutoReaderSpeed,
    initialPageIndex: Int,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
) {
    val view = LocalView.current

    DisposableEffect(autoReaderSpeed) {
        val window = (view.context as? Activity)?.window
        if (autoReaderSpeed != AutoReaderSpeed.OFF) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

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
            padding = padding,
            autoReaderSpeed = autoReaderSpeed,
            initialPageIndex = initialPageIndex,
            onOpenPrevious = onOpenPrevious,
            onOpenNext = onOpenNext,
            onPageVisible = onPageVisible,
        )
    }
}

@Composable
private fun ReaderContent(
    chapterKey: String?,
    chapter: DownloadedChapter?,
    previousChapter: DownloadedChapter?,
    nextChapter: DownloadedChapter?,
    pages: List<File>,
    isLoading: Boolean,
    padding: PaddingValues,
    autoReaderSpeed: AutoReaderSpeed,
    initialPageIndex: Int,
    onOpenPrevious: () -> Unit,
    onOpenNext: () -> Unit,
    onPageVisible: (pageIndex: Int, pageCount: Int, allowCompletion: Boolean) -> Unit,
) {
    val minScale = 1f
    val maxScale = 4f
    var readerScale by remember(chapterKey) { mutableStateOf(minScale) }
    var readerOffsetX by remember(chapterKey) { mutableStateOf(0f) }
    var readerOffsetY by remember(chapterKey) { mutableStateOf(0f) }
    var viewportSize by remember(chapterKey) { mutableStateOf(IntSize.Zero) }
    var restoreComplete by remember(chapterKey) { mutableStateOf(false) }
    var hasReaderMovedAfterRestore by remember(chapterKey) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(chapterKey, pages.size) {
        if (chapter == null || pages.isEmpty()) return@LaunchedEffect

        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (restoreComplete && scrolling) {
                    hasReaderMovedAfterRestore = true
                }
            }
    }

    LaunchedEffect(chapterKey, pages.size, initialPageIndex) {
        if (chapter == null || pages.isEmpty()) return@LaunchedEffect

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

    LaunchedEffect(autoReaderSpeed, chapterKey) {
        if (autoReaderSpeed == AutoReaderSpeed.OFF || chapter == null) return@LaunchedEffect
        val pauseMs = autoReaderSpeed.pauseSeconds * 1000L
        while (true) {
            delay(pauseMs)
            val info = listState.layoutInfo
            val topVisible = info.visibleItemsInfo.firstOrNull { item ->
                val k = item.key
                k is String && k != "reader-nav-top" && k != "reader-nav-bottom"
            } ?: continue
            val targetIndex = topVisible.index + 1
            if (targetIndex >= info.totalItemsCount) break
            listState.animateScrollToItem(targetIndex)
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

    fun applyOneFingerPan(scale: Float, panChange: Offset) {
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
            EmptyStateText(
                text = "Nessuna pagina disponibile",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(if (readerScale > minScale) Color.Black else Color.Transparent)
                    .clipToBounds()
                    .pointerInput(chapterKey) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.size >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        val nextScale = (readerScale * zoomChange)
                                            .coerceIn(minScale, maxScale)
                                        val effectiveZoomChange = nextScale / readerScale
                                        if (nextScale <= minScale) {
                                            readerScale = minScale
                                            readerOffsetX = 0f
                                            readerOffsetY = 0f
                                        } else {
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
                                    if (panChange != Offset.Zero) {
                                        applyOneFingerPan(readerScale, panChange)
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(chapterKey) {
                        detectTapGestures(
                            onDoubleTap = { tapOffset ->
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item("reader-nav-top") {
                        ReaderChapterNavigationRow(
                            previousChapter = previousChapter,
                            nextChapter = nextChapter,
                            onOpenPrevious = onOpenPrevious,
                            onOpenNext = onOpenNext,
                        )
                    }
                    items(pages, key = { it.absolutePath }) { page ->
                        AsyncImage(
                            model = page,
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
    }
}

private const val ReaderPageItemOffset = 1
