package com.lorenzo.mangadownloader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomTest {

    private val viewport = IntSize(1000, 2000)

    @Test
    fun zoomedOffset_keepsTheFocusPointStill() {
        // Doppio tap a 2x vicino all'angolo in alto a sinistra: il contenuto si sposta verso
        // destra/basso di quanto basta a lasciare il punto toccato sotto il dito.
        val focus = viewport.focusOf(Offset(100f, 200f))
        assertEquals(Offset(-400f, -800f), focus)
        assertEquals(Offset(400f, 800f), zoomedOffset(Offset.Zero, zoomChange = 2f, focus = focus))
    }

    @Test
    fun zoomedOffset_addsPan() {
        assertEquals(
            Offset(15f, -5f),
            zoomedOffset(Offset(10f, -10f), zoomChange = 1f, focus = Offset(300f, 300f), pan = Offset(5f, 5f)),
        )
    }

    @Test
    fun clampZoomOffset_limitsToTheZoomedOverflow() {
        // A 2x il contenuto sborda di metà viewport per lato.
        assertEquals(Offset(500f, -1000f), clampZoomOffset(Offset(900f, -5000f), scale = 2f, viewport = viewport))
        assertEquals(Offset(20f, 30f), clampZoomOffset(Offset(20f, 30f), scale = 2f, viewport = viewport))
        // A scala 1 non c'è nulla da spostare.
        assertEquals(Offset.Zero, clampZoomOffset(Offset(20f, 30f), scale = 1f, viewport = viewport))
    }
}
