# Design: serie multi-fonte — selettore fonte nella scheda, ricerca raggruppata, identità canonica

Data: 2026-07-19 · Branch: `dev` · Stato: approvato a voce, in attesa di revisione scritta

## Obiettivo

Preparare l'app a molti più server manga rendendo la **serie** (non la coppia fonte+URL)
l'unità che l'utente segue:

1. **Selettore fonte nella scheda manga**: un select Material sotto il titolo per cambiare
   server della stessa serie, con confronto integrato (capitoli disponibili, ultimo uscito).
2. **Ricerca aggregata raggruppata**: la stessa serie trovata su N fonti diventa una card
   sola, usando AniList come "colla" tra titoli in lingue diverse.
3. **Identità canonica di serie**: preferiti, progressi di lettura e tracking AniList
   appartengono alla serie e sopravvivono al cambio fonte.
4. **Toggle per-fonte nelle impostazioni**: l'utente sceglie quali server partecipano.

Fuori scope (esplicito): l'aggiunta dei nuovi server (una task per fonte con la skill
`add-manga-source`), confronto qualità immagini, auto-selezione della "fonte migliore",
merge manuale di card direttamente dalla ricerca. Nessun commit automatico.

## Contesto attuale (verificato sul codice)

- Ricerca già aggregata multi-fonte con filtro lingua: `runAggregatedSearch`
  (`MangaViewModel.kt:2233`), merge round-robin `interleaveBySource` (`MangaSources.kt:107`).
- Identità attuale fonte-dipendente: `identityKey = "$sourceId::$normalizedUrl"`
  (`MangaSources.kt:160`) — àncora preferiti, tracking AniList (`AniListStore`),
  progressi streaming, chiavi griglia ricerca.
- Ponte Scopri debole: `onPickAniListManga` (`MangaViewModel.kt:2458`) inietta solo il
  titolo come query testuale; il `mediaId` AniList si perde.
- Fallback parziali già esistenti da riusare: `identityKeyOrNull(..., title)` a base titolo
  (`MangaSources.kt:169`), matching libreria per titolo + numero capitolo
  (`LibraryMatching.kt:44`), re-risoluzione `sourceId` in lettura (`FavoritesStore.kt:22`).
- 4 fonti registrate: Mangapill (ENG), Hasta Team (ITA), MangaWorld (ITA), VyManga (ENG).
- Persistenza: SharedPreferences + JSON tipizzato (`SharedPreferencesJson.kt`), niente Room.

## Decisioni prese (con l'utente)

| Tema | Decisione |
| --- | --- |
| Ruolo AniList in ricerca | Ibrido: fan-out sulle fonti resta, AniList raggruppa i risultati |
| Selettore fonte | Informativo: nome, lingua, n° capitoli, ultimo capitolo (lazy) |
| Identità | Canonica di serie: `anilist:<id>` / fallback `title:<normalizzato>` |
| Fonti attive | Toggle per-fonte nelle impostazioni, tutte attive di default |
| Approccio matching | A: chiave serie + AniList come colla; match conservativo; degradazione senza AniList |

## 1. Identità canonica e SeriesLinksStore

### SeriesKey

- `anilist:<mediaId>` quando la serie è agganciata ad AniList.
- `title:<titolo-normalizzato>` altrimenti (doujin, scan minori, AniList offline).
- Una chiave `title:` è **promossa** ad `anilist:` appena arriva l'aggancio (match in
  ricerca confermato da un tap, o collegamento tracking manuale): lo store riscrive la
  chiave e un helper di re-key migra le voci dipendenti (preferiti, tracking, progressi).

### SeriesLinksStore (nuovo)

Stesso pattern degli store esistenti (SharedPreferences + `@Serializable` JSON):

```kotlin
@Serializable
data class SeriesSourceBinding(val sourceId: String, val mangaUrl: String, val addedAt: Long)

@Serializable
data class SeriesLink(
    val seriesKey: String,          // "anilist:30013" | "title:one piece"
    val aniListId: Int?,
    val canonicalTitle: String,
    val coverUrl: String?,
    val sources: List<SeriesSourceBinding>,
    val preferredSourceId: String?,
)
```

