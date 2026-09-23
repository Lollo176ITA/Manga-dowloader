package com.lorenzo.mangadownloader.ui.widget

import com.lorenzo.mangadownloader.data.library.DownloadedSeries
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ResumeReadingItem
import com.lorenzo.mangadownloader.domain.reading.computeHomeResume

/**
 * La lettura che mostra il widget: la stessa del blocco "Riprendi" della Home, così widget e
 * app non raccontano due storie diverse. `null` = niente da riprendere. Pura.
 */
fun readingWidgetResume(
    library: List<DownloadedSeries>,
    memory: Map<String, ReadChapterMemory>,
): ResumeReadingItem? = computeHomeResume(library, memory)

/** "Capitolo 12 · pagina 8 di 40": la seconda riga del widget. Pura. */
fun ResumeReadingItem.widgetSubtitle(): String {
    val page = pageIndex ?: return chapterLabel
    val total = pageCount?.takeIf { it > 0 }
    val progress = if (total != null) "pagina ${page + 1} di $total" else "pagina ${page + 1}"
    return "$chapterLabel · $progress"
}
