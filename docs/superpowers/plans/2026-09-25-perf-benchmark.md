# Benchmark UI in locale — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un comando locale (`scripts/perf.ps1`, orchestrato dalla skill `/perf`) che misura ricomposizioni, costo di composizione della radice e frame dell'app sull'emulatore e scrive un report Markdown confrontato col giro precedente.

**Architecture:** Build type `benchmark` nell'app (release firmata debug, `.benchmark`, con `runtime-tracing` solo lì) + modulo `:benchmark` (Macrobenchmark + UiAutomator) con 6 scenari e dati finti generati al volo + `perf_report.py` (solo stdlib) che trasforma il JSON di Macrobenchmark in report + skill di progetto.

**Tech Stack:** AGP 9.4.1 (Kotlin integrato), Kotlin 2.4.20, Compose BOM 2026.09.00, `androidx.benchmark:benchmark-macro-junit4:1.5.0`, `androidx.test.uiautomator:uiautomator:2.4.0`, `androidx.tracing:tracing-perfetto(-binary):1.0.1`, `androidx.compose.runtime:runtime-tracing` (BOM → 1.12.1), `androidx.profileinstaller:profileinstaller:1.4.1`, Python 3.14 stdlib, PowerShell 5.1.

**Spec:** [docs/superpowers/specs/2026-09-25-perf-benchmark-design.md](../specs/2026-09-25-perf-benchmark-design.md)

## Global Constraints

- **Niente commit né push** in nessun task: l'utente committa da sé. Gli step "Commit" del template sono sostituiti da "Verifica `git status`".
- Branch di lavoro: `dev` (già allineato a `main`).
- Pacchetto dell'app da misurare: `com.lorenzo.mangadownloader.benchmark`. Pacchetto/namespace del modulo di test: `com.lorenzo.mangadownloader.perftest`.
- `runtime-tracing` **solo** in `benchmarkImplementation`; la release pubblicata non cambia (unica dipendenza nuova in `implementation`: `profileinstaller`).
- Emulatore: AVD `Pixel_8`, API 36, seriale tipico `emulator-5554`.
- Gradle da PowerShell con **percorsi assoluti**: `& "C:\Users\utente\Documents\Lorenzo Censi\Manga-dowloader\android-app\gradlew.bat" -p "C:\Users\utente\Documents\Lorenzo Censi\Manga-dowloader\android-app" <task>`.
- `adb`: `C:\Users\utente\AppData\Local\Android\Sdk\platform-tools\adb.exe`; da Git Bash serve `export MSYS_NO_PATHCONV=1`.
- Il percorso del repo contiene uno spazio (`Lorenzo Censi`): quotare sempre.
- Commenti nel codice in italiano, stile del resto del progetto.
- Scenari: `coldStartup`, `homeScroll`, `searchTyping`, `tabSwitch`, `readerScroll`, `libraryScroll`; 5 iterazioni ciascuno.
- Composable tracciati: `MangaDownloaderAppContent`, `AppTopBar`, `AppBottomBar`, `HomeScreen`, `SearchScreen`, `LibraryScreen`, `TutorialOverlay`, `ReaderScreen`, `VerticalReader`.
- Report in `perf-reports/` (in `.gitignore`): `AAAA-MM-GG_HHMM.md`, `AAAA-MM-GG_HHMM.json`, `latest.json`.

## Review Focus

- **Nomi delle sezioni Compose diversi dal filtro `%.<Nome> (%`** → tutte le ricomposizioni a zero: il report deve dirlo esplicitamente ("tracing Compose assente"), non mostrare zeri. Test: Task 4, `test_no_tracing_warning`.
- **`latest.json` corrotto o che punta a un file sparito** → il report va generato comunque, senza colonne delta. Test: Task 4, `test_broken_latest_is_ignored`.
- **Emulatore in stato `offline`** (successo davvero il 2026-09-25) → `perf.ps1` deve trattarlo come "non pronto" e fermarsi con messaggio chiaro, non lanciare Gradle. Verifica: Task 5, step 4.
- **Dialog imprevisti al primo avvio** ("Aggiornamento disponibile" → "Più tardi", card tutorial / notifiche → "Non ora") → chiusi in `AppSetup.dismissPopups`, anche ripetuti. Verifica: Task 3, step 4 (tutti gli scenari passano da dati puliti).
- **Percorso con spazi** (`Lorenzo Censi`) → `perf.ps1` e `perf_report.py` devono funzionare dal percorso reale. Verifica: Task 5, step 3 (giro end-to-end dal repo vero).

---

## File Structure

| File | Responsabilità |
| --- | --- |
| `android-app/build.gradle.kts` (mod.) | dichiara il plugin `com.android.test` |
| `android-app/settings.gradle.kts` (mod.) | `include(":benchmark")` |
| `android-app/app/build.gradle.kts` (mod.) | build type `benchmark`, `profileinstaller`, `runtime-tracing` solo benchmark |
| `android-app/app/src/benchmark/AndroidManifest.xml` (nuovo) | `<profileable android:shell="true"/>` solo per la build benchmark |
| `android-app/benchmark/build.gradle.kts` (nuovo) | modulo di test Macrobenchmark |
| `android-app/benchmark/src/main/AndroidManifest.xml` (nuovo) | manifest minimo del modulo |
| `.../perftest/BenchData.kt` (nuovo) | genera e copia le serie finte |
| `.../perftest/AppSetup.kt` (nuovo) | reset app, avvio, popup, tab, swipe, digitazione |
| `.../perftest/Metrics.kt` (nuovo) | metriche condivise (frame, ricomposizioni, ms radice) |
| `.../perftest/UiScenariosBenchmark.kt` (nuovo) | i 6 scenari |
| `scripts/perf_report.py` (nuovo) | JSON Macrobenchmark → report Markdown + confronto |
| `scripts/tests/test_perf_report.py` (nuovo) | unit test del report |
| `scripts/tests/fixtures/benchmarkData.json` (nuovo) | JSON reale del primo giro (Task 3) |
| `scripts/perf.ps1` (nuovo) | orchestrazione: controllo device, Gradle, report |
| `.gitignore` (mod.) | `perf-reports/` |
| `.claude/skills/perf/SKILL.md` (nuovo) | skill `/perf` |

