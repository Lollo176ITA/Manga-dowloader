package com.lorenzo.mangadownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.math.BigDecimal

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val client = MangapillClient(appContext)

    override suspend fun doWork(): Result {
        val mangaUrl = inputData.getString(KEY_MANGA_URL)?.trim().orEmpty()
        val startLabel = inputData.getString(KEY_START_CHAPTER)?.trim().orEmpty()
        if (mangaUrl.isEmpty() || startLabel.isEmpty()) {
            return Result.failure(workDataOf(PROGRESS_MESSAGE to "Manga o capitolo iniziale mancante"))
        }

        val startNumber = try {
            BigDecimal(startLabel)
        } catch (_: NumberFormatException) {
            return Result.failure(workDataOf(PROGRESS_MESSAGE to "Numero capitolo non valido: $startLabel"))
        }

        return try {
            setForeground(makeForegroundInfo("Preparazione download"))
            val plan = client.buildDownloadPlan(mangaUrl, startNumber)
            updateStatus(
                message = "Trovati ${plan.chapters.size} capitoli da ${plan.startChapterLabel} in poi",
                doneChapters = 0,
                totalChapters = plan.chapters.size,
            )

            var completed = 0
            for ((index, chapter) in plan.chapters.withIndex()) {
                if (isStopped) {
                    return Result.success()
                }

                val chapterLabel = chapter.displayNumber()
                updateStatus(
                    message = "Capitolo $chapterLabel (${index + 1}/${plan.chapters.size})",
                    doneChapters = completed,
                    totalChapters = plan.chapters.size,
                )

                val result = client.downloadChapterAsCbz(
                    chapter = chapter,
                    outputDir = plan.outputDir,
                ) { pageIndex, pageTotal ->
                    val pageMessage = "Capitolo $chapterLabel: pagina $pageIndex/$pageTotal"
                    updateStatus(
                        message = pageMessage,
                        doneChapters = completed,
                        totalChapters = plan.chapters.size,
                    )
                }

                if (result == DownloadResult.DOWNLOADED || result == DownloadResult.SKIPPED_EXISTING) {
                    completed += 1
                }
            }

            updateStatus(
                message = "Download completato: ${plan.chapters.size} capitoli",
                doneChapters = plan.chapters.size,
                totalChapters = plan.chapters.size,
            )
            Result.success(
                workDataOf(
                    PROGRESS_MESSAGE to "Completato",
                    PROGRESS_DONE_CHAPTERS to plan.chapters.size,
                    PROGRESS_TOTAL_CHAPTERS to plan.chapters.size,
                ),
            )
        } catch (ioe: IOException) {
            Result.retry()
        } catch (exc: Exception) {
            Result.failure(workDataOf(PROGRESS_MESSAGE to (exc.message ?: "Errore sconosciuto")))
        }
    }

    private suspend fun updateStatus(message: String, doneChapters: Int, totalChapters: Int) {
        setProgress(
            workDataOf(
                PROGRESS_MESSAGE to message,
                PROGRESS_DONE_CHAPTERS to doneChapters,
                PROGRESS_TOTAL_CHAPTERS to totalChapters,
            ),
        )
        setForeground(makeForegroundInfo(message))
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

        private const val KEY_MANGA_URL = "manga_url"
        private const val KEY_START_CHAPTER = "start_chapter"
        private const val NOTIFICATION_CHANNEL_ID = "manga_downloads"
        private const val NOTIFICATION_ID = 1001

        fun enqueue(context: Context, mangaUrl: String, startChapterLabel: String) {
            val input = Data.Builder()
                .putString(KEY_MANGA_URL, mangaUrl.trim())
                .putString(KEY_START_CHAPTER, startChapterLabel.trim())
                .build()

            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
