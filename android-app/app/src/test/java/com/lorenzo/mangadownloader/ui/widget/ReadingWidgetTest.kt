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
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun event(title: String, chapter: String, at: Long, seen: Boolean = false) = FavoriteUpdateEvent(
        title = title,
        sourceId = MangaSourceIds.MANGAPILL,
        mangaUrl = "https://mangapill.com/manga/${title.hashCode()}",
        chapterLabel = chapter,
        timestampMillis = at,
        seen = seen,
    )

    @Test
    fun data_usesHomeResumeRules_andNewestUpdatesFirst() {
        val feed = listOf(
            event("A", "Capitolo 1", at = 1, seen = true),
            event("B", "Capitolo 2", at = 4),
            event("C", "Capitolo 3", at = 3),
            event("D", "Capitolo 4", at = 2),
        )

        val data = buildReadingWidgetData(
            library = emptyList(),
            memory = mapOf("streaming:abc" to streamingRead),
            feed = feed,
        )

        assertEquals("Berserk", data.resume?.seriesTitle)
        assertEquals("Pagina 8 di 40", data.resume?.progressLabel())
        assertEquals(listOf("B", "C", "D"), data.updates.map { it.title })
        assertEquals(3, data.unseenCount)
    }

    @Test
    fun data_withoutReopenableReading_hasNoResume() {
        val data = buildReadingWidgetData(
            library = emptyList(),
            // Record storico senza coordinate: la Home non lo offre, il widget nemmeno.
            memory = mapOf("streaming:abc" to streamingRead.copy(chapterUrl = "")),
            feed = emptyList(),
        )
        assertNull(data.resume)
    }

    @Test
    fun tallWidget_showsResumeAndUpdates() {
        ReadingMemoryStore(prefs()).persist(mapOf("streaming:abc" to streamingRead))
        FavoriteUpdatesFeedStore(prefs()).write(listOf(event("One Piece", "Capitolo 1130", at = 5)))

        val texts = render("reading-widget-tall", DpSize(260.dp, 260.dp))

        assertTrue(texts.toString(), "Berserk" in texts)
        assertTrue(texts.toString(), "Capitolo 12 · Pagina 8 di 40" in texts)
        assertTrue(texts.toString(), "Nuovi capitoli · 1" in texts)
        assertTrue(texts.toString(), "One Piece" in texts)
    }

    @Test
    fun compactWidget_showsOnlyResume() {
        FavoriteUpdatesFeedStore(prefs()).write(listOf(event("One Piece", "Capitolo 1130", at = 5)))

        val texts = render("reading-widget-compact", DpSize(250.dp, 120.dp))

        assertTrue(texts.toString(), "Nessuna lettura in corso" in texts)
        assertFalse(texts.toString(), "One Piece" in texts)
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