`...` = `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader`.

---

### Task 1: Build type `benchmark` nell'app

**Files:**
- Modify: `android-app/build.gradle.kts`
- Modify: `android-app/app/build.gradle.kts` (blocco `buildTypes` ~riga 147, blocco `dependencies` ~riga 197)
- Create: `android-app/app/src/benchmark/AndroidManifest.xml`

**Interfaces:**
- Produces: variante `benchmark` dell'app, pacchetto `com.lorenzo.mangadownloader.benchmark`, profileable da shell, con sezioni di trace per composable. Task Gradle `:app:assembleBenchmark`.

- [ ] **Step 1: Dichiarare il plugin di test nel progetto radice**

In `android-app/build.gradle.kts`, dentro `plugins { … }`, dopo la riga di `com.android.application`:

```kotlin
    id("com.android.test") version "9.4.1" apply false
```

- [ ] **Step 2: Aggiungere il build type**

In `android-app/app/build.gradle.kts`, dentro `buildTypes { … }` subito dopo la chiusura di `release { … }`:

```kotlin
        // Build misurata dal modulo :benchmark (Macrobenchmark): identica alla release (R8
        // compreso) ma firmata con la chiave debug e installata a parte (.benchmark), così non
        // tocca i dati né la build debug sull'emulatore.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
        }
```

- [ ] **Step 3: Dipendenze**

Nello stesso file, in `dependencies { … }`, dopo `implementation("io.github.aldefy:lumen-android:1.0.0-beta20")`:

```kotlin
    // Installa i profili ART (richiesto da Macrobenchmark; servirà anche al Baseline Profile).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Una sezione di trace per ogni composable: il benchmark conta così le ricomposizioni.
    // Solo nella build benchmark, la release pubblicata resta identica.
    "benchmarkImplementation"("androidx.compose.runtime:runtime-tracing")
```

- [ ] **Step 4: Manifest della variante**

Creare `android-app/app/src/benchmark/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Solo build benchmark: permette a Macrobenchmark di tracciare l'app non debuggabile. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <profileable
            android:shell="true"
            tools:targetApi="29" />
    </application>
</manifest>
```

- [ ] **Step 5: Compilare benchmark e release**

Run (PowerShell): `& "…\android-app\gradlew.bat" -p "…\android-app" :app:assembleBenchmark :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`; esiste `android-app/app/build/outputs/apk/benchmark/app-benchmark.apk`.

- [ ] **Step 6: Verificare che la release non contenga runtime-tracing**

Run (PowerShell): `& "…\android-app\gradlew.bat" -p "…\android-app" :app:dependencies --configuration releaseRuntimeClasspath | Select-String runtime-tracing`
Expected: nessun output. Ripetere con `--configuration benchmarkRuntimeClasspath`: Expected una riga `androidx.compose.runtime:runtime-tracing … -> 1.12.1`.

- [ ] **Step 7: Verifica `git status`** — attesi solo i 3 file sopra (più MIGLIORIE.md e docs già modificati). Niente commit.

---

### Task 2: Modulo `:benchmark` + dati finti + primi due scenari (con verifica del tracing)

Questo task esiste da solo perché valida il rischio principale (sezioni Compose visibili nel trace di una build R8) prima di scrivere tutti gli scenari.

**Files:**
- Modify: `android-app/settings.gradle.kts`
- Create: `android-app/benchmark/build.gradle.kts`
- Create: `android-app/benchmark/src/main/AndroidManifest.xml`
- Create: `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader/perftest/BenchData.kt`
- Create: `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader/perftest/AppSetup.kt`
- Create: `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader/perftest/Metrics.kt`
- Create: `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader/perftest/UiScenariosBenchmark.kt`

**Interfaces:**
- Consumes: variante `benchmark` dell'app (Task 1).
- Produces:
  - `BenchData.TARGET_PACKAGE: String`, `BenchData.READER_SERIES = "Bench01"`, `BenchData.READER_CHAPTER = "Capitolo 1"`, `BenchData.prepareSource(): File`, `BenchData.install(device: UiDevice, source: File)`.
  - `AppSetup.resetApp(device: UiDevice)`, estensioni su `MacrobenchmarkScope`: `launchToHome()`, `dismissPopups()`, `openTab(label: String)`, `swipeUp(times: Int)`, `typeSlowly(text: String)`, `waitForText(text: String, timeoutMs: Long = …)`.
  - `Metrics.COMPOSABLES: List<String>`, `Metrics.ui(): List<Metric>`; nomi delle metriche nel JSON: `recomp_<Nome>Count`, `rootComposeSumMs`, `frameDurationCpuMs`, `frameOverrunMs`, `frameCount`, `timeToInitialDisplayMs`.
  - Task Gradle `:benchmark:connectedBenchmarkAndroidTest`.

- [ ] **Step 1: Registrare il modulo**

In `android-app/settings.gradle.kts`, dopo `include(":app")`:

```kotlin
include(":benchmark")
```

- [ ] **Step 2: `android-app/benchmark/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.test")
}

android {
    // Non "…benchmark": è il pacchetto dell'app di test e collide con l'app misurata
    // (com.lorenzo.mangadownloader.benchmark).
    namespace = "com.lorenzo.mangadownloader.perftest"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Gira sull'emulatore per scelta: i conteggi di ricomposizione restano affidabili.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        // Raccoglie le sezioni Perfetto SDK (le emette runtime-tracing, una per composable).
        testInstrumentationRunnerArguments["androidx.benchmark.fullTracing.enable"] = "true"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.tracing:tracing-perfetto:1.0.1")
    implementation("androidx.tracing:tracing-perfetto-binary:1.0.1")
}

// Solo la variante benchmark: le altre non hanno una build dell'app da misurare.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
```

- [ ] **Step 3: `android-app/benchmark/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- L'app di test deve poter vedere il pacchetto misurato (Android 11+). -->
    <queries>
        <package android:name="com.lorenzo.mangadownloader.benchmark" />
    </queries>
</manifest>
```

- [ ] **Step 4: `BenchData.kt`**