- Indice inverso in memoria `(sourceId, normalizedUrl) → seriesKey` per lookup O(1).
- Scritture solo su azioni esplicite (tap su card raggruppata, aggancio/scollegamento
  fonte, cambio fonte) — mai durante la digitazione in ricerca.

### Ri-ancoraggio degli store esistenti

| Store | Oggi chiavato su | Diventa | Migrazione |
| --- | --- | --- | --- |
| Preferiti (`FavoritesStore`) | `sourceId::url` | SeriesKey | Lazy in lettura: voce con tracking esistente → `anilist:<mediaId>`; altrimenti `title:` dal titolo salvato |
| Tracking AniList (`AniListStore`) | `identityKey` | SeriesKey | Immediata in lettura: `mediaId` già presente nel valore → `anilist:<mediaId>` |
| Progressi streaming (`LibraryRepository.streamingReadChapterIds`) | `(sourceId, mangaUrl)` + id capitolo URL-derivati | SeriesKey + id capitolo **a base numero** | In lettura: merge del set legacy per-fonte nel set per-serie |
| `series.json` download (`SeriesMetadata`) | cartella + `sourceId` | invariato + campo opzionale `seriesKey` | Additiva: campo scritto ai prossimi download; `LibraryMatching` matcha per seriesKey prima, poi fallback titolo esistente |

I download restano fisicamente legati alla fonte da cui sono stati scaricati (i CBZ sono
quelli); cambia solo come la libreria li riconduce alla serie.

> Nota di implementazione (2026-07-19): il campo `seriesKey` in `series.json` è risultato
> superfluo — il matching libreria riceve direttamente i binding del `SeriesLink`
> (`LibraryMatching.matchingDownloadedSeries(..., extraBindings)`), che copre le serie già
> scaricate senza migrare i file su disco.

Nota: le voci preferite conservano comunque un binding fonte (serve per aprire la
scheda), ma identità e dedup usano la SeriesKey — aggiungere ai preferiti la stessa
serie da due fonti diverse produce una voce sola.

## 2. Ricerca raggruppata

Pipeline (estende `runAggregatedSearch`, il debounce esistente resta):

1. Fan-out parallelo sulle fonti **abilitate** (nuovo filtro `disabledSourceIds`)
   nell'ambito lingua corrente, come oggi.
2. In parallelo, **1 chiamata** di ricerca AniList (client esistente; la query GraphQL
   viene estesa con `synonyms` e titolo nativo).
3. **Matcher conservativo**: titolo del risultato fonte normalizzato (lowercase, accenti
   rimossi via NFKD, punteggiatura eliminata, spazi collassati) confrontato per
   **uguaglianza esatta** con il set titoli di ogni candidato AniList
   {romaji, english, nativo, sinonimi}. Niente fuzzy nella prima versione: meglio due
   card separate che un raggruppamento sbagliato.
4. **Raggruppamento**:
   - match sullo stesso media AniList → una card con cover/titolo AniList e badge "N fonti";
   - non matchati → raggruppati tra loro per titolo normalizzato;
   - singoli → card singola con badge fonte come oggi.
5. **Degradazione**: AniList giù/lenta → si salta il passo 2-3 e si raggruppa per solo
   titolo. La ricerca non dipende mai da AniList per funzionare.
6. Il raggruppamento in ricerca è **effimero**: il `SeriesLink` si crea/aggiorna solo al
   tap sulla card.

**Ponte Scopri potenziato**: `onPickAniListManga` porta con sé il `mediaId` — il matcher
è pre-seedato su quel media e i risultati coerenti confluiscono nella card unica della
serie, invece dell'attuale "titolo incollato nella barra di ricerca".

## 3. Scheda manga: selettore fonte

- **Posizione**: sotto il titolo nel `SeriesHeader` (`DetailScreen.kt:96`), select Material
  (exposed dropdown) in stile M3 Expressive (skill `app-ui`), etichetta = nome fonte + lingua.
- **Fonte iniziale**: `preferredSourceId` del `SeriesLink` → altrimenti fonte coerente col
  filtro lingua corrente → altrimenti la prima. Da card singola: la fonte della card.
