# Benchmark di prestazioni UI in locale — design

Data: 2026-09-25 · Branch: `dev`

## Obiettivo

Automatizzare le misure fatte a mano il 2026-09-25 (ricomposizioni per composable, costo di
composizione della radice, frame in jank) in un comando unico che gira **sul PC locale**
contro l'emulatore, produce un report in Markdown confrontato col giro precedente, e alimenta
il ciclo "report → verifica sul codice → MIGLIORIE.md → migliorie approvate una per una".

Non è un gate: un peggioramento non blocca nulla, il report informa.

Fuori scope (per ora): workflow GitHub Actions (troppo lento: 20-30 min con emulatore),
generazione del Baseline Profile (il modulo lo renderà possibile dopo), soglie di regressione.

## Decisioni prese

- **Locale, non CI.** Emulatore `Pixel_8` (API 36) già in uso; Gradle con cache.
- **Macrobenchmark** (Jetpack) invece di uno script adb fatto in casa: niente contatori nel
  codice dell'app, interazioni per testo (UiAutomator) invece che a coordinate.
- **Le ricomposizioni sono il dato affidabile**; i millisecondi da emulatore valgono come
  tendenza e il report lo dice.

## Architettura

### 1. Build type `benchmark` nell'app (`android-app/app/build.gradle.kts`)

- `create("benchmark") { initWith(release); signingConfig = debug; matchingFallbacks += "release"; applicationIdSuffix = ".benchmark"; isDebuggable = false }`.
- App separata (`com.lorenzo.mangadownloader.benchmark`): non tocca i dati né la build debug
  installata sull'emulatore; parte da dati puliti a ogni giro.
- `"benchmarkImplementation"("androidx.compose.runtime:runtime-tracing")` (versione dal BOM
  Compose, 1.12.1): emette una sezione di trace per ogni composable. **Solo** in questo build
  type: la release pubblicata resta identica.
- `androidx.profileinstaller:profileinstaller` (1.4.1) nell'app: richiesto da Macrobenchmark
  per le modalità di compilazione, e già utile per il futuro Baseline Profile.
- R8 resta attivo: i nomi nelle sezioni di trace sono stringhe emesse dal compilatore Compose,
  non vengono offuscati. Da verificare nel primo giro (vedi Rischi).

### 2. Modulo `android-app/benchmark` (plugin `com.android.test`)

- `namespace = "com.lorenzo.mangadownloader.perftest"` (non `…benchmark`: è il pacchetto
  dell'app di test e andrebbe in conflitto con l'app `.benchmark`).
- `targetProjectPath = ":app"`, `experimentalProperties["android.experimental.self-instrumenting"] = true`,
  build type `benchmark` allineato a quello dell'app.
- Dipendenze: `androidx.benchmark:benchmark-macro-junit4:1.5.0`,
  `androidx.test.uiautomator:uiautomator:2.4.0`, `androidx.test.ext:junit:1.3.0`,
  `androidx.tracing:tracing-perfetto` + `tracing-perfetto-binary` 1.0.1 (abilitano la raccolta
  delle sezioni Compose nel trace Perfetto).
- Argomento di strumentazione `androidx.benchmark.suppressErrors=EMULATOR` (gira su emulatore
  per scelta) e `androidx.benchmark.fullTracing.enable=true` per il tracing Compose.
- `settings.gradle.kts`: `include(":benchmark")`.

File:

- `BenchData.kt` — genera i dati finti: 12 serie (`Bench01`…`Bench12`, nomi senza spazi perché
  `UiDevice.executeShellCommand` non gestisce le virgolette), ognuna con `cover.jpg` e un
  `chapter_001.cbz`: 20 pagine alte per `Bench01` (quella del reader), 2 per le altre
  (servono solo a riempire la griglia della Libreria). Pagine 1080×3000, immagini disegnate al
  volo con `Bitmap`/`Canvas`, JPEG), scritte nella cache **esterna** dell'app di test
  (`externalCacheDir`, leggibile dalla shell; quella privata non lo è) e copiate via shell in
  `/sdcard/Android/data/com.lorenzo.mangadownloader.benchmark/files/Download/MangaDownloader/<Serie>/`.
  `series.json` non serve: la scansione ricava il capitolo dal nome file.