```kotlin
package com.lorenzo.mangadownloader.perftest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serie finte per Libreria e reader, così quegli scenari non dipendono dalla rete.
 * Nomi senza spazi: `UiDevice.executeShellCommand` spezza gli argomenti sugli spazi e non
 * gestisce le virgolette.
 */
object BenchData {
    const val TARGET_PACKAGE = "com.lorenzo.mangadownloader.benchmark"
    const val READER_SERIES = "Bench01"
    // La scansione ricava il titolo dal nome file chapter_001.cbz: "Capitolo 1".
    const val READER_CHAPTER = "Capitolo 1"

    private val SERIES = (1..12).map { "Bench%02d".format(it) }
    private const val READER_PAGES = 20
    private const val FILLER_PAGES = 2
    private const val PAGE_WIDTH = 1080
    private const val PAGE_HEIGHT = 3000
    private const val LIBRARY_DIR =
        "/sdcard/Android/data/$TARGET_PACKAGE/files/Download/MangaDownloader"

    /**
     * Genera una volta per processo le serie nella cache ESTERNA dell'app di test: quella
     * privata non è leggibile dalla shell che poi le copia.
     */
    fun prepareSource(): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val root = File(context.externalCacheDir, "benchdata")
        val marker = File(root, ".done")
        if (marker.exists()) return root
        root.deleteRecursively()
        SERIES.forEachIndexed { index, name ->
            val dir = File(root, name).apply { mkdirs() }
            File(dir, "cover.jpg").writeBytes(jpeg(600, 900, index, name))
            val pages = if (name == READER_SERIES) READER_PAGES else FILLER_PAGES
            writeChapter(File(dir, "chapter_001.cbz"), index, pages)
        }
        marker.writeText("ok")
        return root
    }

    /** Copia le serie nella libreria dell'app (dopo `pm clear`, che cancella anche questa cartella). */
    fun install(device: UiDevice, source: File) {
        device.executeShellCommand("mkdir -p $LIBRARY_DIR")
        SERIES.forEach { name ->
            device.executeShellCommand("cp -r ${source.absolutePath}/$name $LIBRARY_DIR/")
        }
    }

    private fun writeChapter(file: File, seed: Int, pages: Int) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            repeat(pages) { page ->
                zip.putNextEntry(ZipEntry("%03d.jpg".format(page + 1)))
                zip.write(jpeg(PAGE_WIDTH, PAGE_HEIGHT, seed + page, "${page + 1}"))
                zip.closeEntry()
            }
        }
    }

    private fun jpeg(width: Int, height: Int, seed: Int, label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.HSVToColor(floatArrayOf((seed * 37f) % 360f, 0.35f, 0.95f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = width / 6f
            textAlign = Paint.Align.CENTER
        }
        // Qualche riga orizzontale: un'immagine a tinta unita comprime in modo irrealistico.
        for (y in 0 until height step 120) {
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 8f, paint)
        }
        canvas.drawText(label, width / 2f, height / 2f, paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
```

- [ ] **Step 5: `AppSetup.kt`**

```kotlin
package com.lorenzo.mangadownloader.perftest

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

private const val UI_TIMEOUT_MS = 15_000L

/** Porta l'app in uno stato noto prima di ogni iterazione. */
object AppSetup {
    private val source by lazy { BenchData.prepareSource() }

    /** Dati puliti + serie finte + permesso notifiche (senza, l'app apre un dialog). */
    fun resetApp(device: UiDevice) {
        device.executeShellCommand("pm clear ${BenchData.TARGET_PACKAGE}")
        BenchData.install(device, source)
        device.executeShellCommand(
            "pm grant ${BenchData.TARGET_PACKAGE} android.permission.POST_NOTIFICATIONS",
        )
    }
}

fun MacrobenchmarkScope.waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    check(device.wait(Until.hasObject(By.text(text)), timeoutMs)) {
        "Elemento \"$text\" non trovato entro ${timeoutMs}ms"
    }
}

/** Avvia l'app, aspetta la bottom bar e chiude i popup del primo avvio. */
fun MacrobenchmarkScope.launchToHome() {
    startActivityAndWait()
    waitForText("Home")
    dismissPopups()
}

/**
 * Chiude i popup noti del primo avvio: aggiornamento disponibile ("Più tardi"), card del
 * tutorial e spiegazione notifiche ("Non ora"). Più giri: possono comparire in sequenza.
 */
fun MacrobenchmarkScope.dismissPopups() {
    repeat(3) {
        val button = device.findObject(By.text("Più tardi"))
            ?: device.findObject(By.text("Non ora"))
            ?: return
        button.click()
        device.waitForIdle()
    }
}

/** Tocca una voce della bottom bar. Lo stesso testo può essere anche il titolo in alto:
 * la voce della barra è quella più in basso. */
fun MacrobenchmarkScope.openTab(label: String) {
    waitForText(label)
    device.findObjects(By.text(label)).maxBy { it.visibleBounds.centerY() }.click()
    device.waitForIdle()
}

/** Swipe verticali al centro dello schermo (contenuto verso l'alto: niente pull-to-refresh). */
fun MacrobenchmarkScope.swipeUp(times: Int) {
    val x = device.displayWidth / 2
    val from = device.displayHeight * 3 / 4
    val to = device.displayHeight / 4
    repeat(times) {
        device.swipe(x, from, x, to, 20)
        device.waitForIdle()
    }
}

/** Un carattere alla volta via IME, come una persona: ogni tasto è un'emissione di stato. */
fun MacrobenchmarkScope.typeSlowly(text: String) {
    text.forEach { char ->
        device.executeShellCommand("input text $char")
        SystemClock.sleep(300)
    }
}
```

- [ ] **Step 6: `Metrics.kt`**

```kotlin
package com.lorenzo.mangadownloader.perftest

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric

object Metrics {
    /** Composable di cui contare le ricomposizioni (sezioni emesse da runtime-tracing). */
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
```

- [ ] **Step 7: `UiScenariosBenchmark.kt` con i primi due scenari**

```kotlin
package com.lorenzo.mangadownloader.perftest

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ITERATIONS = 5

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
}
```

- [ ] **Step 8: Compilare il modulo**

