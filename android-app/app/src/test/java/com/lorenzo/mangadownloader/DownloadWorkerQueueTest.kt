package com.lorenzo.mangadownloader

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.ui.library.buildSeriesDownloadStatuses
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gestione della coda download: una catena WorkManager per serie, stop per serie o totale.
 * I worker non partono (vincolo di rete mai soddisfatto) tranne dove il test lo chiede.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadWorkerQueueTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun enqueue(mangaUrl: String, firstUrl: String = "$mangaUrl/chapter-1") {
        DownloadWorker.enqueue(
            context = context,
            firstUrl = firstUrl,
            sourceId = MangaSourceIds.MANGAPILL,
            seriesTitle = mangaUrl.substringAfterLast('/'),
            mangaUrl = mangaUrl,
        )
    }

    private fun chain(mangaUrl: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(
            DownloadWorker.seriesWorkName(
                sourceId = MangaSourceIds.MANGAPILL,
                mangaUrl = mangaUrl,
                seriesTitle = mangaUrl.substringAfterLast('/'),
                firstUrl = "",
            ),
        ).get()

    private fun allDownloads(): List<WorkInfo> =
        workManager.getWorkInfosByTag(DownloadWorker.ALL_DOWNLOADS_TAG).get()

    @Test
    fun differentSeries_getIndependentChains() {
        enqueue(SERIES_A)
        enqueue(SERIES_B)

        val a = chain(SERIES_A).single()
        val b = chain(SERIES_B).single()
        // Con l'unica catena di prima, B sarebbe stata BLOCKED dietro ad A.
        assertEquals(WorkInfo.State.ENQUEUED, a.state)
        assertEquals(WorkInfo.State.ENQUEUED, b.state)
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun sameSeries_rangesQueueOneAfterTheOther() {
        enqueue(SERIES_A, firstUrl = "$SERIES_A/chapter-1")
        enqueue(SERIES_A, firstUrl = "$SERIES_A/chapter-5")

        val states = chain(SERIES_A).map(WorkInfo::state).sorted()
        assertEquals(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), states)
    }

    @Test
    fun failedSeries_doesNotFailTheOthers() {
        // firstUrl vuoto: il worker fallisce subito, senza rete.
        enqueue(SERIES_A, firstUrl = "")
        enqueue(SERIES_A, firstUrl = "$SERIES_A/chapter-5")
        enqueue(SERIES_B)
        val failing = chain(SERIES_A).single { it.state == WorkInfo.State.ENQUEUED }

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(failing.id)

        val a = chain(SERIES_A)
        assertTrue(a.all { it.state == WorkInfo.State.FAILED })
        assertEquals(
            "URL iniziale mancante",
            a.single { it.id == failing.id }.outputData.getString(DownloadWorker.PROGRESS_MESSAGE),
        )
        assertEquals(WorkInfo.State.ENQUEUED, chain(SERIES_B).single().state)
    }

    @Test
    fun stopWork_stopsOnlyThatSeries() {
        enqueue(SERIES_A, firstUrl = "$SERIES_A/chapter-1")
        enqueue(SERIES_A, firstUrl = "$SERIES_A/chapter-5")
        enqueue(SERIES_B)
        val status = buildSeriesDownloadStatuses(
            allDownloads().filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED },
        ).values.single { it.mangaUrl == SERIES_A }

        DownloadWorker.stopWork(context, status.workIds)

        assertTrue(chain(SERIES_A).all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, chain(SERIES_B).single().state)
    }

    @Test
    fun stopAll_alsoStopsTheLegacySingleQueue() {
        // Download accodato da una versione precedente, nella vecchia catena unica.
        workManager.enqueueUniqueWork(
            "manga-download-work",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build(),
        ).result.get()
        enqueue(SERIES_A)
        enqueue(SERIES_B)
        assertEquals(3, allDownloads().size)

        DownloadWorker.stopAll(context)

        assertTrue(allDownloads().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun seriesWorkName_matchesLibraryGroupingAndFallsBackToFirstUrl() {
        val byUrl = DownloadWorker.seriesWorkName(MangaSourceIds.MANGAPILL, SERIES_A, "Titolo", "x")
        assertEquals(byUrl, DownloadWorker.seriesWorkName(MangaSourceIds.MANGAPILL, " $SERIES_A ", null, "y"))
        assertNotEquals(byUrl, DownloadWorker.seriesWorkName(MangaSourceIds.MANGAPILL, SERIES_B, "Titolo", "x"))
        assertNotEquals(
            DownloadWorker.seriesWorkName(null, null, null, "https://a/1"),
            DownloadWorker.seriesWorkName(null, null, null, "https://a/2"),
        )
    }

    @Test
    fun waitingWorker_isShownAsQueued_andStatusCollectsAllRequestIds() {
        val tags = setOf(
            "${DownloadWorker.TAG_SOURCE_ID_PREFIX}${MangaSourceIds.MANGAPILL}",
            "${DownloadWorker.TAG_MANGA_URL_PREFIX}$SERIES_A",
            "${DownloadWorker.TAG_SERIES_TITLE_PREFIX}A",
        )
        val waiting = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.RUNNING,
            tags = tags,
            progress = workDataOf(DownloadWorker.PROGRESS_WAITING to true),
        )
        val blocked = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.BLOCKED,
            tags = tags,
            progress = Data.EMPTY,
        )

        val status = buildSeriesDownloadStatuses(listOf(blocked, waiting)).values.single()

        assertEquals(WorkInfo.State.ENQUEUED, status.state)
        assertEquals(setOf(waiting.id, blocked.id), status.workIds.toSet())
    }

    private companion object {
        const val SERIES_A = "https://mangapill.com/manga/1/serie-a"
        const val SERIES_B = "https://mangapill.com/manga/2/serie-b"
    }
}
