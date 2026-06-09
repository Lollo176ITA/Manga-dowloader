# Migliorie — Manga Downloader

> Lista di todo generata da un'analisi del progetto (architettura, test/robustezza, client Python, CI) e **revisionata file per file** il 2026-05-26. Aggiornata il 2026-06-09 con l'audit del codice nuovo (notifiche aggiornamenti preferiti, reader).
> Legenda stato verifica: ✅ = controllato sul codice reale · 🔎 = valutazione/da confermare prima di intervenire.
> Tag: **Impatto** Alto/Medio/Basso · **Sforzo** Basso/Medio/Alto.

---

## 🔴 Sicurezza & affidabilità del rilascio (priorità massima)

- [x] **Verificare firma/checksum dell'APK di auto-update prima di installarlo** ✅ — *fatto (2026-05-26)*
  - Dove: `AppUpdateManager.downloadUpdateApk` [android-app/app/src/main/java/com/lorenzo/mangadownloader/AppUpdateManager.kt:164-196]; install via `REQUEST_INSTALL_PACKAGES` [AndroidManifest.xml:9].
  - Perché: l'APK veniva scaricato (HTTPS da GitHub, ok) e installato **senza** verificare il certificato di firma né un hash noto. Se l'URL fosse dirottato si installerebbe un APK arbitrario.
  - **Fatto:** `AppUpdateInstaller.installApk` ora chiama `verifyTrustedSignature` prima di lanciare l'intent: confronta i certificati di firma dell'APK scaricato (`GET_SIGNING_CERTIFICATES`, fallback `GET_SIGNATURES` su API 26-27) con quelli dell'app installata; **fail-closed** se le firme non sono leggibili o non corrispondono → `IOException` mostrata come errore. Funzione pura `signaturesTrusted(installed, downloaded)` con unit test in `AppUpdateManagerTest`.

