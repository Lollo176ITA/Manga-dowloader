package com.lorenzo.mangadownloader.ui.reader

import android.graphics.Matrix
import coil3.Bitmap

internal actual fun cropBitmap(input: Bitmap, left: Int, width: Int): Bitmap =
    Bitmap.createBitmap(input, left, 0, width, input.height)

internal actual fun rotateBitmap(input: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
}

internal actual val Bitmap.pixelWidth: Int get() = width

internal actual val Bitmap.pixelHeight: Int get() = height