- `AppSetup.kt` — stato noto prima di ogni scenario: `pm clear`, ri-copia dei dati finti,
  `pm grant … POST_NOTIFICATIONS`, avvio, attesa della Home, chiusura della card tutorial
  ("Non ora") se presente.
- `Metrics.kt` — l'elenco delle metriche condivise:
  - `FrameTimingMetric()` (durata frame p50/p90/p99, frame oltre budget);
  - un `TraceSectionMetric("%<Nome> (%", Mode.Count)` per ciascun composable:
    `MangaDownloaderAppContent`, `AppTopBar`, `AppBottomBar`, `HomeScreen`, `SearchScreen`,
    `LibraryScreen`, `TutorialOverlay`, `ReaderScreen`, `VerticalReader`;
  - `TraceSectionMetric("%MangaDownloaderAppContent (%", Mode.Sum)` = ms totali di
    composizione della radice.
- `UiScenariosBenchmark.kt` — uno `@Test` per scenario, 5 iterazioni,
  `CompilationMode.DEFAULT` (`Partial()` pretende un Baseline Profile che non c'è ancora),
  `startupMode = null` con reset completo (`AppSetup`) nel `setupBlock` di ogni iterazione
  (tranne l'avvio, `StartupMode.COLD`):

| Scenario | Azioni | Metriche |
| --- | --- | --- |
| `coldStartup` | avvio a freddo fino alla Home | `StartupTimingMetric` |
| `homeScroll` | 8 swipe verso il basso sulla lista Home (niente pull-to-refresh) | frame + ricomposizioni |
| `searchTyping` | tab "Cerca", tap sul campo, "berserk" un carattere alla volta | frame + ricomposizioni + ms radice |
| `tabSwitch` | Home → Cerca → Preferiti → Libreria → Home | frame + ricomposizioni |
| `readerScroll` | Libreria → "Bench01" → "Capitolo 1" → 15 swipe | frame + ricomposizioni |
| `libraryScroll` | Libreria, scroll della griglia | frame + ricomposizioni |

Gli elementi si trovano per testo/descrizione (`By.text("Cerca")`, `By.desc(...)`), con
`wait(Until…)` invece di sleep fissi.

### 3. `scripts/perf.ps1` + `scripts/perf_report.py`

`perf.ps1`:

1. Verifica `adb devices`; se non c'è un emulatore `device`, si ferma con messaggio chiaro
   (l'avvio dell'emulatore lo fa la skill, non lo script).
2. `gradlew.bat -p android-app :benchmark:connectedBenchmarkAndroidTest` (percorsi assoluti).
   Un fallimento di singoli test non interrompe: il JSON contiene quelli riusciti.
3. Trova il `*-benchmarkData.json` in
   `android-app/benchmark/build/outputs/connected_android_test_additional_output/`.
4. Chiama `python scripts/perf_report.py <json>`.

`perf_report.py` (solo libreria standard):

- Copia il JSON in `perf-reports/AAAA-MM-GG_HHMM.json`, scrive
  `perf-reports/AAAA-MM-GG_HHMM.md`, aggiorna `perf-reports/latest.json` (percorso del giro).
- Confronta con il giro precedente indicato da `latest.json`, se esiste.
- Report:
  1. intestazione: data, commit + branch (+ "modifiche non committate" se `git status` non è
     pulito), device/API dal JSON, avviso sui ms da emulatore, avviso "usa la rete" su
     `homeScroll`/`searchTyping`;
  2. tabella frame per scenario (p50/p90/p99, % jank) con delta;
  3. tabella ricomposizioni (scenario × composable, mediane) con delta; celle anomale in
     grassetto;
  4. **segnali automatici**, regole fisse:
     - un `*Screen` si ricompone in uno scenario in cui non è la schermata visibile
       (es. `HomeScreen` in `searchTyping`);
     - la radice si ricompone più di 1 volta per swipe negli scenari di solo scroll;
     - % jank > 10 in uno scenario;
     - qualsiasi metrica peggiorata di oltre il 20% rispetto al giro precedente.
  5. scenari falliti: ❌ + motivo (assenti dal JSON o con errore).
- `perf-reports/` va in `.gitignore`.

### 4. Skill di progetto `.claude/skills/perf/SKILL.md`

Invocabile come `/perf`. Passi:

1. Controlla l'emulatore; se spento lo avvia (`emulator -avd Pixel_8`) e attende `device`.
2. Esegue `scripts/perf.ps1` (in background, ~5-10 min).
3. Legge il report e riassume: migliorato / peggiorato / segnali.
4. Per ogni segnale verifica la causa sul codice e aggiorna MIGLIORIE.md (dati + causa).
5. Propone le migliorie una alla volta con AskUserQuestion (raccomandazione prima) e aspetta
   l'ok prima di toccare codice.

Aggiornare la memoria "Project skills" con la nuova skill.

## Gestione errori

- Nessun emulatore: `perf.ps1` esce con codice ≠ 0 e messaggio "avvia l'emulatore".
- Scenario fallito (rete assente, elemento non trovato): segnato ❌ nel report, gli altri
  restano validi.
- Nessun giro precedente: niente colonne delta.
- Sezioni Compose assenti nel trace (tracing non attivo): il report lo segnala esplicitamente
  invece di mostrare zeri.

## Verifica

- Primo giro completo sull'emulatore: tutti e 6 gli scenari presenti nel report.
- Le ricomposizioni misurate devono riprodurre quelle a mano del 2026-09-25 (es.
  `searchTyping`: `HomeScreen` ≈ 1 per tasto; `readerScroll`: radice ≈ 0).
- `perf_report.py` testato su un JSON di esempio (fixture salvata dal primo giro) e sul caso
  "nessun giro precedente".
- `:app:assembleRelease` invariato (nessuna dipendenza nuova fuori dal build type benchmark,
  a parte `profileinstaller`).

## Rischi

- **Tracing Compose + R8**: se le sezioni non compaiono nel build minificato, ripiego:
  `isMinifyEnabled = false` solo nel build type `benchmark` (i conteggi restano validi, i ms
  diventano pessimistici).
- **Formato delle sezioni**: i nomi hanno la forma `<fqName> (<File>.kt:<riga>)`; il filtro
  `%Nome (%` va verificato sul primo trace.
- **Card tutorial / dialog al primo avvio** diversi da quelli attesi: `AppSetup` li chiude per
  testo; se cambiano i testi, lo scenario fallisce con messaggio chiaro.
- **Rete**: `homeScroll` e `searchTyping` dipendono da AniList e dalle fonti.
- **`pm clear` e cartella esterna**: `pm clear` cancella anche
  `/sdcard/Android/data/<pkg>/`; la shell deve ricrearla (`mkdir -p`) prima di copiare i dati
  finti. Se su API 36 la shell non può scriverci, ripiego: avviare l'app una volta (crea la
  cartella) e copiare dopo, forzando poi la ri-scansione con un riavvio.

## Deviazioni emerse in implementazione (2026-09-25)

- **Niente `runtime-tracing`.** La 1.13 (allineata al runtime alpha dell'app) passa per
  androidx.tracing 2.0, che Macrobenchmark 1.5.0 non raccoglie. Al suo posto
  `ComposeTraceInstaller` (ContentProvider nel source set `app/src/benchmark/`) installa un
  `CompositionTracer` su `android.os.Trace`, filtrato ai 9 composable misurati (tracciarli tutti
  gonfiava i frame). Le regole R8 del runtime non eliminano `traceEventStart`: minify resta attivo.
- **Dati finti via `dd`.** L'app di test non riesce a crearsi la cartella esterna: i file sono
  generati in memoria e scritti con `UiAutomation.executeShellCommandRw("dd of=…")` (modulo di
  test con `minSdk 31`). Vanno copiati dopo un primo avvio dell'app, altrimenti la cartella
  creata dalla shell resta della shell e l'app non vede i file.
- **Build `benchmark` senza controllo aggiornamenti** (`UPDATE_*` vuoti): il dialog asincrono
  poteva bloccare i tocchi a metà misura.
- **Segnali su emulatore solo dai conteggi**: i ms oscillano anche di volte intere tra due giri.
- **Il report mostra il motivo** degli scenari falliti, letto dai risultati JUnit.
