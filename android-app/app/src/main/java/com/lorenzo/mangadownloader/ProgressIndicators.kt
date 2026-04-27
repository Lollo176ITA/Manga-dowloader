package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
