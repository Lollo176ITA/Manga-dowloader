# Migliorie — Manga Downloader

> Lista di todo generata da un'analisi del progetto (architettura, test/robustezza, client Python, CI) e **revisionata file per file** il 2026-05-26.
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

- [ ] **Gestire lo spazio disco insufficiente** 🔎
  - Dove: scrittura pagine [MangaSources.kt:381-388], estrazione [LibraryRepository.kt:575-577], cache streaming.
  - Perché: su disco pieno `IOException` → il worker va in retry potenzialmente infinito; nessun messaggio dedicato.
  - Cosa fare: check spazio (`StatFs`) prima del download + messaggio utente; test del fallimento di scrittura. Impatto Medio · Sforzo Medio.

- [ ] **Coprire i rami d'errore di `buildDownloadPlan`/`downloadChapterAsCbz`** 🔎
  - Dove: [MangaSources.kt:194-240, 279-329] — capitolo iniziale/finale non trovato, intervallo invertito, lista vuota (oggi solo happy path).
  - Cosa fare: test con `TestMangaSource` su URL fuori range / lista vuota; verifica zip prodotto e skip file esistente. Impatto Medio · Sforzo Medio.

- [ ] **Test del `CrashReporter`** 🔎
  - Dove: [CrashReporter.kt] — round-trip write→read→clear e caso "file assente → null".
  - Perché: è la diagnostica dei crash; un bug qui si paga proprio quando serve. Impatto Medio · Sforzo Basso.

- [ ] **Alimentare il `CrashReporter`/log sugli errori di parsing** 🔎
  - Dove: le source lanciano `IllegalStateException("Nessun capitolo…")` senza dire *quale* selettore è fallito [MangapillSource.kt:127].
  - Cosa fare: loggare URL + selettore fallito su lista vuota; valutare un parsing-fallback (JSON-LD/`<title>`). Impatto Medio · Sforzo Medio.

---

## 🔵 Architettura & manutenibilità (interventi grossi, valore nel tempo)

- [ ] **Spezzare la god class `MangaViewModel` (~2039 righe, ~98 funzioni)** 🔎
  - Dove: [MangaViewModel.kt] — mescola ricerca, dettaglio, libreria, reader, auto-download, smart cleanup, parental control, tutorial, update, persistenza.
  - Cosa fare: estrarre collaboratori plain-class iniettati nel costruttore (come già con `appUpdateRepository`): `ParentalControlController`, `TutorialController`, `ReaderController`/use-case, store per favoriti/settings/recenti. Niente librerie nuove. Impatto Alto · Sforzo Alto.

- [ ] **Back-stack di navigazione esplicito invece dei booleani sparsi** 🔎
  - Dove: `showSettings`/`showStorageManager`/`selected`/`selectedDownloadedSeries`/`readerChapter` + `when`/`BackHandler` [MainActivity.kt:251-260, 676-693].
  - Perché: lo stack è implicito nell'ordine del `when`; combinazioni illegali sono rappresentabili e ogni nuova schermata tocca 4 punti.
  - Cosa fare: `sealed interface Screen` + `List<Screen>` nel ViewModel con `push/pop`; `handleBack = pop`; rendering esaustivo sul `last()`. Niente Navigation Compose. Impatto Medio · Sforzo Medio.

- [ ] **Spostare la logica di dominio fuori da `MainActivity`** 🔎
  - Dove: `downloadedChapterKeysFor`, `downloadedReadChapterIdsFor`, `matchingDownloadedSeries`, `tutorialSampleSeries` [MainActivity.kt:596-693] — matching identità serie/capitoli (dominio, non UI).
  - Cosa fare: spostare nel ViewModel o in un `LibraryMatching` accanto a `MangaSourceCatalog`, esponendo i dati già pronti nello stato → diventa testabile. Impatto Medio · Sforzo Basso/Medio.

- [ ] **Ridurre il `MangaUiState` monolitico / ricomposizioni** 🔎
  - Dove: 40+ campi in un solo `MutableStateFlow` [MangaViewModel.kt:135-174]; `saveReaderPagePosition` rimappa liste grandi ad ogni pagina [MangaViewModel.kt:1764-1768]; `SearchScreen`/`LibraryScreen` ricevono l'intero `state`.
  - Cosa fare: passare alle schermate solo i sotto-campi necessari; opzionale split in `searchState`/`libraryState`/`readerState` derivati con `map`+`distinctUntilChanged`. Impatto Medio · Sforzo Medio.

- [ ] **Centralizzare `LibraryRepository`/`MangaSourceRegistry` come singleton** 🔎
  - Dove: `BaseMangaSource.prepareSeriesStorage` crea un `LibraryRepository` al volo [MangaSources.kt:266] mentre il ViewModel ne ha già uno [MangaViewModel.kt:202]; il `DownloadWorker` crea il proprio registry.
  - Perché: la TTL-cache del repository non è condivisa tra worker e ViewModel (oggi mitigato da `forceRefresh` su eventi worker, accoppiamento fragile).
  - Cosa fare: istanze applicative uniche in `MangaApplication`, iniettate. Impatto Basso/Medio · Sforzo Basso.

- [ ] **Debounce del salvataggio posizione pagina + serializzazione tipizzata** 🔎
  - Dove: `reader_page_index::<path>` salvato ad ogni pagina visibile [LibraryRepository.kt:533-546]; favoriti/recenti come JSON costruito a mano [MangaViewModel.kt:1854-1911] (duplicato con `SeriesMetadataJson`).
  - Cosa fare: throttle/debounce del salvataggio; un unico `@Serializable data class` + `Json.encodeToString` al posto dei `buildJsonObject` a mano. (DataStore solo se si vuole investire, opzionale.) Impatto Basso/Medio · Sforzo Medio.

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
