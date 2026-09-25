package com.lorenzo.mangadownloader.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Trace
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

/**
 * Solo build benchmark: collega il tracer di composizione di Compose alle sezioni
 * `android.os.Trace`, così Macrobenchmark conta le ricomposizioni per composable
 * (`TraceSectionMetric`). Fa quello che faceva `runtime-tracing`, che dalla 1.13 passa per
 * androidx.tracing 2.0 e non viene più raccolto da Macrobenchmark 1.5.
 * Un ContentProvider parte prima di `Application.onCreate`, quindi prima di ogni composizione.
 */
class ComposeTraceInstaller : ContentProvider() {
    @OptIn(InternalComposeTracingApi::class)
    override fun onCreate(): Boolean {
        Composer.setTracer(FilteredTracer)
        return true
    }

    /**
     * Emette una sezione solo per i composable misurati: una sezione atrace per OGNI composable
     * rallenta i frame quanto basta a falsare le metriche di jank. Le chiamate sono annidate,
     * quindi una pila ricorda quali start hanno aperto una sezione da chiudere.
     */
    @OptIn(InternalComposeTracingApi::class)
    private object FilteredTracer : CompositionTracer {
        // Da tenere allineato a Metrics.COMPOSABLES nel modulo :benchmark.
        private val TRACED = setOf(
            "MangaDownloaderAppContent",
            "AppTopBar",
            "AppBottomBar",
            "HomeScreen",
            "SearchScreen",
            "LibraryScreen",
            "TutorialOverlay",
            "ReaderScreen",
            "VerticalReader",
        )
        private val opened = ThreadLocal.withInitial { ArrayDeque<Boolean>() }

        override fun isTraceInProgress(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()

        // Il nome ha la forma "<fqName> (<File>.kt:<riga>)"; atrace accetta al massimo 127 caratteri.
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val traced = info.substringBefore(" (").substringAfterLast('.') in TRACED
            if (traced) Trace.beginSection(info.take(127))
            opened.get()!!.addLast(traced)
        }

        override fun traceEventEnd() {
            if (opened.get()!!.removeLastOrNull() == true) Trace.endSection()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
