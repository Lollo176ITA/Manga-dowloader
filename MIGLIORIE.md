# Migliorie — Manga Downloader

> Voci ancora aperte. Le migliorie completate non vivono più qui: sono nel [CHANGELOG.md](CHANGELOG.md) per la parte che l'utente vede, e in `git log` per il resto — tenerne una seconda copia commentata significava solo mantenerla.
> Legenda stato verifica: ✅ = controllato sul codice reale · 🔎 = valutazione/da confermare prima di intervenire.
> Tag: **Impatto** Alto/Medio/Basso · **Sforzo** Basso/Medio/Alto.

---

## 🔐 Sicurezza e distribuzione larga

> Audit esterno verificato il 2026-10-26 sul branch `dev`. P0 → P3 è l'ordine consigliato prima di distribuire a molti utenti.

- [ ] **P0 · Chiudere il vecchio canale SMTP** ✅ — Impatto Alto · Sforzo Basso *(passi manuali)*
  - Fatto il 2026-10-26: le segnalazioni passano dal Worker [`report-relay/`](report-relay/README.md), che crea la card con l'API di Trello. Jakarta Mail, le regole ProGuard e la configurazione SMTP sono stati tolti dall'app e dalla CI.
  - Resta da fare, a mano e **dopo** aver pubblicato una versione col relay: revocare la app password di `mangapp.reports@gmail.com` (le versioni vecchie smettono di inviare) e cancellare i secret GitHub `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `REPORT_TO_EMAIL`.

- [ ] **Da verificare sul telefono (fix del 2026-10-26)** ✅ — Impatto Alto · Sforzo Basso
  - Login AniList: il redirect deve riportare lo `state` passato in `authorizationUrl()` ([AniListTracking.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/anilist/AniListTracking.kt)). Se AniList non lo restituisce, il collegamento si rompe per tutti: va provato prima della release.
  - Blocco del PIN dopo un riavvio, filtro adulti su preferiti e download già presenti, fonti col nuovo User-Agent.

- [ ] **P2 · Canale aggiornamenti legato al repo GitHub** 🔎 — Impatto Medio · Sforzo Medio
  - Il feed (`update-stable.json` / `update-preview.json`, asset della release `update-feed`) e gli APK stanno nel repo. Un'app di scraping molto diffusa può ricevere una segnalazione DMCA: se il repo sparisce, le app installate restano senza aggiornamenti. Far puntare l'URL del feed ([AppUpdateManager.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/update/AppUpdateManager.kt) `updateFeedUrl`) a un dominio proprio lascia una via d'uscita.

- [ ] **P2 · Informativa privacy e consenso** 🔎 — Impatto Medio · Sforzo Basso
  - Dal telefono escono dati: segnalazioni (email facoltativa, vocali, screenshot), crash (percorsi e titoli) e AniList. Distribuendo a molti serve un'informativa breve (nel README, con un link nelle Impostazioni) e un consenso esplicito prima di inviare un crash.

- [ ] **P3 · Letture senza tetto di dimensione** ✅ — Impatto Basso · Sforzo Basso
  - Dove: `MangaNetworkClient.fetchBytes` (:43) usa `body.bytes()` e `BackupManager.restore` (:64) usa `readBytes()`. Mettere tetti fissi (es. 50 MB e 20 MB) e un limite sulle voci dei CBZ. L'APK di aggiornamento ha già la verifica della firma: un hash non aggiungerebbe nulla.

- [ ] **P3 · CI** ✅ — Impatto Basso · Sforzo Basso
  - Fissare le Action allo SHA e attivare Dependabot (Action e Gradle), che tiene aggiornati anche gli SHA.
  - Passare i secret solo agli step di build dà poco: anche `assembleRelease` esegue gli stessi plugin di terzi.

---

## ✨ Feature (ordinate per resa con tanti utenti)

- [ ] **Scarica solo in Wi-Fi** — Basso · vincolo `NetworkType.UNMETERED` sul worker. Il più economico.
- [ ] **Lingua inglese** — Impatto Alto (6 fonti su 8 sono inglesi) · Sforzo Alto: tutte le stringhe sono nel codice (`strings.xml` ne ha 4). Spostarle nelle risorse Android (`values/` + `values-en/`), una schermata alla volta.
- [ ] **MangaDex come fonte** — Impatto Alto (scan in italiano, API ufficiale) · Sforzo Medio (skill `add-manga-source`). Rispettare le regole dell'API: User-Agent identificabile e limiti di richiesta.
- [ ] **Esporta in Download / cartella scelta con SAF** — Medio/Medio. Oggi i `.cbz` stanno in `Android/data` e si perdono disinstallando l'app.
- [ ] **Download automatico dei nuovi capitoli dei preferiti** (solo Wi-Fi e in carica) — Medio/Medio, si appoggia a `FavoriteUpdatesWorker`.
- [ ] **Silenziare un preferito / scegliere la frequenza del controllo** — Basso/Basso.
- [ ] Rinviabili: incognito, deep link alle fonti, tasti volume nel reader, ritaglio dei bordi, seppia, scorrimento automatico, altri tracker (MAL, Kitsu), backup periodico.

---

## 🟢 Bug & quick win

- [ ] **Letture SharedPreferences sincrone nel costruttore del ViewModel (main thread)** ✅ — *RINVIATO (2026-05-26): lo stato iniziale (tab con parental control, fase tutorial, preferiti) è costruito sincronamente da queste letture; renderle async non è un vero quick win (rischio flash/regressioni su tutorial e tab iniziale) e il guadagno è marginale con prefs piccole. Da fare con cura a parte.*
  - Dove: `MangaViewModel` field init → `settingsStore`, `favoriteUpdatesStore`, `readingMemoryStore`, `readingDiaryStore`, `recentSearchesStore`, `sourceHealthStore`, `favoriteUpdatesFeedStore` letti tutti alla creazione, più i preferiti e le descrizioni in `init`.
  - Perché: I/O + parsing JSON sincroni all'avvio. Quasi tutti gli store sono piccoli o limitati (il feed ha `MAX_FEED_EVENTS`), ma `ReadingMemoryStore` tiene una voce per ogni capitolo letto e non ha tetto: è l'unico che cresce con l'uso.
  - Cosa fare: stato iniziale "vuoto/loading" e caricamento in `init { viewModelScope.launch(Dispatchers.IO) { … } }`. Impatto Medio · Sforzo Basso.

---

## 🟡 Affidabilità & test mancanti

- [ ] **DemonicScans: nessun ripiego sull'host immagini di riserva** ✅ — Impatto Basso · Sforzo Basso
  - Dove: [DemonicScansSource.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/sources/DemonicScansSource.kt). Il reader del sito, se un'immagine fallisce, riprova sostituendo `demoniclibs` con `librarydm` nell'URL (`tryAgain` nella pagina, verificato il 2026-09-25). L'app invece fa fallire la pagina.
  - Cosa fare: al fallimento di una pagina `cdn.demoniclibs.com`, riprovare una volta su `librarydm`.

- [ ] **VyManga: date dei capitoli non lette** ✅ — Impatto Basso · Sforzo Basso
  - Dove: [VyMangaSource.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/sources/VyMangaSource.kt). Ogni `a.list-chapter` contiene `<p class="text-right font-italic small">Sep 11, 2026</p>` (verificato sul nuovo dominio `mangavyvy.com`).
  - Cosa fare: un parser `MMM d, yyyy` in `ChapterDates.kt` (mesi inglesi hardcoded, come quelli italiani) e `publishedAtMillis` in `parseChapters`.

- [ ] **Alimentare il `CrashReporter`/log sugli errori di parsing** 🔎
  - Dove: le source lanciano `IllegalStateException("Nessun capitolo…")` senza dire *quale* selettore è fallito ([MangapillSource.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/sources/MangapillSource.kt)).
  - Perché: un log sul telefono dell'utente non lo vede nessuno; serve che l'informazione arrivi con la segnalazione.
  - Cosa fare: tenere gli ultimi errori delle fonti (fonte, URL, cosa non è stato trovato nella pagina) in un piccolo buffer e allegarli a "Segnala un problema". Impatto Medio · Sforzo Medio.

- [ ] **Stato di lettura per-capitolo nelle SharedPreferences, senza tetto** ✅ — *RINVIATO (2026-09-23): migrazione pesante (dati utenti, streaming) per un costo che si sente solo con migliaia di capitoli.* Impatto Basso · Sforzo Alto
  - Dove: [LibraryRepository.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/library/LibraryRepository.kt) — fino a 4 chiavi per capitolo in `manga_library_prefs` (`read::`, `reader_page_index::`, `reader_page_count::`, `reader_read_at::` + percorso), mai potate se non eliminando il capitolo. `saveReaderPagePosition` scrive a ogni avanzamento di pagina, e ogni `apply()` copia e riscrive l'intero file.
  - Non è un bug di coerenza: il "letto" vive anche in `readChapterIds` dei metadati, ma la scansione li unisce in OR e "segna/togli letto" aggiorna entrambi.
  - Cosa fare: posizione e "letto" dei capitoli scaricati nel JSON della serie (con migrazione una tantum dalle prefs), lasciando alle prefs solo lo streaming; oppure DataStore/Room per tutto.

- [ ] **Navigazione a flag booleani invece di un back-stack esplicito** 🔎 — *RINVIATO (2026-09-23).* Impatto Basso · Sforzo Alto
  - Dove: [Screen.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/Screen.kt) — `currentScreen()` ricava la schermata da `showX`/`selected != null` in ordine di priorità; ogni schermata nuova vuole un flag, un ramo nel `when`, un `closeX()` e un caso in `handleBack`.
  - Già mitigato: la priorità è centralizzata in un tipo sigillato testabile, e le combinazioni "incoerenti" (es. `showSettings` + `showUpdates`) si risolvono in modo deterministico, come uno stack.
  - Cosa fare, se le schermate crescono ancora: `List<Screen>` nello stato al posto dei flag `show*`, o Navigation Compose.

---

## ⚡ Prestazioni

- [ ] **Un solo file SharedPreferences per impostazioni, preferiti e memoria di lettura** ✅ — Impatto Medio · Sforzo Medio
  - Dove: 16 store del ViewModel condividono `SettingsStore.PREFS_NAME`, compreso [ReadingMemoryStore](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/store/ReadingMemoryStore.kt), che tiene un JSON con una voce per ogni capitolo mai letto, senza tetto. Ogni `apply()` (cambio di un'impostazione, preferito, feed) riscrive su disco l'intero file, memoria di lettura compresa.
  - Cosa fare: spostare memoria e diario di lettura in un file proprio (o in DataStore), con migrazione una tantum. Si lega alla voce sulle prefs per-capitolo più in basso.

- [ ] **Barre, tutorial e tab vicine ricevono `state` intero e si ricompongono a ogni emissione** ✅ — *RINVIATO (2026-09-25): guadagno non percepibile rispetto alla complessità.* Impatto Basso · Sforzo Medio
  - Misurato col benchmark (`/perf`): in `searchTyping` ogni tasto ricompone `AppTopBar`, `AppBottomBar`, `TutorialOverlay` e `SearchScreen` (9 ciascuno per 7 tasti); nel reader `AppTopBar` e `TutorialOverlay` si ricompongono a ogni salvataggio dell'avanzamento (6 in 15 swipe).
  - Perché rinviato: la top bar è un titolo e qualche icona, una frazione dei ~5 ms di composizione per tasto (emulatore). Per evitarlo serve un modello dedicato per barra e tutorial (~60 righe ciascuno), una lambda stabile per "salva capitolo" e l'obbligo di tenere il modello allineato a ogni nuova azione della barra. Provato e annullato: troppa complessità per un effetto che sul telefono non si vede.
  - Da riprendere solo se il benchmark mostra un costo reale (ms per tasto in crescita) o dentro un refactor più ampio dello stato.

- [ ] **Nessun Baseline Profile** ✅ — Impatto Medio · Sforzo Basso
  - Dove: `profileinstaller` 1.4.1 e il modulo `:benchmark` ci sono già (aggiornato il 2026-10-26), manca solo il profilo. Senza profilo, le app Compose eseguono in JIT il codice dell'avvio e del primo scroll.
  - Cosa fare: aggiungere il plugin `androidx.baselineprofile` 1.5.0 e un test generatore in `:benchmark` (avvio → Home → Libreria → reader). Misurare con `/perf` prima e dopo.

---

## 🧹 Snellimento & architettura

> Audit strutturale del 2026-11-15 su `dev` (32.500 righe di codice in 128 file, 14.700 di test in 98 file).
> **Valore** = (Impatto + Rischio) × (6 − Sforzo), ognuno da 1 a 5: più alto, prima si fa.
> Il criterio che ordina tutto: un file grosso costa solo se **cambia spesso**. Per ogni file è indicato in quanti degli ultimi 60 commit è stato toccato.

| Valore | Voce | Impatto | Rischio | Sforzo |
| --- | --- | --- | --- | --- |
| 40 | Skill di progetto che descrivono un progetto che non esiste più | 4 | 4 | 1 |
| 21 | `FavoriteUpdatesWorker.doWork` (202 righe, nessun test) | 3 | 4 | 3 |
| 16 | Store creati a mano in tre posti | 2 | 2 | 2 |
| 15 | Modelli di stato dentro `MangaViewModel.kt` | 2 | 1 | 1 |
| 12 | Controller "scheda manga" e "preferiti" dal ViewModel | 3 | 3 | 4 |
| 10 | Residui della migrazione KMP/iOS | 1 | 1 | 1 |
| 9 | Schermate con decine di parametri | 2 | 1 | 3 |
| 6 | `MangaUiState` monolitico | 2 | 1 | 4 |
| 6 | File grossi ma fermi (`ReaderScreen`, `UiComponents`, test delle fonti) | 1 | 1 | 3 |

- [ ] **Skill di progetto che descrivono un progetto che non esiste più** ✅ — Valore 40
  - Dove: `.claude/skills/android-testing/SKILL.md` (righe 8-67 e 155: modulo KMP `:shared`, `commonTest`, `MapSettings`/`FakeFileSystem`/`MockEngine`, comando `:shared:testAndroidHostTest`, "il `MangaViewModel` vive in `:shared` e riceve un `AppContainer`"), `app-ui/SKILL.md` e `accessibility/SKILL.md` (link a `android-app/shared/src/commonMain/...`). `android-testing` e `app-ui` citano anche il client Python `manga_downloader.py`.
  - Verificato: `settings.gradle.kts` include solo `:app` e `:benchmark`; `shared/src/commonMain/kotlin` e `manga_downloader.py` non esistono; il ViewModel è in `app/` e crea gli store da solo.
  - Perché è la prima: il codice lo scrive Claude leggendo queste skill. Ogni sessione di test o di UI parte da percorsi che non esistono e da un comando di test su un modulo che Gradle non conosce.
  - Cosa fare: riallineare le tre skill ai percorsi `android-app/app/src/...` e al solo `:app:testDebugUnitTest`.

- [ ] **Rifare il giro `/perf` dopo lo spezzamento di `MainActivity`** ✅ — Impatto Basso · Sforzo Basso
  - Il 2026-11-15 `MangaDownloaderAppContent` è sceso da 923 a 357 righe (`MainActivity.kt` da 1230 a 618): schermate, tab, impostazioni, dialog, permessi notifiche, avvio download e backup stanno in `ui/app/`.
  - La metrica `rootCompose` del benchmark somma il tempo della sola radice (`Metrics.kt`): ora che il `when` delle schermate è un composable a parte, il numero non è più confrontabile col giro precedente. Serve un giro nuovo che faccia da riferimento.

- [ ] **`FavoriteUpdatesWorker.doWork` fa tutto in 202 righe e non ha test** ✅ — Valore 21
  - Dove: [FavoriteUpdatesWorker.kt:82-283](android-app/app/src/main/java/com/lorenzo/mangadownloader/FavoriteUpdatesWorker.kt#L82). Migrazione, risoluzione identità, sync AniList, giro di rete con ripiego sui mirror, baseline, feed, notifiche e cambio di fonte nello stesso metodo. Toccato in 16 degli ultimi 60 commit.
  - Le funzioni pure che usa (`computeFavoriteUpdate`, `appendUpdateEvent`, `fetchFromFirstAvailable`) sono testate; l'orchestrazione no: nessun test nomina la classe.
  - Perché: gira in background, un errore qui (notifica doppia, baseline persa, mirror cambiato a torto) non si vede finché un utente non lo segnala.
  - Cosa fare: spostare il ciclo in una classe senza `Context` (`FavoriteUpdatesRun`, sul modello di `AniListFavoritesSynchronizer`), con fetch e notifier passati come parametri, e testarla: mirror di ripiego, interruzione a metà, serie per adulti non notificata.

- [ ] **Store creati a mano in tre posti** ✅ — Valore 16
  - Dove: [MangaViewModel.kt:470-505](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/MangaViewModel.kt#L470) (15 store), [FavoriteUpdatesWorker.kt:84-148](android-app/app/src/main/java/com/lorenzo/mangadownloader/FavoriteUpdatesWorker.kt#L84) (12 store, più la stessa `FavoritesSeriesMigration` con gli stessi 6 argomenti), `ReadingWidget.kt` (3).
  - Cosa fare: una classe `AppStores(context)` con gli store come proprietà `lazy` e `migrateFavorites()`. Non è un framework DI (scartato, vedi 📦): è un solo file che toglie la duplicazione e rende impossibile dimenticare la migrazione in un nuovo punto d'ingresso.

- [ ] **Modelli di stato dentro `MangaViewModel.kt`** ✅ — Valore 15
  - Dove: righe 150-450 del file: `DiscoveryUiState`, `AniListUiState`, `FavoriteManga`, `AppSettings` (37 campi), `MangaUiState` (67 campi), `MangaInfoDialogState`.
  - Cosa fare: spostarli in `app/MangaUiState.kt` e `app/AppSettings.kt`. Puro spostamento, stesso package, nessun import da cambiare: il ViewModel scende da 2390 a circa 2090 righe e chi cerca un campo dello stato non apre un file da 105 KB.

- [ ] **Estrarre i controller "scheda manga" e "preferiti" dal ViewModel (2390 righe)** ✅ — Valore 12
  - Già estratti: `ReaderController` (1380 righe), `AniListController`, `HomeFeedController`, `AppUpdateController`, `ParentalControlController`, `TutorialController`. Il ViewModel era a 4300 righe.
  - Restano, per dimensione: **scheda e scelta della fonte** (righe 1286-1810: `selectSeries`, `loadSourceOptions`, `switchSource`, `selectManga` da 161 righe, `showMangaInfo`, `toggleFavorite`) → **preferiti e scaffali** (753-910) → **ricerca aggregata** (2035-2155).
  - Il ViewModel è toccato in 10 degli ultimi 60 commit: conviene farlo quando si rimette mano alla scheda manga, non come lavoro a sé.

- [ ] **Residui della migrazione KMP/iOS abbandonata** ✅ — Valore 10
  - `android-app/shared/src/commonMain/composeResources/files/CHANGELOG.md` è tracciato ma non lo legge nessuno (la build copia quello della root, `app/build.gradle.kts:184`) ed è fermo a 246 righe contro 278.
  - `.gitignore`: le righe per `ios-app/`, cartella che non esiste.
  - `README.md:7-9` dice "7 fonti" e non cita WeebCentral, che è nel catalogo (`MangaSourceIds`, 8 fonti).
  - [MangaViewModel.kt:685](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/MangaViewModel.kt#L685): a ogni avvio apre un `edit` sulle prefs per togliere due chiavi (`favorite_categories_json`, `favorite_category_assignments_json`) rimosse da versioni fa. Va protetto con un `contains`, o tolto.

- [ ] **Schermate con decine di parametri** ✅ — Valore 9 · *rinviabile*
  - `SettingsScreen` 44 parametri, `FavoritesScreen` 27, `DetailScreen` 23, `HomeScreen` 22, `AppTopBar` 20.
  - Raggruppare le callback in un oggetto "azioni" per schermata accorcia `MainActivity`, ma è un'astrazione in più da tenere allineata: da fare solo dentro lo spezzamento di `MangaDownloaderAppContent`, se il `when` resta illeggibile.

- [ ] **`MangaUiState` monolitico e schermate che lo ricevono intero** ✅ — Valore 6 · *rinviabile*
  - Dove: 67 campi; `AppTopBar`, `HomeScreen`, `LibraryScreen`, `SearchScreen`, `StatsScreen` e `TutorialOverlay` prendono `state: MangaUiState`.
  - Misurato (2026-09-25): il reader è quasi isolato, e il costo sulla digitazione in ricerca è piccolo (vedi la voce rinviata in ⚡). Sotto-stati per area solo insieme all'estrazione di un controller, schermata per schermata.

- [ ] **File grossi ma fermi** ✅ — Valore 6 · *non toccare*
  - `ReaderScreen.kt` (1226 righe, `VerticalReader` 353; 4 commit su 60), `UiComponents.kt` (1025 righe, 24 componenti; 3 su 60), `LibraryRepository.kt` (951; 3 su 60), `MangaSourcesTest.kt` (1763 righe, tutte le fonti in un file).
  - Sono lunghi ma cambiano poco: dividerli costa un diff grande e non rende niente. Da spezzare solo il giorno in cui si lavora a lungo su uno di loro.

---

## 📦 Librerie

> Versioni lette il 2026-11-15 dai metadati di Google Maven e Maven Central. Quel giorno: AGP 9.5.1, Gradle 9.9.1, Material3 `1.5.0-rc01`, jsoup 1.24.1, `kotlinx-serialization-json` 1.13.0, UiAutomator 2.4.1. Tutto il resto era già all'ultima stabile.

- [ ] **Robolectric 4.17 e 4.18 mandano in crash il processo dei test su Windows** ✅ — Impatto Basso · Sforzo Basso
  - Il 2026-10-26 sono state aggiornate le altre librerie: Kotlin 2.4.21, Gradle 9.8.2, Material3 `1.5.0-beta02`, OkHttp 5.5.1, `kotlinx-serialization-json` 1.12.0. Robolectric è rimasta alla 4.16.1, perché con la 4.17 il test executor si chiude con `0xC0000409` (crash nativo, non un test fallito); con la 4.16.1 passano 697 test su 697.
  - Causa (verificata il 2026-10-26): la 4.17 porta `nativeruntime-dist-compat` 1.0.19, che per la prima volta implementa in nativo `BitmapRegionDecoder` (la 1.0.18 della 4.16.1 non contiene `nativeDecodeRegion`). Sulla DLL Windows quel codice è rotto: `BitmapRegionDecoder.newInstance` lancia `UnsatisfiedLinkError` su `BitmapRegionDecoderNatives.nativeNewInstance`, e nel percorso reale manda in crash la JVM. Colpisce solo i 3 test di [TallPageNormalizerTest](android-app/app/src/test/java/com/lorenzo/mangadownloader/ui/reader/TallPageNormalizerTest.kt) che spezzano davvero l'immagine. A quella data non c'era nessuna issue upstream.
  - Scelta: si resta sulla 4.16.1. Sulla nostra configurazione la 4.17 non porta niente: gli SDK 36/37 richiedono Java 21, e i test girano con Java 17.
  - Riprovato il 2026-11-15 con la 4.18: stesso crash `0xC0000409` su `TallPageNormalizerTest`.
  - Cosa fare: riprovare a ogni nuova versione (o con `nativeruntime-dist-compat` > 1.0.19). Per verificare basta lanciare `TallPageNormalizerTest` su Windows.
  - Nota per Material3: non usare la versione del BOM (1.4.x), perché `MaterialExpressiveTheme` esiste solo nella 1.5.

- [ ] **Baseline Profile** — vedi la voce in ⚡ Prestazioni.

- [ ] **Sentry per i crash** 🔎 — Impatto Alto con molti utenti · Sforzo Medio · *dopo* il Worker (voce P0)
  - `io.sentry:sentry-android` 8.61.1 + plugin Gradle (carica la mappa R8).
  - Cosa porta: crash raggruppati e deduplicati, stack trace decodificati, breadcrumb, versione e dispositivo. I crash mandati via Worker su Trello invece aprono una card ciascuno: con molti utenti la board si riempie di doppioni.
  - Condizioni: invio solo con consenso, regione dati UE, niente titoli o percorsi nei dati extra. Controllare la quota del piano gratuito prima di scegliere.

- [ ] **Chucker** (`debugImplementation`, 4.3.1) — Impatto Basso · Sforzo Basso. Mostra sul telefono le richieste HTTP delle fonti quando un sito si rompe.

- [ ] **Test HTTP delle fonti** con MockWebServer3 5.5.0 (stessa versione di OkHttp): retry, Referer, 404, risposte troncate. Roborazzi 1.77.0 e Turbine 1.2.1 solo se servono davvero (regressioni di UI, test sui Flow).

- Valutate e **scartate**, a oggi:
  - **Telephoto**: provata nel reader il 2026-09-23 e scartata. Serve solo nel reader a pagine, mentre `TallPageNormalizer`/`ReaderTallPage` restano indispensabili nel verticale.
  - **Tink / `security-crypto` / `datastore-tink`**: vedi la voce sul backup in 🔐.
  - **Apollo Kotlin** per AniList: plugin, schema e codice generato per poche query scritte a mano. Il guadagno non giustifica il costo.
  - **kotlinx-collections-immutable**: lo strong skipping è attivo, e il costo misurato delle ricomposizioni è già stato giudicato non percepibile.
  - **Detekt 1.23.x** 🔎: è pensato per Kotlin più vecchi del 2.4. Se serve analisi statica, meglio `compose-lint-checks` come `lintChecks` di Android Lint.
  - **LeakCanary 3.0-alpha**, **ACRA**, **estensioni Mihon/Tachiyomi** (eseguirebbero codice di terzi e annullerebbero la verifica della firma).
  - **DataStore** per le prefs che crescono: la variante Preferences riscrive comunque l'intero file a ogni modifica, quindi non risolve il problema; separare i file basta.
  - **Room**: sarebbe lo strumento giusto *se* si spostano posizioni/letto per capitolo e memoria di lettura in un DB (query per serie, potatura, statistiche). Costa plugin KSP e migrazione dei dati; ha senso solo insieme a quella voce, non da solo.
  - **Hilt/Koin**: l'app crea a mano ~16 store e 2 repository condivisi; un framework DI aggiungerebbe codice generato e build più lente senza togliere complessità vera. Per dividere il ViewModel basta lo schema a controller già in uso. (Riconfermato il 2026-10-26.)
  - **Room / DataStore 1.3.0**: riconfermato. L'argomento dell'audit sul ripristino non atomico è vero, ma tutti gli store stanno in un unico file di prefs: un solo `Editor` per l'intero restore lo rende atomico senza database 🔎.
  - **Navigation Compose / Navigation 3 (1.2.0)**: vedi la voce rinviata sulla navigazione.
  - **`material-icons-extended`** (81 icone usate su migliaia): R8 elimina il resto in release, quindi l'APK non ne soffre; pesa solo sulle build debug. Passare ai drawable Material Symbols è pulizia, non prestazioni.

---

## 🔒 Controllo genitori

- [ ] **Ricerca "tutto o niente" sotto controllo genitori** 🔎 — Impatto Medio · Sforzo Basso
  - Oggi con il parentale attivo la tab Cerca chiede il PIN, e dopo il PIN i risultati sono comunque filtrati. Un ragazzo quindi non può cercare niente da solo.
  - Da decidere: un'opzione "Consenti la ricerca filtrata" che lascia cercare senza PIN, con il filtro per adulti sempre acceso. Scelta di prodotto, non tecnica.

- [ ] **PIN impostati prima del PBKDF2 ancora con l'hash veloce** ✅ — Impatto Basso · Sforzo Basso
  - Fatto: i PIN nuovi usano PBKDF2 con 100.000 iterazioni ([ParentalControlSecurity.kt:22](android-app/app/src/main/java/com/lorenzo/mangadownloader/domain/ParentalControlSecurity.kt#L22)).
  - Resta: un PIN impostato prima continua a essere verificato con SHA-256 a un passaggio, perché `hashParentalPin` è chiamato solo quando si imposta il PIN (`ParentalControlController.kt:167`). Va ricalcolato e salvato al primo sblocco riuscito.
