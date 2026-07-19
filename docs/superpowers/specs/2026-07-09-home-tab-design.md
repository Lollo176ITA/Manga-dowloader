# Design — Tab "Home" (centro dell'app)

**Data:** 2026-07-09
**Branch:** `dev`
**Stato:** approvato (design), in attesa di revisione spec → poi piano di implementazione.

## Obiettivo

Introdurre una tab **Home** che diventa il centro dell'app: un punto di partenza che
raccoglie le azioni chiave e i contenuti rilevanti (riprendi lettura, novità dai preferiti,
preferiti recenti, scopri), con onboarding non invasivo e uno stato iniziale utile per chi
non ha ancora contenuti. Le tab in basso diventano **Home · Cerca · Preferiti · Libreria**;
la vecchia tab opzionale **Scopri** viene assorbita come blocco dentro la Home.

## Decisioni confermate (bivi di prodotto)

1. **Personalizzazione**: modalità modifica con **frecce ↑↓ (riordino) + mostra/nascondi**.
   Niente drag&drop (più fragile/meno accessibile). Long-press su un blocco come entrata extra.
2. **Continua a leggere**: **solo capitoli scaricati** (riuso `computeContinueReading`, già
   testato). Le letture solo-streaming non compaiono ancora → miglioria futura.
3. **Scopri**: blocco **visibile a tutti** di default (nascondibile dalla personalizzazione);
   **rimuovo** la tab opzionale `DISCOVERY` e il flag `discoveryEnabled`.
4. **Controllo parentale**: il blocco **Scopri è nascosto del tutto** quando il controllo
   parentale è attivo (nessuna possibilità di aprirlo). L'atterraggio resta su **Libreria**.
5. **Top bar su Home**: logo app + **nessun titolo centrale** + ingranaggio Impostazioni +
   icona matita (Modifica).

## Contesto architetturale (dal codice)

- **Nessun NavHost**: la navigazione è *state-derived*. `MangaUiState.currentScreen()`
  (Screen.kt) deriva la schermata in primo piano; `Screen.Tabs` è la root che ospita un
  `HorizontalPager` sulle tab `AppTab`. Aggiungere una tab = estendere l'enum + il plumbing,
  **non** registrare una route.
- **Un solo state holder**: `MangaViewModel` espone un unico `StateFlow<MangaUiState>`. Tutto
  ciò che serve alla Home è già su `MangaUiState`: `favorites`, `favoriteUpdates`, `library`,
  `discovery`, `tutorialState`, `settings`.
- **Persistenza**: SharedPreferences (`manga_downloader_prefs`) via `SettingsStore`
  (`read()`/`persist(AppSettings)`), niente DataStore. `AppSettings` è un data class immutabile;
  le mutazioni passano da `updateSettings { it.copy(...) }` (persiste in automatico).

## Componente per componente

### 1. Enum tab e navigazione

- `AppTab`: da `{ DISCOVERY, SEARCH, FAVORITES, LIBRARY }` a **`{ HOME, SEARCH, FAVORITES, LIBRARY }`**.
- `visibleTabs()` (Screen.kt): rimuovo il filtro condizionale su `DISCOVERY` → tutte le tab
  sempre visibili. `tabPageIndex()`/pager math restano corretti (conteggio statico = 4).
- Bottom bar (`AppBottomBar`, AppBars.kt): voce **Home** (`Icons.Filled.Home`, "Home") come
  primo item; rimuovo la voce Scopri condizionale. Preferiti mantiene il badge `favoritesBadgeCount`.
- Top bar (`AppTopBar`, AppBars.kt): per `HOME` → logo come nav icon, nessun titolo centrale,
  azioni: ingranaggio Impostazioni (già presente su `Screen.Tabs`) + **matita "Modifica Home"**
  (nuova, solo su HOME).

### 2. Landing / tab d'avvio

- Default `currentTab = AppTab.HOME` (`MangaUiState` default + `when` di init in `MangaViewModel`).
- **Override invariati**: `parentalControlEnabled` → `LIBRARY`. Rimuovo il ramo
  `discoveryEnabled && !shouldStartTutorial → DISCOVERY`. `currentTab` non è persistito → nessuna migrazione.

### 3. `HomeScreen.kt` (nuovo) — struttura

`LazyColumn` con, dall'alto:

1. **Saluto leggero** (chrome, non personalizzabile) — testo in base all'ora
   ("Buongiorno / Buon pomeriggio / Buonasera"); se loggato AniList, opzionale "Bentornato, {nome}".
   Funzione pura dell'ora (testabile).
2. **Card onboarding** (chrome, condizionale) — vedi §5.
3. **RESUME — "Continua a leggere"** (blocco) — card ricca (copertina + titolo serie +
   etichetta capitolo + barra progresso da `readerPageIndex/readerPageCount`), da
   `computeContinueReading(state.library, limit = 1)`; tap → `viewModel.openReader(chapter)`.
   Se non c'è nulla in corso, il blocco **si nasconde**.
