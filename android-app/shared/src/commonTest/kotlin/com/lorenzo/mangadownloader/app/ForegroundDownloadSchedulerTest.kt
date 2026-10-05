package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.download.SeriesDownloadProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundDownloadSchedulerTest {
    @Test
    fun queuedDownloadsWaitUntilThePreviousOneCompletes() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val scheduler = ForegroundDownloadScheduler(backgroundScope) { request, _ ->
            started += request.firstUrl
            if (request.firstUrl == "first") release.await()
        }
        scheduler.enqueue(ChapterDownloadRequest("first"))
        scheduler.enqueue(ChapterDownloadRequest("second"))
        runCurrent()
        assertEquals(listOf("first"), started)
        assertEquals(listOf(DownloadJobState.RUNNING, DownloadJobState.ENQUEUED), scheduler.jobs.value.map { it.state })
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), started)
        assertTrue(scheduler.jobs.value.all { it.state == DownloadJobState.SUCCEEDED })
    }

    @Test
    fun cancellingAQueuedDownloadPreventsItFromStarting() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val scheduler = ForegroundDownloadScheduler(backgroundScope) { request, _ ->
            started += request.firstUrl
            release.await()
        }
        scheduler.enqueue(ChapterDownloadRequest("first"))
        scheduler.enqueue(ChapterDownloadRequest("cancelled"))
        runCurrent()
        scheduler.stop(listOf(scheduler.jobs.value.last().id))
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first"), started)
        assertEquals(DownloadJobState.CANCELLED, scheduler.jobs.value.last().state)
    }

    @Test
    fun failureDoesNotBlockTheNextDownloadAndRetainsItsRetryRequest() = runTest {
        val scheduler = ForegroundDownloadScheduler(backgroundScope) { request, _ ->
            if (request.firstUrl == "broken") throw IOException("offline")
        }
        scheduler.enqueue(ChapterDownloadRequest("broken", lastUrl = "last", sourceId = "source"))
        scheduler.enqueue(ChapterDownloadRequest("working"))
        runCurrent()
        val failed = scheduler.jobs.value.first()
        assertEquals(DownloadJobState.FAILED, failed.state)
        assertEquals("offline", failed.message)
        assertEquals("broken", failed.firstUrl)
        assertEquals("last", failed.lastUrl)
        assertEquals("source", failed.sourceId)
        assertEquals(DownloadJobState.SUCCEEDED, scheduler.jobs.value.last().state)
    }

    @Test
    fun progressReachesTheUiAndStopAllCancelsActiveJobsOnly() = runTest {
        val scheduler = ForegroundDownloadScheduler(backgroundScope) { request, progress ->
            if (request.firstUrl == "done") return@ForegroundDownloadScheduler
            progress(SeriesDownloadProgress("source", "Title", "manga", "1/3", 1, 3))
            awaitCancellation()
        }
        scheduler.enqueue(ChapterDownloadRequest("done"))
        scheduler.enqueue(ChapterDownloadRequest("running"))
        scheduler.enqueue(ChapterDownloadRequest("queued"))
        runCurrent()
        val running = scheduler.jobs.value[1]
        assertEquals("Title", running.seriesTitle)
        assertEquals(1, running.doneChapters)
        assertEquals(3, running.totalChapters)
        scheduler.stopAll()
        runCurrent()
        assertEquals(DownloadJobState.SUCCEEDED, scheduler.jobs.value.first().state)
        assertTrue(scheduler.jobs.value.drop(1).all { it.state == DownloadJobState.CANCELLED })
        assertFalse(scheduler.jobs.value.any { it.isActive })
    }
}
