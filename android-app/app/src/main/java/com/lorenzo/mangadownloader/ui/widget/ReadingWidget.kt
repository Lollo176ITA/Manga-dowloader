package com.lorenzo.mangadownloader.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.lorenzo.mangadownloader.MainActivity
import com.lorenzo.mangadownloader.R
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.reading.ResumeReadingItem
import com.lorenzo.mangadownloader.sharedLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget "Continua a leggere": una striscia 4×1 con l'ultima lettura in corso (scaricata o in
 * streaming), la copertina e il punto in cui ci si era fermati. Un tocco la riapre.
 */
class ReadingWidget : GlanceAppWidget() {

    // Una sola disposizione, che si adatta alla larghezza: la striscia si allarga, non si alza.
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val resume = withContext(Dispatchers.IO) { loadResume(context) }
        val cover = resume?.coverModel?.let { loadCover(context, it) }
        provideContent {
            GlanceTheme {
                ReadingWidgetStrip(context, resume, cover)
            }
        }
    }

    private fun loadResume(context: Context): ResumeReadingItem? {
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        return readingWidgetResume(
            library = runCatching { sharedLibraryRepository(context).scanLibrary() }.getOrDefault(emptyList()),
            memory = ReadingMemoryStore(prefs).read(),
        )
    }

    /** Copertina piccola e software (i bitmap hardware non passano nelle RemoteViews). */
    private suspend fun loadCover(context: Context, model: Any): Bitmap? = try {
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(COVER_WIDTH_PX, COVER_HEIGHT_PX)
            .allowHardware(false)
            .build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Extra dell'intent: apri l'app e riprendi l'ultima lettura. */
        const val EXTRA_RESUME_READING = "widget_resume_reading"

        private const val COVER_WIDTH_PX = 96
        private const val COVER_HEIGHT_PX = 136

        /** Ridisegna tutti i widget piazzati. Best-effort: un widget vecchio non è un errore. */
        suspend fun updateAll(context: Context) {
            try {
                ReadingWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun ReadingWidgetStrip(context: Context, resume: ResumeReadingItem?, cover: Bitmap?) {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(ReadingWidget.EXTRA_RESUME_READING, resume != null)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(32.dp, 46.dp).cornerRadius(6.dp),
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = resume?.seriesTitle ?: "Continua a leggere",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = resume?.widgetSubtitle() ?: "Nessuna lettura in corso",
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Image(
            provider = ImageProvider(R.drawable.ic_widget_play),
            contentDescription = if (resume != null) "Riprendi la lettura" else "Apri l'app",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(28.dp),
        )
    }
}
