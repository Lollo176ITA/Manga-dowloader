package com.lorenzo.mangadownloader.ui.library

import android.content.Context
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.showAutoDismissSnackbar
import kotlinx.coroutines.launch

internal data class DownloadWorkUiState(
    val statuses: Map<String, SeriesDownloadStatus>,
)

/** Osserva la coda download, aggiorna la libreria e presenta i fallimenti recenti. */
@Composable
internal fun rememberDownloadWorkUiState(
    context: Context,
    viewModel: MangaViewModel,
    snackbarHostState: SnackbarHostState,
): DownloadWorkUiState {
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val workManager = remember(appContext) { WorkManager.getInstance(appContext) }
    val workInfos by remember(workManager) { DownloadWorker.observeAll(workManager) }
        .collectAsStateWithLifecycle(emptyList())
    val activeWorkInfos = remember(workInfos) { workInfos.filter(WorkInfo::isActiveDownload) }
    // Capitoli completati per ogni download attivo: cambia quando uno qualsiasi avanza. Con
    // le catene per serie possono esserci più worker RUNNING (chi aspetta il turno lo è).
    val chapterProgressKey = remember(activeWorkInfos) {
        activeWorkInfos
            .mapNotNull { info ->
                info.progress.getInt(DownloadWorker.PROGRESS_DONE_CHAPTERS, -1)
                    .takeIf { it > 0 }
                    ?.let { "${info.id}:$it" }
            }
            .sorted()
            .joinToString("|")
            .ifEmpty { null }
    }
    val terminalWorkKey = remember(workInfos) {
        workInfos
            .filter(WorkInfo::isTerminalDownload)
            .map { "${it.id}:${it.state}" }
            .sorted()
            .joinToString("|")
    }
    val statuses = remember(activeWorkInfos) { buildSeriesDownloadStatuses(activeWorkInfos) }
    var lastForcedChapterProgressKey by remember { mutableStateOf<String?>(null) }
    var lastForcedTerminalWorkKey by remember { mutableStateOf("") }

    LaunchedEffect(
        chapterProgressKey,
        terminalWorkKey,
        activeWorkInfos.size,
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
    LaunchedEffect(workInfos) {
        val failed = workInfos.filter { it.state == WorkInfo.State.FAILED }
        if (!failuresInitialized) {
            handledFailureIds = failed.mapTo(mutableSetOf()) { it.id.toString() }
            failuresInitialized = true
            return@LaunchedEffect
        }
        val fresh = failed.filter { it.id.toString() !in handledFailureIds }
        if (fresh.isEmpty()) return@LaunchedEffect
        handledFailureIds = handledFailureIds + fresh.map { it.id.toString() }

        val workInfo = fresh.last()
        val data = workInfo.progress
        val output = workInfo.outputData
        fun field(key: String) = output.getString(key)?.takeIf(String::isNotBlank)
            ?: data.getString(key)?.takeIf(String::isNotBlank)
        fun tagValue(prefix: String) = workInfo.tags
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf(String::isNotBlank)

        val title = field(DownloadWorker.PROGRESS_SERIES_TITLE)
            ?: tagValue(DownloadWorker.TAG_SERIES_TITLE_PREFIX)
        val message = field(DownloadWorker.PROGRESS_MESSAGE) ?: "errore sconosciuto"
        val firstUrl = field(DownloadWorker.PROGRESS_FIRST_URL)
        val label = title?.let { "Download di $it non riuscito" } ?: "Download non riuscito"
        scope.launch {
            val result = snackbarHostState.showAutoDismissSnackbar(
                message = "$label: $message",
                actionLabel = if (firstUrl != null) "Riprova" else null,
            )
            if (result == SnackbarResult.ActionPerformed && firstUrl != null) {
                DownloadWorker.enqueue(
                    context = appContext,
                    firstUrl = firstUrl,
                    lastUrl = field(DownloadWorker.PROGRESS_LAST_URL),
                    sourceId = field(DownloadWorker.PROGRESS_SOURCE_ID)
                        ?: tagValue(DownloadWorker.TAG_SOURCE_ID_PREFIX),
                    seriesTitle = title,
                    mangaUrl = field(DownloadWorker.PROGRESS_MANGA_URL)
                        ?: tagValue(DownloadWorker.TAG_MANGA_URL_PREFIX),
                    coverUrl = tagValue(DownloadWorker.TAG_COVER_URL_PREFIX),
                )
            }
        }
    }

    return DownloadWorkUiState(statuses)
}

private fun WorkInfo.isActiveDownload(): Boolean =
    state == WorkInfo.State.RUNNING ||
        state == WorkInfo.State.ENQUEUED ||
        state == WorkInfo.State.BLOCKED

private fun WorkInfo.isTerminalDownload(): Boolean =
    state == WorkInfo.State.SUCCEEDED ||
        state == WorkInfo.State.FAILED ||
        state == WorkInfo.State.CANCELLED
