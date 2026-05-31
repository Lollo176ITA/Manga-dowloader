package com.lorenzo.mangadownloader

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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controllo periodico dei preferiti: per ogni preferito "in corso" scarica i dettagli,
 * confronta l'ultimo capitolo con quello già visto e, se è uscito qualcosa di nuovo, manda
 * una notifica. I manga già noti come conclusi vengono saltati (niente rete). Best-effort:
 * un errore su un preferito non blocca gli altri.
 */
class FavoriteUpdatesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        if (!SettingsStore(prefs).read().favoriteNewChapterNotificationsEnabled) {
            return Result.success()
        }
        val favorites = FavoritesStore(prefs).read()
        if (favorites.isEmpty()) {
            return Result.success()
        }

        val store = FavoriteUpdatesStore(prefs)
        val seenMap = store.read().toMutableMap()
        val registry = sharedSourceRegistry(context)
        val notifier = FavoriteUpdateNotifier(context)

        for (favorite in favorites) {
            val key = MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl)
            val seen = seenMap[key]
            if (!shouldPollFavorite(seen)) {
                continue
            }
            try {
                val details = withContext(Dispatchers.IO) {
                    registry.resolve(favorite.sourceId, favorite.mangaUrl)
                        .fetchMangaDetails(favorite.mangaUrl)
                }
                val latest = details.chapters.maxByOrNull { it.numberValue } ?: continue
                val result = computeFavoriteUpdate(
                    seen = seen,
                    latestNumber = latest.numberValue,
                    latestLabel = latest.displayLabel(),
                    status = details.status,
                )
                seenMap[key] = result.newState
                result.newChapterLabel?.let { label ->
                    notifier.notifyNewChapter(favorite, label)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort: rete/parsing fallito su un preferito → si riprova al giro dopo.
            }
        }

        store.write(seenMap)
        return Result.success()
    }
}

/** Invio delle notifiche "nuovo capitolo". Canale dedicato; il tap apre l'app. */
class FavoriteUpdateNotifier(private val context: Context) {

    fun notifyNewChapter(favorite: FavoriteManga, chapterLabel: String) {
        if (!canPostNotifications()) {
            return
        }
        ensureChannel()
        val notificationId = MangaSourceCatalog.identityKey(favorite.sourceId, favorite.mangaUrl).hashCode()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(favorite.title)
            .setContentText("Nuovo capitolo disponibile: $chapterLabel")
            .setAutoCancel(true)
            .setContentIntent(appLaunchIntent(notificationId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permesso revocato tra il controllo e l'invio: ignora.
        }
    }

    private fun appLaunchIntent(requestCode: Int): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
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

    private companion object {
        const val CHANNEL_ID = "favorite_new_chapters"
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
    fun onAppStart(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        schedulePeriodic(context)
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val lastRun = prefs.getLong(KEY_LAST_RUN_AT, 0L)
        if (lastRun != 0L && nowMillis - lastRun < APP_OPEN_MIN_INTERVAL_MS) {
            return
        }
        prefs.edit().putLong(KEY_LAST_RUN_AT, nowMillis).apply()
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
