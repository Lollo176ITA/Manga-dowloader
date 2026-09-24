package com.lorenzo.mangadownloader

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.lorenzo.mangadownloader.ui.widget.ReadingWidget

/**
 * Provider del widget "Continua a leggere". Resta nel package principale insieme a
 * [MainActivity]: il launcher registra i widget piazzati col nome completo di questa classe.
 */
class ReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingWidget()
}