- **Menu aperto** (lazy al primo apri, richieste in parallelo, cache di sessione):
  per ogni fonte collegata → nome, lingua, "N capitoli · ultimo: cap. X" da
  `fetchMangaDetails`; spinner per voce in caricamento, "non raggiungibile" per voce in
  errore senza rompere le altre. Questo è il confronto tra server.
- **Cambio fonte**: ricarica capitoli dalla fonte scelta (percorso `selectManga` con nuovo
  binding), salva `preferredSourceId`. Letti/scaricati preservati via SeriesKey + matching
  per numero (`LibraryMatching` esteso). Download e streaming usano la fonte attiva.
  La riga tracking AniList resta identica, ora ancorata alla SeriesKey.
- **"Cerca su altre fonti…"** (ultima voce del menu): cerca titolo + sinonimi AniList
  sulle fonti abilitate non ancora collegate, risultati in bottom sheet, il tap aggiunge
  il binding. Dallo stesso sheet si può scollegare una fonte agganciata per errore.

## 4. Impostazioni: toggle fonti

- Nuova sezione "Fonti": elenco delle fonti registrate (nome, lingua, sito) con switch,
  tutte attive di default (componenti `SettingsComponents.kt`).
- Nuovo campo `AppSettings.disabledSourceIds: Set<String>` + serializzazione in
  `SettingsStore` e `BackupSchema`.
- `descriptorsForScope` (o un wrapper) filtra le disabilitate → ricerca aggregata,
  "Cerca su altre fonti" e selettore le ignorano. Le fonti disabilitate con binding già
  salvati restano leggibili dalla scheda (non si nasconde ciò che l'utente ha già).
- Guardia: l'ultima fonte attiva non è disabilitabile.

## 5. Gestione errori

- Fonte che fallisce in ricerca → `runCatching` → lista vuota, silenzioso (come oggi).
- AniList giù → degradazione a raggruppamento per titolo; selettore funziona con i
  binding salvati.
- Voce selettore irraggiungibile → errore localizzato alla singola voce.
- Match sbagliato → correzione manuale da "Cerca su altre fonti…" (scollega/aggancia).

## 6. Test

Pattern della skill `android-testing`:

- **Unit JVM**: normalizzazione titoli (ITA/ENG, accenti, punteggiatura); matcher
  (match esatto, sinonimi, non-match, pre-seed mediaId); raggruppamento (gruppi, singoli,
  degradazione senza AniList); promozione `title:` → `anilist:` con re-key.
- **Robolectric**: `SeriesLinksStore` round-trip; migrazione lazy preferiti/tracking/
  progressi; `disabledSourceIds` in settings + backup round-trip.
- **ViewModel**: cambio fonte (capitoli ricaricati, preferred salvato, letti/scaricati
  preservati); ricerca che rispetta le fonti disabilitate; ponte Scopri con mediaId.

## File principali da toccare

| Area | File |
| --- | --- |
| Identità + registry | `MangaSources.kt` (SeriesKey, catalogo filtrato per abilitate) |
| Nuovo store | `SeriesLinksStore.kt` (nuovo), `SharedPreferencesJson.kt` (riuso) |
| Ricerca | `MangaViewModel.kt` (`runAggregatedSearch`, stato risultati raggruppati), `SearchScreen.kt` (card raggruppata) |
| AniList | `AniListClient.kt` (synonyms/native nella query), `MangaViewModel.kt` (`onPickAniListManga`), `AniListTracking.kt`/`AniListStore` (re-key) |
| Scheda | `DetailScreen.kt` (selettore + sheet fonti), `MangaViewModel.kt` (stato selettore, cambio fonte) |
| Libreria/progressi | `LibraryRepository.kt` (`SeriesMetadata.seriesKey`, progressi per-serie), `LibraryMatching.kt` |
| Impostazioni | `SettingsComponents.kt`, `SettingsStore.kt`, `BackupSchema.kt`, `MangaViewModel.kt` (AppSettings) |
| Preferiti | `FavoritesStore.kt`, `MangaViewModel.kt` (`FavoriteManga`) |
