package com.lorenzo.mangadownloader.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.lorenzo.mangadownloader.ReadingWidgetReceiver

/** Aggiunta del widget dalla app: la richiesta la fa il launcher, con il suo dialog. */
object ReadingWidgetPinning {

    private fun provider(context: Context) = ComponentName(context, ReadingWidgetReceiver::class.java)

    /** Almeno un widget è già nella Home. */
    fun isPlaced(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(provider(context)).isNotEmpty()

    /** Il launcher accetta la richiesta di aggiunta (non tutti lo fanno). */
    fun canRequestPin(context: Context): Boolean =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** Chiede al launcher di aggiungere il widget; `false` se la richiesta non è partita. */
    fun requestPin(context: Context): Boolean =
        canRequestPin(context) &&
            AppWidgetManager.getInstance(context).requestPinAppWidget(provider(context), null, null)
}
