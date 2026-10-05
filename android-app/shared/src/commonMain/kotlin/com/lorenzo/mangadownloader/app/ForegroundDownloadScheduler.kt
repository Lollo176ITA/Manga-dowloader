package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.download.SeriesDownloadProgress
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Coda seriale in memoria: lavora finché il processo della piattaforma resta attivo. */
class ForegroundDownloadScheduler(
    private val scope: CoroutineScope,
    private val download: suspend (ChapterDownloadRequest, suspend (SeriesDownloadProgress) -> Unit) -> Unit,
) : DownloadScheduler {
    private val nextId = atomic(0L)
    private val queue = Mutex()
    private val lock = SynchronizedObject()
    private val activeJobs = mutableMapOf<String, Job>()
    private val state = MutableStateFlow<List<DownloadJob>>(emptyList())
    override val jobs = state.asStateFlow()

    override fun enqueue(request: ChapterDownloadRequest) {
        val id = "foreground-${nextId.incrementAndGet()}"
        state.update { it + DownloadJob(
            id = id, state = DownloadJobState.ENQUEUED,
            sourceId = request.sourceId, seriesTitle = request.seriesTitle,
            mangaUrl = request.mangaUrl, coverUrl = request.coverUrl,
            firstUrl = request.firstUrl, lastUrl = request.lastUrl,
        ) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                queue.withLock {
                    coroutineContext.ensureActive()
                    update(id) { copy(state = DownloadJobState.RUNNING) }
                    download(request) { progress ->
                        update(id) { copy(
                            sourceId = progress.sourceId, seriesTitle = progress.seriesTitle,
                            mangaUrl = progress.mangaUrl, message = progress.message,
                            doneChapters = progress.doneChapters, totalChapters = progress.totalChapters,
                        ) }
                    }
                    coroutineContext.ensureActive()
                    update(id) { copy(state = DownloadJobState.SUCCEEDED) }
                }
            } catch (cancelled: CancellationException) {
                update(id) { copy(state = DownloadJobState.CANCELLED) }
                throw cancelled
            } catch (error: Exception) {
                update(id) { copy(state = DownloadJobState.FAILED, message = error.message) }
            }
        }
        synchronized(lock) { activeJobs[id] = job }
        job.invokeOnCompletion { synchronized(lock) { activeJobs.remove(id) } }
        job.start()
    }

    override fun stopAll() = stop(jobs.value.filter { it.isActive }.map { it.id })

    override fun stop(jobIds: Collection<String>) {
        jobIds.forEach { id ->
            synchronized(lock) { activeJobs[id] }?.cancel()
            update(id) { copy(state = DownloadJobState.CANCELLED) }
        }
    }

    private fun update(id: String, transform: DownloadJob.() -> DownloadJob) {
        state.update { jobs -> jobs.map { if (it.id == id && it.isActive) it.transform() else it } }
    }
}
