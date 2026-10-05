package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.download.BackgroundChapterDownloader
import com.lorenzo.mangadownloader.data.download.BackgroundDownloadManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import kotlin.coroutines.ContinuationInterceptor

fun interface IosDownloadResult { fun complete(value: String?, error: String?) }
fun interface IosDownloadObserver { fun update(snapshot: String) }

/** Coda, trasferimenti e runtime iOS appartengono all'host; il motore conserva parsing e CBZ. */
interface IosDownloadServices {
    fun connect(engine: IosDownloadEngine, observer: IosDownloadObserver)
    fun enqueue(request: String)
    fun stop(ids: List<String>)
}

class IosDownloadEngine internal constructor(
    private val scope: CoroutineScope,
    private val downloader: BackgroundChapterDownloader,
    private val onChapterReady: () -> Unit,
) {
    private val work = mutableMapOf<String, Job>()

    fun prepare(jobId: String, request: String, result: IosDownloadResult) = prepare(jobId, request, null, null, result)

    /** [previous] è l'ultimo checkpoint salvato dall'host; [checkpoint] riceve i nuovi sul main thread. */
    fun prepare(jobId: String, request: String, previous: String?, checkpoint: IosDownloadObserver?, result: IosDownloadResult) {
        execute("$jobId:prepare", result) { onCaller ->
            downloader.prepare(
                JSON.decodeFromString<ChapterDownloadRequest>(request),
                previous = previous?.let(BackgroundDownloadManifest::decode),
                onCheckpoint = { manifest ->
                    if (checkpoint != null) {
                        val text = manifest.encode()
                        onCaller { checkpoint.update(text) }
                    }
                },
            ).encode()
        }
    }

    fun finalizeChapter(jobId: String, manifest: String, index: Int, stagingDirectory: String, result: IosDownloadResult) {
        execute("$jobId:$index", result) { _ ->
            downloader.finalizeChapter(BackgroundDownloadManifest.decode(manifest), index, stagingDirectory.toPath())
            onChapterReady()
            "ready"
        }
    }

    /** Chiamato sul main thread dall'host, come prepare/finalizeChapter. */
    fun cancel(jobId: String) {
        work.filterKeys { it.startsWith("$jobId:") }.values.toList().forEach { it.cancel() }
    }

    /** [action] lavora su IO; la funzione che riceve riporta un blocco sul dispatcher dell'host. */
    private fun execute(key: String, result: IosDownloadResult, action: suspend (suspend (() -> Unit) -> Unit) -> String) {
        check(key !in work) { "Operazione download già attiva: $key" }
        var delivered = false
        fun complete(value: String?, error: String?) {
            if (delivered) return
            delivered = true
            work.remove(key)
            result.complete(value, error)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val caller = coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main
                val value = withContext(Dispatchers.IO) { action { block -> withContext(caller) { block() } } }
                complete(value, null)
            } catch (_: CancellationException) {
                complete(null, "Operazione interrotta")
            } catch (error: Exception) {
                complete(null, error.message ?: "Download non riuscito")
            }
        }
        work[key] = job
        // La cancellazione può precedere anche il primo dispatch della coroutine.
        job.invokeOnCompletion {
            if (work[key] === job) work.remove(key)
            if (!delivered) complete(null, "Operazione interrotta")
        }
        job.start()
    }
}

internal class IosDownloadScheduler(
    private val services: IosDownloadServices,
    engine: IosDownloadEngine,
) : DownloadScheduler {
    private val state = MutableStateFlow<List<DownloadJob>>(emptyList())
    override val jobs = state.asStateFlow()

    init {
        services.connect(engine, IosDownloadObserver { snapshot ->
            state.value = JSON.decodeFromString<List<NativeJobSnapshot>>(snapshot).map { native ->
                DownloadJob(
                    id = native.id,
                    state = when (native.phase) {
                        "queued" -> DownloadJobState.ENQUEUED
                        "paused" -> DownloadJobState.BLOCKED
                        "succeeded" -> DownloadJobState.SUCCEEDED
                        "failed" -> DownloadJobState.FAILED
                        "cancelled" -> DownloadJobState.CANCELLED
                        else -> DownloadJobState.RUNNING
                    },
                    sourceId = native.manifest?.sourceId ?: native.request.sourceId,
                    seriesTitle = native.manifest?.seriesTitle ?: native.request.seriesTitle,
                    mangaUrl = native.manifest?.mangaUrl ?: native.request.mangaUrl,
                    coverUrl = native.request.coverUrl,
                    message = native.message,
                    doneChapters = native.finishedChapters.size,
                    totalChapters = native.manifest?.chapters?.size ?: -1,
                    firstUrl = native.request.firstUrl,
                    lastUrl = native.request.lastUrl,
                )
            }
        })
    }

    override fun enqueue(request: ChapterDownloadRequest) = services.enqueue(JSON.encodeToString(request))
    override fun stopAll() = stop(state.value.filter { it.isActive }.map { it.id })
    override fun stop(jobIds: Collection<String>) = services.stop(jobIds.toList())
}

@Serializable
private data class NativeJobSnapshot(
    val id: String,
    val request: ChapterDownloadRequest,
    val phase: String,
    val manifest: BackgroundDownloadManifest? = null,
    val finishedChapters: Set<Int> = emptySet(),
    val message: String,
)

private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
