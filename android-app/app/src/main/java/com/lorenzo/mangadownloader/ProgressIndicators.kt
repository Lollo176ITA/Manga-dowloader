package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppLoadingIndicator(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadProgressIndicator(
    doneChapters: Int,
    totalChapters: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val fraction = if (doneChapters >= 0 && totalChapters > 0) {
        (doneChapters.toFloat() / totalChapters.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    if (fraction != null) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = modifier,
        )
    } else {
        LinearWavyProgressIndicator(modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChapterReadProgressRing(
    pageIndex: Int?,
    pageCount: Int?,
    modifier: Modifier = Modifier,
) {
    val fraction = if (pageIndex != null && pageCount != null && pageCount > 0) {
        ((pageIndex + 1).toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val label = if (pageIndex != null && pageCount != null && pageCount > 0) {
        "Pagina ${pageIndex + 1} di $pageCount"
    } else {
        "Lettura in corso"
    }
    val ringModifier = Modifier.size(24.dp)

    Box(
        modifier = modifier
            .size(32.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) {
            CircularWavyProgressIndicator(
                progress = { fraction },
                modifier = ringModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            CircularWavyProgressIndicator(
                modifier = ringModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
