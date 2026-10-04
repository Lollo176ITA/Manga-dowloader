package com.lorenzo.mangadownloader.data.model

import okio.Path

data class DownloadPlan(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val outputDir: Path,
    val chapters: List<ChapterEntry>,
    val totalChapterCount: Int,
    val startChapterLabel: String,
    val endChapterLabel: String,
)
