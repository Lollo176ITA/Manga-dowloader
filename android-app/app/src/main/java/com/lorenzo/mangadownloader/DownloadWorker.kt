package com.lorenzo.mangadownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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

    private val sourceRegistry = MangaSourceRegistry(appContext)
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
                            ) { pageDone, pageTotal ->
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
        } catch (exc: Exception) {
            Result.failure(
                workDataOf(
                    PROGRESS_MESSAGE to (exc.message ?: "Errore sconosciuto"),
                    PROGRESS_SOURCE_ID to (inputSourceId ?: taggedSourceId),
                    PROGRESS_SERIES_TITLE to taggedSeriesTitle,
                    PROGRESS_MANGA_URL to taggedMangaUrl,
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Manga Downloader")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
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
        const val UNIQUE_WORK_NAME = "manga-download-work"
        const val PROGRESS_MESSAGE = "progress_message"
        const val PROGRESS_DONE_CHAPTERS = "progress_done_chapters"
        const val PROGRESS_TOTAL_CHAPTERS = "progress_total_chapters"
        const val PROGRESS_SOURCE_ID = "progress_source_id"
        const val PROGRESS_SERIES_TITLE = "progress_series_title"
        const val PROGRESS_MANGA_URL = "progress_manga_url"
        const val TAG_SOURCE_ID_PREFIX = "source_id:"
        const val TAG_SERIES_TITLE_PREFIX = "series_title:"
        const val TAG_MANGA_URL_PREFIX = "manga_url:"
        const val TAG_COVER_URL_PREFIX = "cover_url:"

        private const val KEY_FIRST_URL = "first_url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_SOURCE_ID = "source_id"
        private const val NOTIFICATION_CHANNEL_ID = "manga_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val CHAPTER_CONCURRENCY = 2
        private const val PAGE_CONCURRENCY = 4

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
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
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
