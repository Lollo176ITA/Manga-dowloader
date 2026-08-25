package com.lorenzo.mangadownloader

import android.graphics.Matrix
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
        if (!isSpreadPage(input.width, input.height)) return input
        val halfWidth = input.width / 2
        if (halfWidth <= 0) return input
        val left = if (half == PageHalf.LEFT) 0 else input.width - halfWidth
        return Bitmap.createBitmap(input, left, 0, halfWidth, input.height)
    }
}

/**
 * Ruota di 90° in senso orario le sole pagine doppie: la facciata destra finisce in basso,
 * quindi lo schermo si legge inclinando il telefono verso sinistra. Le pagine normali passano
 * intatte, così la modalità si può tenere accesa per tutto il capitolo.
 */
object SpreadRotateTransformation : Transformation() {

    override val cacheKey: String = "spread-rotate-90"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!isSpreadPage(input.width, input.height)) return input
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}
