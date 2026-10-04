package com.lorenzo.mangadownloader

import com.lorenzo.mangadownloader.domain.updates.FavoriteUpdatesChecker
import com.lorenzo.mangadownloader.domain.updates.NewChapterNotifier
import com.lorenzo.mangadownloader.platform.currentTimeMillis
import com.lorenzo.mangadownloader.data.store.edit
import com.lorenzo.mangadownloader.platform.settings
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.SettingsStore
import java.util.concurrent.TimeUnit

/** Il controllo dei nuovi capitoli dei preferiti ([FavoriteUpdatesChecker]) via WorkManager. */
class FavoriteUpdatesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        FavoriteUpdatesChecker(
            prefs = applicationContext.settings(SettingsStore.PREFS_NAME),
            registry = sharedSourceRegistry(applicationContext),
            aniListClient = AniListClient(SharedHttpClient.ktor(applicationContext)),
            notifier = FavoriteUpdateNotifier(applicationContext),
        ).run()
        return Result.success()
    }
}

/**
 * Invio delle notifiche "nuovo capitolo". Canale dedicato e gruppo con riepilogo: il tap
 * sulla singola apre direttamente il manga, il tap sul riepilogo apre il feed Aggiornamenti
 * (extras gestiti da MainActivity).
 */
class FavoriteUpdateNotifier(private val context: Context) : NewChapterNotifier {

    /**
     * [seriesKey] è l'identità della serie: l'id della notifica ci si basa perché la stessa
     * serie ritrovata su un mirror diverso deve **aggiornare** la sua notifica, non aprirne
     * una seconda accanto.
     */
    override fun notifyNewChapter(favorite: FavoriteManga, seriesKey: String, chapterLabel: String) {
        if (!canPostNotifications()) {
            return
        }
        ensureChannel()
        val notificationId = seriesKey.hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_manga)
            .setContentTitle(favorite.title)
            .setContentText("Nuovo capitolo disponibile: $chapterLabel")
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(openMangaIntent(favorite, notificationId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permesso revocato tra il controllo e l'invio: ignora.
        }
    }

    /**
     * Riepilogo del gruppo: con più capitoli usciti il pannello mostra una voce compatta
     * ("3 nuovi capitoli") che raccoglie le singole. Tap → feed Aggiornamenti in-app.
     */
    override fun notifySummary(unseenEvents: List<FavoriteUpdateEvent>) {
        if (unseenEvents.size < 2 || !canPostNotifications()) {
            return
        }
        ensureChannel()
        val summaryTitle = "${unseenEvents.size} nuovi capitoli"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(summaryTitle)
        unseenEvents.take(6).forEach { style.addLine("${it.title} — ${it.chapterLabel}") }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_manga)
            .setContentTitle(summaryTitle)
            .setContentText(unseenEvents.joinToString(", ") { it.title })
            .setStyle(style)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openUpdatesFeedIntent())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permesso revocato tra il controllo e l'invio: ignora.
        }
    }

    /** Tap sulla singola notifica: extras per aprire direttamente il dettaglio del manga. */
    private fun openMangaIntent(favorite: FavoriteManga, requestCode: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.putExtra(EXTRA_OPEN_MANGA_SOURCE_ID, favorite.sourceId)
        launchIntent.putExtra(EXTRA_OPEN_MANGA_TITLE, favorite.title)
        launchIntent.putExtra(EXTRA_OPEN_MANGA_URL, favorite.mangaUrl)
        launchIntent.putExtra(EXTRA_OPEN_MANGA_COVER, favorite.coverUrl)
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Tap sul riepilogo: extra per aprire il feed Aggiornamenti. */
    private fun openUpdatesFeedIntent(): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.putExtra(EXTRA_OPEN_UPDATES_FEED, true)
        return PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Nuovi capitoli preferiti",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Avvisi quando esce un nuovo capitolo di un manga che segui."
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "favorite_new_chapters"
        private const val GROUP_KEY = "favorite_new_chapters_group"

        // ID stabile e fuori dal range tipico degli hashCode dei manga: il riepilogo
        // del gruppo non deve mai sovrascrivere una notifica singola.
        private const val SUMMARY_NOTIFICATION_ID = 920_001

        const val EXTRA_OPEN_MANGA_SOURCE_ID = "notif_open_manga_source_id"
        const val EXTRA_OPEN_MANGA_TITLE = "notif_open_manga_title"
        const val EXTRA_OPEN_MANGA_URL = "notif_open_manga_url"
        const val EXTRA_OPEN_MANGA_COVER = "notif_open_manga_cover"
        const val EXTRA_OPEN_UPDATES_FEED = "notif_open_updates_feed"
    }
}

/** Programmazione del controllo periodico dei preferiti via WorkManager. */
object FavoriteUpdatesScheduler {
    private const val PERIODIC_WORK_NAME = "favorite-updates-periodic"
    private const val ONE_SHOT_WORK_NAME = "favorite-updates-now"
    private const val KEY_LAST_RUN_AT = "favorite_updates_last_run_at"

    // All'apertura dell'app si fa al massimo un controllo immediato ogni 6h, per non
    // martellare la rete quando l'utente apre/chiude spesso.
    private const val APP_OPEN_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) schedulePeriodic(context) else cancel(context)
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(ONE_SHOT_WORK_NAME)
    }

    /**
     * All'avvio app (se le notifiche sono attive): assicura lo schedule periodico e lancia un
     * controllo immediato, ma non più di una volta ogni [APP_OPEN_MIN_INTERVAL_MS].
     */
    fun onAppStart(context: Context, nowMillis: Long = currentTimeMillis()) {
        schedulePeriodic(context)
        val prefs = context.settings(SettingsStore.PREFS_NAME)
        val lastRun = prefs.getLong(KEY_LAST_RUN_AT, 0L)
        if (lastRun != 0L && nowMillis - lastRun < APP_OPEN_MIN_INTERVAL_MS) {
            return
        }
        prefs.edit { putLong(KEY_LAST_RUN_AT, nowMillis) }
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FavoriteUpdatesWorker>()
                .setConstraints(constraints())
                .build(),
        )
    }

    private fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FavoriteUpdatesWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints())
                .build(),
        )
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