4. **FAVORITE_UPDATES — "Novità dai preferiti"** (blocco) — fino a **5 righe** di
   `state.favoriteUpdates` ordinate per `timestampMillis` desc; riga = composable estratto da
   `UpdatesScreen` (oggi `private`). Header con azione "Vedi tutte" → `viewModel.openUpdates()`.
   Tap riga → `viewModel.openMangaFromUpdate(event)` (segna letta **solo** quella; **non** azzera
   il badge). Nasconde il blocco se il feed è vuoto.
5. **RECENT_FAVORITES — "Preferiti recenti"** (blocco) — carosello orizzontale dei primi
   **12** `state.favorites` (più recenti prima), tile `FavoriteCard`. "Vedi tutti" → `selectTab(FAVORITES)`;
   tap → costruisce un `MangaSearchResult` e chiama `viewModel.selectManga(...)`. Nasconde il
   blocco se non ci sono preferiti.
6. **DISCOVER — "Scopri"** (blocco) — i 3 caroselli esistenti (Tendenze / Più votati / Novità)
   riusando `section()` + `DiscoveryCard` (da rendere riusabili). `viewModel.loadDiscovery()`
   idempotente al primo mostrarsi; tap → `viewModel.onPickAniListManga(...)` (ponte verso
   ricerca aggregata); badge info → `showDiscoveryInfo`/`AniListInfoDialog`. La griglia
   per-genere resta concetto da tab e **non** entra nel blocco.
   **Nascosto del tutto se `settings.parentalControlEnabled`.**

