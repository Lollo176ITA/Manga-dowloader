# Migliorie — Manga Downloader

> Voci ancora aperte. Le migliorie completate non vivono più qui: sono nel [CHANGELOG.md](CHANGELOG.md) per la parte che l'utente vede, e in `git log` per il resto — tenerne una seconda copia commentata significava solo mantenerla.
> Legenda stato verifica: ✅ = controllato sul codice reale · 🔎 = valutazione/da confermare prima di intervenire.
> Tag: **Impatto** Alto/Medio/Basso · **Sforzo** Basso/Medio/Alto.

---

## 🟢 Bug & quick win

- [ ] **Letture SharedPreferences sincrone nel costruttore del ViewModel (main thread)** ✅ — *RINVIATO (2026-05-26): lo stato iniziale (tab con parental control, fase tutorial, preferiti) è costruito sincronamente da queste letture; renderle async non è un vero quick win (rischio flash/regressioni su tutorial e tab iniziale) e il guadagno è marginale con prefs piccole. Da fare con cura a parte.*
  - Dove: `MangaViewModel` field init → `readFavorites()` (JSON a mano), `readSettings()`, `readRecentSearches()` eseguiti alla creazione (in composizione).
  - Perché: I/O + parsing JSON sincroni all'avvio → jank potenziale man mano che i favoriti crescono.
  - Cosa fare: stato iniziale "vuoto/loading" e caricamento in `init { viewModelScope.launch(Dispatchers.IO) { … } }`. Impatto Medio · Sforzo Basso.

- [ ] **I preferiti AniList che nessuna fonte espone sono invisibili** ✅ — Impatto Medio · Sforzo Medio
  - Dove: [AniListFavoritesSynchronizer.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/AniListFavoritesSynchronizer.kt) — il match mancato finisce in `failedImports` e il titolo sparisce senza una parola.
  - Perché: metti la stella su AniList, apri l'app e non è successo niente, senza sapere perché. Da 2026-08-28 l'elenco almeno non è più definitivo (si azzera quando cambiano le fonti interrogate, comprese quelle tornate su), ma resta muto.
  - Cosa fare: un gruppo collassabile in fondo ai Preferiti ("Senza scan"), alimentato da `failedImports` + i metadati AniList, **non** da `FavoriteManga` — l'invariante "un preferito ha una fonte" va tenuta. Card cliccabile: il tap rifà la ricerca aggregata (`onPickAniListManga`), che è insieme la spiegazione e il rimedio.

- [ ] **Il worker dei preferiti legge la salute delle fonti ma non la scrive** ✅ — Impatto Basso · Sforzo Basso
  - Dove: [FavoriteUpdatesWorker.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/FavoriteUpdatesWorker.kt) — usa `SourceHealthStore` per saltare i siti giù, ma solo la ricerca in app aggiorna il contatore.
  - Perché: non è un guasto (il cooldown scade da solo, quindi il worker non perpetua mai una decisione vecchia), ma il worker tocca ogni preferito ed è il segnale più ricco che abbiamo sulle fonti.
  - Cosa fare: registrare l'esito per-fonte nel giro di controllo, filtrando con `isSourceOutage` come fa il ViewModel.

---

## 📥 Download

- [ ] **Stop davvero per-serie (oggi è "ferma tutto" con conferma)** 🔎 — Impatto Medio · Sforzo Medio
  - Perché: lo stop dei download è protetto da conferma, ma resta globale: ferma l'intera coda WorkManager, non la singola serie. Tutti i download condividono un'unica catena `enqueueUniqueWork(UNIQUE_WORK_NAME, APPEND_OR_REPLACE)`, quindi cancellare per tag troncherebbe anche i work concatenati delle altre serie.
  - Cosa fare: dare a ogni serie una propria unique work (`manga-download-<identityKey>`) con un tag globale condiviso, così lo stop per-card cancella solo quella serie (`cancelUniqueWork`) e il FAB ferma tutto (`cancelAllWorkByTag`); l'osservatore in MainActivity passa da `getWorkInfosForUniqueWorkLiveData` a `getWorkInfosByTagLiveData`. Valutare l'effetto sulla concorrenza (serie ora parallele).

---

## 🟡 Affidabilità & test mancanti

- [ ] **Test del `DownloadWorker` (cuore dell'app)** 🔎
  - Dove: [DownloadWorker.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/DownloadWorker.kt) — `doWork`, `enqueue`, retry/cancellazione, concorrenza con `Semaphore`/`Mutex`.
  - Cosa fare: test di `enqueue` (constraint/tag) con `WorkManagerTestInitHelper`, e/o estrarre l'orchestrazione in una classe testabile con `MangaSource` fake (come `StreamingReadStateTest.TestMangaSource`). Impatto Alto · Sforzo Medio/Alto.

- [ ] **Alimentare il `CrashReporter`/log sugli errori di parsing** 🔎
  - Dove: le source lanciano `IllegalStateException("Nessun capitolo…")` senza dire *quale* selettore è fallito ([MangapillSource.kt](android-app/app/src/main/java/com/lorenzo/mangadownloader/MangapillSource.kt)).
  - Cosa fare: loggare URL + selettore fallito su lista vuota; valutare un parsing-fallback (JSON-LD/`<title>`). Impatto Medio · Sforzo Medio.

---

## 🔧 Repo & tooling

- [ ] **Rendere robusto il default-path di `bump_version.py`** 🔎
  - Dove: `Path(__file__).resolve().parents[4]` in `.claude/skills/release-android/scripts/bump_version.py` dipende dalla profondità della cartella.
  - Cosa fare: risalire fino a trovare `android-app/version.properties` o usare `git rev-parse --show-toplevel`. Impatto Basso · Sforzo Basso.
