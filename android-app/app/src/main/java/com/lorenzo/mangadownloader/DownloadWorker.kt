package com.lorenzo.mangadownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lorenzo.mangadownloader.data.model.DownloadResult
import com.lorenzo.mangadownloader.data.model.readingUnitPlural
import com.lorenzo.mangadownloader.data.model.readingUnitSingular
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val sourceRegistry = sharedSourceRegistry(appContext)
    private val workTags = workerParams.tags

    override suspend fun doWork(): Result {
        val firstUrl = inputData.getString(KEY_FIRST_URL)?.trim().orEmpty()
        val lastUrl = inputData.getString(KEY_LAST_URL)?.trim().orEmpty().ifBlank { null }
        val inputSourceId = inputData.getString(KEY_SOURCE_ID)?.trim().orEmpty().ifBlank { null }
        val taggedSeriesTitle = workTags.tagValue(TAG_SERIES_TITLE_PREFIX)
        val taggedMangaUrl = workTags.tagValue(TAG_MANGA_URL_PREFIX)
        val taggedSourceId = workTags.tagValue(TAG_SOURCE_ID_PREFIX)
        if (firstUrl.isEmpty()) {
            return Result.failure(workDataOf(PROGRESS_MESSAGE to "URL iniziale mancante"))
        }

        // Una serie alla volta, come quando la coda era unica: il carico sulle fonti e la
        // notifica restano quelli di prima. Chi aspetta resta RUNNING per WorkManager, quindi
        // si promuove comunque a foreground: senza, JobScheduler lo fermerebbe dopo dieci
        // minuti di attesa e lo rimetterebbe in coda con il backoff.
        if (!seriesSlot.tryAcquire()) {
            setProgress(
                workDataOf(
                    PROGRESS_WAITING to true,
                    PROGRESS_SOURCE_ID to (inputSourceId ?: taggedSourceId),
                    PROGRESS_SERIES_TITLE to taggedSeriesTitle,
                    PROGRESS_MANGA_URL to taggedMangaUrl,
                    PROGRESS_MESSAGE to "In coda",
                ),
            )
            safeSetForeground(lastForegroundMessage ?: "Download in coda")
            seriesSlot.acquire()
        }
        return try {
            downloadSeries(firstUrl, lastUrl, inputSourceId, taggedSourceId, taggedSeriesTitle, taggedMangaUrl)
        } finally {
            seriesSlot.release()
        }
    }

    private suspend fun downloadSeries(
        firstUrl: String,
        lastUrl: String?,
        inputSourceId: String?,
        taggedSourceId: String?,
        taggedSeriesTitle: String?,
        taggedMangaUrl: String?,
    ): Result {
        return try {
            safeSetForeground(taggedSeriesTitle ?: "Preparazione download")
            val source = sourceRegistry.resolve(inputSourceId ?: taggedSourceId, firstUrl)
            val plan = source.buildDownloadPlan(firstUrl, lastUrl)
            val unitSingular = readingUnitSingular(plan.chapters)
            val unitPlural = readingUnitPlural(plan.chapters)
            source.prepareSeriesStorage(plan)
            updateStatus(
                sourceId = plan.sourceId,
                seriesTitle = plan.seriesTitle,
                mangaUrl = plan.mangaUrl,
                message = if (plan.startChapterLabel == plan.endChapterLabel) {
                    "Trovato 1 $unitSingular: ${plan.startChapterLabel}"
                } else {
                    "Trovati ${plan.chapters.size} $unitPlural da ${plan.startChapterLabel} a ${plan.endChapterLabel}"
                },
                doneChapters = 0,
                totalChapters = plan.chapters.size,
            )

            val totalChapters = plan.chapters.size
            val completedChapters = AtomicInteger(0)
            val statusMutex = Mutex()
            val chapterSemaphore = Semaphore(CHAPTER_CONCURRENCY)
            val lastPageEmitMs = AtomicLong(0L)

            coroutineScope {
                plan.chapters.map { chapter ->
                    async(Dispatchers.IO) {
                        chapterSemaphore.withPermit {
                            ensureActiveDownload()
                            val chapterLabel = chapter.displayLabel()
                            emitStatus(
                                mutex = statusMutex,
                                sourceId = plan.sourceId,
                                seriesTitle = plan.seriesTitle,
                                mangaUrl = plan.mangaUrl,
                                message = "$chapterLabel in download",
                                doneChapters = completedChapters.get(),
                                totalChapters = totalChapters,
                            )

                            val result = source.downloadChapterAsCbz(
                                chapter = chapter,
                                outputDir = plan.outputDir,
                                pageConcurrency = PAGE_CONCURRENCY,
                                onProcessingProgress = processing@ { processed, pageTotal ->
                                    val isBoundary = processed == 0 ||
                                        processed >= pageTotal ||
                                        processed % PAGE_PROGRESS_STRIDE == 0
                                    if (!isBoundary) return@processing
                                    emitStatus(
                                        mutex = statusMutex,
                                        sourceId = plan.sourceId,
                                        seriesTitle = plan.seriesTitle,
                                        mangaUrl = plan.mangaUrl,
                                        message = "$chapterLabel: preparazione immagini $processed/$pageTotal",
                                        doneChapters = completedChapters.get(),
                                        totalChapters = totalChapters,
                                    )
                                },
                            ) download@ { pageDone, pageTotal ->
                                // Skip per-page emits except the final one or boundaries:
                                // a chapter of 50 pages would otherwise produce 50 setProgress
                                // round-trips, each waking the UI observer.
                                val isFinalPage = pageDone >= pageTotal
                                val isBatchBoundary = pageDone % PAGE_PROGRESS_STRIDE == 0
                                val now = System.currentTimeMillis()
                                val previousEmitMs = lastPageEmitMs.get()
                                val timedOut = now - previousEmitMs >= PAGE_PROGRESS_MIN_INTERVAL_MS
                                if (
                                    !isFinalPage &&
                                    !isBatchBoundary &&
                                    !timedOut
                                ) {
                                    return@download
                                }
                                lastPageEmitMs.set(now)
                                emitStatus(
                                    mutex = statusMutex,
                                    sourceId = plan.sourceId,
                                    seriesTitle = plan.seriesTitle,
                                    mangaUrl = plan.mangaUrl,
                                    message = "$chapterLabel: pagina $pageDone/$pageTotal",
                                    doneChapters = completedChapters.get(),
                                    totalChapters = totalChapters,
                                )
                            }

                            val done = completedChapters.incrementAndGet()
                            val message = when (result) {
                                DownloadResult.DOWNLOADED ->
                                    "$chapterLabel completato"
                                DownloadResult.SKIPPED_EXISTING ->
                                    "$chapterLabel già presente"
                            }
                            emitStatus(
                                mutex = statusMutex,
                                sourceId = plan.sourceId,
                                seriesTitle = plan.seriesTitle,
                                mangaUrl = plan.mangaUrl,
                                message = message,
                                doneChapters = done,
                                totalChapters = totalChapters,
                            )
                        }
                    }
                }.awaitAll()
            }

            updateStatus(
                sourceId = plan.sourceId,
                seriesTitle = plan.seriesTitle,
                mangaUrl = plan.mangaUrl,
                message = "Download completato: $totalChapters $unitPlural",
                doneChapters = totalChapters,
                totalChapters = totalChapters,
            )
            // Notifica finale NON-ongoing (id separato): la notifica di progresso è ongoing e
            // viene rimossa dal sistema appena il worker termina, quindi chi ha lo schermo
            // spento non vedrebbe mai un segnale di fine. Tocco = apre l'app sulla Libreria.
            postResultNotification(
                key = notificationKey(plan.sourceId, plan.mangaUrl, plan.seriesTitle),
                title = plan.seriesTitle,
                text = "Download completato: $totalChapters $unitPlural — tocca per leggere",
            )
            Result.success(
                workDataOf(
                    PROGRESS_MESSAGE to "Completato",
                    PROGRESS_DONE_CHAPTERS to totalChapters,
                    PROGRESS_TOTAL_CHAPTERS to totalChapters,
                    PROGRESS_SOURCE_ID to plan.sourceId,
                    PROGRESS_SERIES_TITLE to plan.seriesTitle,
                    PROGRESS_MANGA_URL to plan.mangaUrl,
                ),
            )
        } catch (ioe: IOException) {
            // Ritenta solo questa serie: con una catena per serie le altre non aspettano.
            Result.retry()
        } catch (cancelled: DownloadStoppedException) {
            Result.success(
                workDataOf(
                    PROGRESS_MESSAGE to "Fermato",
                    PROGRESS_SOURCE_ID to (inputSourceId ?: taggedSourceId),
                    PROGRESS_SERIES_TITLE to taggedSeriesTitle,
                    PROGRESS_MANGA_URL to taggedMangaUrl,
                ),
            )
        } catch (cancelled: CancellationException) {
            // Stop mentre si aspetta la rete: non è un errore, niente notifica "non riuscito".
            throw cancelled
        } catch (exc: Exception) {
            // first/last URL nell'output così la UI può proporre "Riprova" ri-accodando lo
            // stesso intervallo (il ramo failure prima non li conservava).
            postResultNotification(
                key = notificationKey(inputSourceId ?: taggedSourceId, taggedMangaUrl, taggedSeriesTitle),
                title = taggedSeriesTitle,
                text = "Download non riuscito: ${exc.message ?: "errore sconosciuto"}",
            )
            Result.failure(
                workDataOf(
                    PROGRESS_MESSAGE to (exc.message ?: "Errore sconosciuto"),
                    PROGRESS_SOURCE_ID to (inputSourceId ?: taggedSourceId),
                    PROGRESS_SERIES_TITLE to taggedSeriesTitle,
                    PROGRESS_MANGA_URL to taggedMangaUrl,
                    PROGRESS_FIRST_URL to firstUrl,
                    PROGRESS_LAST_URL to lastUrl,
                ),
            )
        }
    }

    private suspend fun emitStatus(
        mutex: Mutex,
        sourceId: String,
        seriesTitle: String,
        mangaUrl: String,
        message: String,
        doneChapters: Int,
        totalChapters: Int,
    ) {
        mutex.withLock {
            updateStatus(sourceId, seriesTitle, mangaUrl, message, doneChapters, totalChapters)
        }
    }

    private fun ensureActiveDownload() {
        if (isStopped) {
            throw DownloadStoppedException()
        }
    }

    private suspend fun updateStatus(
        sourceId: String?,
        seriesTitle: String?,
        mangaUrl: String?,
        message: String,
        doneChapters: Int,
        totalChapters: Int,
    ) {
        setProgress(
            workDataOf(
                PROGRESS_SOURCE_ID to sourceId,
                PROGRESS_SERIES_TITLE to seriesTitle,
                PROGRESS_MANGA_URL to mangaUrl,
                PROGRESS_MESSAGE to message,
                PROGRESS_DONE_CHAPTERS to doneChapters,
                PROGRESS_TOTAL_CHAPTERS to totalChapters,
            ),
        )
        safeSetForeground(message)
    }

    private suspend fun safeSetForeground(message: String) {
        if (!canShowForegroundNotification()) {
            return
        }
        lastForegroundMessage = message

        try {
            setForeground(makeForegroundInfo(message))
        } catch (_: Exception) {
            // Some devices still reject the foreground promotion even after the permission check.
            // Let the worker continue in background instead of killing the app process.
        }
    }

    private fun canShowForegroundNotification(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun makeForegroundInfo(message: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_manga)
            .setContentTitle("Manga Downloader")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Tocco sulla notifica = apre l'app (prima era un passo morto).
            .setContentIntent(appContentIntent(NOTIFICATION_ID))
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Notifica finale (non-ongoing) di esito download, su un id stabile per-serie. */
    private fun postResultNotification(key: Int, title: String?, text: String) {
        if (!canShowForegroundNotification()) {
            return
        }
        ensureNotificationChannel()
        val notificationId = COMPLETION_ID_BASE + (key and 0xFFFF)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_manga)
            .setContentTitle(title?.takeIf(String::isNotBlank) ?: "Manga Downloader")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(appContentIntent(notificationId))
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permesso revocato tra il check e la notify: ignora.
        }
    }

    /** PendingIntent che apre l'app sulla tab Libreria (dove vivono progresso e coda). */
    private fun appContentIntent(requestCode: Int): PendingIntent? {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?: return null
        launchIntent.putExtra(EXTRA_OPEN_TAB, OPEN_TAB_LIBRARY)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationKey(sourceId: String?, mangaUrl: String?, title: String?): Int {
        val raw = mangaUrl?.takeIf(String::isNotBlank)
            ?: title?.takeIf(String::isNotBlank)
            ?: sourceId.orEmpty()
        return raw.hashCode()
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Manga downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        /**
         * Tag che WorkManager mette da solo su ogni richiesta: il nome completo della classe.
         * Raggruppa tutti i download, compresi quelli accodati dalle versioni che usavano
         * un'unica catena `manga-download-work` (ancora in coda su chi aggiorna l'app).
         */
        val ALL_DOWNLOADS_TAG: String = DownloadWorker::class.java.name
        private const val SERIES_WORK_PREFIX = "manga-download:"
        /** `true` mentre il worker aspetta che finisca il download di un'altra serie. */
        const val PROGRESS_WAITING = "progress_waiting"
        const val PROGRESS_MESSAGE = "progress_message"
        const val PROGRESS_DONE_CHAPTERS = "progress_done_chapters"
        const val PROGRESS_TOTAL_CHAPTERS = "progress_total_chapters"
        const val PROGRESS_SOURCE_ID = "progress_source_id"
        const val PROGRESS_SERIES_TITLE = "progress_series_title"
        const val PROGRESS_MANGA_URL = "progress_manga_url"
        const val PROGRESS_FIRST_URL = "progress_first_url"
        const val PROGRESS_LAST_URL = "progress_last_url"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val OPEN_TAB_LIBRARY = "library"
        const val TAG_SOURCE_ID_PREFIX = "source_id:"
        const val TAG_SERIES_TITLE_PREFIX = "series_title:"
        const val TAG_MANGA_URL_PREFIX = "manga_url:"
        const val TAG_COVER_URL_PREFIX = "cover_url:"

        private const val KEY_FIRST_URL = "first_url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_SOURCE_ID = "source_id"
        private const val NOTIFICATION_CHANNEL_ID = "manga_downloads"
        private const val NOTIFICATION_ID = 1001
        // Le notifiche finali (per-serie) vivono in un range separato dall'id ongoing (1001).
        private const val COMPLETION_ID_BASE = 2000
        private const val CHAPTER_CONCURRENCY = 2
        private const val PAGE_CONCURRENCY = 4
        private const val PAGE_PROGRESS_STRIDE = 5
        private const val PAGE_PROGRESS_MIN_INTERVAL_MS = 1_500L

        /** Serie scaricate insieme (FIFO: kotlinx `Semaphore` serve le attese in ordine). */
        private val seriesSlot = Semaphore(1)

        /**
         * Ultimo testo mostrato nella notifica di download: chi aspetta il suo turno si
         * promuove a foreground con lo stesso id, e con un testo suo coprirebbe quello
         * della serie che sta scaricando.
         */
        @Volatile
        private var lastForegroundMessage: String? = null

        /**
         * Nome della catena WorkManager di una serie. Stessa chiave con cui la Libreria
         * raggruppa i download ([MangaSourceCatalog.identityKeyOrNull]); senza serie
         * riconoscibile ogni richiesta sta per conto suo.
         */
        fun seriesWorkName(
            sourceId: String?,
            mangaUrl: String?,
            seriesTitle: String?,
            firstUrl: String,
        ): String {
            val key = MangaSourceCatalog.identityKeyOrNull(
                sourceId?.trim()?.takeIf(String::isNotBlank),
                mangaUrl?.trim()?.takeIf(String::isNotBlank),
                seriesTitle?.trim()?.takeIf(String::isNotBlank),
            ) ?: "url:${firstUrl.trim()}"
            return SERIES_WORK_PREFIX + key
        }

        /** Tutti i download, di ogni serie e di ogni stato. */
        fun observeAll(workManager: WorkManager): LiveData<List<WorkInfo>> =
            workManager.getWorkInfosByTagLiveData(ALL_DOWNLOADS_TAG)

        /** Ferma ogni download, compresi quelli rimasti nella vecchia coda unica. */
        fun stopAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ALL_DOWNLOADS_TAG)
        }

        /**
         * Ferma solo le richieste indicate (quelle di una serie, vedi `SeriesDownloadStatus`).
         * Per id e non per nome di catena: così vale anche per i download accodati prima
         * delle catene per serie.
         */
        fun stopWork(context: Context, workIds: Collection<UUID>) {
            val workManager = WorkManager.getInstance(context)
            workIds.forEach(workManager::cancelWorkById)
        }

        fun enqueue(
            context: Context,
            firstUrl: String,
            lastUrl: String? = null,
            sourceId: String? = null,
            seriesTitle: String? = null,
            mangaUrl: String? = null,
            coverUrl: String? = null,
        ) {
            val input = Data.Builder()
                .putString(KEY_FIRST_URL, firstUrl.trim())
                .putString(KEY_LAST_URL, lastUrl?.trim())
                .putString(KEY_SOURCE_ID, sourceId?.trim())
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(input)
                .apply {
                    sourceId?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { addTag("$TAG_SOURCE_ID_PREFIX$it") }
                    seriesTitle?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { addTag("$TAG_SERIES_TITLE_PREFIX$it") }
                    mangaUrl?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { addTag("$TAG_MANGA_URL_PREFIX$it") }
                    coverUrl?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { addTag("$TAG_COVER_URL_PREFIX$it") }
                }
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .build()

            // Una catena per serie: due intervalli della stessa serie si scaricano uno dopo
            // l'altro (stessa cartella), ma il fallimento o il retry di una serie non tocca
            // le altre. Con l'unica catena di prima, una serie fallita faceva fallire tutte
            // quelle accodate dopo.
            WorkManager.getInstance(context).enqueueUniqueWork(
                seriesWorkName(sourceId, mangaUrl, seriesTitle, firstUrl),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

private class DownloadStoppedException : RuntimeException()

private fun Set<String>.tagValue(prefix: String): String? {
    return firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
}
