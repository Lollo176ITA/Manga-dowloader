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

/** "pagina 8 di 40", come nella card "Riprendi" della Home; `null` senza il totale. Pura. */
fun ResumeReadingItem.widgetPageLabel(): String? {
    val page = pageIndex ?: return null
    val total = pageCount?.takeIf { it > 0 } ?: return null
    return "pagina ${page + 1} di $total"
}

/** Avanzamento nel capitolo (0..1) per la barra della card completa. Pura. */
fun ResumeReadingItem.readProgress(): Float {
    val total = pageCount?.takeIf { it > 0 } ?: return 0f
    return (((pageIndex ?: 0) + 1).toFloat() / total).coerceIn(0f, 1f)
}
