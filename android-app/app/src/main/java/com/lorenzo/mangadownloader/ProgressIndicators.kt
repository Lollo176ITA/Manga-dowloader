package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
fun LibraryReadProgressRing(
    readCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    val safeTotal = totalCount.coerceAtLeast(1)
    val fraction = (readCount.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    val percent = (fraction * 100f).toInt()
    val isComplete = totalCount > 0 && readCount >= totalCount
    val label = if (isComplete) {
        "Completato"
    } else {
        "$percent per cento letto"
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(48.dp),
            color = if (isComplete) ReadGreen else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        if (isComplete) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ReadGreen,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
