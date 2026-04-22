package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal

data class ChapterEntry(
    val numberText: String,
    val numberValue: BigDecimal,
    val url: String,
    val slug: String,
) {
    fun displayNumber(): String = numberValue.stripTrailingZeros().toPlainString()
}

data class DownloadPlan(
    val sourceId: String,
    val seriesTitle: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val outputDir: File,
    val chapters: List<ChapterEntry>,
    val totalChapterCount: Int,
    val startChapterLabel: String,
    val endChapterLabel: String,
)

data class MangaSearchResult(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
)

data class MangaDetails(
    val sourceId: String,
    val title: String,
    val coverUrl: String?,
    val mangaUrl: String,
    val chapters: List<ChapterEntry>,
)

enum class DownloadResult {
    DOWNLOADED,
    SKIPPED_EXISTING,
}
