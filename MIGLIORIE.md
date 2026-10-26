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

- [ ] **P1 · Redirect AniList accettato senza un login in corso** ✅ — Impatto Medio · Sforzo Basso
  - Dove: [MainActivity.kt:168](android-app/app/src/main/java/com/lorenzo/mangadownloader/MainActivity.kt#L168) e [MangaViewModel.kt:3649](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/MangaViewModel.kt#L3649). L'intent-filter è `BROWSABLE`: un link `mangapp://anilist-auth#access_token=<token altrui>` collega l'account dell'attaccante (`persistAccount` sovrascrive quello attuale), che da quel momento riceve progressi e preferiti.
  - Fix: salvare un timestamp "login avviato" all'apertura di `authorizationUrl()` ([MainActivity.kt:849](android-app/app/src/main/java/com/lorenzo/mangadownloader/MainActivity.kt#L849)) e rifiutare i redirect se manca o ha più di 10 minuti; va consumato al primo uso. Per `KEY_HANDLED_AUTH_TOKEN` salvare l'hash SHA-256, non il token in chiaro.
  - Auth Tab / Custom Tab (`androidx.browser` 1.10.0) migliora solo la UX, alla sicurezza non serve.

- [ ] **P1 · Blocco del PIN aggirabile spostando l'orologio** ✅ — Impatto Medio · Sforzo Basso
  - Dove: [ParentalControlController.kt:254](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/ParentalControlController.kt#L254) usa `System.currentTimeMillis()`.
  - Attenzione al fix dell'audit: `SystemClock.elapsedRealtime()` da solo si azzera al riavvio, quindi basterebbe riavviare il telefono. Va salvato anche `elapsedRealtime` + `Settings.Global.BOOT_COUNT` a ogni errore. Nello stesso avvio vale `elapsedRealtime`; dopo un riavvio vale l'orologio, ma se l'ora è precedente all'ultimo errore il PIN resta bloccato.
  - Limite da dichiarare all'utente: cancellare i dati o disinstallare l'app azzera comunque il controllo. È una barriera leggera; per garanzie vere serve Family Link. L'hash veloce è la voce in 🔒 Controllo genitori: priorità bassa, perché uno spazio di 10⁶ PIN resta piccolo anche con PBKDF2.

- [ ] **P1 · Token AniList nel backup automatico di Android** ✅ — Impatto Basso · Sforzo Basso
  - Dove: [AndroidManifest.xml:15](android-app/app/src/main/AndroidManifest.xml#L15) ha `allowBackup="true"` senza `dataExtractionRules`/`fullBackupContent`. Tutti gli store, token compreso, stanno nello stesso file `SettingsStore.PREFS_NAME`: escluderlo intero toglierebbe dal backup anche preferiti e impostazioni.
  - Fix: spostare token e profilo AniList in un file di prefs a parte (con migrazione una tantum) ed escludere solo quello: `data_extraction_rules.xml` per API 31+, `fullBackupContent` per API 26-30.
  - Tink / Keystore: scartato. Il token sta già nella sandbox e cifrarlo protegge solo da root e backup, già coperti qui sopra.

- [ ] **P2 · Aggiornamenti tramite l'API GitHub senza autenticazione** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [AppUpdateManager.kt:81](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/update/AppUpdateManager.kt#L81) (anche :104 e :134) chiama `api.github.com`, che ha un limite di 60 richieste all'ora per IP. Con molti utenti dietro lo stesso IP (CGNAT degli operatori mobili) arriva un 403: lo stabile ripiega su `version.properties`, ma si perdono le note e le preview.
  - Le note sono il messaggio del commit (:72): per questo nel popup è comparso "daje roma".
  - Fix: il workflow pubblica un `update.json` statico (versione, URL dell'APK, note prese dalla sezione del `CHANGELOG.md`) su raw o Pages, e l'app legge solo quello. Risolve entrambi i problemi.
  - Rischio da valutare: tutto il canale aggiornamenti dipende dal repo GitHub. Un'app di scraping molto diffusa può ricevere una segnalazione DMCA; se il repo sparisce, le app installate restano senza aggiornamenti. Far puntare `updateConfigUrl` a un dominio proprio lascia una via d'uscita.

- [ ] **P2 · Filtro adulti assente su Preferiti, Libreria, Cronologia, Aggiornamenti, widget e notifiche** ✅ — Impatto Medio · Sforzo Medio
  - Dove: `hidesAdultContent()` ([MangaViewModel.kt:297](android-app/app/src/main/java/com/lorenzo/mangadownloader/app/MangaViewModel.kt#L297)) si usa solo in ricerca, Home/Scopri, consigliati e nelle fonti VyManga e WeebCentral. `FavoriteManga` (:277) non salva se la serie è per adulti, quindi oggi non c'è niente su cui filtrare.
  - Subito: all'attivazione del controllo genitori, avvisare che preferiti e download già presenti restano visibili.
  - Poi: salvare `isAdult` sul preferito quando lo si aggiunge (il dato c'è già nel risultato di ricerca e in AniList), ricavarlo in background per i preferiti vecchi e filtrare ovunque con lo stesso flag.

- [ ] **P2 · User-Agent fisso su Chrome 124** ✅ — Impatto Medio · Sforzo Basso
  - Dove: [MangaNetworkClient.kt:135](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/network/MangaNetworkClient.kt#L135) e AniListClient.kt:308. Uno UA vecchio di due anni è un segnale per Cloudflare, e con molti utenti i blocchi pesano di più.
  - Fix: usare lo UA di `WebSettings.getDefaultUserAgent()`, che si aggiorna con la WebView del telefono, togliendo il marcatore `; wv`, con la costante come ripiego. Valutare anche un limite di richieste parallele per host.

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

- [ ] **Estrarre altri controller dal ViewModel (4300 righe)** ✅ — Impatto Medio · Sforzo Alto
  - Stesso schema già usato per `ParentalControlController` e `TutorialController`: classe che condivide il `MutableStateFlow`, esposta come `viewModel.xxx`.
  - Candidati, dal più isolato al più intrecciato: **Home/Scopri** (`loadDiscovery`, `loadRecommendations`, `refreshHomeFeeds`, generi, ~300 righe) → **AniList** (auth, match, tracker, sync, ~550 righe) → **Preferiti e scaffali** → **Reader** (apertura, streaming, prefetch, avanzamento, adiacenze: ~1100 righe, il più accoppiato con libreria e memoria di lettura).

- [ ] **`MangaUiState` monolitico e schermate che lo ricevono intero** ✅ — Impatto Medio · Sforzo Alto
  - Dove: `MangaUiState` ha ~90 campi; `AppTopBar`, `HomeScreen`, `LibraryScreen`, `SearchScreen`, `StatsScreen` e `TutorialOverlay` prendono `state: MangaUiState`, quindi rigirano a ogni emissione qualsiasi.
  - Cosa fare: sotto-stati per area (`ReaderUiState`, `LibraryUiState`, `FavoritesUiState`, `ParentalUiState`…, sul modello di `DiscoveryUiState`/`AniListUiState` che esistono già) e ogni schermata riceve solo il suo. Va di pari passo con l'estrazione dei controller.
  - Misurato (2026-09-25): strong skipping attivo e 547/741 composable saltabili, ma `MangaUiState` è una nuova istanza a ogni emissione, quindi chi lo riceve intero si ricompone sempre. Il reader, il percorso più caldo, è **quasi isolato**: `ReaderScreen` non si ricompone mai, la radice solo quando si salva l'avanzamento (4-6 volte in 15 swipe). Il guadagno reale è sulla digitazione in ricerca (vedi la voce "Barre, tutorial e tab vicine…" in Prestazioni, rinviata: il costo misurato è piccolo). Conviene farlo **a pezzi**, schermata per schermata, non come refactor unico.

- [ ] **`MangaDownloaderAppContent` è un unico composable di ~900 righe** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [MainActivity.kt:200-1106](android-app/app/src/main/java/com/lorenzo/mangadownloader/MainActivity.kt#L200): launcher dei permessi, backup, snackbar, top/bottom bar, pager, `when` delle schermate e dialog, tutto nello stesso scope di ricomposizione.
  - Cosa fare: separare `AppScaffold`, `ScreenHost` (il `when`) e `AppDialogsHost`, ognuno con i soli parametri che usa. Rende efficaci i due punti sopra.

- [ ] **File troppo grossi da navigare** 🔎 — Impatto Basso · Sforzo Medio
  - `UiComponents.kt` (1045 righe, componenti eterogenei) e `ReaderScreen.kt` (1280 righe, con `VerticalReader` da solo ~410). Solo organizzazione: dividere per componente, nessun guadagno di prestazioni.

---

## 📦 Librerie

> Versioni lette il 2026-10-26 dai metadati di Google Maven e Maven Central.

- [ ] **Robolectric 4.17 manda in crash il processo dei test su Windows** ✅ — Impatto Basso · Sforzo Basso
  - Il 2026-10-26 sono state aggiornate le altre librerie: Kotlin 2.4.21, Gradle 9.8.2, Material3 `1.5.0-beta02`, OkHttp 5.5.1, `kotlinx-serialization-json` 1.12.0. Robolectric è rimasta alla 4.16.1, perché con la 4.17 il test executor si chiude con `0xC0000409` (crash nativo, non un test fallito); con la 4.16.1 passano 697 test su 697.
  - Causa (verificata il 2026-10-26): la 4.17 porta `nativeruntime-dist-compat` 1.0.19, che per la prima volta implementa in nativo `BitmapRegionDecoder` (la 1.0.18 della 4.16.1 non contiene `nativeDecodeRegion`). Sulla DLL Windows quel codice è rotto: `BitmapRegionDecoder.newInstance` lancia `UnsatisfiedLinkError` su `BitmapRegionDecoderNatives.nativeNewInstance`, e nel percorso reale manda in crash la JVM. Colpisce solo i 3 test di [TallPageNormalizerTest](android-app/app/src/test/java/com/lorenzo/mangadownloader/ui/reader/TallPageNormalizerTest.kt) che spezzano davvero l'immagine. A quella data non c'era nessuna issue upstream.
  - Scelta: si resta sulla 4.16.1. Sulla nostra configurazione la 4.17 non porta niente: gli SDK 36/37 richiedono Java 21, e i test girano con Java 17.
  - Cosa fare: passare alla 4.17.x (o a `nativeruntime-dist-compat` > 1.0.19) quando correggono il problema. Per verificare basta lanciare `TallPageNormalizerTest` su Windows.
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

- [ ] **Hash del PIN troppo veloce** 🔎 — Impatto Basso · Sforzo Basso
  - Dove: [ParentalControlSecurity.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/domain/ParentalControlSecurity.kt) — SHA-256 con sale, un solo passaggio: chi legge le preferenze dell'app (root, backup di sistema) prova tutti i PIN a 6 cifre in un attimo.
  - Cosa fare: PBKDF2 con molte iterazioni, migrando l'hash al primo sblocco riuscito.
