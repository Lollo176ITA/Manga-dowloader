package com.lorenzo.mangadownloader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

// Geometria dello zoom, comune al lettore verticale e a quello a pagine. Gli offset sono
// traslazioni del contenuto rispetto al centro del viewport, come in `graphicsLayer`.

/**
 * Offset dopo uno zoom di [zoomChange] attorno a [focus] (vedi [focusOf]), più l'eventuale
 * [pan]: il punto sotto le dita resta dov'è.
 */
internal fun zoomedOffset(
    offset: Offset,
    zoomChange: Float,
    focus: Offset,
    pan: Offset = Offset.Zero,
): Offset = offset * zoomChange + focus * (1f - zoomChange) + pan

/** Offset ammesso a [scale]: il contenuto ingrandito non lascia scoperti i bordi del viewport. */
internal fun clampZoomOffset(offset: Offset, scale: Float, viewport: IntSize): Offset {
    val maxX = ((viewport.width * (scale - 1f)) / 2f).coerceAtLeast(0f)
    val maxY = ((viewport.height * (scale - 1f)) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** [position] (coordinate del viewport) rispetto al suo centro. */
internal fun IntSize.focusOf(position: Offset): Offset = position - Offset(width / 2f, height / 2f)
