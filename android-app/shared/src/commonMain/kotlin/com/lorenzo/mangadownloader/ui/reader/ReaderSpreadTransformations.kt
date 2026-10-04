package com.lorenzo.mangadownloader.ui.reader

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Il ritaglio e la rotazione delle pagine doppie, fatti dove costano meno: dentro la pipeline
 * di Coil, subito dopo la decodifica.
 *
 * Farlo qui — invece che con giochi di layout o un secondo decoder — significa che l'immagine
 * che arriva a Compose ha già le proporzioni giuste, quindi `ContentScale` funziona senza
 * correzioni, e che il risultato entra nella cache in memoria con la sua chiave: le due metà
 * della stessa pagina restano due voci distinte, decodificate una volta sola a testa.
 *
 * Entrambe le trasformazioni sono innocue su una pagina normale: se l'immagine non ha le
 * proporzioni di una doppia, tornano l'originale intatto. Serve perché la decisione di
 * dividere si prende dai file locali, mentre la rotazione si applica all'intero capitolo,
 * pagine remote comprese, di cui le dimensioni si scoprono solo qui.
 */

/** Ritaglia la metà indicata di una pagina doppia. */
class SpreadHalfTransformation(private val half: PageHalf) : Transformation() {

    override val cacheKey: String = "spread-half-${half.name}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!isSpreadPage(input.pixelWidth, input.pixelHeight)) return input
        val halfWidth = input.pixelWidth / 2
        if (halfWidth <= 0) return input
        val left = if (half == PageHalf.LEFT) 0 else input.pixelWidth - halfWidth
        return cropBitmap(input, left, halfWidth)
    }
}

/**
 * Ruota di 90° le sole pagine doppie: si leggono girando il telefono. Il verso arriva
 * dall'ordine di lettura (vedi [SpreadRotation]), perché è lui a decidere quale facciata
 * finisce in alto e quindi in che direzione scorre la lettura. Le pagine normali passano
 * intatte, così la modalità si può tenere accesa per tutto il capitolo.
 */
class SpreadRotateTransformation(private val rotation: SpreadRotation) : Transformation() {

    override val cacheKey: String = "spread-rotate-${rotation.name}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!isSpreadPage(input.pixelWidth, input.pixelHeight)) return input
        return rotateBitmap(input, rotation.degrees)
    }
}

/** La fascia verticale di [input] larga [width] a partire da [left], a tutta altezza. */
internal expect fun cropBitmap(input: Bitmap, left: Int, width: Int): Bitmap

/** [input] ruotato di [degrees] (±90), con larghezza e altezza scambiate. */
internal expect fun rotateBitmap(input: Bitmap, degrees: Float): Bitmap

internal expect val Bitmap.pixelWidth: Int

internal expect val Bitmap.pixelHeight: Int
