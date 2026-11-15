package com.lorenzo.mangadownloader.ui.app

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.showAutoDismissSnackbar
import kotlinx.coroutines.launch

/**
 * Azione "scarica da [ChapterEntry] a [ChapterEntry]": mette in coda il download, chiede il
 * permesso notifiche se manca e offre il salto in Libreria, dove vivono progresso, coda e stop.
 */
@Composable
internal fun rememberStartDownload(
    appContext: Context,
    snackbarHostState: SnackbarHostState,
    notifications: NotificationPermissions,
    onQueued: (MangaDetails) -> Unit,
    onOpenLibrary: () -> Unit,
): (MangaDetails, ChapterEntry, ChapterEntry) -> Unit {
    val scope = rememberCoroutineScope()
    return { details, startChapter, endChapter ->
        val firstUrl = startChapter.url.trim()
        val lastUrl = endChapter.url.trim()
        if (firstUrl.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("URL non valido")
            }
        } else {
            try {
                notifications.requestForDownloadIfMissing()
                DownloadWorker.enqueue(
                    context = appContext,
                    firstUrl = firstUrl,
                    lastUrl = lastUrl,
                    sourceId = details.sourceId,
                    seriesTitle = details.title,
                    mangaUrl = details.mangaUrl,
                    coverUrl = details.coverUrl,
                )
                onQueued(details)
                scope.launch {
                    val result = snackbarHostState.showAutoDismissSnackbar(
                        message = if (startChapter.url == endChapter.url) {
                            "Download aggiunto in coda: ${startChapter.displayLabel()}"
                        } else {
                            "Download aggiunto in coda: ${startChapter.displayLabel()} - ${endChapter.displayLabel()}"
                        },
                        actionLabel = "Libreria",
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onOpenLibrary()
                    }
                }
            } catch (exc: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar(exc.message ?: "Impossibile avviare il download")
                }
            }
        }
    }
}