Run: `& "…\android-app\gradlew.bat" -p "…\android-app" :benchmark:assembleBenchmark`
Expected: `BUILD SUCCESSFUL`.
Se fallisce con "Kotlin non configurato" / sorgenti `.kt` ignorati (il Kotlin integrato di AGP 9 non copre `com.android.test`): aggiungere in `android-app/build.gradle.kts` `id("org.jetbrains.kotlin.android") version "2.4.20" apply false` e in `benchmark/build.gradle.kts` `id("org.jetbrains.kotlin.android")`, poi rilanciare.

- [ ] **Step 9: Eseguire i due scenari sull'emulatore**

Prerequisito: `adb devices` mostra `emulator-5554	device`.
Run: `& "…\android-app\gradlew.bat" -p "…\android-app" :benchmark:connectedBenchmarkAndroidTest`
Expected: `BUILD SUCCESSFUL`, 2 test passati.

- [ ] **Step 10: Verificare che le ricomposizioni siano nel JSON (rischio R8)**

Run (Git Bash):
```bash
f=$(find "C:/Users/utente/Documents/Lorenzo Censi/Manga-dowloader/android-app/benchmark/build/outputs/connected_android_test_additional_output" -name "*benchmarkData.json" | head -1)
python -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); [print(b['name'], sorted(b['metrics'].keys())) for b in d['benchmarks']]" "$f"
```
Expected: `homeScroll` ha le chiavi `recomp_…Count` e `rootComposeSumMs`, e almeno `recomp_MangaDownloaderAppContentCount` con mediana > 0.
Se le chiavi ci sono ma sono tutte 0, o mancano: aprire il trace (`*.perfetto-trace` nella stessa cartella) su https://ui.perfetto.dev, cercare "HomeScreen" e confrontare il nome reale con il filtro. Se le sezioni non compaiono affatto con R8, applicare il ripiego della spec: in `app/build.gradle.kts` nel build type `benchmark` aggiungere `isMinifyEnabled = false` e `isShrinkResources = false`, ricompilare e ripetere lo step 9.

- [ ] **Step 11: Verifica `git status`** — niente commit.

---

### Task 3: Gli altri quattro scenari + fixture reale

