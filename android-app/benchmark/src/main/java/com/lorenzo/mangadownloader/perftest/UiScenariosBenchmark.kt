package com.lorenzo.mangadownloader.perftest

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ITERATIONS = 5
private const val QUERY = "berserk"

/**
 * Scenari UI misurati da scripts/perf.ps1. Ogni iterazione riparte da dati puliti
 * (AppSetup.resetApp) così i conteggi di ricomposizione sono confrontabili tra giri.
 */
@RunWith(AndroidJUnit4::class)
class UiScenariosBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Reset + avvio fino alla Home + [prepare]; misura solo [measure]. */
    private fun scenario(
        prepare: MacrobenchmarkScope.() -> Unit = {},
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = BenchData.TARGET_PACKAGE,
        metrics = Metrics.ui(),
        iterations = ITERATIONS,
        // Partial() pretende un Baseline Profile che non c'è ancora; DEFAULT lo usa se presente.
        compilationMode = CompilationMode.DEFAULT,
        startupMode = null,
        setupBlock = {
            AppSetup.resetApp(device)
            launchToHome()
            prepare()
        },
        measureBlock = measure,
    )

    @Test
    fun coldStartup() = rule.measureRepeated(
        packageName = BenchData.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        setupBlock = { AppSetup.resetApp(device) },
    ) {
        startActivityAndWait()
    }

    @Test
    fun homeScroll() = scenario(
        // Best effort: se i feed AniList arrivano durante la misura sporcano i conteggi.
        prepare = { runCatching { waitForText("Tendenze") } },
    ) {
        swipeUp(8)
    }

    @Test
    fun searchTyping() = scenario(
        prepare = {
            openTab("Cerca")
            // Si tocca il segnaposto, non "il primo EditText": il pager tiene composte anche le
            // pagine vicine (Preferiti ha il suo campo, e un pulsante "Cerca manga" quando è
            // vuoto). Il campo è l'occorrenza visibile più in alto.
            waitForText("Cerca manga")
            val focusSearchField = {
                device.findObjects(By.text("Cerca manga"))
                    .filter { it.visibleBounds.run { !isEmpty && centerX() in 0 until device.displayWidth } }
                    .minBy { it.visibleBounds.top }
                    .click()
                device.waitForIdle()
            }
            focusSearchField()
            if (dismissKeyboardOnboarding()) focusSearchField()
        },
    ) {
        typeSlowly(QUERY)
        // Lascia arrivare la ricerca automatica partita dall'ultimo tasto.
        SystemClock.sleep(1_000)
        // Senza questo controllo uno scenario che non scrive nulla "passerebbe" con zero misure.
        check(device.hasObject(By.clazz("android.widget.EditText").text(QUERY))) {
            "Digitazione non arrivata al campo di ricerca"
        }
    }

    @Test
    fun tabSwitch() = scenario {
        openTab("Cerca")
        openTab("Preferiti")
        openTab("Libreria")
        openTab("Home")
    }

    @Test
    fun readerScroll() = scenario(
        prepare = {
            openTab("Libreria")
            clickUntilText(BenchData.READER_SERIES, BenchData.READER_CHAPTER)
            device.findObject(By.text(BenchData.READER_CHAPTER)).click()
            // Il reader apre il .cbz e decodifica le prime pagine prima di essere scorrevole.
            SystemClock.sleep(2_000)
            device.waitForIdle()
        },
    ) {
        swipeUp(15)
    }

    @Test
    fun libraryScroll() = scenario(
        prepare = {
            openTab("Libreria")
            waitForText(BenchData.READER_SERIES)
        },
    ) {
        swipeUp(5)
    }
}
