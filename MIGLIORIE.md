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

## 🔒 Controllo genitori

- [ ] **Ricerca "tutto o niente" sotto controllo genitori** 🔎 — Impatto Medio · Sforzo Basso
  - Oggi con il parentale attivo la tab Cerca chiede il PIN, e dopo il PIN i risultati sono comunque filtrati. Un ragazzo quindi non può cercare niente da solo.
  - Da decidere: un'opzione "Consenti la ricerca filtrata" che lascia cercare senza PIN, con il filtro per adulti sempre acceso. Scelta di prodotto, non tecnica.

- [ ] **Il filtro per adulti non vede i titoli che AniList non conosce** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [ContentFilter.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/domain/ContentFilter.kt) — il segnale viene dai candidati AniList della ricerca e da poche parole esplicite nel titolo.
  - Cosa fare: leggere i generi dalla pagina del manga sulle fonti che li espongono (MangaWorld, Asura…) e bloccare il dettaglio se tra i generi c'è Hentai/Smut/Adult/Ecchi.

- [ ] **Hash del PIN troppo veloce** 🔎 — Impatto Basso · Sforzo Basso
  - Dove: [ParentalControlSecurity.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/domain/ParentalControlSecurity.kt) — SHA-256 con sale, un solo passaggio: chi legge le preferenze dell'app (root, backup di sistema) prova tutti i PIN a 6 cifre in un attimo.
  - Cosa fare: PBKDF2 con molte iterazioni, migrando l'hash al primo sblocco riuscito.