**Files:**
- Modify: `android-app/benchmark/src/main/java/com/lorenzo/mangadownloader/perftest/UiScenariosBenchmark.kt`
- Create: `scripts/tests/fixtures/benchmarkData.json` (copiato dall'output)

**Interfaces:**
- Consumes: `scenario(...)`, `openTab`, `typeSlowly`, `swipeUp`, `waitForText`, `BenchData.READER_SERIES`, `BenchData.READER_CHAPTER` (Task 2).
- Produces: nel JSON i benchmark `coldStartup`, `homeScroll`, `searchTyping`, `tabSwitch`, `readerScroll`, `libraryScroll`; la fixture usata dal Task 4.

- [ ] **Step 1: Aggiungere gli scenari**

In `UiScenariosBenchmark`, dopo `homeScroll()`; aggiungere anche gli import `android.os.SystemClock`, `androidx.test.uiautomator.By`:

```kotlin
    @Test
    fun searchTyping() = scenario(
        prepare = {
            openTab("Cerca")
            device.findObject(By.clazz("android.widget.EditText")).click()
            device.waitForIdle()
        },
    ) {
        typeSlowly("berserk")
        // Lascia arrivare la ricerca automatica partita dall'ultimo tasto.
        SystemClock.sleep(1_000)
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
            waitForText(BenchData.READER_SERIES)
            device.findObject(By.text(BenchData.READER_SERIES)).click()
            waitForText(BenchData.READER_CHAPTER)
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
```

- [ ] **Step 2: Eseguire tutti gli scenari**

Run: `& "…\android-app\gradlew.bat" -p "…\android-app" :benchmark:connectedBenchmarkAndroidTest`
Expected: `BUILD SUCCESSFUL`, 6 test passati (~5-10 minuti).

- [ ] **Step 3: Confrontare con le misure fatte a mano (2026-09-25)**

Con lo stesso comando Python del Task 2 step 10, stampare le mediane (`b['metrics'][k]['median']`). Attesi, a grandi linee:
- `searchTyping`: `recomp_HomeScreenCount` ≈ numero di tasti (7) o più; `recomp_SearchScreenCount` simile.
- `readerScroll`: `recomp_MangaDownloaderAppContentCount` ≈ 0–2.
- `homeScroll`: `recomp_MangaDownloaderAppContentCount` ≈ 2 per swipe (≈ 16), `recomp_HomeScreenCount` ≈ 0.
Se `readerScroll` non trova "Capitolo 1": fare uno screenshot del dettaglio serie (`adb shell screencap`) e correggere `READER_CHAPTER` con il titolo reale.

- [ ] **Step 4: Salvare la fixture**

Copiare il `*benchmarkData.json` in `scripts/tests/fixtures/benchmarkData.json` (Git Bash: `mkdir -p scripts/tests/fixtures && cp "$f" scripts/tests/fixtures/benchmarkData.json`).

- [ ] **Step 5: Verifica `git status`** — niente commit.

---

### Task 4: `perf_report.py` (TDD)

**Files:**
- Create: `scripts/perf_report.py`
- Create: `scripts/tests/test_perf_report.py`

**Interfaces:**
- Consumes: formato JSON di Macrobenchmark (fixture del Task 3): `{"context": {"build": {"model": str, "version": {"sdk": int}}}, "benchmarks": [{"name": str, "metrics": {k: {"median": float, …}}, "sampledMetrics": {"frameDurationCpuMs": {"P50","P90","P99", "runs": [[…]]}, "frameOverrunMs": {"runs": [[…]]}}}]}`.
- Produces:
  - `parse(data: dict) -> dict[str, Scenario]` con `Scenario = {"frames": {"p50","p90","p99","jank"} | None, "recomp": {nome: float}, "root_ms": float | None, "startup": {metrica: float}}`
  - `signals(cur: dict, prev: dict | None) -> list[str]`
  - `render(cur: dict, prev: dict | None, meta: dict) -> str` con `meta = {"date","commit","branch","dirty","device","sdk","gradle_exit"}`
  - `load_previous(out_dir: Path) -> dict | None`
  - `main(argv: list[str]) -> int`; CLI: `python scripts/perf_report.py <benchmarkData.json> [--out-dir DIR] [--gradle-exit N]`

- [ ] **Step 1: Scrivere i test**

`scripts/tests/test_perf_report.py`:

```python
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import perf_report  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "benchmarkData.json"


def bench(name, recomp=None, root_ms=None, frames=True, overrun=None, startup=None):
    metrics = {}
    for comp, value in (recomp or {}).items():
        metrics[f"recomp_{comp}Count"] = {"median": value}
    if root_ms is not None:
        metrics["rootComposeSumMs"] = {"median": root_ms}
    for key, value in (startup or {}).items():
        metrics[key] = {"median": value}
    sampled = {}
    if frames:
        sampled["frameDurationCpuMs"] = {"P50": 10.0, "P90": 20.0, "P99": 30.0, "runs": [[10.0]]}
        sampled["frameOverrunMs"] = {"runs": [overrun if overrun is not None else [-5.0, -3.0, 2.0, -1.0]]}
    return {"name": name, "metrics": metrics, "sampledMetrics": sampled}


def data(*benchmarks):
    return {"context": {"build": {"model": "Pixel 8", "version": {"sdk": 36}}}, "benchmarks": list(benchmarks)}


META = {"date": "2026-09-25 12:00", "commit": "abc1234", "branch": "dev", "dirty": False,
        "device": "Pixel 8", "sdk": 36, "gradle_exit": 0}


class ParseTest(unittest.TestCase):
    def test_extracts_frames_jank_recomp_root(self):
        parsed = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 3.0}, root_ms=4.5)))
        s = parsed["homeScroll"]
        self.assertEqual(s["frames"]["p90"], 20.0)
        self.assertAlmostEqual(s["frames"]["jank"], 25.0)
        self.assertEqual(s["recomp"], {"HomeScreen": 3.0})
        self.assertEqual(s["root_ms"], 4.5)

    def test_startup_metrics(self):
        parsed = perf_report.parse(data(bench("coldStartup", frames=False, startup={"timeToInitialDisplayMs": 812.0})))
        self.assertEqual(parsed["coldStartup"]["startup"], {"timeToInitialDisplayMs": 812.0})
        self.assertIsNone(parsed["coldStartup"]["frames"])

    def test_real_fixture_parses(self):
        parsed = perf_report.parse(json.loads(FIXTURE.read_text(encoding="utf-8")))
        self.assertIn("homeScroll", parsed)
        self.assertTrue(parsed["homeScroll"]["recomp"])


class SignalsTest(unittest.TestCase):
    def test_offscreen_screen_recomposition(self):
        cur = perf_report.parse(data(bench("searchTyping", {"SearchScreen": 7.0, "HomeScreen": 7.0})))
        self.assertTrue(any("HomeScreen" in s and "searchTyping" in s for s in perf_report.signals(cur, None)))

    def test_visible_screen_is_not_a_signal(self):
        cur = perf_report.parse(data(bench("searchTyping", {"SearchScreen": 7.0})))
        self.assertFalse(any("SearchScreen" in s for s in perf_report.signals(cur, None)))

    def test_root_more_than_once_per_swipe(self):
        cur = perf_report.parse(data(bench("homeScroll", {"MangaDownloaderAppContent": 16.0})))
        self.assertTrue(any("radice" in s and "homeScroll" in s for s in perf_report.signals(cur, None)))

    def test_high_jank(self):
        cur = perf_report.parse(data(bench("tabSwitch", overrun=[1.0, 1.0, -1.0, -1.0])))
        self.assertTrue(any("jank" in s and "tabSwitch" in s for s in perf_report.signals(cur, None)))

    def test_regression_over_20_percent(self):
        prev = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 5.0})))
        cur = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 8.0})))
        self.assertTrue(any("peggiorat" in s and "AppTopBar" in s for s in perf_report.signals(cur, prev)))

    def test_small_absolute_change_is_not_regression(self):
        prev = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 1.0})))
        cur = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 2.0})))
        self.assertFalse(any("peggiorat" in s for s in perf_report.signals(cur, prev)))


class RenderTest(unittest.TestCase):
    def test_missing_scenario_marked_failed(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 0.0})))
        self.assertIn("❌ `readerScroll`", perf_report.render(cur, None, META))

    def test_no_tracing_warning(self):
        cur = perf_report.parse(data(bench("homeScroll", {})))
        self.assertIn("tracing Compose assente", perf_report.render(cur, None, META))

    def test_no_previous_means_no_delta(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 2.0})))
        out = perf_report.render(cur, None, META)
        self.assertNotIn("(+", out)
        self.assertNotIn("(−", out)

    def test_delta_shown_with_previous(self):
        prev = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 2.0})))
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 5.0})))
        self.assertIn("5 (+3)", perf_report.render(cur, prev, META))


class MainTest(unittest.TestCase):
    def run_main(self, out_dir, payload):
        src = out_dir / "input.json"
        src.write_text(json.dumps(payload), encoding="utf-8")
        with mock.patch.object(perf_report, "git_meta", return_value=("abc1234", "dev", False)):
            return perf_report.main([str(src), "--out-dir", str(out_dir / "perf reports")])

    def test_writes_report_and_compares_second_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            self.assertEqual(self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 2.0}))), 0)
            reports = tmp / "perf reports"
            self.assertTrue((reports / "latest.json").exists())
            self.assertEqual(len(list(reports.glob("*.md"))), 1)
            with mock.patch.object(perf_report, "timestamp", return_value="2099-01-01_0000"):
                self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 5.0})))
            second = (reports / "2099-01-01_0000.md").read_text(encoding="utf-8")
            self.assertIn("5 (+3)", second)

    def test_broken_latest_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            reports = tmp / "perf reports"
            reports.mkdir()
            (reports / "latest.json").write_text("{non json", encoding="utf-8")
            self.assertEqual(self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 2.0}))), 0)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Verificare che falliscano**

Run: `python -m unittest discover -s scripts/tests -v`
Expected: errore `ModuleNotFoundError: No module named 'perf_report'`.

- [ ] **Step 3: Implementare `scripts/perf_report.py`**

```python
"""Trasforma il JSON di Macrobenchmark (modulo :benchmark) in un report Markdown in
perf-reports/, confrontato con il giro precedente. Solo libreria standard."""
import argparse
import datetime
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCENARIOS = ["coldStartup", "homeScroll", "searchTyping", "tabSwitch", "readerScroll", "libraryScroll"]
COMPOSABLES = ["MangaDownloaderAppContent", "AppTopBar", "AppBottomBar", "HomeScreen", "SearchScreen",
               "LibraryScreen", "TutorialOverlay", "ReaderScreen", "VerticalReader"]
# Schermata visibile durante la misura: le altre *Screen non dovrebbero ricomporsi.
VISIBLE = {"homeScroll": "HomeScreen", "searchTyping": "SearchScreen",
           "readerScroll": "ReaderScreen", "libraryScroll": "LibraryScreen"}
SCREENS = ["HomeScreen", "SearchScreen", "LibraryScreen", "ReaderScreen"]
# Swipe per scenario di solo scroll: la radice non dovrebbe ricomporsi più di una volta ciascuno.
SWIPES = {"homeScroll": 8, "readerScroll": 15, "libraryScroll": 5}
NETWORK = {"homeScroll", "searchTyping"}
JANK_LIMIT = 10.0
REGRESSION = 0.20
MIN_DELTA = {"count": 2.0, "ms": 1.0, "pct": 2.0}


def parse(data):
    result = {}
    for bench in data.get("benchmarks", []):
        metrics = bench.get("metrics", {})
        sampled = bench.get("sampledMetrics", {})
        frames = None
        if "frameDurationCpuMs" in sampled:
            dur = sampled["frameDurationCpuMs"]
            overruns = [x for run in sampled.get("frameOverrunMs", {}).get("runs", []) for x in run]
            jank = 100.0 * sum(1 for x in overruns if x > 0) / len(overruns) if overruns else 0.0
            frames = {"p50": dur["P50"], "p90": dur["P90"], "p99": dur["P99"], "jank": jank}
        recomp = {k[len("recomp_"):-len("Count")]: v["median"] for k, v in metrics.items()
                  if k.startswith("recomp_") and k.endswith("Count")}
        root_ms = next((v["median"] for k, v in metrics.items() if k.startswith("rootCompose")), None)
        startup = {k: v["median"] for k, v in metrics.items() if k.startswith("timeTo")}
        result[bench["name"]] = {"frames": frames, "recomp": recomp, "root_ms": root_ms, "startup": startup}
    return result


def _regressed(cur, prev, kind):
    return prev is not None and cur is not None and prev > 0 \
        and cur > prev * (1 + REGRESSION) and cur - prev >= MIN_DELTA[kind]


def signals(cur, prev):
    out = []
    for name, s in cur.items():
        visible = VISIBLE.get(name)
        if visible:
            for screen in SCREENS:
                count = s["recomp"].get(screen, 0)
                if screen != visible and count > 0:
                    out.append(f"`{name}`: **{screen}** si ricompone {count:g} volte senza essere visibile")
        swipes = SWIPES.get(name)
        root = s["recomp"].get("MangaDownloaderAppContent", 0)
        if swipes and root > swipes:
            out.append(f"`{name}`: la radice si ricompone {root:g} volte in {swipes} swipe (più di una per swipe)")
        if s["frames"] and s["frames"]["jank"] > JANK_LIMIT:
            out.append(f"`{name}`: {s['frames']['jank']:.1f}% di frame in jank (soglia {JANK_LIMIT:g}%)")
        p = (prev or {}).get(name)
        if not p:
            continue
        for comp, count in s["recomp"].items():
            if _regressed(count, p["recomp"].get(comp), "count"):
                out.append(f"`{name}`: ricomposizioni di {comp} peggiorate da {p['recomp'][comp]:g} a {count:g}")
        if _regressed(s["root_ms"], p["root_ms"], "ms"):
            out.append(f"`{name}`: composizione radice peggiorata da {p['root_ms']:.1f} a {s['root_ms']:.1f} ms")
        if s["frames"] and p["frames"]:
            if _regressed(s["frames"]["p90"], p["frames"]["p90"], "ms"):
                out.append(f"`{name}`: frame p90 peggiorato da {p['frames']['p90']:.1f} a {s['frames']['p90']:.1f} ms")
            if _regressed(s["frames"]["jank"], p["frames"]["jank"], "pct"):
                out.append(f"`{name}`: jank peggiorato da {p['frames']['jank']:.1f}% a {s['frames']['jank']:.1f}%")
    return out


def _num(value, digits):
    return f"{value:.{digits}f}" if digits else f"{value:g}"


def fmt(cur, prev, digits=0):
    if cur is None:
        return "–"
    text = _num(cur, digits)
    if prev is None:
        return text
    diff = cur - prev
    if abs(diff) < (0.05 if digits else 0.5):
        return text
    sign = "+" if diff > 0 else "−"
    return f"{text} ({sign}{_num(abs(diff), digits)})"


def render(cur, prev, meta):
    prev = prev or {}
    lines = [f"# Report prestazioni — {meta['date']}", ""]
    dirty = " (con modifiche non committate)" if meta["dirty"] else ""
    lines += [f"- Commit `{meta['commit']}` su `{meta['branch']}`{dirty}",
              f"- Dispositivo: {meta['device']} (API {meta['sdk']})",
              "- I millisecondi vengono da un emulatore: leggili come tendenza, non come valori assoluti.",
              f"- Scenari che usano la rete (numeri più variabili): {', '.join(f'`{n}`' for n in sorted(NETWORK))}."]
    if meta.get("gradle_exit"):
        lines.append(f"- ⚠️ Gradle è uscito con codice {meta['gradle_exit']}: alcuni scenari possono essere falliti.")
    if prev:
        lines.append("- Tra parentesi la differenza rispetto al giro precedente.")
    lines.append("")

    failed = [n for n in SCENARIOS if n not in cur]
    if failed:
        lines += ["## Scenari falliti", ""]
        lines += [f"- ❌ `{n}`: assente dal JSON (errore durante il benchmark: vedi l'output di Gradle)" for n in failed]
        lines.append("")

    with_frames = [n for n in SCENARIOS if n in cur and cur[n]["frames"]]
    if not any(cur[n]["recomp"] for n in with_frames) and with_frames:
        lines += ["> ⚠️ **tracing Compose assente**: nessuna sezione di ricomposizione nel trace. "
                  "Controllare `runtime-tracing` nella build benchmark.", ""]

    start = cur.get("coldStartup")
    if start and start["startup"]:
        p = prev.get("coldStartup", {}).get("startup", {})
        lines += ["## Avvio a freddo", ""]
        lines += [f"- {k}: {fmt(v, p.get(k), 1)} ms" for k, v in sorted(start["startup"].items())]
        lines.append("")

    lines += ["## Frame", "", "| Scenario | p50 ms | p90 ms | p99 ms | jank % |", "| --- | --- | --- | --- | --- |"]
    for n in with_frames:
        f = cur[n]["frames"]
        pf = (prev.get(n) or {}).get("frames") or {}
        lines.append(f"| `{n}` | {fmt(f['p50'], pf.get('p50'), 1)} | {fmt(f['p90'], pf.get('p90'), 1)} | "
                     f"{fmt(f['p99'], pf.get('p99'), 1)} | {fmt(f['jank'], pf.get('jank'), 1)} |")
    lines.append("")

    lines += ["## Ricomposizioni (mediana per iterazione)", "",
              "| Scenario | " + " | ".join(COMPOSABLES) + " | ms radice |",
              "| --- | " + " | ".join("---" for _ in COMPOSABLES) + " | --- |"]
    for n in with_frames:
        s = cur[n]
        p = prev.get(n) or {"recomp": {}, "root_ms": None}
        visible = VISIBLE.get(n)
        cells = []
        for comp in COMPOSABLES:
            count = s["recomp"].get(comp)
            cell = fmt(count, p["recomp"].get(comp))
            offscreen = visible and comp in SCREENS and comp != visible and (count or 0) > 0
            cells.append(f"**{cell}**" if offscreen else cell)
        lines.append(f"| `{n}` | " + " | ".join(cells) + f" | {fmt(s['root_ms'], p['root_ms'], 1)} |")
    lines.append("")

    found = signals(cur, prev or None)
    lines += ["## Segnali", ""]
    lines += [f"- {s}" for s in found] if found else ["- Nessun segnale."]
    lines.append("")
    return "\n".join(lines)


def load_previous(out_dir):
    try:
        latest = json.loads((out_dir / "latest.json").read_text(encoding="utf-8"))
        return parse(json.loads((out_dir / latest["json"]).read_text(encoding="utf-8")))
    except (OSError, ValueError, KeyError, TypeError):
        return None


def git_meta():
    def git(*args):
        return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True).stdout.strip()
    return git("rev-parse", "--short", "HEAD"), git("rev-parse", "--abbrev-ref", "HEAD"), bool(git("status", "--porcelain"))


