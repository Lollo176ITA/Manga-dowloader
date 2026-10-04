package com.lorenzo.mangadownloader.ui.reader

import coil3.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

internal actual fun cropBitmap(input: Bitmap, left: Int, width: Int): Bitmap {
    val output = Bitmap()
    output.allocN32Pixels(width, input.height)
    Canvas(output).drawImageRect(
        Image.makeFromBitmap(input),
        Rect.makeXYWH(left.toFloat(), 0f, width.toFloat(), input.height.toFloat()),
        Rect.makeWH(width.toFloat(), input.height.toFloat()),
    )
    output.setImmutable()
    return output
}

internal actual fun rotateBitmap(input: Bitmap, degrees: Float): Bitmap {
    val output = Bitmap()
    output.allocN32Pixels(input.height, input.width)
    Canvas(output).apply {
        // Il centro dell'uscita coincide col centro dell'immagine ruotata.
        translate(input.height / 2f, input.width / 2f)
        rotate(degrees)
        drawImage(Image.makeFromBitmap(input), -input.width / 2f, -input.height / 2f)
    }
    output.setImmutable()
    return output
}

internal actual val Bitmap.pixelWidth: Int get() = width

internal actual val Bitmap.pixelHeight: Int get() = height
