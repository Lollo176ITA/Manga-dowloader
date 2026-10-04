package com.lorenzo.mangadownloader.data.library

import com.lorenzo.mangadownloader.data.model.ChapterNumber
import kotlinx.serialization.Serializable
import okio.Path

data class DownloadedChapter(
    val title: String,
    val numberText: String,
    val numberValue: ChapterNumber?,
    val volumeText: String?,
    val labelPrefix: String,
    val variantTag: String? = null,
    val file: Path,
    val relativePath: String,
    val chapterId: String,
    val isRead: Boolean,
    val readerPageIndex: Int?,
    val readerPageCount: Int?,
    val lastReadAtMillis: Long? = null,
    /** Quando la fonte ha pubblicato il capitolo, se lo sapevamo al download. */
    val publishedAtMillis: Long? = null,
)

data class ReaderPagePosition(
    val pageIndex: Int,
    val pageCount: Int?,
    val lastReadAtMillis: Long? = null,
)

data class DownloadedSeries(
    val sourceId: String,
    val title: String,
    val mangaUrl: String?,
    val coverFile: Path?,
    val directory: Path,
    val chapters: List<DownloadedChapter>,
    val totalChapterCount: Int,
    val readChapterIds: Set<String>,
)

@Serializable
data class SeriesMetadata(
    val sourceId: String = "",
    val title: String = "",
    val mangaUrl: String? = null,
    val coverFileName: String? = null,
    val totalChapters: Int? = null,
    val readChapterIds: Set<String> = emptySet(),
    val chapters: List<SeriesMetadataChapter> = emptyList(),
)

@Serializable
data class SeriesMetadataChapter(
    val numberText: String = "",
    val url: String? = null,
    val slug: String? = null,
    val fileName: String = "",
    val id: String? = null,
    val volumeText: String? = null,
    val labelPrefix: String = "Capitolo",
    val variantTag: String? = null,
    /**
     * Data di pubblicazione dichiarata dalla fonte al momento del download (epoch millis).
     * Salvata qui perché la lista dei capitoli scaricati si legge **offline**, quando la fonte
     * non è interrogabile. `null` per i capitoli scaricati da fonti che non la pubblicano e
     * per quelli già su disco da prima di questo campo: il JSON ha default, quindi i
     * `series.json` esistenti continuano a leggersi senza migrazione.
     */
    val publishedAtMillis: Long? = null,
)