def timestamp():
    return datetime.datetime.now().strftime("%Y-%m-%d_%H%M")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json")
    parser.add_argument("--out-dir", default=str(REPO / "perf-reports"))
    parser.add_argument("--gradle-exit", type=int, default=0)
    args = parser.parse_args(argv)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    source = Path(args.json)
    data = json.loads(source.read_text(encoding="utf-8"))
    cur = parse(data)
    prev = load_previous(out_dir)

    stamp = timestamp()
    commit, branch, dirty = git_meta()
    build = data.get("context", {}).get("build", {})
    meta = {"date": stamp.replace("_", " "), "commit": commit, "branch": branch, "dirty": dirty,
            "device": build.get("model", "?"), "sdk": build.get("version", {}).get("sdk", "?"),
            "gradle_exit": args.gradle_exit}

    shutil.copyfile(source, out_dir / f"{stamp}.json")
    report = out_dir / f"{stamp}.md"
    report.write_text(render(cur, prev, meta), encoding="utf-8")
    (out_dir / "latest.json").write_text(json.dumps({"json": f"{stamp}.json", "md": f"{stamp}.md"}), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 4: Eseguire i test**

Run: `python -m unittest discover -s scripts/tests -v`
Expected: tutti `ok`. Se `test_real_fixture_parses` fallisce perché le chiavi reali differiscono (es. `recomp_HomeScreenCount` con altro suffisso), adattare i filtri di `parse` ai nomi presenti nella fixture e rilanciare.

- [ ] **Step 5: Report dalla fixture reale**

Run: `python scripts/perf_report.py scripts/tests/fixtures/benchmarkData.json --out-dir "%TEMP%/perf-try"` (Git Bash: `--out-dir "$TEMP/perf-try"`)
Expected: stampa il percorso del `.md`; aprirlo e verificare che le tabelle abbiano senso e che i segnali riproducano quelli trovati a mano (HomeScreen in `searchTyping`, radice in `homeScroll`).

- [ ] **Step 6: Verifica `git status`** — niente commit.

---

### Task 5: `perf.ps1` + `.gitignore` + giro end-to-end

**Files:**
- Create: `scripts/perf.ps1`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `:benchmark:connectedBenchmarkAndroidTest` (Task 2-3), `scripts/perf_report.py` CLI (Task 4).
- Produces: `perf-reports/<stamp>.md` + percorso stampato in fondo all'output; exit code 0 se il report è stato scritto, 1 se manca il device o qualsiasi risultato.

- [ ] **Step 1: `scripts/perf.ps1`**

```powershell
# Benchmark UI in locale: esegue il modulo :benchmark sull'emulatore acceso e scrive il
# report in perf-reports/ (confrontato col giro precedente). Uso: scripts\perf.ps1
$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android-app'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

# Solo lo stato "device" è pronto: "offline" e "unauthorized" farebbero fallire Gradle a metà.
$ready = & $adb devices | Select-String -Pattern '^\S+\s+device$'
if (-not $ready) {
    Write-Host "Nessun emulatore pronto (stato 'device'). Avvia l'AVD Pixel_8 e riprova."
    exit 1
}

# Via i risultati del giro precedente: il JSON trovato dopo deve essere di questo giro.
$results = Join-Path $android 'benchmark\build\outputs\connected_android_test_additional_output'
if (Test-Path $results) { Remove-Item -Recurse -Force $results }

& (Join-Path $android 'gradlew.bat') -p $android ':benchmark:connectedBenchmarkAndroidTest' '--continue'
$gradleExit = $LASTEXITCODE

$json = Get-ChildItem -Path $results -Recurse -Filter '*benchmarkData.json' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $json) {
    Write-Host "Nessun risultato di benchmark trovato (Gradle exit $gradleExit)."
    exit 1
}

python (Join-Path $PSScriptRoot 'perf_report.py') $json.FullName --gradle-exit $gradleExit
exit $LASTEXITCODE
```

- [ ] **Step 2: `.gitignore`**

Aggiungere in fondo a `.gitignore`:

```
# Report del benchmark locale (scripts/perf.ps1): dipendono dalla macchina.
perf-reports/
```

- [ ] **Step 3: Giro completo dal percorso reale**

Run (PowerShell): `& "C:\Users\utente\Documents\Lorenzo Censi\Manga-dowloader\scripts\perf.ps1"`
Expected: output Gradle, poi l'ultima riga è il percorso di `perf-reports\AAAA-MM-GG_HHMM.md`; exit 0. Aprire il report: 6 scenari, nessun ❌, segnali coerenti con il Task 3 step 3.
Lanciarlo **una seconda volta**: il secondo report deve mostrare le differenze tra parentesi.

- [ ] **Step 4: Emulatore non pronto**

Run (PowerShell): `& $adb emu kill` (con `$adb` = percorso di adb), attendere che `adb devices` non mostri più `device`, poi lanciare di nuovo `scripts\perf.ps1`.
Expected: messaggio "Nessun emulatore pronto…", exit 1, Gradle non parte. Poi riavviare l'emulatore (`emulator.exe -avd Pixel_8`) per il Task 6.

- [ ] **Step 5: Verificare che `perf-reports/` sia ignorata**

Run: `git status --short`
Expected: `perf-reports/` non compare. Niente commit.

---

### Task 6: Skill `/perf` + memoria

**Files:**
- Create: `.claude/skills/perf/SKILL.md`
- Modify: `C:\Users\utente\.claude\projects\c--Users-utente-Documents-Lorenzo-Censi-Manga-dowloader\memory\project-skills.md`

**Interfaces:**
- Consumes: `scripts/perf.ps1` (Task 5).
- Produces: skill invocabile `/perf`.

- [ ] **Step 1: `.claude/skills/perf/SKILL.md`**

```markdown
---
name: perf
description: Misura le prestazioni UI dell'app Android Manga Downloader in locale (ricomposizioni per composable, costo di composizione della radice, frame in jank) con il modulo Macrobenchmark `:benchmark` sull'emulatore, genera un report in perf-reports/ confrontato col giro precedente e propone le migliorie. Usala quando l'utente dice "/perf", "misura le prestazioni", "lancia il benchmark", "quanto ricompone", "è diventata più lenta?", o dopo un refactor di UI/stato per verificarne l'effetto. NON per il client Python né per i test unitari.
---

# /perf — benchmark UI in locale

## Passi

1. **Emulatore.** `adb devices` (adb in `%LOCALAPPDATA%\Android\Sdk\platform-tools`). Serve una riga
   in stato `device`. Se c'è solo `offline`: `adb kill-server`, poi, se resta offline, chiudere
   `qemu-system-x86_64.exe` e riavviare a freddo. Se non c'è: avviare
   `emulator.exe -avd Pixel_8 -no-snapshot-load` in background e attendere `device`
   (`adb wait-for-device`, poi `getprop sys.boot_completed` = 1).
2. **Benchmark.** Eseguire `scripts\perf.ps1` in background (PowerShell, ~5-10 minuti).
   L'ultima riga dell'output è il percorso del report.
3. **Riassunto.** Leggere il report e riassumere in poche righe: cosa è migliorato, cosa è
   peggiorato (colonne tra parentesi), la sezione "Segnali". Ricordare che i ms da emulatore
   valgono come tendenza; le ricomposizioni sono il dato affidabile.
4. **Verifica sul codice.** Per ogni segnale trovare la causa nel codice (chi legge quale stato,
   quale parametro cambia) prima di proporre qualsiasi cosa.
5. **MIGLIORIE.md.** Aggiornare/aggiungere le voci nella sezione "⚡ Prestazioni" con dati misurati
   (data, scenario, numeri) e causa verificata (✅).
6. **Migliorie una per una.** Proporle con AskUserQuestion, raccomandazione per prima, e
   attendere l'ok prima di toccare codice. Dopo una miglioria, rilanciare `/perf` per misurarne
   l'effetto.

## Da sapere

- L'app misurata è la build `benchmark` (`com.lorenzo.mangadownloader.benchmark`): installata a
  parte, dati puliti a ogni iterazione, non tocca la build debug né i dati sull'emulatore.
- Scenari e composable tracciati sono in `android-app/benchmark/.../perftest/`; se aggiungi uno
  scenario o un composable, aggiorna anche `SCENARIOS`/`COMPOSABLES`/`VISIBLE`/`SWIPES` in
  `scripts/perf_report.py` e i suoi test (`python -m unittest discover -s scripts/tests`).
- "tracing Compose assente" nel report = le sezioni per composable non sono nel trace: controllare
  `benchmarkImplementation("androidx.compose.runtime:runtime-tracing")` in `app/build.gradle.kts`.
- `homeScroll` e `searchTyping` usano la rete: oscillazioni piccole lì non sono regressioni.
- Non committare i report (`perf-reports/` è in `.gitignore`).
```

- [ ] **Step 2: Aggiornare la memoria "Project skills"**

Leggere `project-skills.md` e aggiungere `perf` all'elenco delle skill con una riga: "perf — benchmark UI locale (Macrobenchmark `:benchmark` + `scripts/perf.ps1`), report in perf-reports/; tenerla in sync con scenari e `perf_report.py`". Aggiornare di conseguenza la riga in `MEMORY.md`.

- [ ] **Step 3: Provare la skill**

Invocare `/perf` con l'emulatore acceso e seguire i passi 1-3 della skill fino al riassunto.
Expected: riassunto coerente con il report del Task 5. (I passi 4-6 sono il lavoro sulle migliorie, fuori da questo piano.)

- [ ] **Step 4: Verifica `git status`** — atteso: file nuovi del piano, nessun report. Niente commit.
