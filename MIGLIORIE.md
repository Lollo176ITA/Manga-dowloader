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

- [ ] **I preferiti AniList che nessuna fonte espone sono invisibili** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [AniListFavoritesSynchronizer.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/anilist/AniListFavoritesSynchronizer.kt) — il match mancato finisce in `failedImports` e il titolo sparisce senza una parola.
  - Perché: metti la stella su AniList, apri l'app e non è successo niente, senza sapere perché. Da 2026-08-28 l'elenco almeno non è più definitivo (si azzera quando cambiano le fonti interrogate, comprese quelle tornate su), ma resta muto.
  - Cosa fare: un gruppo collassabile in fondo ai Preferiti ("Senza scan"), alimentato da `failedImports` + i metadati AniList, **non** da `FavoriteManga` — l'invariante "un preferito ha una fonte" va tenuta. Card cliccabile: il tap rifà la ricerca aggregata (`onPickAniListManga`), che è insieme la spiegazione e il rimedio.

---

## 📥 Download

- [ ] **Stop davvero per-serie (oggi è "ferma tutto" con conferma)** ✅ — Impatto Alto · Sforzo Medio
  - Bug collegato (verificato 2026-09-23): per la stessa catena unica, se il download di una serie finisce in `Result.failure` (qualsiasi eccezione non-`IOException`, es. "Nessun capitolo trovato"), WorkManager segna FAILED anche tutte le serie accodate dopo, che non partono mai. E un `Result.retry()` su `IOException` blocca l'intera coda per tutto il backoff. Con una catena per serie i due problemi spariscono insieme allo stop globale.
  - Perché: lo stop dei download è protetto da conferma, ma resta globale: ferma l'intera coda WorkManager, non la singola serie. Tutti i download condividono un'unica catena `enqueueUniqueWork(UNIQUE_WORK_NAME, APPEND_OR_REPLACE)`, quindi cancellare per tag troncherebbe anche i work concatenati delle altre serie.
  - Cosa fare: dare a ogni serie una propria unique work (`manga-download-<identityKey>`) con un tag globale condiviso, così lo stop per-card cancella solo quella serie (`cancelUniqueWork`) e il FAB ferma tutto (`cancelAllWorkByTag`); l'osservatore in MainActivity passa da `getWorkInfosForUniqueWorkLiveData` a `getWorkInfosByTagLiveData`. Valutare l'effetto sulla concorrenza (serie ora parallele). Insieme: test della gestione della coda (`enqueue`, stop, fallimento di una serie) con `WorkManagerTestInitHelper`; piano e CBZ sono già coperti da `DownloadPlanAndCbzTest`.

---

## 🟡 Affidabilità & test mancanti

- [ ] **Alimentare il `CrashReporter`/log sugli errori di parsing** 🔎
  - Dove: le source lanciano `IllegalStateException("Nessun capitolo…")` senza dire *quale* selettore è fallito ([MangapillSource.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/data/sources/MangapillSource.kt)).
  - Perché: un log sul telefono dell'utente non lo vede nessuno; serve che l'informazione arrivi con la segnalazione.
  - Cosa fare: tenere gli ultimi errori delle fonti (fonte, URL, cosa non è stato trovato nella pagina) in un piccolo buffer e allegarli a "Segnala un problema". Impatto Medio · Sforzo Medio.
