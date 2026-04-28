package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DownloadedSeriesScreen(
    series: DownloadedSeries,
    padding: PaddingValues,
    onOpenChapter: (DownloadedChapter) -> Unit,
    onDeleteChapter: (DownloadedChapter) -> Unit,
) {
    val isFullyRead = series.isFullyRead()
    var chapterPendingDelete by remember { mutableStateOf<DownloadedChapter?>(null) }
    val firstChapter = remember(series) { series.chapters.firstOrNull() }
    val resumeChapter = remember(series) { series.resumeChapter() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        SeriesHeader(
            coverModel = series.coverFile,
            title = series.title,
            subtitle = "${series.chapters.size} capitoli scaricati",
            status = if (isFullyRead) "Completato" else null,
            statusColor = ReadGreen,
        )

        val anchorFor = LocalTutorialAnchor.current
        val firstChapterPath = series.chapters.firstOrNull()?.relativePath
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(series.chapters, key = { it.relativePath }) { chapter ->
                val chapterModifier = if (chapter.relativePath == firstChapterPath) {
                    anchorFor(TutorialAnchor.DOWNLOADED_CHAPTER_FIRST)
                } else {
                    Modifier
                }
                Box(modifier = chapterModifier) {
                    DownloadedChapterRow(
                        chapter = chapter,
                        onOpen = { onOpenChapter(chapter) },
                        onDelete = { chapterPendingDelete = chapter },
                    )
                }
            }
        }

        val downloadedCount = series.chapters.size
        DownloadedSeriesActionBar(
            readCount = series.readChapterCount().coerceAtMost(downloadedCount),
            totalCount = downloadedCount,
            firstChapter = firstChapter,
            resumeChapter = resumeChapter,
            onOpenChapter = onOpenChapter,
        )
    }

    chapterPendingDelete?.let { chapter ->
        DeleteChapterDialog(
            chapterTitle = chapter.title,
            onDismiss = { chapterPendingDelete = null },
            onConfirm = {
                chapterPendingDelete = null
                onDeleteChapter(chapter)
            },
        )
    }
}
