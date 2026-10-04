package com.lorenzo.mangadownloader.data.download

import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.data.model.DownloadResult
import com.lorenzo.mangadownloader.data.model.readingUnitPlural
import com.lorenzo.mangadownloader.data.model.readingUnitSingular
import com.lorenzo.mangadownloader.data.sources.MangaSource
import com.lorenzo.mangadownloader.platform.currentTimeMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** A che punto è il download di una serie, come lo mostrano notifica e Libreria. */
data class SeriesDownloadProgress(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val message: String,
    val doneChapters: Int,
    val totalChapters: Int,
)

/** Esito di un download di serie andato a buon fine. */
data class SeriesDownloadSummary(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val totalChapters: Int,
    /** "capitoli", "volumi"…: l'unità di lettura dei capitoli scaricati. */
    val unitPlural: String,
)

/** L'utente ha fermato il download prima che toccasse a un capitolo. */
class DownloadStoppedException : RuntimeException()

/**
 * Scarica un intervallo di capitoli di una serie: piano, cartella, capitoli in parallelo e i
 * messaggi di avanzamento. La piattaforma ci mette intorno l'esecuzione in background (worker
 * Android) e le notifiche; qui c'è tutto ciò che deve comportarsi allo stesso modo ovunque.
 *
 * Gli errori arrivano al chiamante così come sono: è lui a decidere se ritentare (I/O) o
 * fallire, e [DownloadStoppedException] segnala lo stop richiesto da [download].
 */
class ChapterDownloader(
    private val resolveSource: (sourceId: String?, url: String) -> MangaSource,
) {
    suspend fun download(
        request: ChapterDownloadRequest,
        /** Controllato prima di ogni capitolo: `true` interrompe con [DownloadStoppedException]. */
        isStopped: () -> Boolean,
        onStatus: suspend (SeriesDownloadProgress) -> Unit,
    ): SeriesDownloadSummary {
        val source = resolveSource(request.sourceId, request.firstUrl)
        val plan = source.buildDownloadPlan(request.firstUrl, request.lastUrl)
        val unitSingular = readingUnitSingular(plan.chapters)
        val unitPlural = readingUnitPlural(plan.chapters)
        source.prepareSeriesStorage(plan)
        val totalChapters = plan.chapters.size

        fun status(message: String, done: Int) = SeriesDownloadProgress(
            sourceId = plan.sourceId,
            seriesTitle = plan.seriesTitle,
            mangaUrl = plan.mangaUrl,
            message = message,
            doneChapters = done,
            totalChapters = totalChapters,
        )

        onStatus(
            status(
                message = if (plan.startChapterLabel == plan.endChapterLabel) {
                    "Trovato 1 $unitSingular: ${plan.startChapterLabel}"
                } else {
                    "Trovati $totalChapters $unitPlural da ${plan.startChapterLabel} a ${plan.endChapterLabel}"
                },
                done = 0,
            ),
        )

        val completedChapters = atomic(0)
        val statusMutex = Mutex()
        val chapterSemaphore = Semaphore(CHAPTER_CONCURRENCY)
        val lastPageEmitMs = atomic(0L)

        suspend fun emit(message: String, done: Int = completedChapters.value) {
            statusMutex.withLock { onStatus(status(message, done)) }
        }

        coroutineScope {
            plan.chapters.map { chapter ->
                async(Dispatchers.IO) {
                    chapterSemaphore.withPermit {
                        if (isStopped()) throw DownloadStoppedException()
                        val chapterLabel = chapter.displayLabel()
                        emit("$chapterLabel in download")

                        val result = source.downloadChapterAsCbz(
                            chapter = chapter,
                            outputDir = plan.outputDir,
                            pageConcurrency = PAGE_CONCURRENCY,
                            onProcessingProgress = processing@{ processed, pageTotal ->
                                val isBoundary = processed == 0 ||
                                    processed >= pageTotal ||
                                    processed % PAGE_PROGRESS_STRIDE == 0
                                if (!isBoundary) return@processing
                                emit("$chapterLabel: preparazione immagini $processed/$pageTotal")
                            },
                        ) download@{ pageDone, pageTotal ->
                            // Niente aggiornamento a ogni pagina: un capitolo di 50 pagine
                            // sveglierebbe la UI 50 volte. Solo l'ultima, i multipli e, se
                            // la rete è lenta, almeno uno ogni tanto.
                            val isFinalPage = pageDone >= pageTotal
                            val isBatchBoundary = pageDone % PAGE_PROGRESS_STRIDE == 0
                            val now = currentTimeMillis()
                            val timedOut = now - lastPageEmitMs.value >= PAGE_PROGRESS_MIN_INTERVAL_MS
                            if (!isFinalPage && !isBatchBoundary && !timedOut) return@download
                            lastPageEmitMs.value = now
                            emit("$chapterLabel: pagina $pageDone/$pageTotal")
                        }

                        val done = completedChapters.incrementAndGet()
                        val message = when (result) {
                            DownloadResult.DOWNLOADED -> "$chapterLabel completato"
                            DownloadResult.SKIPPED_EXISTING -> "$chapterLabel già presente"
                        }
                        emit(message, done)
                    }
                }
            }.awaitAll()
        }

        onStatus(status("Download completato: $totalChapters $unitPlural", totalChapters))
        return SeriesDownloadSummary(
            sourceId = plan.sourceId,
            seriesTitle = plan.seriesTitle,
            mangaUrl = plan.mangaUrl,
            totalChapters = totalChapters,
            unitPlural = unitPlural,
        )
    }

    private companion object {
        const val CHAPTER_CONCURRENCY = 2
        const val PAGE_CONCURRENCY = 4
        const val PAGE_PROGRESS_STRIDE = 5
        const val PAGE_PROGRESS_MIN_INTERVAL_MS = 1_500L
    }
}
