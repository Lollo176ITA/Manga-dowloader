package com.lorenzo.mangadownloader.perftest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serie finte per Libreria e reader, così quegli scenari non dipendono dalla rete.
 * Nomi senza spazi: `UiDevice.executeShellCommand` spezza gli argomenti sugli spazi e non
 * gestisce le virgolette.
 */
object BenchData {
    const val TARGET_PACKAGE = "com.lorenzo.mangadownloader.benchmark"
    const val READER_SERIES = "Bench01"
    // La scansione ricava il titolo dal nome file chapter_001.cbz: "Capitolo 1".
    const val READER_CHAPTER = "Capitolo 1"

    private val SERIES = (1..12).map { "Bench%02d".format(it) }
    private const val READER_PAGES = 20
    private const val FILLER_PAGES = 2
    private const val PAGE_WIDTH = 1080
    private const val PAGE_HEIGHT = 3000
    private const val LIBRARY_DIR =
        "/sdcard/Android/data/$TARGET_PACKAGE/files/Download/MangaDownloader"

    /**
     * File delle serie (percorso relativo alla libreria → contenuto), generati una volta per
     * processo e tenuti in memoria (~10 MB): l'app di test non riesce a crearsi una cartella
     * esterna su Android 11+, e la shell non può leggere quella privata.
     */
    val files: Map<String, ByteArray> by lazy {
        buildMap {
            SERIES.forEachIndexed { index, name ->
                put("$name/cover.jpg", jpeg(600, 900, index, name))
                val pages = if (name == READER_SERIES) READER_PAGES else FILLER_PAGES
                put("$name/chapter_001.cbz", chapter(index, pages))
            }
        }
    }

    fun libraryDirExists(device: UiDevice): Boolean =
        device.executeShellCommand("ls -d $LIBRARY_DIR").trim() == LIBRARY_DIR

    /** Scrive le serie nella libreria dell'app, che deve averla già creata (vedi AppSetup.resetApp). */
    fun install(device: UiDevice) {
        SERIES.forEach { name -> device.executeShellCommand("mkdir -p $LIBRARY_DIR/$name") }
        files.forEach { (path, bytes) -> pushFile("$LIBRARY_DIR/$path", bytes) }
    }

    /** Passa i byte sullo stdin di `dd`, che gira come shell e può scrivere nella cartella dell'app. */
    private fun pushFile(path: String, bytes: ByteArray) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val (stdout, stdin) = automation.executeShellCommandRw("dd of=$path")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        // Leggere fino in fondo aspetta che dd abbia finito di scrivere.
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    private fun chapter(seed: Int, pages: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            repeat(pages) { page ->
                zip.putNextEntry(ZipEntry("%03d.jpg".format(page + 1)))
                zip.write(jpeg(PAGE_WIDTH, PAGE_HEIGHT, seed + page, "${page + 1}"))
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun jpeg(width: Int, height: Int, seed: Int, label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((seed * 37f) % 360f, 0.35f, 0.95f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = width / 6f
            textAlign = Paint.Align.CENTER
        }
        // Qualche riga orizzontale: un'immagine a tinta unita comprime in modo irrealistico.
        for (y in 0 until height step 120) {
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 8f, paint)
        }
        canvas.drawText(label, width / 2f, height / 2f, paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
