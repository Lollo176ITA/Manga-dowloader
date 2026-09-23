package com.lorenzo.mangadownloader.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
import com.lorenzo.mangadownloader.FavoriteUpdateNotifier
import com.lorenzo.mangadownloader.MainActivity
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.sharedLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget "Continua a leggere": l'ultima lettura in corso (scaricata o in streaming) e, se c'è
 * spazio, gli ultimi capitoli nuovi dei preferiti. Ogni tocco apre l'app nel punto giusto,
 * con gli stessi extra delle notifiche dove esistono già.
 */
class ReadingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, WITH_UPDATES))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = withContext(Dispatchers.IO) { loadData(context) }
        val cover = data.resume?.coverModel?.let { loadCover(context, it) }
        provideContent {
            GlanceTheme {
                ReadingWidgetContent(context, data, cover)
            }
        }
    }

    private fun loadData(context: Context): ReadingWidgetData {
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        return buildReadingWidgetData(
            library = runCatching { sharedLibraryRepository(context).scanLibrary() }.getOrDefault(emptyList()),
            memory = ReadingMemoryStore(prefs).read(),
            feed = FavoriteUpdatesFeedStore(prefs).read(),
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

        private val COMPACT = DpSize(180.dp, 110.dp)
        private val WITH_UPDATES = DpSize(180.dp, 220.dp)
        private const val COVER_WIDTH_PX = 144
        private const val COVER_HEIGHT_PX = 204

        /** Ridisegna tutti i widget piazzati. Best-effort: un widget vecchio non è un errore. */
        suspend fun updateAll(context: Context) {
            try {
                ReadingWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        internal fun showsUpdates(height: androidx.compose.ui.unit.Dp): Boolean = height >= WITH_UPDATES.height
    }
}

@Composable
private fun ReadingWidgetContent(context: Context, data: ReadingWidgetData, cover: Bitmap?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp),
    ) {
        ResumeSection(context, data, cover)
        if (ReadingWidget.showsUpdates(LocalSize.current.height)) {
            Spacer(modifier = GlanceModifier.height(12.dp))
            UpdatesSection(context, data)
        }
    }
}

@Composable
private fun ResumeSection(context: Context, data: ReadingWidgetData, cover: Bitmap?) {
    val resume = data.resume
    val intent = appIntent(context).putExtra(ReadingWidget.EXTRA_RESUME_READING, resume != null)
    Row(
        modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(56.dp, 80.dp).cornerRadius(10.dp),
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "Continua a leggere",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
            if (resume == null) {
                Text(
                    text = "Nessuna lettura in corso",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "Tocca per aprire l'app",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            } else {
                Text(
                    text = resume.seriesTitle,
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = listOfNotNull(resume.chapterLabel, resume.progressLabel()).joinToString(" · "),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun UpdatesSection(context: Context, data: ReadingWidgetData) {
    val feedIntent = appIntent(context).putExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED, true)
    Text(
        text = if (data.unseenCount > 0) "Nuovi capitoli · ${data.unseenCount}" else "Nuovi capitoli",
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(feedIntent)),
    )
    if (data.updates.isEmpty()) {
        Text(
            text = "Nessun capitolo nuovo dai preferiti",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
        return
    }
    data.updates.forEach { event ->
        Spacer(modifier = GlanceModifier.height(6.dp))
        UpdateRow(context, event)
    }
}

@Composable
private fun UpdateRow(context: Context, event: FavoriteUpdateEvent) {
    val intent = appIntent(context)
        .putExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_SOURCE_ID, event.sourceId)
        .putExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_TITLE, event.title)
        .putExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL, event.mangaUrl)
        .putExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_COVER, event.coverUrl)
    Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(intent))) {
        Text(
            text = event.title,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = if (event.seen) FontWeight.Normal else FontWeight.Bold,
            ),
        )
        Text(
            text = event.chapterLabel,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

private fun appIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
