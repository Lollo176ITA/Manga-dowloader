# Riuso & semplificazione componenti — Manga Downloader

> Audit mirato sul **riuso dei componenti** (obiettivo: semplificare la struttura), generato con analisi multi-agente
> su 6 aree in parallelo e **verificato file per file sul codice reale** il 2026-05-31.
> 36 candidati esaminati → 28 confermati (verdetto *conviene*/*forse*), 8 scartati.
> Legenda: **Impatto/Sforzo** Alto/Medio/Basso · le righe risparmiate sono stime nette.
> Nota trasversale: la UI Compose e il `MangaViewModel` hanno **poca copertura di test** (come già documentato in
> [MIGLIORIE.md]); per questo i refactor puramente UI valgono meno di quelli su funzioni pure / persistenza / rete già testate.

---

## Verdetto in breve

**Sì, conviene — ma in modo selettivo.** Quasi tutti gli interventi sono *piccoli* (Basso/Basso): non c'è una grande
riscrittura da fare, ma una decina di duplicazioni e pezzi di codice morto che si tolgono a rischio quasi nullo, per un
totale di **~135-170 righe** in meno e qualche *single-source-of-truth* in più.

- **Da fare ora** (✅): 8 interventi ad alto rapporto valore/rischio, concentrati su **fonti/scraper** (helper duplicati + codice morto), **dialog/componenti UI condivisi** e **rete**.
- **Marginali** (🟡): 14 interventi che da soli rendono poco — farli solo *quando si tocca già quell'area*, o accorparli.
- **Da NON fare** (🔴): 8 casi in cui unificare aumenterebbe i parametri/accoppiamento più di quanto risparmi, o la "duplicazione" non esiste davvero. Coerente con le scelte già motivate in [MIGLIORIE.md] (god-class, split stato, base comune store).

---

## ✅ Conviene (quick win, rischio basso)

> **Implementato il 2026-05-31 sul branch `dev`** — tutti gli interventi qui sotto sono fatti; build OK e 143 unit test verdi.

### Fonti / scraper

- [ ] **`firstNonBlankStatic` definito 4 volte (una è codice morto)** · Impatto Basso · Sforzo Basso · ~25-30 righe
  - Lo stesso helper varargs è ricopiato in 3 companion + 1 metodo della base. Due comportamenti: *no-trim* in `BaseMangaSource.firstNonBlank` [MangaSources.kt:371-378] e `MangapillSource` [MangapillSource.kt:248-255] (byte-identici); *con-trim* in `MangaWorldSource` [MangaWorldSource.kt:264-272] e `VyMangaSource` [VyMangaSource.kt:251-259] (byte-identici tra loro).
  - **Importante:** `BaseMangaSource.firstNonBlank` ha **0 chiamanti** (i companion sono `static` e non vedono il metodo d'istanza → l'hanno ricopiato). È quindi morta e va rimossa, non solo le copie.
  - Cosa fare: una sola `internal fun firstNonBlankTrimmed(vararg…)` top-level nel package. Il trim su Mangapill è innocuo (il titolo è già `.trim()`-ato a valle, MangapillSource.kt:132). Coperto da `MangaSourcesTest`. Evita anche la 5ª/6ª copia quando si aggiunge una fonte con la skill `add-manga-source`.

- [ ] **`extractImageExtension` duplicato Base ↔ cache streaming** · Impatto Basso · Sforzo Basso · ~4-5 righe
  - Stessa logica byte-identica (substringBefore `?` / substringAfterLast `.` / lowercase / filtra alfanumerici / fallback `jpg`) in `BaseMangaSource.extractImageExtension` [MangaSources.kt:380-384] e in `StreamingReaderCacheRepository` [StreamingReaderCacheRepository.kt:232-236].
  - Cosa fare: spostarla in `DownloadStorage` (dove già vivono `safeFilename`/`buildChapterFileName`/`parseChapterValueOrNull`/`freeSpaceBytes`), `fun imageExtension(url): String`. Nessun nuovo accoppiamento (la base usa già `DownloadStorage`). Occasione per aggiungere 3 unit test (manca `DownloadStorageTest`).

- [ ] **`parseChapterNumber` della base è codice morto** · Impatto Basso · Sforzo Basso · ~2 righe
  - `BaseMangaSource.parseChapterNumber` [MangaSources.kt:368-369] ha 0 chiamanti: tutte e 4 le fonti convertono il numero capitolo nei companion `static` chiamando `DownloadStorage.parseChapterValueOrNull` direttamente. Rimuovere (stessa categoria della voce "Rimuovere codice morto" già chiusa in [MIGLIORIE.md]). **Non** estrarre un `parseChapterValueOrThrow` condiviso: solo Mangapill usa quella semantica.

### Dialog / componenti UI condivisi

- [ ] **Dialog di conferma "Elimina manga" scritto a mano 2 volte mentre esiste già `ConfirmationDialog`** · Impatto Medio · Sforzo Basso · ~25-35 righe
  - `AlertDialog` di conferma eliminazione (shape extraLarge, conferma "Elimina" + "Annulla") duplicato inline in `LibrarySeriesCard` [LibrarySeriesCard.kt:139-159] e `StorageScreen` [StorageScreen.kt:141-159], mentre `ConfirmationDialog` privato in [AppDialogs.kt:252-276] (già usato da `DeleteChapterDialog`) fa esattamente questo.
  - Cosa fare: rendere `ConfirmationDialog` pubblico/`internal` (o aggiungere `DeleteSeriesDialog` accanto a `DeleteChapterDialog`) e instradare i due call-site. La differenza di testo (Storage aggiunge "I capitoli scaricati verranno rimossi.") passa nel parametro `text` già esistente → **zero nuovi parametri**. Allinea anche allo standard della skill `app-ui` (i dialog stanno in `AppDialogs.kt`).

- [ ] **`CardDefaults.cardColors(surfaceContainerHigh)` ripetuto in 5 punti** · Impatto Basso · Sforzo Basso · ~10-15 righe
  - Identico in `ResultCard`/`FavoriteCard` [UiComponents.kt:308-310, 419-421], `LibrarySeriesCard` [LibrarySeriesCard.kt:53-55], `SettingsSection` [SettingsComponents.kt:56-58], `StorageHeader` [StorageScreen.kt:172-174].
  - Cosa fare: un helper puro `@Composable fun appCardColors() = CardDefaults.cardColors(containerColor = …surfaceContainerHigh)` (in `MangaDownloaderTheme.kt` o `UiComponents.kt`). **Solo i colori**, non un wrapper `Card` (le `shape` variano: extraLarge vs large → esploderebbero i parametri). Escludere `StorageSeriesRow` [StorageScreen.kt:269] che usa `lerp(...)` intenzionale.

- [ ] **Campo PIN duplicato 3 volte nei dialog parental** · Impatto Basso · Sforzo Basso · ~12-15 righe
  - `OutlinedTextField` PIN identico (PasswordVisualTransformation, NumberPassword) due volte in `ParentalPinSetupDialog` [AppDialogs.kt:175-192] e una in `ParentalPinEntryDialog` [AppDialogs.kt:225-233].
  - Cosa fare: estrarre `PinTextField(value, onValueChange, label, modifier)`. **Non** estrarre il `PinDialogScaffold` (i due dialog divergono sul numero di campi → poco guadagno).

- [ ] **Spinner di caricamento a tutto schermo ripetuto in 3-4 schermate** · Impatto Basso · Sforzo Basso · ~15-18 righe
  - `Box(fillMaxSize, TopCenter){ AppLoadingIndicator(padding top=24.dp) }` byte-identico in `LibraryScreen` [LibraryScreen.kt:61-66], `StorageScreen` [StorageScreen.kt:90-95], `DetailScreen` [DetailScreen.kt:98-100]; variante con `.align(TopCenter)` in `SearchScreen` [SearchScreen.kt:69-73].
  - Cosa fare: `FullScreenLoading(modifier)` in `UiComponents.kt` (accanto a `EmptyState`). Puro markup, rischio minimo. *(Questo finding è emerso due volte dall'audit — UI e schermate: è lo stesso intervento.)*

### Stato / ViewModel

- [ ] **29 `_state.value = _state.value.copy(...)` diretti vs `updateState { }` già esistente** · Impatto Basso · Sforzo Basso · ~29 righe di rumore
  - `updateState` [MangaViewModel.kt:1846-1848] è usato 59 volte; la forma verbosa 25 volte (`copy` pura) + 4 concatenate (`withLibrarySnapshot`/`clearedReaderState`). Sono semanticamente identici.
  - **Aggiorna la voce "Uniformare gli aggiornamenti di stato" di [MIGLIORIE.md] (linee 122-124):** la stima "~30" è confermata, il numero reale è **29**. Le funzioni `withReaderProgress`/`withReaderAdjacency`/`withLibrarySnapshot`/`clearedReaderState` sono estensioni `MangaUiState.() -> MangaUiState`, quindi **anche i casi concatenati sono convertibili** (`updateState { copy(...).withReaderProgress(...) }`). Unico escluso: l'assegnazione condizionale a [MangaViewModel.kt:1507]. Refactor meccanico.

- [ ] **Ricalcolo di `favoriteMangaKeys` duplicato 3 volte** · Impatto Basso · Sforzo Basso · ~6-10 righe
  - `favorites.mapTo(linkedSetOf()) { identityKey(it.sourceId, it.mangaUrl) }` identico in init [MangaViewModel.kt:210-212], `cleanupTutorialSample` [MangaViewModel.kt:788-790], `toggleFavorite` [MangaViewModel.kt:942-944].
  - Cosa fare: estensione privata `List<FavoriteManga>.identityKeys(): Set<String>` (mantenere `linkedSetOf` per l'ordine) + opzionale helper `persistFavorites(list)`. **Non** unificare il pattern secondario di rimozione (toggle usa `indexOfFirst`+`removeAt`, cleanup usa `removeAll`: scopi diversi).

### Rete

- [ ] **`MangaNetworkClient.defaultHttpClient()` è morto e duplica (divergente) `SharedHttpClient`** · Impatto Basso · Sforzo Basso · ~7 righe
  - Il default-param ricostruisce un OkHttpClient con timeout **diversi** (30/60/120s, senza pool/cache/retry) rispetto al condiviso (15/45/90s + pool + cache 20 MB + retry) [MangaNetworkClient.kt:13, 112-118] vs [SharedHttpClient.kt:32-42]. In produzione viene **sempre** iniettato `SharedHttpClient.get(...)` (MangaSources.kt:169, MangaViewModel.kt:196) → `defaultHttpClient()` non gira mai: è una seconda fonte di verità che mente sui timeout.
  - Cosa fare: rendere `httpClient` parametro **obbligatorio** (i call-site lo passano già). I test costruiscono il proprio client, non si rompono.

---

## 🟡 Forse / marginale (solo se tocchi già l'area, o accorpa)

> Tecnicamente sani ma a basso valore: spesso il wrapper costa quasi quanto risparmia, o il vero guadagno è "coerenza futura".
> Diversi sono refactor su Composable senza copertura test → stesso bias documentato in [MIGLIORIE.md] (rinviare).

### Fonti / scraper
- **Template `fetchMangaDetails` (canonical-or-throw → fetch → parse)** ripetuto nei 3 scraper HTML [MangapillSource.kt:40-44, MangaWorldSource.kt:42-47, VyMangaSource.kt:60-64]. HastaTeam non è riconducibile (endpoint `/api/comics/<slug>`). Conviene solo dentro un riordino più ampio della base. ~12-15 righe.
- **`HastaTeamSource.absolutize`** [HastaTeamSource.kt:240-249] reimplementa `URI.resolve` già in `MangaNetworkClient.absolutize` [MangaNetworkClient.kt:75-77]. Va condiviso **solo** come util pura top-level (i companion `static` non possono dipendere da `networkClient`, vincolo per i test senza rete). ~5-7 righe.
- **Regex `chapter N`** identica in Mangapill [MangapillSource.kt:57-58] e VyManga [VyMangaSource.kt:97-98]: condividere **solo il literal regex** in `DownloadStorage`, non le pipeline di conversione (3 varianti throw/skip/fallback diverse). ~6-10 righe.
- **Stringa errore "Nessuna immagine trovata per il capitolo"** ripetuta in 4 fonti: centralizzarla **solo come costante** in `BaseMangaSource` (compatibile col follow-up "log errori di parsing" di [MIGLIORIE.md]). L'helper di loop sui selettori va **scartato** (logica di selezione attributi davvero diversa per fonte). ~3-6 righe.

### Componenti UI
- **`ChapterRow` vs `DownloadedChapterRow`** [UiComponents.kt:458-513, 515-576] condividono la shell Surface+Row+titolo. `ChapterRowScaffold(title, highlighted, onClick, trailing)` è pulito ma sono solo 2 call-site, UI senza test. **A costo zero ora: uniformare l'alpha** (0.45 vs 0.40, quasi certo refuso). ~15-20 righe.
- **`FavoriteToggleBadge` vs `FavoriteToggleAction`** [UiComponents.kt:372-407, AppBars.kt:371-394]: ~70% comune ma differiscono su colori/size/animazione scale. Conviene **solo** estrarre l'helper icona+`contentDescription`, non l'intero bottone. ~10-15 righe.
- **Shell card cover+titolo** (`ResultCard`/`FavoriteCard`) [UiComponents.kt:303-346, 414-456]: un `CoverGridCard` con slot overlay/footer ha senso, ma su 2 soli call-site il guadagno è quasi pari all'astrazione. Le card *orizzontali* (Library/Storage) e `SeriesHeader` (che **non** è una Card) sono troppo diverse: escludere. ~15-25 righe.
- **`SegmentedButtonRow`** ricostruito 3 volte (ThemeMode/ReadingMode/StorageSort) [SettingsScreen.kt:279-298, 319-331, StorageScreen.kt:239-248]: `SegmentedChoiceRow<T>` in `SettingsComponents.kt` estrarrebbe solo la `Row` (non Column+header). Attenzione: lo slot icona dev'essere nullable. ~20-30 righe.

### Schermate
- **`LazyVerticalGrid` 3 colonne** identica in Search [SearchScreen.kt:81-87] e Favorites [FavoritesScreen.kt:67-72]: `MangaCoverGrid` è neutro in righe e oggi YAGNI (nessuna griglia adattiva). Farlo se nasce una 3ª schermata-galleria. ~3-6 righe.
- **Wrap del primo item per il tutorial** (`Box`+`anchorFor`+confronto `firstKey`) in 3 liste [SearchScreen.kt:76-101, LibraryScreen.kt:86-104, DownloadedSeriesScreen.kt:44-57]: `Modifier.tutorialAnchorIfFirst(anchor, isFirst)` possibile, ma il tutorial è **feature delicata** e l'anchor registra i bounds dello spotlight → rischio funzionale medio per ~6-9 righe. Verificare on-device.

### Persistenza
- **Boilerplate lista-JSON-su-prefs** duplicato tra `FavoritesStore` [FavoritesStore.kt:16-55] e `RecentSearchesStore` [RecentSearchesStore.kt:15-35] (istanza `Json`, blank-guard, try/catch→emptyList, encode/apply). Solo 2 call-site, in tensione con la scelta di [MIGLIORIE.md] di tenere gli store separati per dominio. Se fatto: due **extension function** `SharedPreferences.readJsonList/writeJsonList`, **non** una classe parametrica. Attenzione alla retrocompatibilità del formato su disco. ~10-15 righe.
- **Plumbing codec JSON** (config `Json`, isFile-guard+parse+try/catch→null, write+pattern lista-stringhe-sanificata) tra `StreamingReaderCacheRepository` [StreamingReaderCacheRepository.kt:49-107] e `SeriesMetadataJson` [LibraryRepository.kt:180-284]. Estrarre **solo** helper (`File.readJsonObjectOrNull`, `writeJsonObject`, `JsonObject.stringList`, `putIfNotNull`), mai unificare i due `write` (ordine `put` + array annidato divergono → byte-compat). Coperto da test. ~25-35 righe.

### Stato / ViewModel
- **Guscio `launch{} + try/catch(CancellationException)/catch(Exception){copy(loading=false, errorMessage=…)}`** ripetuto ~10 volte [MangaViewModel.kt:857-863, 895-906, 968-974, 1074-1080, 1413-1419, 1442-1448, 1571-1577]. Un `launchGuarded(onError){block}` garantirebbe il re-throw di `CancellationException`, ma le code d'errore si spostano (non spariscono) e 3-4 blocchi sono atipici (`showMangaInfo`, `checkForAppUpdate` con `finally`, `openStreamingReader` con job annidato) → tenerli fuori. Da accorpare alla voce "uniformare aggiornamenti di stato". ~25-35 righe.
- **`withContext(IO){ sourceRegistry.resolve(...).fetchMangaDetails(url) }`** identico 3 volte [MangaViewModel.kt:846-848, 882-884, 1306-1308] → `private suspend fun fetchDetails(sourceId, mangaUrl)`. **Non** accorpare con `requireById(...).searchManga/…` (metodi e lookup diversi). ~4-6 righe.
- **Reset condizionale di `pendingSearchAccessReturnTab` su `OPEN_SEARCH`** in 3 dismiss parental [MangaViewModel.kt:436-440, 524-528, 608-612]: estrarre **solo** l'helper stretto, mai quello monolitico (`disableParentalControl` azzera in modo incondizionato e cambia tab). ~8 righe.

### Rete / modelli
- **Quartetto `sourceId+title+mangaUrl+coverUrl`** in `MangaSearchResult`/`FavoriteManga`/`MangaDetails`/`TutorialSample`. Conviene **solo** la factory `FavoriteManga.from(ref)` per togliere le 2 copie campo-per-campo [MangaViewModel.kt:817-822, 952-957]. L'interfaccia `MangaRef` ampia + overload `identityKeyOrNull(ref)` rende poco (le call-site passano `mangaUrl` *e* `null` per il fallback title-only) e accoppia rete e modello persistito. ~8-12 righe.

---

## 🔴 Da NON fare (scartati con motivo)

- **`canonicalMangaUrl = canonicalSeriesUrl`** (4 one-liner) — toccare il dispatch `when` del `MangaSourceCatalog` (load-bearing per `identityKey`/`normalizeSeriesUrl`/`sourceIdForUrl`) per risparmiare 4 righe banali: rischio > beneficio.
- **`isPlaceholderImage` promosso a util condivisa** — è genuinamente per-sito (VyManga ha `.gif`, Mangapill solo `data:`, le altre niente): nessun secondo consumatore reale, rischio falsi positivi.
- **Icone badge stato (letto/scaricato/segnalibro)** — solo `CheckCircle/ReadGreen/22.dp` è davvero condivisa; le altre compaiono una volta sola e i `contentDescription` divergono. Eventualmente assorbire dentro un futuro unify delle row, non come task isolato.
- **Empty-state "Nessun risultato/corrisponde"** — `EmptyState` è **già** il componente condiviso e già riusato; i 3 titoli divergono per UX (informazione utile), uniformarli è decisione di prodotto, non refactor.
- **Scaffold di schermata "SearchField + when(stati)"** (Search/Favorites/Library) — lo scheletro coincide solo in astratto; PullToRefresh, FAB "Ferma download", grid vs LazyColumn, ramo query-vuota rendono un `SearchableListScreen` un festival di slot/parametri meno leggibile dell'attuale. I mattoni piccoli (`AppLoadingIndicator`, `EmptyState`) sono già condivisi.
- **Superclasse comune per gli store su prefs** — `SettingsStore` (campi tipizzati + coercizioni), gli store JSON e i codec metadata hanno responsabilità eterogenee. Già scelti come collaboratori separati in [MIGLIORIE.md]: **lasciarli separati**.
- **Helper TTL/cache condiviso** — la cache `LibraryRepository` (TTL 5s in memoria) e l'eviction LRU di `StreamingReaderCacheRepository` sono politiche diverse; il cooldown update in `MangaViewModel` legge da prefs. Nessuna vera duplicazione.
- **Costanti header HTTP co-locate** — i due User-Agent sono valori **diversi** (desktop vs mobile, scelta voluta) e gli `Accept` sono purpose-specific (HTML vs immagine): non è deduplicazione, centralizzarli aggiungerebbe accoppiamento senza togliere righe.

---

## Tabella riassuntiva

| # | Intervento | Area | Verdetto | Impatto | Sforzo | ~Righe |
|---|---|---|---|---|---|---|
| 1 | `firstNonBlankStatic` ×4 + base morta → util | fonti | ✅ | Basso | Basso | 25-30 |
| 2 | `extractImageExtension` → `DownloadStorage` | fonti | ✅ | Basso | Basso | 4-5 |
| 3 | `parseChapterNumber` morto → rimuovi | fonti | ✅ | Basso | Basso | 2 |
| 4 | Dialog "Elimina" → riusa `ConfirmationDialog` | UI | ✅ | Medio | Basso | 25-35 |
| 5 | `appCardColors()` helper | UI | ✅ | Basso | Basso | 10-15 |
| 6 | `PinTextField` | UI | ✅ | Basso | Basso | 12-15 |
| 7 | `FullScreenLoading` | schermate | ✅ | Basso | Basso | 15-18 |
| 8 | 29× `_state.value` → `updateState` | VM | ✅ | Basso | Basso | 29 |
| 9 | `List<FavoriteManga>.identityKeys()` | VM | ✅ | Basso | Basso | 6-10 |
| 10 | `defaultHttpClient()` morto → param obbligatorio | rete | ✅ | Basso | Basso | 7 |
| — | 14 voci marginali | varie | 🟡 | Basso | Basso/Medio | ~140 (sparse) |
| — | 8 voci | varie | 🔴 scarta | — | — | — |

**Totale gruppo ✅: ~135-170 righe in meno, rischio basso.** La maggior parte fuori dalla UI o su funzioni pure/persistenza/rete già coperte da test.

---

## Suggerimento operativo

Il gruppo ✅ è abbastanza piccolo e a basso rischio da stare su `main` (vedi regole di branching in [CLAUDE.md]); le voci 🟡 più grosse (codec JSON, `launchGuarded`, `SegmentedChoiceRow`) se affrontate andrebbero su un branch `dev`. Quando converto i `_state.value` → `updateState` (voce 8) conviene farlo **insieme** alle code d'errore di `launchGuarded` (sono lo stesso punto del codice).
