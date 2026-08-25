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
import androidx.core.content.edit
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
 *
 * Un preferito è **una serie**, non una serie-su-una-fonte: se il mirror da cui la leggevi non
 * risponde si prova il successivo tra quelli agganciati e, se ne risponde uno, diventa il nuovo
 * preferito per quella serie. Tutto è indicizzato per `seriesKey`, così il cambio di mirror non
 * fa ripartire la baseline (che significherebbe ri-notificare capitoli già visti).
 */
class FavoriteUpdatesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = SettingsStore(prefs).read()
        if (!settings.favoriteNewChapterNotificationsEnabled) {
            return Result.success()
        }

        val favoritesStore = FavoritesStore(prefs)
        val store = FavoriteUpdatesStore(prefs)
        val descriptionsStore = FavoriteDescriptionsStore(prefs)
        val feedStore = FavoriteUpdatesFeedStore(prefs)
        val seriesLinksStore = SeriesLinksStore(prefs)
        val healthStore = FavoriteSourceHealthStore(prefs)

        // Idempotente: la fa il ViewModel all'avvio, ma il worker può girare prima di lui.
        var favorites = FavoritesSeriesMigration(
            prefs = prefs,
            favoritesStore = favoritesStore,
            favoriteUpdatesStore = store,
            favoriteDescriptionsStore = descriptionsStore,
            favoriteUpdatesFeedStore = feedStore,
            seriesLinksStore = seriesLinksStore,
        ).migrateIfNeeded()
        if (favorites.isEmpty()) {
            return Result.success()
        }

        val registry = sharedSourceRegistry(context)
        val aniListClient = AniListClient(SharedHttpClient.get(context))

        // Prima le identità, poi i capitoli: così un preferito appena promosso ad `anilist:`
        // usa già la chiave definitiva per baseline e feed in questo stesso giro.
        favorites = FavoriteIdentityResolver(
            favoritesStore = favoritesStore,
            favoriteUpdatesStore = store,
            favoriteDescriptionsStore = descriptionsStore,
            favoriteSourceHealthStore = healthStore,
            seriesLinksStore = seriesLinksStore,
            attemptsStore = AniListResolutionAttemptsStore(prefs),
            searchAniList = { title -> withContext(Dispatchers.IO) { aniListClient.searchManga(title) } },
        ).resolve(favorites)

        val seenMap = store.read().toMutableMap()
        val descriptions = descriptionsStore.read().toMutableMap()
        val healthMap = healthStore.read().toMutableMap()
        val notifier = FavoriteUpdateNotifier(context)
        var notifiedCount = 0
        var favoritesChanged = false
        val updatedFavorites = favorites.toMutableList()

        try {
        for ((index, favorite) in favorites.withIndex()) {
            val key = favorite.canonicalKey()
            val seen = seenMap[key]
            if (!shouldPollFavorite(seen)) {
                continue
            }
            try {
                val candidates = favoriteSourceCandidates(
                    favorite = favorite,
                    link = seriesLinksStore.linkFor(key),
                    disabledSourceIds = settings.disabledSourceIds,
                )
                val fetched = fetchFromFirstAvailable(candidates) { binding ->
                    withContext(Dispatchers.IO) {
                        registry.resolve(binding.sourceId, binding.mangaUrl)
                            .fetchMangaDetails(binding.mangaUrl)
                    }
                }
                if (fetched == null) {
                    // Nessun mirror raggiungibile: NON si cambia la fonte preferita (di
                    // solito è la rete del telefono, non il sito). Si conta e basta.
                    healthMap[key] = recordSourceFailure(healthMap[key])
                    continue
                }
                val details = fetched.details
                val usedBinding = fetched.binding
                healthMap[key] = recordSourceSuccess(
                    current = healthMap[key],
                    previousSourceId = favorite.sourceId,
                    usedSourceId = usedBinding.sourceId,
                    nowMillis = System.currentTimeMillis(),
                )
                if (usedBinding.sourceId != favorite.sourceId) {
                    // Ha risposto un mirror diverso: diventa quello da cui leggere la serie.
                    seriesLinksStore.setPreferredSource(key, usedBinding.sourceId)
                    updatedFavorites[index] = favorite.copy(
                        sourceId = usedBinding.sourceId,
                        mangaUrl = usedBinding.mangaUrl,
                    )
                    favoritesChanged = true
                }
                // La trama dei preferiti viene scaricata comunque qui: salvala così il
                // pulsante info è pronto anche dopo il riavvio dell'app (è solo testo).
                details.description?.trim()?.takeIf(String::isNotBlank)?.let { descriptions[key] = it }
                val latest = details.chapters.maxByOrNull { it.numberValue } ?: continue
                val result = computeFavoriteUpdate(
                    seen = seen,
                    latestNumber = latest.numberValue,
                    latestLabel = latest.displayLabel(),
                    status = details.status,
                )
                seenMap[key] = result.newState
                result.newChapterLabel?.let { label ->
                    // Persisti baseline e feed PRIMA di notificare: se il processo muore
                    // subito dopo la notifica, al giro dopo non si rinotifica lo stesso
                    // capitolo e l'evento è comunque nel feed in-app. L'append è un
                    // read-merge-write atomico per evento: non si tiene in mano il feed
                    // durante il giro di rete, così un "segna come visto" fatto
                    // dall'utente nel frattempo non viene sovrascritto.
                    store.write(seenMap)
                    feedStore.update { events ->
                        appendUpdateEvent(
                            events,
                            FavoriteUpdateEvent(
                                identityKey = MangaSourceCatalog.identityKey(
                                    usedBinding.sourceId,
                                    usedBinding.mangaUrl,
                                ),
                                title = favorite.title,
                                // Fonte e URL dell'evento sono quelli del mirror che ha
                                // risposto: è lì che il tap deve portare.
                                sourceId = usedBinding.sourceId,
                                mangaUrl = usedBinding.mangaUrl,
                                chapterLabel = label,
                                chapterNumber = latest.numberValue.stripTrailingZeros().toPlainString(),
                                coverUrl = favorite.coverUrl,
                                timestampMillis = System.currentTimeMillis(),
                                seen = false,
                                seriesKey = key,
                            ),
                        )
                    }
                    notifier.notifyNewChapter(updatedFavorites[index], key, label)
                    notifiedCount++
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Best-effort: rete/parsing fallito su un preferito → si riprova al giro dopo.
            }
        }
        // Riepilogo del gruppo: con 2+ capitoli non visti il pannello resta compatto
        // (una voce sola che espande le singole) invece di N notifiche sparse.
        if (notifiedCount > 0) {
            notifier.notifySummary(feedStore.read().filter { !it.seen })
        }
        } finally {
            // Persisti SEMPRE i progressi parziali: se WorkManager interrompe il worker a metà
            // (es. rete caduta), così non si rinotificano i capitoli già avvisati al giro dopo.
            // Il feed NON si riscrive qui: ogni evento è già stato persistito al momento
            // della notifica (vedi sopra).
            store.write(seenMap)
            descriptionsStore.write(descriptions)
            healthStore.write(healthMap)
            if (favoritesChanged) {
                favoritesStore.persist(updatedFavorites)
            }
        }
        return Result.success()
    }
}

/**
 * Invio delle notifiche "nuovo capitolo". Canale dedicato e gruppo con riepilogo: il tap
 * sulla singola apre direttamente il manga, il tap sul riepilogo apre il feed Aggiornamenti
 * (extras gestiti da MainActivity).
 */
class FavoriteUpdateNotifier(private val context: Context) {

    /**
     * [seriesKey] è l'identità della serie: l'id della notifica ci si basa perché la stessa
     * serie ritrovata su un mirror diverso deve **aggiornare** la sua notifica, non aprirne
     * una seconda accanto.
     */
    fun notifyNewChapter(favorite: FavoriteManga, seriesKey: String, chapterLabel: String) {
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
    fun notifySummary(unseenEvents: List<FavoriteUpdateEvent>) {
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
    fun onAppStart(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        schedulePeriodic(context)
        val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
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