- [x] **Far girare i test in CI (su PR/`dev`) e prima della release** ✅ — *fatto (2026-05-26)*
  - Dove: `.github/workflows/android.yml` e `android-preview.yml` eseguivano solo `assembleRelease`; nessuno step di test.
  - Perché: ~81 test esistono ma una release poteva pubblicare codice rotto agli utenti (che ricevono l'auto-update) senza che nulla girasse.
  - **Fatto:** step `Run unit tests` (`./gradlew testDebugUnitTest`) aggiunto **prima** della build in `android.yml` (gate della release) e in `android-preview.yml` (gate delle preview); nuovo workflow `android-tests.yml` che gira i test sulle **pull request** (paths `android-app/**`). Verificato in locale: suite verde.

---

## 🟢 Bug & quick win (alto rapporto valore/sforzo)

- [ ] **Race sul feed aggiornamenti: il worker sovrascrive il "visto" dell'utente** ✅ *(verificato 2026-06-09)*
  - Dove: `FavoriteUpdatesWorker.doWork` legge il feed all'inizio [FavoriteUpdatesWorker.kt:54] e lo riscrive **per intero** nel `finally` [FavoriteUpdatesWorker.kt:109]; nel frattempo l'app può scrivere lo stesso feed con `markAllUpdatesSeen()` [MangaViewModel.kt:688-693].
  - Perché: il loop del worker fa rete per ogni preferito (può durare decine di secondi). Se mentre gira l'utente apre "Aggiornamenti" e azzera il badge, il `finally` del worker riscrive la copia stantia con `seen=false` → il badge "non visto" risorge e gli eventi marcati visti tornano non visti. Stessa finestra anche per il caso opposto (worker in-process e refresh in foreground).
  - Cosa fare: nel `finally` fare **read-merge-write**: rileggere il feed da disco, fare merge con gli eventi nuovi del worker (dedup già esistente su identityKey+chapterNumber) preservando i flag `seen` più recenti, e scrivere il risultato. In alternativa appendere/persistere subito a ogni evento invece di accumulare. Impatto Medio/Alto · Sforzo Basso.

- [ ] **Notifica inviata prima di persistere l'evento nel feed** ✅ *(verificato 2026-06-09)*
  - Dove: `notifyNewChapter` parte a [FavoriteUpdatesWorker.kt:82], l'evento entra nel feed dopo [FavoriteUpdatesWorker.kt:83-96] e la persistenza è solo nel `finally`.
  - Perché: se il processo muore tra notifica e `finally`, l'utente ha ricevuto la notifica ma il feed (e il `seenMap`) non la registrano → al giro dopo ri-notifica lo stesso capitolo e il feed resta vuoto rispetto alla notifica vista.
  - Cosa fare: persistere evento + `seenMap` **prima** di chiamare `notifyNewChapter` (o subito dopo ogni iterazione). Si combina bene col read-merge-write dell'item sopra. Impatto Basso · Sforzo Basso.

- [x] **Referer immagini sbagliato per fonti diverse da Mangapill** ✅ — *fatto (2026-05-26): le pagine `Remote` passano il proprio Referer via Coil `ImageRequest`; l'interceptor mette il default mangapill solo se assente (copertine invariate).*
  - Dove: header `Referer: https://mangapill.com/` fisso su **tutte** le immagini Coil [MangaApplication.kt:21]; le pagine streaming iniziali sono `ReaderPage.Remote` [MangaViewModel.kt:1179-1184] ma `ReaderScreen` usa solo `page.url` [ReaderScreen.kt:509, 745] e ignora il campo `Remote.referer` [ReaderModels.kt:46].
  - Perché: leggendo in streaming da MangaWorld/HastaTeam (e per le copertine di quelle fonti) le immagini partono col referer di Mangapill → possibili 403/immagini rotte finché la cache locale non subentra.
  - Cosa fare: togliere il referer fisso dall'interceptor (lasciare solo lo User-Agent) e impostare il referer per-immagine via `ImageRequest.Builder().setHeader("Referer", page.referer)` per i `Remote`. Impatto Alto · Sforzo Basso/Medio.

- [x] **Bug latente: CBZ corrotto lascia cache parziale trattata come completa** ✅ — *fatto (2026-05-26): estrazione in try/catch che pulisce la cache su errore; test `ReaderPageExtractionTest`.*
  - Dove: `LibraryRepository.extractReaderPages` [LibraryRepository.kt:548-591].
  - Perché: se lo zip estrae alcune pagine e poi lancia (`ZipException`/IOException a riga 575-582), la `cacheDir` parziale **non** viene ripulita (il cleanup è solo nel ramo `extracted.isEmpty()`); alla riapertura `existing.isNotEmpty()` (551-557) restituisce le pagine parziali come fosse completo → capitolo mostrato monco in modo permanente.
  - Cosa fare: avvolgere l'estrazione in try/catch che cancella `cacheDir` su qualunque errore; aggiungere test con CBZ valido/vuoto/corrotto. Impatto Medio · Sforzo Basso.

- [ ] **Letture SharedPreferences sincrone nel costruttore del ViewModel (main thread)** ✅ — *RINVIATO (2026-05-26): lo stato iniziale (tab con parental control, fase tutorial, preferiti) è costruito sincronamente da queste letture; renderle async non è un vero quick win (rischio flash/regressioni su tutorial e tab iniziale) e il guadagno è marginale con prefs piccole. Da fare con cura a parte.*
  - Dove: `MangaViewModel` field init [MangaViewModel.kt:205-206, 233] → `readFavorites()` (JSON a mano), `readSettings()`, `readRecentSearches()` eseguiti alla creazione (in composizione).
  - Perché: I/O + parsing JSON sincroni all'avvio → jank potenziale man mano che i favoriti crescono.
  - Cosa fare: stato iniziale "vuoto/loading" e caricamento in `init { viewModelScope.launch(Dispatchers.IO) { … } }`. Impatto Medio · Sforzo Basso.

- [x] **Niente retry/backoff di rete** 🔎 — *fatto (2026-05-26): `MangaNetworkClient` ritenta solo gli errori di trasporto (3 tentativi, backoff), mai gli HTTP non-2xx; `DownloadWorker.enqueue` ora ha `setBackoffCriteria(EXPONENTIAL, 30s)`.*
  - Dove: `MangaNetworkClient` fa una sola `execute()` [MangaNetworkClient.kt:42-48]; `DownloadWorker.enqueue` non imposta `setBackoffCriteria` [DownloadWorker.kt:327]. (`SharedHttpClient` ha `retryOnConnectionFailure` ma copre solo i fallimenti di connessione, non le risposte non-2xx.)
  - Perché: un blip di rete fa fallire ricerca/dettaglio; il retry del worker usa il default implicito.
  - Cosa fare: retry leggero con backoff (2-3 tentativi su IOException/5xx, mai su 4xx) nel client; `setBackoffCriteria(EXPONENTIAL, …)` nel worker. Impatto Medio · Sforzo Basso.

- [x] **README disallineato** ✅ — *fatto (2026-05-26): app come progetto principale, 3 fonti elencate, sezione Python rietichettata come CLI.*
  - Dove: [README.md:3, 23] — "supporto: solo Mangapill" e il client Python presentato come progetto principale.
  - Perché: le fonti reali sono 3 (Mangapill, MangaWorld, HastaTeam) e l'app è il progetto attivo, non lo script Python.
  - Cosa fare: aggiornare elenco fonti, mettere l'app Android come protagonista, citare le preview da `dev`, ridimensionare/segnalare lo stato del client Python. Impatto Medio · Sforzo Basso.

- [x] **Aggiungere un file `LICENSE`** ✅ — *fatto (2026-05-26): **PolyForm Noncommercial License 1.0.0** (standard, testo ufficiale verbatim) con `Required Notice` di copyright. Codice aperto, uso libero solo non commerciale, nessuna responsabilità.*
  - Dove: root del repo (nessun `LICENSE*` presente).
  - Perché: senza licenza il codice è "all rights reserved" di default.
  - Cosa fare: aggiungere una licenza (es. MIT) o una nota d'uso esplicita. Impatto Medio · Sforzo Basso.

- [x] **Rimuovere codice morto** ✅ — *fatto (2026-05-26): rimosso `buildReleaseTag(versionName)` inutilizzato; `*.log` aggiunto a `.gitignore`. (Il campo `ReaderPage.Remote.referer` ora è usato dal fix referer, quindi non più morto.)*
  - `buildReleaseTag(versionName)` [AppUpdateManager.kt:199]: nessun chiamante (tutti usano l'overload a 2 argomenti, 38/260/358).
  - Campo `ReaderPage.Remote.referer` [ReaderModels.kt:46]: oggi mai letto dal display (collegato al fix referer sopra).
  - Aggiungere `*.log` a [.gitignore] (in passato fu committato `batch_stdout.log`). Impatto Basso · Sforzo Basso.

---

## 🟡 Affidabilità & test mancanti (sforzo medio)

- [x] **Rendere testabile il parsing di Mangapill (fonte di default)** ✅ — *fatto (2026-05-26)*
  - Dove: `MangapillSource` faceva fetch live nei metodi d'istanza, a differenza di MangaWorld/HastaTeam che espongono funzioni statiche `parseSearchResults/parseMangaDetails/parsePageImageUrls` già testate su HTML d'esempio.
  - **Fatto:** estratte nel `companion object` le funzioni pure `parseSearchResults(raw, baseUrl)`, `parseMangaDetails(raw, mangaUrl)`, `parsePageImageUrls(raw, chapterUrl)` (parsing via `Jsoup.parse(raw, base)` + `absUrl`, stesso pattern di MangaWorld); i metodi d'istanza ora sono wrapper sottili che fanno solo `fetchString` e delegano. Aggiunti 4 test in `MangaSourcesTest` su HTML d'esempio (ricerca con merge degli anchor duplicati, dettaglio con ordinamento capitoli + copertina, fallback del numero capitolo dall'URL, pagine del reader). Suite verde.

- [ ] **Test del `DownloadWorker` (cuore dell'app)** 🔎
  - Dove: [DownloadWorker.kt:41-186] — `doWork`, `enqueue`, retry/cancellazione, concorrenza con `Semaphore`/`Mutex`.
  - Cosa fare: test di `enqueue` (constraint/tag) con `WorkManagerTestInitHelper`, e/o estrarre l'orchestrazione in una classe testabile con `MangaSource` fake (come `StreamingReadStateTest.TestMangaSource`). Impatto Alto · Sforzo Medio/Alto.

- [ ] **Download lunghi (100+ capitoli) interrotti a schermo spento se manca il permesso notifiche** ✅ *(verificato sul codice 2026-06-04)*
  - Dove: `DownloadWorker.safeSetForeground` → `canShowForegroundNotification()` [DownloadWorker.kt:229-250]; fallback "continua in background" [DownloadWorker.kt:236-239]; stop di sistema → `DownloadStoppedException` → **`Result.success`** ("Fermato") [DownloadWorker.kt:167-175, 202-206]; permesso dichiarato [AndroidManifest.xml:6] e service `dataSync` [AndroidManifest.xml:19-22].
  - Perché: il worker è un long-running worker promosso a **foreground service** (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`). Il foreground service tiene CPU/rete vive a schermo spento ed è esente da Doze → con il permesso notifiche concesso, 100+ capitoli completano anche a schermo spento (lenti, batteria, ma OK ✅; retry su `IOException` riprende grazie allo `SKIPPED_EXISTING`). **MA** se l'utente ha negato `POST_NOTIFICATIONS` (Android 13+), `canShowForegroundNotification()` ritorna `false`, `safeSetForeground` esce subito e **non** chiama mai `setForeground`: il worker gira come job di background normale. A quel punto: (a) WorkManager impone ~10 min di esecuzione ai worker **non** foreground → su 100 capitoli viene fermato; (b) a schermo spento Doze sospende rete/esecuzione. Lo stop di sistema setta `isStopped` → `ensureActiveDownload()` lancia `DownloadStoppedException`, gestita come **`Result.success`** ("Fermato") → **niente auto-retry**: il download si ferma a metà in silenzio.
  - Cosa fare: (1) chiedere il permesso notifiche **prima** di avviare un download grosso (oggi serve per il foreground service, non solo per la notifica); (2) distinguere stop utente da stop di sistema (`getStopReason()`, WorkManager 2.9+) e in caso di stop di sistema restituire **`Result.retry()`** invece di `success`, così WorkManager riprende quando le condizioni lo permettono (idempotente via skip-existing); (3) opzionale: guidare l'utente all'esenzione dalla battery optimization sugli OEM aggressivi. Impatto Alto (per chi scarica serie lunghe) · Sforzo Medio.

- [x] **Gestire lo spazio disco insufficiente** ✅ — *fatto (2026-05-26)*
  - Dove: scrittura pagine [MangaSources.kt], estrazione [LibraryRepository.kt:575-577], cache streaming.
  - Perché: su disco pieno `IOException` → il worker andava in retry potenzialmente infinito; nessun messaggio dedicato.
  - **Fatto:** prima di scaricare un capitolo (`downloadChapterAsCbz`, dopo lo skip del file esistente) si controlla lo spazio libero con `StatFs` (`DownloadStorage.freeSpaceBytes`, **fail-open**: se la misura fallisce non blocca) contro una soglia di 50 MB (`MIN_FREE_SPACE_BYTES`); se insufficiente lancia `InsufficientStorageException` — eccezione **non**-`IOException`, così il `DownloadWorker` la mappa su `Result.failure` con messaggio ("Spazio insufficiente sul dispositivo…") invece di ciclare in retry. Policy isolata in `hasEnoughFreeSpace(available, required)` (pura) e misura dietro `availableSpaceBytes(dir)` (seam overridabile). Test: policy pura + `downloadChapterAsCbz` che lancia con disco simulato quasi pieno senza toccare la rete. Suite verde.
  - *Nota scope:* coperto il percorso di download (quello che causava i retry infiniti del worker). L'estrazione CBZ in lettura usa già il try/catch che pulisce la cache su errore (vedi item "CBZ corrotto"); aggiungere un check `StatFs` anche lì è un follow-up minore.

- [x] **Coprire i rami d'errore di `buildDownloadPlan`/`downloadChapterAsCbz`** ✅ — *fatto (2026-05-26)*
  - Dove: [MangaSources.kt:194-240, 279-329] — capitolo iniziale/finale non trovato, intervallo invertito, lista vuota (prima solo happy path).
  - **Fatto:** nuovo `DownloadPlanAndCbzTest` (Robolectric) con un `TestMangaSource` configurabile. Copre `buildDownloadPlan`: URL non riconosciuto → `IllegalArgumentException`, capitolo iniziale/finale assente e intervallo invertito → `IllegalStateException`, più gli happy path (intervallo inclusivo + `totalChapterCount`, default all'ultimo capitolo). Copre `downloadChapterAsCbz`: **zip prodotto** (un'entry per pagina, niente residui `.part`/`_pages`) iniettando byte finti via interceptor OkHttp (nessuna rete) e **skip del file già esistente**. 8 test verdi.

- [x] **Test del `CrashReporter`** ✅ — *fatto (2026-05-26)*
  - Dove: [CrashReporter.kt] — round-trip write→read→clear e caso "file assente → null".
  - **Fatto:** nuovo `CrashReporterTest` (Robolectric): file assente → `null`, `clearLastCrash` idempotente, `crashFilePath` punta a `diagnostics/last_crash.txt`, e round-trip completo invocando l'handler installato da `install` (con handler precedente non-null per evitare `exitProcess`) → il report contiene messaggio/eccezione/thread, poi `clear` riporta a `null` e la catena dell'handler precedente è rispettata. 4 test verdi.

- [ ] **Alimentare il `CrashReporter`/log sugli errori di parsing** 🔎
  - Dove: le source lanciano `IllegalStateException("Nessun capitolo…")` senza dire *quale* selettore è fallito [MangapillSource.kt:127].
  - Cosa fare: loggare URL + selettore fallito su lista vuota; valutare un parsing-fallback (JSON-LD/`<title>`). Impatto Medio · Sforzo Medio.

---

## 🔵 Architettura & manutenibilità (interventi grossi, valore nel tempo)

- [x] **Spezzare la god class `MangaViewModel`** ✅ — *fatto (parziale, 2026-05-26)*
  - Dove: [MangaViewModel.kt] — mescolava ricerca, dettaglio, libreria, reader, auto-download, smart cleanup, parental control, tutorial, update, persistenza.
  - **Fatto:** estratta la **persistenza** in tre collaboratori cohesi ([SettingsStore.kt], [FavoritesStore.kt], [RecentSearchesStore.kt]): il ViewModel ora delega `read/persist` e tiene solo l'orchestrazione di stato. `MangaViewModel` da ~2040 a ~1867 righe; le chiavi prefs delle impostazioni/preferiti/recenti vivono nei rispettivi store. Test: `RecentSearchesStoreTest` (regola pura dedup/cap); read/persist coperti dai test VM esistenti (recenti, preferiti, parental). Insieme a [ReaderProgress.kt] e [LibraryMatching.kt] (estratti negli item precedenti) il VM ha già diversi collaboratori puri fuori.
  - *Scelta concordata:* estratti **solo gli store** (basso rischio, testabili). I controller di stato (`ParentalControlController`/`TutorialController`/`ReaderController`) **non** sono stati estratti: manipolano `MangaUiState` condiviso e la UI/macchina a stati non ha copertura di test → rischio di regressione non giustificato ora. Restano come follow-up se si aggiunge prima copertura di test sul ViewModel.

- [x] **Back-stack di navigazione esplicito invece dei booleani sparsi** ✅ — *fatto (2026-05-26)*
  - Dove: `showSettings`/`showStorageManager`/`selected`/`selectedDownloadedSeries`/`readerChapter` + `when`/`BackHandler` [MainActivity.kt].
  - **Fatto:** nuovo `sealed interface Screen` (Tabs/Detail/DownloadedSeries/Reader/Settings/StorageManager) + funzione pura `MangaUiState.currentScreen()` che codifica la gerarchia **in un punto solo**; `MainActivity` rende con un `when (currentScreen())` **esaustivo** (il compilatore obbliga a gestire ogni schermata) e `showPager`/`canHandleBack` derivano da lì; il back è centralizzato in `MangaViewModel.handleBack()` (testabile). Test: `ScreenTest` (6, puro) + `MangaViewModelBackNavigationTest` (2, Robolectric — pop StorageManager→Settings→Tabs).
  - *Deviazione motivata:* **non** uno `List<Screen>` mutabile con push/pop, ma una schermata **derivata** dai campi di stato esistenti (unica fonte di verità → niente stack parallelo da tenere in sync). La UI non è coperta da test, quindi migrare i dati delle schermate dentro entry di stack mutabili era rischio di regressione non giustificato; questa forma ottiene gli stessi obiettivi (esplicito, esaustivo, back centralizzato, niente booleani sparsi nel `when`) a rischio molto minore.

- [x] **Spostare la logica di dominio fuori da `MainActivity`** ✅ — *fatto (2026-05-26)*
  - Dove: `downloadedChapterKeysFor`, `downloadedReadChapterIdsFor`, `matchingDownloadedSeries`, `tutorialSampleSeries` [MainActivity.kt] — matching identità serie/capitoli (dominio, non UI).
  - **Fatto:** estratte in un nuovo `object LibraryMatching` (puro, accanto a `MangaSourceCatalog`); `MainActivity` ora le chiama e `tutorialSampleSeries` prende `(sample, library)` invece dello stato UI. 7 unit test JVM (`LibraryMatchingTest`, niente Robolectric) coprono match per identità, fallback sul titolo, no-match e le chiavi capitolo/letti.

- [x] **Ridurre il `MangaUiState` monolitico / ricomposizioni** ✅ — *fatto (parziale, 2026-05-26)*
  - Dove: `saveReaderPagePosition`→`withReaderProgress` rimappava liste grandi ad ogni pagina [MangaViewModel.kt]; `SearchScreen`/`LibraryScreen` ricevono l'intero `state`.
  - **Fatto:** estratte funzioni pure `DownloadedChapter/DownloadedSeries.withReaderProgressApplied` ([ReaderProgress.kt]) che restituiscono **la stessa istanza** per le serie/capitoli non toccati dal capitolo corrente → niente ricostruzione dell'intera libreria a ogni pagina sfogliata (meno allocazioni/GC in lettura). `withReaderProgress` ora le usa. 5 unit test puri (`ReaderProgressFunctionsTest`) verificano identità e correttezza.
  - *Scope ridotto di proposito:* il passaggio dei soli sotto-campi alle schermate / lo split in `searchState`/`libraryState`/`readerState` **non** è stato fatto: `MangaUiState` ha campi `List` che in Compose sono **instabili**, quindi restringere le firme non abiliterebbe lo skip delle ricomposizioni senza una nuova dipendenza (kotlinx-immutable-collections) che il progetto evita; inoltre i Composable non hanno copertura di test. Rapporto valore/rischio basso → rimandato.

- [x] **Centralizzare `LibraryRepository`/`MangaSourceRegistry` come singleton** ✅ — *fatto (2026-05-26)*
  - Dove: `BaseMangaSource.prepareSeriesStorage` creava un `LibraryRepository` al volo mentre il ViewModel ne aveva già uno; il `DownloadWorker` creava il proprio registry.
  - **Fatto:** `MangaApplication` espone `libraryRepository` e `sourceRegistry` come singleton `lazy`; helper `sharedLibraryRepository(context)`/`sharedSourceRegistry(context)` con fallback per Context non-MangaApplication (test). ViewModel e DownloadWorker ora usano le istanze condivise; `MangaSourceRegistry` e `BaseMangaSource` ricevono il `LibraryRepository` iniettato (default-arg per non rompere i test), così `prepareSeriesStorage` non istanzia più un repo al volo. La TTL-cache è ora condivisa.

- [x] **Serializzazione tipizzata favoriti/recenti** ✅ — *fatto (2026-05-26)*; **debounce salvataggio pagina: scartato di proposito** ⚠️
  - Dove: `reader_page_index::<path>` salvato ad ogni pagina visibile [LibraryRepository.kt]; favoriti/recenti come JSON costruito a mano [MangaViewModel.kt].
  - **Fatto (serializzazione):** aggiunto il plugin `org.jetbrains.kotlin.plugin.serialization` (2.0.21, allineato a Kotlin); favoriti via `@Serializable data class FavoriteEntryJson` + `Json.encode/decodeFromString`, recenti come `List<String>` tipizzata, al posto dei `buildJsonObject`/`jsonArray` a mano. Formato su disco invariato (retrocompatibile). Test `FavoritesPersistenceTest` (round-trip + lettura del vecchio JSON senza cover).
  - **Debounce scartato:** un test esistente (`ReaderProgressTest.streamingReader_positionSurvivesNewViewModel`) garantisce che la posizione sia **durevole subito** dopo il salvataggio (simula riavvio app con un nuovo ViewModel). Il debounce rompe questa garanzia e introduce perdita di dato su process-death; inoltre `prefs.apply()` è già asincrono (nessun jank sul main thread) e il vero costo per-pagina è il remap di stato in `withReaderProgress` — affrontato nell'item ricomposizioni. Mantenuta quindi la scrittura immediata.

- [ ] **Uniformare gli aggiornamenti di stato** 🔎
  - Dove: ~30 `_state.value = _state.value.copy(...)` diretti vs l'esistente `updateState { copy(...) }`.
  - Cosa fare: uniformare su `updateState` (o `_state.update { }` di kotlinx per atomicità). Refactor meccanico. Impatto Basso · Sforzo Basso.

---

## 🐍 Client Python & repo

- [ ] **Decidere il destino di `manga_downloader.py`** 🔎
  - Dove: [manga_downloader.py] — fermo dal 2026-04-21, feature inferiori all'app, parsing Mangapill duplicato e divergente da `MangapillSource.kt`.
  - Cosa fare: (a) tenerlo come tool CLI "best-effort" (ha i batch PDF/JPG che l'app non ha) con nota di stato; (b) deprecarlo; (c) rimuoverlo. In ogni caso aggiungere un `pyproject.toml` con i pin di `requirements.txt`. Impatto Alto (chiarezza) · Sforzo Basso/Medio.

- [ ] **Rate limiting + retry nel client Python** 🔎
  - Dove: loop pagine senza pausa [manga_downloader.py:251-253], `ThreadPoolExecutor` con `--jobs` arbitrario [manga_downloader.py:388-392], un solo `session.get` senza retry [manga_downloader.py:87-90, 119-126].
  - Cosa fare: piccolo delay tra pagine/capitoli + cap su `--jobs`; `HTTPAdapter` con `urllib3 Retry` (429/5xx + backoff). Scraper più "educato" e robusto. Impatto Medio · Sforzo Basso.

- [ ] **Rendere robusto il default-path di `bump_version.py`** 🔎
  - Dove: `Path(__file__).resolve().parents[4]` [.claude/skills/release-android/scripts/bump_version.py:32-35] dipende dalla profondità della cartella.
  - Cosa fare: risalire fino a trovare `android-app/version.properties` o usare `git rev-parse --show-toplevel`. Impatto Basso · Sforzo Basso.

---

## Note trasversali (positive)

- Nessun TODO/FIXME pendente nel codice Kotlin; gestione `CancellationException` corretta ovunque.
- CI di firma/keystore e release/preview ben curata; i due buchi reali sono *no-test-in-CI* e *verifica firma sull'auto-update* (vedi sezione 🔴).
- Dove la logica è separata dall'I/O (parser statici delle source, `pageDownloader`/`nowMillis` nella cache streaming, lambda nello scanner) i test sono puliti: la strada è tracciata, va estesa (Mangapill, CBZ, DownloadWorker).
