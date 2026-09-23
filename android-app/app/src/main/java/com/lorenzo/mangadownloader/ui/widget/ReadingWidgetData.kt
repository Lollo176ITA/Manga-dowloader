package com.lorenzo.mangadownloader.ui.widget

import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ResumeReadingItem
import com.lorenzo.mangadownloader.domain.reading.computeHomeResume

/** Quanti nuovi capitoli mostra il widget quando è abbastanza alto. */
const val READING_WIDGET_MAX_UPDATES = 3

/** Cosa mostra il widget: la stessa "Riprendi" della Home e gli ultimi capitoli nuovi. */
data class ReadingWidgetData(
    val resume: ResumeReadingItem?,
    val updates: List<FavoriteUpdateEvent>,
    val unseenCount: Int,
)

/** Pura: stesse regole della Home, così widget e app non raccontano due storie diverse. */
fun buildReadingWidgetData(
    library: List<DownloadedSeries>,
    memory: Map<String, ReadChapterMemory>,
    feed: List<FavoriteUpdateEvent>,
): ReadingWidgetData = ReadingWidgetData(
    resume = computeHomeResume(library, memory),
    updates = feed.sortedByDescending { it.timestampMillis }.take(READING_WIDGET_MAX_UPDATES),
    unseenCount = feed.count { !it.seen },
)

/** "Pagina 12 di 40", o `null` se non si sa dove si era arrivati. Pura. */
fun ResumeReadingItem.progressLabel(): String? {
    val page = pageIndex ?: return null
    val total = pageCount?.takeIf { it > 0 }
    return if (total != null) "Pagina ${page + 1} di $total" else "Pagina ${page + 1}"
}
