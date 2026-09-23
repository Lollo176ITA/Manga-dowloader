package com.lorenzo.mangadownloader.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dati del widget (stesse regole della Home) e rendering reale delle RemoteViews. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReadingWidgetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    private val streamingRead = ReadChapterMemory(
        seriesKey = "streaming:berserk",
        seriesTitle = "Berserk",
        chapterLabel = "Capitolo 12",
        pagesRead = 8,
        pageCount = 40,
        isRead = false,
        lastReadAtMillis = 2_000L,
        sourceId = MangaSourceIds.MANGAPILL,
        mangaUrl = "https://mangapill.com/manga/3/berserk",
        chapterUrl = "https://mangapill.com/chapters/3-12/berserk-chapter-12",
    )

    @Test
    fun resume_usesHomeRules_andSubtitleShowsWhereYouStopped() {
        val resume = readingWidgetResume(emptyList(), mapOf("streaming:abc" to streamingRead))

        assertEquals("Berserk", resume?.seriesTitle)
        assertEquals("Capitolo 12 · pagina 8 di 40", resume?.widgetSubtitle())
    }

    @Test
    fun resume_withoutReopenableReading_isNull() {
        // Record storico senza coordinate: la Home non lo offre, il widget nemmeno.
        assertNull(readingWidgetResume(emptyList(), mapOf("streaming:abc" to streamingRead.copy(chapterUrl = ""))))
    }

    @Test
    fun strip4x1_showsOnlyTheReading() {
        ReadingMemoryStore(prefs()).persist(mapOf("streaming:abc" to streamingRead))

        val texts = render("reading-widget-4x1", DpSize(300.dp, 64.dp))

        assertEquals(listOf("Berserk", "Capitolo 12 · pagina 8 di 40"), texts)
    }

    @Test
    fun strip4x1_withoutReadings_invitesToOpenTheApp() {
        val texts = render("reading-widget-4x1-empty", DpSize(300.dp, 64.dp))

        assertEquals(listOf("Continua a leggere", "Nessuna lettura in corso"), texts)
    }

    @Test
    fun strip4x1_withCover_fitsALowCell() {
        // Copertina locale (file:// ) al posto dell'URL della fonte: niente rete nei test.
        val coverFile = File(context.cacheDir, "cover.png")
        Bitmap.createBitmap(90, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFB71C1C.toInt()) }
            .let { bitmap -> coverFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        ReadingMemoryStore(prefs()).persist(
            mapOf("streaming:abc" to streamingRead.copy(coverUrl = coverFile.toURI().toString())),
        )

        val texts = render("reading-widget-4x1-cover", DpSize(300.dp, 52.dp))

        assertEquals(listOf("Berserk", "Capitolo 12 · pagina 8 di 40"), texts)
    }

    /** Compone il widget, applica le RemoteViews a una view vera e ne raccoglie i testi. */
    private fun render(name: String, size: DpSize): List<String> {
        val remoteViews = runBlocking { ReadingWidget().compose(context, size = size) }
        val parent = FrameLayout(context)
        val view = remoteViews.apply(context, parent)
        val density = context.resources.displayMetrics.density
        val width = (size.width.value * density).toInt()
        val height = (size.height.value * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        System.getenv("SCREENSHOT_DIR")?.takeIf(String::isNotBlank)?.let { dir ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFF607D8B.toInt()) // "sfondo della Home" dietro al widget
            view.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return collectTexts(view)
    }

    private fun collectTexts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { collectTexts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
