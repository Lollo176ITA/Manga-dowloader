# Migliorie — Manga Downloader

> Voci ancora aperte. Le migliorie completate non vivono più qui: sono nel [CHANGELOG.md](CHANGELOG.md) per la parte che l'utente vede, e in `git log` per il resto — tenerne una seconda copia commentata significava solo mantenerla.
> Legenda stato verifica: ✅ = controllato sul codice reale · 🔎 = valutazione/da confermare prima di intervenire.
> Tag: **Impatto** Alto/Medio/Basso · **Sforzo** Basso/Medio/Alto.

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

- [ ] **La radice si ricompone a ogni swipe verticale per colpa del pager** ✅ — Impatto Basso · Sforzo Basso
  - Dove: [MainActivity.kt:425-429](android-app/app/src/main/java/com/lorenzo/mangadownloader/MainActivity.kt#L425) — `visiblePagerTab` legge `pagerState.isScrollInProgress`/`targetPage`/`currentPage` direttamente nello scope di `MangaDownloaderAppContent`.
  - Misurato (2026-09-25, release su Pixel_8 emulato): 2 ricomposizioni della radice per ogni swipe verticale su Home o Cerca, senza nessuna emissione di stato. Costano poco (~1,2 ms l'una), ma sono lavoro gratuito proprio durante lo scroll.
  - Cosa fare: `val visiblePagerTab by remember { derivedStateOf { … } }`, così la radice si ricompone solo quando la tab cambia davvero.

- [ ] **La Home resta composta (e si ricompone) mentre si usa un'altra tab** ✅ — Impatto Medio · Sforzo Basso
  - Misurato (2026-09-25): scrivendo nella ricerca, ogni tasto (`query`) ricompone radice, `AppTopBar`, `AppBottomBar`, `TutorialOverlay`, `SearchScreen` **e `HomeScreen`**, che è fuori schermo ma resta composta dal `HorizontalPager`. Costo per tasto: 3–4,5 ms di composizione sul PC host (su un telefono medio va moltiplicato); 40% di frame in jank durante la digitazione, tastiera compresa.
  - Cosa fare: capire perché il pager tiene la pagina Home (cache delle pagine vicine della foundation) e passare `HomeScreen` solo i campi che usa, così salta la ricomposizione quando cambia `query`. È il primo pezzo concreto della voce "`MangaUiState` monolitico".

- [ ] **Nessun Baseline Profile** 🔎 — Impatto Medio · Sforzo Medio
  - Dove: `app/build.gradle.kts` ha R8 attivo ma niente `profileinstaller` né modulo `baselineprofile`. Le app Compose senza profilo eseguono in JIT il codice di avvio e del primo scroll.
  - Cosa fare: modulo `:baselineprofile` con Macrobenchmark (avvio → Home → Libreria → reader) generato sull'emulatore. Il guadagno va misurato prima e dopo.

---

## 🧹 Snellimento & architettura

- [ ] **Estrarre altri controller dal ViewModel (4300 righe)** ✅ — Impatto Medio · Sforzo Alto
  - Stesso schema già usato per `ParentalControlController` e `TutorialController`: classe che condivide il `MutableStateFlow`, esposta come `viewModel.xxx`.
  - Candidati, dal più isolato al più intrecciato: **Home/Scopri** (`loadDiscovery`, `loadRecommendations`, `refreshHomeFeeds`, generi, ~300 righe) → **AniList** (auth, match, tracker, sync, ~550 righe) → **Preferiti e scaffali** → **Reader** (apertura, streaming, prefetch, avanzamento, adiacenze: ~1100 righe, il più accoppiato con libreria e memoria di lettura).

- [ ] **`MangaUiState` monolitico e schermate che lo ricevono intero** ✅ — Impatto Medio · Sforzo Alto
  - Dove: `MangaUiState` ha ~90 campi; `AppTopBar`, `HomeScreen`, `LibraryScreen`, `SearchScreen`, `StatsScreen` e `TutorialOverlay` prendono `state: MangaUiState`, quindi rigirano a ogni emissione qualsiasi.
  - Cosa fare: sotto-stati per area (`ReaderUiState`, `LibraryUiState`, `FavoritesUiState`, `ParentalUiState`…, sul modello di `DiscoveryUiState`/`AniListUiState` che esistono già) e ogni schermata riceve solo il suo. Va di pari passo con l'estrazione dei controller.
  - Misurato (2026-09-25): strong skipping attivo e 547/741 composable saltabili, ma `MangaUiState` è una nuova istanza a ogni emissione, quindi chi lo riceve intero si ricompone sempre. Il reader, il percorso più caldo, è **già isolato** (0 ricomposizioni della radice in 15 swipe, frame p99 19 ms). Il guadagno reale è sulla digitazione in ricerca (vedi la voce "La Home resta composta" in Prestazioni). Conviene farlo **a pezzi**, schermata per schermata, non come refactor unico.

- [ ] **`MangaDownloaderAppContent` è un unico composable di ~900 righe** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [MainActivity.kt:200-1106](android-app/app/src/main/java/com/lorenzo/mangadownloader/MainActivity.kt#L200): launcher dei permessi, backup, snackbar, top/bottom bar, pager, `when` delle schermate e dialog, tutto nello stesso scope di ricomposizione.
  - Cosa fare: separare `AppScaffold`, `ScreenHost` (il `when`) e `AppDialogsHost`, ognuno con i soli parametri che usa. Rende efficaci i due punti sopra.

- [ ] **File troppo grossi da navigare** 🔎 — Impatto Basso · Sforzo Medio
  - `UiComponents.kt` (1045 righe, componenti eterogenei) e `ReaderScreen.kt` (1280 righe, con `VerticalReader` da solo ~410). Solo organizzazione: dividere per componente, nessun guadagno di prestazioni.

---

## 📦 Librerie

- [ ] **Zoom e pagine lunghissime del reader scritti a mano** 🔎 — Impatto Medio · Sforzo Alto
  - Dove: [TallPageNormalizer.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/ui/reader/TallPageNormalizer.kt) (322 righe: spezza le pagine alte in fasce su disco), [ReaderTallPage.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/ui/reader/ReaderTallPage.kt) (281, budget di memoria e sample size), `ZoomablePage` in `ReaderScreen.kt` (~130, pinch/pan/doppio tap).
  - Candidata: [Telephoto](https://github.com/saket/telephoto) (`zoomable-image-coil3`): zoom + *subsampling* (decodifica a tessere, come le mappe), che è proprio il problema delle pagine webtoon. Potrebbe sostituire gran parte di quelle ~700 righe.
  - Rischi: il reader verticale zooma l'intera lista, non la singola immagine, e i gesti sono stati ritoccati da poco (tap e Precedente/Successivo da ingranditi). Serve prima una prova su un ramo, non una sostituzione alla cieca.
  - Testata: risultato insoddisfacente

- [ ] **Baseline Profile (`profileinstaller` + plugin `baselineprofile`)** — vedi la voce in ⚡ Prestazioni.

- Valutate e **scartate**, a oggi:
  - **DataStore** per le prefs che crescono: la variante Preferences riscrive comunque l'intero file a ogni modifica, quindi non risolve il problema; separare i file basta.
  - **Room**: sarebbe lo strumento giusto *se* si spostano posizioni/letto per capitolo e memoria di lettura in un DB (query per serie, potatura, statistiche). Costa plugin KSP e migrazione dei dati; ha senso solo insieme a quella voce, non da solo.
  - **Hilt/Koin**: l'app crea a mano ~16 store e 2 repository condivisi; un framework DI aggiungerebbe codice generato e build più lente senza togliere complessità vera.
  - **Navigation Compose**: vedi la voce rinviata sulla navigazione.
  - **Jakarta Mail** è la dipendenza più pesante, ma è una scelta già accettata per le segnalazioni senza backend.
  - **`material-icons-extended`** (81 icone usate su migliaia): R8 elimina il resto in release, quindi l'APK non ne soffre; pesa solo sulle build debug. Passare ai drawable Material Symbols è pulizia, non prestazioni.

---

## 🔒 Controllo genitori

- [ ] **Ricerca "tutto o niente" sotto controllo genitori** 🔎 — Impatto Medio · Sforzo Basso
  - Oggi con il parentale attivo la tab Cerca chiede il PIN, e dopo il PIN i risultati sono comunque filtrati. Un ragazzo quindi non può cercare niente da solo.
  - Da decidere: un'opzione "Consenti la ricerca filtrata" che lascia cercare senza PIN, con il filtro per adulti sempre acceso. Scelta di prodotto, non tecnica.

- [ ] **Hash del PIN troppo veloce** 🔎 — Impatto Basso · Sforzo Basso
  - Dove: [ParentalControlSecurity.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/domain/ParentalControlSecurity.kt) — SHA-256 con sale, un solo passaggio: chi legge le preferenze dell'app (root, backup di sistema) prova tutti i PIN a 6 cifre in un attimo.
  - Cosa fare: PBKDF2 con molte iterazioni, migrando l'hash al primo sblocco riuscito.
