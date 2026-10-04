package com.lorenzo.mangadownloader.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.lorenzo.mangadownloader.ui.reader.TallPageNormalizer
import java.io.FileInputStream
import okio.IOException
import okio.Path

private const val TallPageWebpCompressionEffort = 20

/** [ImageOps] su BitmapFactory/BitmapRegionDecoder: gli stessi decoder ed encoder di sempre. */
object AndroidImageOps : ImageOps {

    override fun readSize(path: Path): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), options)
        return ImageSize(options.outWidth, options.outHeight).takeIf { it.width > 0 && it.height > 0 }
    }

    // WEBP_LOSSLESS non esiste sulle API 26-29 supportate dall'app: lì si ripiega sul PNG.
    private val useWebp: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override val losslessExtension: String get() = if (useWebp) "webp" else "png"

    override fun writeStrips(source: Path, width: Int, rows: List<IntRange>, destinations: List<Path>) {
        // Per WEBP_LOSSLESS il valore regola lo sforzo CPU, non la qualità visiva: 20 conserva
        // gli stessi pixel con file un po' più grandi ma codifica molto prima.
        val (format, qualityOrEffort) = if (useWebp) {
            Bitmap.CompressFormat.WEBP_LOSSLESS to TallPageWebpCompressionEffort
        } else {
            Bitmap.CompressFormat.PNG to 100
        }
        FileInputStream(source.toFile()).use { inputStream ->
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(inputStream.fd, false)
                ?: throw IOException("Cannot open image decoder for $source")
            try {
                rows.forEachIndexed { index, range ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }
                    val bitmap = decoder.decodeRegion(Rect(0, range.first, width, range.last + 1), options)
                        ?: throw IOException("Cannot decode image part ${index + 1} of ${rows.size}")
                    try {
                        destinations[index].toFile().outputStream().buffered().use { output ->
                            if (!bitmap.compress(format, qualityOrEffort, output)) {
                                throw IOException("Cannot encode ${destinations[index].name}")
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                decoder.recycle()
            }
        }
    }
}

/** Il normalizzatore delle pagine alte con i decoder Android e il file system del dispositivo. */
val AndroidTallPageNormalizer: TallPageNormalizer = TallPageNormalizer(AndroidImageOps)

actual val platformImageOps: ImageOps get() = AndroidImageOps