**Refresh dei dati** (oggi legati all'ingresso nelle rispettive tab): quando la Home diventa
visibile eseguo `refreshLibrary()` (per Continua a leggere) e `refreshUpdatesFeed()` (per le
Novità). `loadDiscovery()` parte quando il blocco Scopri appare.

### 4. Empty state (utente senza contenuti)

Quando **niente preferiti, niente libreria, niente lettura in corso**: saluto + (eventuale card
onboarding) + un `EmptyState` ("Inizia la tua collezione", CTA primaria **"Cerca il primo manga"**
→ `goToSearchTab`) **+ il blocco Scopri** sotto (se non in parental) così c'è comunque contenuto
da esplorare. Nessuna schermata vuota.

### 5. Onboarding come card discreta

- La **card** sostituisce il **popup** `WelcomeTutorialDialog`. Visibile quando
  `tutorialState.phase == Welcome` (stesso gate di oggi: utente fresco, zero preferiti, tutorial
  non completato).
- Azioni: **"Inizia il tutorial"** → `viewModel.onTutorialWelcomeStart()` (mantiene **intatto**
  tutto il tour interattivo: preload, coachmark, closing). **"Non ora / X"** →
  `viewModel.onTutorialWelcomeSkip()` (permanente: setta `tutorialCompleted`).
- `TutorialOverlay` continua ad avvolgere l'app; **sopprimo solo** il ramo che mostra
  `WelcomeTutorialDialog` (l'entrata è ora la card). Il resto invariato.
- **"Rivedi il tutorial"**: nuova voce in Impostazioni → nuovo `viewModel.restartTutorial()`
  (rilancia il tour, es. `phase = Welcome` / `onTutorialWelcomeStart()`), così è ri-eseguibile.

### 6. Personalizzazione (frecce + nascondi)

- `enum class HomeBlock { RESUME, FAVORITE_UPDATES, RECENT_FAVORITES, DISCOVER }`
  (saluto/onboarding/empty-state sono chrome, non riordinabili/nascondibili).
- **Modalità modifica**: icona matita nella top bar (solo Home) **oppure** long-press su un
  blocco → entra in edit mode (stato UI transitorio, `remember` in `HomeScreen`, **non**
  persistito). In edit mode ogni blocco mostra ↑ ↓ (riordino) e occhio/occhio-barrato
  (mostra/nascondi). "Fine" esce.
- Nuovi metodi VM: `moveHomeBlock(block, up: Boolean)` e `setHomeBlockHidden(block, hidden)` →
  passano da `updateSettings { copy(...) }` (persistenza automatica).
- **Blocchi effettivamente resi** = `homeBlockOrder` filtrato per: non in `hiddenHomeBlocks`,
  con dati non vuoti (RESUME/UPDATES/FAVORITES si auto-nascondono se vuoti), e **DISCOVER escluso
  se parental**. Fuori da edit mode i blocchi vuoti non occupano spazio; in edit mode i blocchi
  nascosti sono mostrati "spenti" per poterli riattivare.

### 7. Persistenza (primo setting a lista)

- `AppSettings` += `homeBlockOrder: List<HomeBlock>` (default = ordine consigliato: RESUME,
  FAVORITE_UPDATES, RECENT_FAVORITES, DISCOVER) e `hiddenHomeBlocks: Set<HomeBlock>` (default vuoto).
- `SettingsStore`: nuove chiavi `home_block_order` e `home_hidden_blocks`, serializzate in **JSON**
  (idioma `RecentSearchesStore`: `Json { ignoreUnknownKeys = true }`, encode/decode di
  `List<String>` di nomi enum), con **decode tollerante + riconciliazione**:
  - scarto i nomi che non mappano più su `HomeBlock`;
  - **aggiungo in coda** i blocchi nuovi non presenti nella lista salvata (così utenti esistenti
    vedono blocchi futuri; `RecentSearchesStore` scarta gli ignoti ma non aggiunge → serve logica
    esplicita `reconcileHomeBlocks()`).
- Rimuovo il vecchio flag `discoveryEnabled` / `KEY_DISCOVERY_ENABLED` e il relativo toggle
  in `SettingsScreen`.
- **Backup**: aggiungo i due campi a `SettingsBackup` (`toBackup`/`applyTo` in `BackupSchema.kt`);
  aggiungere un campo con default è retro-tollerante (nessun bump di `BACKUP_SCHEMA_VERSION`).
  Rimuovo `discoveryEnabled` dal backup DTO (con cautela di compatibilità: `ignoreUnknownKeys`).

### 8. Controllo parentale

- Atterraggio invariato: `parentalControlEnabled` → `LIBRARY`.
- La Home resta in barra ma il **blocco Scopri è escluso** quando parental è attivo (non solo
  disabilitato: proprio non renderizzato, così "non si può cliccare").
- La CTA "Cerca il primo manga" e ogni salto a Cerca passano da `goToSearchTab`/`selectTab(SEARCH)`
  che **già** applicano il PIN/biometria. Nessun nuovo controllo da inventare.

## File

### Nuovi

- `HomeScreen.kt` — composable Home: saluto, dispatch blocchi via `homeBlockOrder`, edit mode, empty state.
- `HomeBlocks.kt` — `enum HomeBlock`, `reconcileHomeBlocks(stored): List<HomeBlock>`, helper puri
  di riordino/hide (testabili senza Android).
- `HomeSection.kt` — `HomeSection(title, trailingAction?, content)` + `HomeCarousel` condivisi
  (colmano il gap "nessun SectionHeader/Carousel").

### Estrazioni (widening visibilità / spostamento)

- Riga feed + `DayHeader` da `UpdatesScreen.kt` → composable riusabile.
- `section()`, `DiscoveryCard`, `AniListInfoDialog` da `DiscoveryScreen.kt` → `internal`/file dedicato.

### Modificati

- `MangaViewModel.kt` — `AppTab` (add HOME, remove DISCOVERY), default/init `currentTab`, nuovi
  metodi (`moveHomeBlock`, `setHomeBlockHidden`, `restartTutorial`), rimozione `setDiscoveryEnabled`,
  campi `AppSettings`.
- `Screen.kt` — `visibleTabs()` (no filtro DISCOVERY).
- `AppBars.kt` — voce Home in bottom bar, matita in top bar, rimozione Scopri.
- `MainActivity.kt` — ramo pager `HOME → HomeScreen(...)`, rimozione ramo DISCOVERY, wiring
  callback Home, trigger `refreshLibrary()`/`refreshUpdatesFeed()`/`loadDiscovery()` su Home visibile.
- `SettingsStore.kt` / `BackupSchema.kt` — nuove chiavi + backup, rimozione `discovery_enabled`.
- `SettingsScreen.kt` — rimozione toggle Scopri, aggiunta "Rivedi il tutorial".
- `TutorialOverlay.kt` — soppressione del popup Welcome (l'entrata è la card Home).

### Test

- `HomeBlocksTest` — riordino, hide, `reconcileHomeBlocks` (scarta ignoti, appende nuovi), ordine default.
- Aggiornare test che citano `AppTab.DISCOVERY` / landing tab (`ScreenTest`,
  `MangaViewModelParentalControlTest`).
- Round-trip settings/backup dei nuovi campi (estendere `BackupManagerTest`).
- Compose test Home (empty state + render/nascondi blocchi, sul modello di `EmptyStateUiTest`);
  test che il blocco Scopri non compaia con parental attivo.

## Non-obiettivi (fuori scope, possibili migliorie future)

- Ripresa lettura dallo **streaming** (solo scaricati per ora).
- **Drag&drop** dei blocchi (frecce per ora).
- Blocchi Home aggiuntivi oltre ai quattro (es. "consigliati per te").
- Cache offline del feed Scopri.

## Rischi / punti d'attenzione

- Rimuovere `DISCOVERY` tocca molti punti accoppiati (enum, `visibleTabs`, init landing,
  `setDiscoveryEnabled`, ramo pager, titoli/voci barre, toggle Impostazioni, test) → checklist
  esplicita nel piano.
- La libreria si ri-scansiona solo entrando in Libreria: la Home **deve** avere un proprio trigger
  di refresh o mostra dati stantii/vuoti al primo ingresso.
- Semantica "seen" del feed: la Home **non** deve marcare tutto letto in blocco (ucciderebbe il
  badge) → solo `openMangaFromUpdate` per-riga.
- Primo setting a lista in `AppSettings`: `SettingsStore.read/persist` devono gestire
  serializzazione ORDINATA (JSON, non `putStringSet` che perde l'ordine).
- `dev` è divergente da `main` (2 avanti / 1 indietro): sync prima dell'implementazione.
