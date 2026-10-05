package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.download.BackgroundChapterDownloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IosDownloadSchedulerTest {
    @Test
    fun cancellationBeforeCoroutineStartsStillCompletesTheNativeCallbackOnce() = runTest {
        val engine = IosDownloadEngine(backgroundScope, BackgroundChapterDownloader("/library".toPath(), { _, _ -> error("cancelled before parsing") }), {})
        var calls = 0
        engine.prepare("job", """{"firstUrl":"https://test/chapter"}""", IosDownloadResult { value, error ->
            calls++
            assertNull(value)
            assertNotNull(error)
        })
        engine.cancel("job")
        runCurrent()
        assertEquals(1, calls, "Swift deve poter rilasciare work/lease anche se la coroutine non entra nel corpo")
    }

    @Test
    fun restoredNativeSnapshotPublishesChaptersAndRetainsRetryRequest() = runTest {
        val services = Services()
        val engine = IosDownloadEngine(backgroundScope, BackgroundChapterDownloader("/library".toPath(), { _, _ -> error("not used") }), {})
        val scheduler = IosDownloadScheduler(services, engine)
        services.observer.update("""[{"id":"job","phase":"paused","request":{"firstUrl":"https://test/c/1","lastUrl":"https://test/c/2","userInitiated":false},"manifest":{"sourceId":"mangapill","seriesTitle":"Serie","mangaUrl":"https://test/manga","directory":"Serie","chapters":[{"fileName":"chapter_001.cbz","label":"Capitolo 1","url":"https://test/c/1","pages":[],"alreadyPresent":true},{"fileName":"chapter_002.cbz","label":"Capitolo 2","url":"https://test/c/2","pages":[{"url":"https://test/2.jpg","name":"001.jpg"}],"alreadyPresent":false}]},"finishedChapters":[0],"completedPages":["job/0/0"],"message":"Riprende alla riapertura"}]""")
        val job = scheduler.jobs.value.single()
        assertEquals(DownloadJobState.BLOCKED, job.state)
        assertEquals(1, job.doneChapters)
        assertEquals(2, job.totalChapters)
        assertEquals("Serie", job.seriesTitle)
        assertEquals("https://test/c/2", job.lastUrl)
        scheduler.stopAll()
        assertEquals(listOf("job"), services.stopped)
        scheduler.enqueue(ChapterDownloadRequest("https://test/c/1", userInitiated = false))
        assertTrue(services.request.contains("\"userInitiated\":false"))
    }

    private class Services : IosDownloadServices {
        lateinit var observer: IosDownloadObserver
        var request = ""
        var stopped = emptyList<String>()
        override fun connect(engine: IosDownloadEngine, observer: IosDownloadObserver) { this.observer = observer }
        override fun enqueue(request: String) { this.request = request }
        override fun stop(ids: List<String>) { stopped = ids }
    }
}
