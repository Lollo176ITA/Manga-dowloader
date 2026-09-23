package com.lorenzo.mangadownloader.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import androidx.glance.material3.ColorProviders
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
import com.lorenzo.mangadownloader.app.AppSettings
import com.lorenzo.mangadownloader.data.model.ThemeMode
import com.lorenzo.mangadownloader.data.store.ReadingMemoryStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.reading.ResumeReadingItem
import com.lorenzo.mangadownloader.sharedLibraryRepository
import com.lorenzo.mangadownloader.ui.theme.AppDarkColorScheme
import com.lorenzo.mangadownloader.ui.theme.AppLightColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget "Continua a leggere": la card "Riprendi" della Home portata sulla Home del telefono,
 * con gli stessi colori (anche Material You e tema chiaro/scuro scelti nell'app), la copertina
 * e il punto in cui ci si era fermati. Un tocco riapre la lettura.
 *
 * Nato 4×1 come la card compatta; allungato a 2 righe diventa la card completa. Su una riga
 * molto bassa i testi scendono a due righe per non tagliarsi.
 */
class ReadingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(LOW, COMPACT, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (resume, settings) = withContext(Dispatchers.IO) { loadData(context) }
        val cover = resume?.coverModel?.let { loadCover(context, it) }
        val schemes = widgetColorSchemes(context, settings)
        provideContent {
            GlanceTheme(colors = schemes.toColorProviders()) {
                ReadingWidgetCard(context, resume, cover, schemes)
            }
        }
    }

    private fun loadData(context: Context): Pair<ResumeReadingItem?, AppSettings> {
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val resume = readingWidgetResume(
            library = runCatching { sharedLibraryRepository(context).scanLibrary() }.getOrDefault(emptyList()),
            memory = ReadingMemoryStore(prefs).read(),
        )
        return resume to SettingsStore(prefs).read()
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

        internal val LOW = DpSize(250.dp, 40.dp)
        internal val COMPACT = DpSize(250.dp, 76.dp)
        internal val FULL = DpSize(250.dp, 120.dp)
        private const val COVER_WIDTH_PX = 192
        private const val COVER_HEIGHT_PX = 276

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

/**
 * I due schemi di colore dell'app per il widget, con le stesse regole di
 * `MangaDownloaderTheme`: Material You se attivo, tema chiaro/scuro forzato se scelto.
 */
internal data class WidgetColorSchemes(val light: ColorScheme, val dark: ColorScheme) {
    fun toColorProviders() = ColorProviders(light = light, dark = dark)
}

internal fun widgetColorSchemes(context: Context, settings: AppSettings): WidgetColorSchemes {
    val dynamic = settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val light = if (dynamic) dynamicLightColorScheme(context) else AppLightColorScheme
    val dark = if (dynamic) dynamicDarkColorScheme(context) else AppDarkColorScheme
    return when (settings.themeMode) {
        ThemeMode.AUTO -> WidgetColorSchemes(light, dark)
        ThemeMode.LIGHT -> WidgetColorSchemes(light, light)
        ThemeMode.DARK -> WidgetColorSchemes(dark, dark)
    }
}

/** Misure della card per ciascuna taglia: le stesse della card della Home dove ci stanno. */
private data class CardMetrics(
    val padding: Dp,
    val gap: Dp,
    val coverWidth: Dp,
    val coverHeight: Dp,
    val iconSize: Dp,
)

private val LowMetrics = CardMetrics(padding = 8.dp, gap = 10.dp, coverWidth = 28.dp, coverHeight = 40.dp, iconSize = 28.dp)
private val CompactMetrics = CardMetrics(padding = 12.dp, gap = 12.dp, coverWidth = 40.dp, coverHeight = 56.dp, iconSize = 32.dp)
private val FullMetrics = CardMetrics(padding = 16.dp, gap = 14.dp, coverWidth = 64.dp, coverHeight = 92.dp, iconSize = 40.dp)

@Composable
private fun ReadingWidgetCard(
    context: Context,
    resume: ResumeReadingItem?,
    cover: Bitmap?,
    schemes: WidgetColorSchemes,
) {
    val height = LocalSize.current.height
    val full = height >= ReadingWidget.FULL.height
    val low = height < ReadingWidget.COMPACT.height
    val metrics = when {
        full -> FullMetrics
        low -> LowMetrics
        else -> CompactMetrics
    }
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(ReadingWidget.EXTRA_RESUME_READING, resume != null)
    val onContainer = GlanceTheme.colors.onPrimaryContainer
    val title = resume?.seriesTitle ?: "Continua a leggere"
    val chapter = resume?.chapterLabel ?: "Nessuna lettura in corso"
    val pageLabel = resume?.widgetPageLabel()

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(20.dp)
            .padding(horizontal = metrics.padding, vertical = if (low) 4.dp else metrics.padding)
            .clickable(actionStartActivity(intent)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resume != null) {
            WidgetCover(cover, title, metrics)
            Spacer(modifier = GlanceModifier.width(metrics.gap))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (full) {
                Text(
                    text = "RIPRENDI",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
            }
            Text(
                text = title,
                maxLines = 1,
                style = TextStyle(color = onContainer, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            )
            if (low) {
                // Riga bassa: capitolo e pagina insieme, altrimenti la terza riga si taglia.
                Text(
                    text = listOfNotNull(chapter, pageLabel).joinToString(" · "),
                    maxLines = 1,
                    style = TextStyle(color = onContainer, fontSize = 13.sp),
                )
            } else {
                Text(
                    text = chapter,
                    maxLines = 1,
                    style = TextStyle(color = onContainer, fontSize = 14.sp),
                )
                if (pageLabel != null) {
                    if (full) {
                        Spacer(modifier = GlanceModifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = resume?.readProgress() ?: 0f,
                            // Senza altezza esplicita la ProgressBar di sistema è molto più spessa
                            // di quella della Home e spinge fuori l'ultima riga.
                            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                            color = onContainer,
                            backgroundColor = ColorProvider(
                                day = schemes.light.onPrimaryContainer.copy(alpha = 0.25f),
                                night = schemes.dark.onPrimaryContainer.copy(alpha = 0.25f),
                            ),
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                    }
                    Text(
                        text = pageLabel,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
                    )
                }
            }
        }
        Spacer(modifier = GlanceModifier.width(metrics.gap))
        Image(
            provider = ImageProvider(R.drawable.ic_widget_play),
            contentDescription = if (resume != null) "Riprendi la lettura" else "Apri l'app",
            colorFilter = ColorFilter.tint(onContainer),
            modifier = GlanceModifier.size(metrics.iconSize),
        )
    }
}

/** Copertina, o l'iniziale del titolo su fondo neutro come `CoverImage` quando manca. */
@Composable
private fun WidgetCover(cover: Bitmap?, title: String, metrics: CardMetrics) {
    val modifier = GlanceModifier.size(metrics.coverWidth, metrics.coverHeight).cornerRadius(12.dp)
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(GlanceTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}
