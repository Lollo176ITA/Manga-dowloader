package com.lorenzo.mangadownloader.perftest

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric

object Metrics {
    /** Composable di cui contare le ricomposizioni (sezioni emesse da ComposeTraceInstaller, build benchmark). */
    val COMPOSABLES = listOf(
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

    /**
     * Frame + una metrica di conteggio per composable + ms totali di composizione della radice.
     * Le sezioni si chiamano "<fqName> (<File>.kt:<riga>)": il filtro "%.<Nome> (%" prende il
     * composable e non le sue lambda ("….<Nome>.<anonymous> (").
     */
    @OptIn(ExperimentalMetricApi::class)
    fun ui(): List<Metric> = buildList {
        add(FrameTimingMetric())
        COMPOSABLES.forEach { name ->
            add(TraceSectionMetric("%.$name (%", TraceSectionMetric.Mode.Count, label = "recomp_$name"))
        }
        add(
            TraceSectionMetric(
                "%.MangaDownloaderAppContent (%",
                TraceSectionMetric.Mode.Sum,
                label = "rootCompose",
            ),
        )
    }
}
