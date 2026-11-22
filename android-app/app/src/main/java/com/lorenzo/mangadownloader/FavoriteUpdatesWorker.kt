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
import com.lorenzo.mangadownloader.app.AppSettings
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.app.hidesAdultContent
import com.lorenzo.mangadownloader.data.anilist.AniListAuthException
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.anilist.AniListFavoritesSyncStore
import com.lorenzo.mangadownloader.data.anilist.AniListFavoritesSynchronizer
import com.lorenzo.mangadownloader.data.anilist.AniListResolutionAttemptsStore
import com.lorenzo.mangadownloader.data.anilist.AniListStore
import com.lorenzo.mangadownloader.data.anilist.newAniListFavorites
import com.lorenzo.mangadownloader.data.anilist.realAniListFavoritesSynchronizer
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.sources.RemoteSourceConfig
import com.lorenzo.mangadownloader.data.sources.SourceHealthStore
import com.lorenzo.mangadownloader.data.sources.isSourceSkipped
import com.lorenzo.mangadownloader.data.store.AdultSeriesStore
import com.lorenzo.mangadownloader.data.store.FavoriteDescriptionsStore
import com.lorenzo.mangadownloader.data.store.FavoriteSourceHealthStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesStore
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.series.FavoriteIdentityResolver
import com.lorenzo.mangadownloader.domain.series.FavoritesSeriesMigration
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
        // Col filtro adulti attivo le serie del registro si controllano lo stesso (spegnendo il
        // filtro lo stato è in pari), ma non si notificano: finiscono solo nel feed, che le
        // nasconde finché il filtro resta acceso.
        val adultKeys = if (settings.hidesAdultContent()) AdultSeriesStore(prefs).read() else emptySet()
        // Fonti che l'app sta saltando perché non rispondono (vedi [SourceHealth]): il worker
        // gira in background su rete mobile, ed è l'ultimo posto dove ha senso restare venti
        // secondi appesi a un sito giù per ogni preferito da controllare.
        val unavailableSourceIds = SourceHealthStore(prefs).read()
            .filterValues { isSourceSkipped(it, System.currentTimeMillis()) }
            .keys

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

        // Prima di interrogare le fonti: un dominio cambiato vale anche per il controllo notturno.
        RemoteSourceConfig.refresh(SharedHttpClient.get(context))
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

        // Preferiti aggiunti su AniList altrove (sito, altro dispositivo): entrano qui, prima
        // del giro capitoli, così ricevono subito la baseline invece di aspettare il prossimo
        // risveglio del worker. Va dopo la risoluzione delle identità: un preferito appena
        // promosso ad `anilist:` ha già l'id con cui confrontarsi.
        favorites = syncAniListFavorites(
            settings = settings,
            favorites = favorites,
            favoritesStore = favoritesStore,
            seriesLinksStore = seriesLinksStore,
            syncStore = AniListFavoritesSyncStore(prefs),
            aniListStore = AniListStore(
                prefs,
                context.getSharedPreferences(AniListStore.ACCOUNT_PREFS_NAME, Context.MODE_PRIVATE),
            ),
            aniListClient = aniListClient,
            registry = registry,
            unavailableSourceIds = unavailableSourceIds,
        )

        val notifier = FavoriteUpdateNotifier(context)
        FavoriteUpdatesRun(
            favoritesStore = favoritesStore,
            updatesStore = store,
            descriptionsStore = descriptionsStore,
            feedStore = feedStore,
            seriesLinksStore = seriesLinksStore,
            healthStore = healthStore,
            disabledSourceIds = settings.disabledSourceIds,
            adultKeys = adultKeys,
            fetchDetails = { binding ->
                withContext(Dispatchers.IO) {
                    registry.resolve(binding.sourceId, binding.mangaUrl)
                        .fetchMangaDetails(binding.mangaUrl)
                }
            },
            notifyNewChapter = notifier::notifyNewChapter,
            notifySummary = notifier::notifySummary,
        ).run(favorites)
        return Result.success()
    }

    /**
     * Un giro di riconciliazione preferiti app <-> AniList (vedi [AniListFavoritesSynchronizer]).
     * Ritorna la lista aggiornata, o quella ricevuta se non c'e' nulla da fare o l'account non
     * e' collegato. Best-effort come il resto del worker: un fallimento non deve impedire il
     * controllo dei capitoli.
     */
    private suspend fun syncAniListFavorites(
        settings: AppSettings,
        favorites: List<FavoriteManga>,
        favoritesStore: FavoritesStore,
        seriesLinksStore: SeriesLinksStore,
        syncStore: AniListFavoritesSyncStore,
        aniListStore: AniListStore,
        aniListClient: AniListClient,
        registry: MangaSourceRegistry,
        unavailableSourceIds: Set<String>,
    ): List<FavoriteManga> {
        if (!settings.aniListFavoritesSyncEnabled) return favorites
        val token = aniListStore.readToken() ?: return favorites
        val viewerId = aniListStore.readViewer()?.id ?: return favorites
        return try {
            val imported = realAniListFavoritesSynchronizer(
                client = aniListClient,
                token = token,
                viewerId = viewerId,
                registry = registry,
                syncStore = syncStore,
                seriesLinksStore = seriesLinksStore,
                disabledSourceIds = { settings.disabledSourceIds },
                unavailableSourceIds = { unavailableSourceIds },
            ).sync(favorites)
            if (newAniListFavorites(imported, favorites).isEmpty()) {
                favorites
            } else {
                // Confronto con i preferiti di ADESSO: la sync dura quanto una ricerca su
                // tutte le fonti e l'utente può averne aggiunti o tolti nel frattempo.
                favoritesStore.update { stored -> newAniListFavorites(imported, stored) + stored }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: AniListAuthException) {
            // Token non più valido: l'account va scollegato dall'app, non dal worker. Qui ci
            // si limita a non insistere; ci penserà il ViewModel alla prossima apertura.
            favorites
        } catch (_: Exception) {
            favorites
        }
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
