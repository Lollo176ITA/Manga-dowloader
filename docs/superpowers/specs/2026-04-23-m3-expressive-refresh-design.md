# Design — Refresh UI Material 3 Expressive

Data: 2026-04-23
Branch di lavoro: `dev`
Stato: approvato dall'utente, pronto per la fase di plan.

## Contesto

L'app Android Manga Downloader è in Jetpack Compose, tema solo dark, palette ambra calda, 3 tab (Cerca / Preferiti / Libreria) + dettaglio + reader + impostazioni. Compose BOM in uso: `2024.06.00` (giugno 2024).

L'obiettivo è un **refresh completo** dell'interfaccia adottando **Material 3 Expressive**, l'evoluzione del design system Material 3 introdotta da Google nel 2025. M3 Expressive aggiunge nuovi componenti (FAB Menu, ButtonGroup, SplitButton, Toolbars, LoadingIndicator), shape morphing, e un nuovo motion-physics system basato su spring.

## Decisioni di alto livello

| Tema | Decisione |
|---|---|
| Scope | Refresh completo (tutta l'app: top bar, bottom nav, schermate tab, dettaglio, reader, settings, dialog) |
| Tema | Light + Dark + auto-switch sistema, con override manuale dalle Impostazioni; Material You attivo da Android 12+ |
| Livello espressività | Bilanciato/Friendly: shape morphing su FAB e nav, spring physics percepibili, titoli display con emphasis |
| Reader | Resta immersivo e minimale: solo `LoadingIndicator` Expressive + spring slide tra capitoli. Niente FAB/toolbar |
| Branch | `dev` (esiste già, attualmente allineato a `origin/dev`) |
| Commit | L'utente fa i commit personalmente — nessun commit automatico |

## 1. Fondamenta — dipendenze e theme system

### Bump dipendenze (`android-app/app/build.gradle.kts`)

- Compose BOM: `2024.06.00` → ultima versione stabile disponibile che includa `material3:1.4.x` (target indicativo `2025.10.00` o successiva). Da verificare in fase di implementazione cercando l'ultima BOM su Maven Central
- `androidx.compose.material3:material3` — versione lasciata gestire dal BOM (deve essere `1.4.x` o superiore per i componenti Expressive)
- Mantengo le altre dipendenze esistenti (`material-icons-extended`, `coil-compose`, `runtime-livedata`, ecc.) — il BOM le aggiorna automaticamente

### Theme system (`MangaDownloaderTheme.kt`)

Tre schemi di colore definiti localmente:
- `AppLightColorScheme` — palette ambra calda in versione chiara
- `AppDarkColorScheme` — palette ambra scura riveduta (secondary/tertiary armonizzati, surface tint corretti per M3)
- Material You (`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`) attivo automaticamente da Android 12+ se l'utente non lo disabilita

Parametri composable:
```kotlin
@Composable
fun MangaDownloaderTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
)
```

Risoluzione del color scheme:
1. `useDynamic = true && Build.VERSION.SDK_INT >= S` → dynamic color scheme (light o dark in base a `themeMode`/`isSystemInDarkTheme()`)
2. Altrimenti → `AppLightColorScheme` o `AppDarkColorScheme` in base a `themeMode`/`isSystemInDarkTheme()`
3. `themeMode == AUTO` → segue `isSystemInDarkTheme()`

`Typography` Expressive personalizzata: scala display/headline più grande con `letterSpacing` rivisto, font-weight emphasis (Medium per titoli).

`Shapes` Expressive: scala `extraSmall` (4dp) → `extraLarge` (28dp) con squircle/rounded corner sizes pensati per il morphing.

`StatusBar` e `NavigationBar` di sistema: icone chiare in dark mode, scure in light mode, gestite via `WindowCompat`.

### Nuovo enum `ThemeMode`

In `MangaModels.kt`:
```kotlin
enum class ThemeMode { AUTO, LIGHT, DARK }
```

### Persistenza preferenze tema

Aggiungo due campi in `AppSettings` (data class esistente, vedi `MangaViewModel.kt`):
```kotlin
val themeMode: ThemeMode = ThemeMode.AUTO,
val useDynamicColor: Boolean = true,
```

Salvataggio: riuso `MangaViewModel.persistSettings(settings: AppSettings)` esistente (chiamato in `MangaViewModel.kt:1227`). `MainActivity` legge `state.settings.themeMode` + `state.settings.useDynamicColor` e li passa a `MangaDownloaderTheme(...)`.

## 2. Componenti per schermata

### Top bar globale (`AppBars.kt → AppTopBar`)

- `CenterAlignedTopAppBar` → **`LargeFlexibleTopAppBar`** Expressive con scroll behavior collassante; titolo display large che si rimpicciolisce in scroll
- Quando `showBack == true`: versione compatta `TopAppBar` Expressive normale
- Icona stella preferiti: `IconToggleButton` Expressive con shape morphing (round → squircle quando attiva)
- Container color: `surfaceContainer`

### Bottom navigation (`AppBars.kt → AppBottomBar`)

- `NavigationBar` → **`ShortNavigationBar`** Expressive
- Item selezionato: shape morph (round → pill squircle) con spring physics
- Active indicator background con `surfaceContainerHighest`

### Search screen (`SearchScreen`)

- `SearchField` (custom) → **`SearchBar`** Expressive con leading icon, trailing clear button, modalità active full-screen con suggerimenti recenti (se la lista recenti è già disponibile nello state — altrimenti senza suggerimenti per ora)
- `LazyVerticalGrid` resta a 3 colonne
- `ResultCard`: `Card` Expressive con shape `extraLarge`, elevation ridotta (preferenza Expressive per surface tint), copertina con shape Expressive, ripple ring espressivo, badge preferito morphato (filled-tonal `IconToggleButton`)
- `CircularProgressIndicator` → **`LoadingIndicator`** Expressive
- Aggiungo `PullToRefreshBox` per re-fetch della lista risultati

### Favorites screen (`FavoritesScreen`)

- Stessa `SearchBar` Expressive
- `FavoriteCard`: stesso trattamento di `ResultCard`
- Empty state: icona/illustrazione grande + testo `headlineSmall` Expressive

### Detail screen (`DetailScreen`)

- `SeriesHeader` ridisegnato: cover `extraLarge` (squircle), titolo `displaySmall`, sotto-meta `bodyMedium`
- "Scarica tutti" button → **`SplitButton`** Expressive: azione principale "Scarica tutto" + chevron con menu opzioni ("Scarica selezione…", "Scarica gli ultimi 10", "Scarica gli ultimi 20")
- `ChapterRow`: shape pill arrotondata, stato letto/non-letto con tinted container Expressive
- `ScrollToBottomButton` → **`SmallFloatingActionButton`** Expressive (shape morphata)
- `CircularProgressIndicator` → `LoadingIndicator` Expressive
- Nuovo **`FloatingActionButtonMenu`** con FAB principale "Azioni" che si espande in: Scarica tutto, Scarica intervallo, Vai in fondo, Apri sito originale

### Library screen (`LibraryScreen`)

- `SearchBar` Expressive
- `LibrarySeriesCard`: shape `large` Expressive, miniatura squircle, indicatore stato download tramite **`LinearWavyProgressIndicator`** Expressive
- `LoadingIndicator` invece di `CircularProgressIndicator`
- "Stop downloads" → **`ExtendedFloatingActionButton`** Expressive con label morphata, visibile solo quando ci sono download attivi

### DownloadedSeriesScreen

- Cards capitolo: shape pill arrotondata Expressive
- "Leggi" button → `Button` Expressive (shape large, label `labelLarge`, surface tint corretto)

### Settings screen (`SettingsScreen`)

- Sezioni → `Card` Expressive con shape `extraLarge`
- Toggle correlati raggruppati in `ButtonGroup` Expressive (es. auto-reader speed)
- `Slider` per limiti download → `Slider` Expressive con value indicator morphato
- **Nuova sezione "Aspetto"** in cima:
  - **Tema**: `ButtonGroup` con 3 segmenti AUTO/LIGHT/DARK (icone `BrightnessAuto`, `LightMode`, `DarkMode`)
  - **Colori dinamici**: `Switch` Expressive (Material You); disabilitato + tooltip esplicativo "Disponibile da Android 12" se `Build.VERSION.SDK_INT < S`

### Reader screen (`ReaderScreen`)

- Top bar trasparente con fade in/out al tap (rifinire spring + alpha animation)
- `CircularProgressIndicator` → `LoadingIndicator` Expressive
- Transizioni capitolo prec/succ: spring slide horizontale invece di cut
- Nessun FAB, nessuna toolbar (confermato)

### Dialogs (`AppDialogs.kt`)

- `AlertDialog` → `AlertDialog` Expressive con shape `extraLarge`, padding aggiornato, button con shape `large`
- `DownloadRangeDialog`: `ExposedDropdownMenuBox` con shape Expressive

## 3. Motion, interazione, stati

### Motion-physics system

Sostituisco `tween`/`AnimationSpec` con **`spring`** Expressive:
- Tab switching: `AnimatedContent` con `motionScheme.fastSpatialSpec()`
- Screen transitions (Detail → Reader, Library → DownloadedSeries): spring slide enter/exit con `motionScheme.defaultSpatialSpec()`
- Card press: scale 1.0 → 0.96 con `springEffectsSpec()`
- FAB Menu open/close: spring scale + fade + stagger sui menu item (50ms tra item)
- Bottom nav active indicator: shape morphing tramite `MaterialShapes.morph(from, to, progress)` su spring

### State layer

Componenti M3 Expressive includono nuovi state layer (più tinta, meno opacità) gestiti automaticamente. Card Expressive: ripple custom con `colorScheme.primaryContainer` come tint del press state.

### Loading patterns

- Liste lazy: `LoadingIndicator` Expressive centrato in alto
- Download in corso: `LinearWavyProgressIndicator` Expressive
- Pull-to-refresh: `PullToRefreshBox` con `LoadingIndicator` Expressive

### Toggle e selezione

- Stelle preferito: `IconToggleButton` Expressive con shape morph round → squircle + spring color transition
- Tab nav bar: shape morph + selected indicator background con `surfaceContainerHighest`
- Theme picker: `ButtonGroup` con segment selezionato che ottiene `tonalElevation` + shape morph

### Search bar

- Modalità collapsed → expanded: spring transform full-screen con suggerimenti
- Clear button: fade + scale spring

### Snackbar / feedback

- `Snackbar` M3 Expressive (forme nuove, surface container)
- Errori (es. fallimento download): mantengo Snackbar (no Toast)

## 4. Accessibilità

- Tutti i nuovi `IconButton` / `IconToggleButton` ricevono `contentDescription` in italiano coerente con quelli attuali
- Contrasto WCAG AA verificato per palette ambra dark e light: `onPrimary`, `onSurface`, `onSurfaceVariant` calibrati
- Touch target minimo 48dp anche dove l'estetica Expressive vuole bottoni più piccoli
- Animazioni < 500ms; rispettano `Settings.Global.ANIMATOR_DURATION_SCALE` (Compose default)
- Reduce motion: spring physics si auto-degradano via `MotionScheme` se l'utente ha animazioni ridotte (gestito da material3 1.4+)

## 5. Rischi e mitigazioni

| Rischio | Mitigazione |
|---|---|
| API Expressive `@Experimental` (FAB Menu, LoadingIndicator, LinearWavyProgressIndicator, ButtonGroup, SplitButton) | `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` localizzato per file |
| Bump Compose BOM 2024.06 → 2025.10 può portare deprecation warning | Compilo, fixo i deprecation se bloccanti, ignoro warnings non bloccanti |
| Dynamic color non disponibile pre-Android 12 | Fallback automatico alla palette ambra; switch UI disabilitato + tooltip |
| `FavoriteYellow` hardcoded in `AppBars.kt` poco contrastato in light theme | Rivedere e adattare (tonalità diverse light/dark se necessario) |
| Componenti Expressive in alpha potrebbero cambiare API in futuro | Accettato — `material3:1.4.x` è la versione target; bump futuri richiederanno polish ma non rifattorizzazioni profonde |

## 6. Branch & commit policy

- Lavoro tutto sul branch `dev` (già esistente, sincronizzato con `origin/dev`)
- **Nessun commit git in autonomia**: l'utente committa personalmente al termine
- Niente push, niente PR, niente merge in `main` automatici

## 7. Testing (validazione manuale)

Niente nuovi test unit/Compose UI (l'app non ha una test suite GUI esistente, non vale la pena introdurla solo per questo refresh). Validazione manuale sulle golden path:

1. **Build** — `./gradlew :app:assembleDebug` passa senza errori
2. **Theme switching** — Settings → Tema AUTO/LIGHT/DARK: tutte le schermate si ricolorano correttamente, niente testo invisibile
3. **Dynamic color toggle** — Su device Android 12+: switch Material You ON → palette si adatta al wallpaper; OFF → torna ambra
4. **Pre-Android 12** — Switch Material You disabilitato + tooltip mostrato; app resta usabile con ambra
5. **Bottom nav shape morph** — tap su tab: l'item selezionato anima la shape (round → squircle) con spring percepibile
6. **FAB Menu detail** — tap FAB: menu si espande con stagger, ogni voce è cliccabile, dismiss su tap fuori
7. **SplitButton scarica** — tap principale = scarica tutto; tap chevron = menu opzioni
8. **Loading indicator** — durante una ricerca lenta: `LoadingIndicator` morphante visibile
9. **Wavy progress download** — avvio un download: `LinearWavyProgressIndicator` Expressive visibile
10. **Reader** — apri capitolo: top bar fade in/out al tap, transizione capitolo con spring slide, nessun FAB
11. **No regressioni funzionali** — preferiti si salvano, ricerca funziona, download partono, libreria mostra serie scaricate, impostazioni esistenti (auto-reader speed, server di ricerca, parental control) restano funzionanti

## 8. Non-goals (esplicitati)

- **Non** spezzare `AppScreens.kt` (1888 righe) in file separati — refactor non richiesto
- **Non** introdurre Hilt/DI/altre librerie nuove oltre a Compose/Material3
- **Non** ridisegnare la struttura di navigazione (resta a 3 tab con back stack manuale via state)
- **Non** aggiungere `NavigationRail` / layout adattivi tablet
- **Non** scrivere unit test Compose ex-novo
- **Non** modificare la logica di download, sources, parser HTML
- **Non** toccare `manga_downloader.py` (client desktop separato, non legato all'app Android)

## 9. Definizione di "fatto"

- Tutto il lavoro è sul branch `dev`, pronto per il commit dell'utente
- App build su Compose BOM `2025.10.00`, `material3:1.4.x`
- Tutte le 11 golden path manuali sopra passano
- Light + dark + dynamic color funzionanti, switchabili da Settings → Aspetto
- Tutti i `CircularProgressIndicator` sostituiti con `LoadingIndicator` Expressive
- Bottom nav, top bar, dialogs, cards, FAB, SplitButton, ButtonGroup, Slider, SearchBar usano API Expressive
- Versione bumpata in `version.properties` (proposta: `1.8.7` → `1.9.0` data l'ampiezza del cambiamento)
