package com.lorenzo.mangadownloader.ui.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lorenzo.mangadownloader.app.ChapterDownloadRequest
import com.lorenzo.mangadownloader.app.DownloadJob
import com.lorenzo.mangadownloader.app.DownloadJobState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.ui.components.showAutoDismissSnackbar
import kotlinx.coroutines.launch

internal data class DownloadWorkUiState(
    val statuses: Map<String, SeriesDownloadStatus>,
)

/** Osserva la coda download, aggiorna la libreria e presenta i fallimenti recenti. */
@Composable
internal fun rememberDownloadWorkUiState(
    viewModel: MangaViewModel,
    snackbarHostState: SnackbarHostState,
): DownloadWorkUiState {
    val scope = rememberCoroutineScope()
    val jobs by viewModel.downloadJobs.collectAsStateWithLifecycle(emptyList())
    val activeJobs = remember(jobs) { jobs.filter(DownloadJob::isActive) }
    // Capitoli completati per ogni download attivo: cambia quando uno qualsiasi avanza. Con
    // le catene per serie possono esserci più download in esecuzione (chi aspetta il turno lo è).
    val chapterProgressKey = remember(activeJobs) {
        activeJobs
            .mapNotNull { job ->
                job.doneChapters
                    .takeIf { it > 0 }
                    ?.let { "${job.id}:$it" }
            }
            .sorted()
            .joinToString("|")
            .ifEmpty { null }
    }
    val terminalWorkKey = remember(jobs) {
        jobs
            .filter(DownloadJob::isTerminal)
            .map { "${it.id}:${it.state}" }
            .sorted()
            .joinToString("|")
    }
    val statuses = remember(activeJobs) { buildSeriesDownloadStatuses(activeJobs) }
    var lastForcedChapterProgressKey by remember { mutableStateOf<String?>(null) }
    var lastForcedTerminalWorkKey by remember { mutableStateOf("") }

    LaunchedEffect(
        chapterProgressKey,
        terminalWorkKey,
        activeJobs.size,
    ) {
        val chapterCompleted = chapterProgressKey != null &&
            chapterProgressKey != lastForcedChapterProgressKey
        val workerTerminated = terminalWorkKey.isNotBlank() &&
            terminalWorkKey != lastForcedTerminalWorkKey

        if (chapterCompleted) lastForcedChapterProgressKey = chapterProgressKey
        if (workerTerminated) lastForcedTerminalWorkKey = terminalWorkKey
        viewModel.refreshLibrary(forceRefresh = chapterCompleted || workerTerminated)
    }

    var handledFailureIds by remember { mutableStateOf(emptySet<String>()) }
    var failuresInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(jobs) {
        val failed = jobs.filter { it.state == DownloadJobState.FAILED }
        if (!failuresInitialized) {
            handledFailureIds = failed.mapTo(mutableSetOf()) { it.id }
            failuresInitialized = true
            return@LaunchedEffect
        }
        val fresh = failed.filter { it.id !in handledFailureIds }
        if (fresh.isEmpty()) return@LaunchedEffect
        handledFailureIds = handledFailureIds + fresh.map { it.id }

        val job = fresh.last()
        val title = job.seriesTitle
        val message = job.message ?: "errore sconosciuto"
        val firstUrl = job.firstUrl
        val label = title?.let { "Download di $it non riuscito" } ?: "Download non riuscito"
        scope.launch {
            val result = snackbarHostState.showAutoDismissSnackbar(
                message = "$label: $message",
                actionLabel = if (firstUrl != null) "Riprova" else null,
            )
            if (result == SnackbarResult.ActionPerformed && firstUrl != null) {
                viewModel.startDownload(
                    ChapterDownloadRequest(
                        firstUrl = firstUrl,
                        lastUrl = job.lastUrl,
                        sourceId = job.sourceId,
                        seriesTitle = title,
                        mangaUrl = job.mangaUrl,
                        coverUrl = job.coverUrl,
                    ),
                )
            }
        }
    }

    return DownloadWorkUiState(statuses)
}
