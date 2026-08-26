# Serie multi-fonte — Piano di implementazione

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** selettore fonte nella scheda manga, ricerca aggregata raggruppata per serie via AniList, identità canonica di serie (`anilist:<id>` / `title:<norm>`), toggle per-fonte nelle impostazioni.

**Architecture:** nuova identità `SeriesIdentity` + store `SeriesLinksStore` (serie → fonti collegate) sopra l'attuale `identityKey = sourceId::url`; il matching titoli usa i sinonimi AniList; preferiti/tracking/progressi si ri-ancorano alla SeriesKey con migrazioni lazy in lettura. Spec: `docs/superpowers/specs/2026-07-19-multi-source-series-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose (M3 Expressive), SharedPreferences + kotlinx-serialization JSON, OkHttp/Jsoup, JUnit4 + Robolectric.

## Global Constraints

- **NESSUN COMMIT**: l'utente lo ha chiesto esplicitamente. Ogni task termina con i test verdi, mai con `git commit`.
- Package unico piatto: `com.lorenzo.mangadownloader` — tutti i file sotto `android-app/app/src/main/java/com/lorenzo/mangadownloader/` (test sotto `android-app/app/src/test/java/com/lorenzo/mangadownloader/`). Nei percorsi qui sotto si indica solo il nome file.
- Testi UI in **italiano**, stile M3 Expressive del progetto (skill `app-ui`); componenti settings riusano `SettingsSection`/`SettingRow` di `SettingsComponents.kt`.
- Persistenza: solo SharedPreferences + `@Serializable` via `readJson`/`writeJson` (`SharedPreferencesJson.kt`). Niente Room, niente nuove dipendenze.
- Formato su disco retrocompatibile: mai rompere la lettura di prefs/backup esistenti; migrazioni lazy in lettura (pattern `FavoritesStore.read()`).
- Comandi di verifica (PowerShell, dalla root del repo):
  - compile check: `& "android-app\gradlew.bat" -p android-app :app:compileDebugKotlin`
  - unit test mirati: `& "android-app\gradlew.bat" -p android-app :app:testDebugUnitTest --tests "com.lorenzo.mangadownloader.<NomeTest>"`
  - tutti i test: `& "android-app\gradlew.bat" -p android-app :app:testDebugUnitTest`
- Test: seguire la skill `android-testing` (JVM puri quando possibile; Robolectric per SharedPreferences/ViewModel; attenzione al looper in pausa nei test ViewModel).

---

### Task 1: SeriesIdentity — normalizzazione titoli e chiavi serie

**Files:**
- Create: `SeriesIdentity.kt`
- Test: `SeriesIdentityTest.kt`

**Interfaces:**
- Consumes: niente (oggetto puro).
- Produces (usati da tutti i task successivi):
  - `SeriesIdentity.normalizeTitle(raw: String): String`
  - `SeriesIdentity.keyForAniList(mediaId: Int): String` → `"anilist:<id>"`
  - `SeriesIdentity.keyForTitle(title: String): String?` → `"title:<normalizzato>"`, `null` se il normalizzato è vuoto
  - `SeriesIdentity.aniListIdFromKey(seriesKey: String): Int?`

- [ ] **Step 1: test fallente**

```kotlin
package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesIdentityTest {

    @Test
    fun `normalizza minuscole accenti e punteggiatura`() {
        assertEquals("lattacco dei giganti", SeriesIdentity.normalizeTitle("L'Attacco dei Giganti!"))
        assertEquals("shingeki no kyojin", SeriesIdentity.normalizeTitle("  Shingeki no Kyojin  "))
        assertEquals("perche no", SeriesIdentity.normalizeTitle("Perché... nò?"))
    }

    @Test
    fun `collassa spazi multipli`() {
        assertEquals("one piece", SeriesIdentity.normalizeTitle("One   Piece"))
    }

    @Test
    fun `chiavi anilist e title`() {
        assertEquals("anilist:30013", SeriesIdentity.keyForAniList(30013))
        assertEquals("title:one piece", SeriesIdentity.keyForTitle("One Piece!"))
        assertNull(SeriesIdentity.keyForTitle("  ...  "))
    }

    @Test
    fun `estrae aniListId dalla chiave`() {
        assertEquals(30013, SeriesIdentity.aniListIdFromKey("anilist:30013"))
        assertNull(SeriesIdentity.aniListIdFromKey("title:one piece"))
        assertNull(SeriesIdentity.aniListIdFromKey("anilist:abc"))
    }
}
```

- [ ] **Step 2: eseguire il test e verificarne il fallimento**

Run: `& "android-app\gradlew.bat" -p android-app :app:testDebugUnitTest --tests "com.lorenzo.mangadownloader.SeriesIdentityTest"`
Expected: FAIL (unresolved reference `SeriesIdentity`).

- [ ] **Step 3: implementazione**

```kotlin
package com.lorenzo.mangadownloader

import java.text.Normalizer
import java.util.Locale

/**
 * Identità canonica di una serie, indipendente dalla fonte: `anilist:<mediaId>` quando la
 * serie è agganciata ad AniList, altrimenti `title:<titolo-normalizzato>`. Sta sopra
 * all'identityKey per-fonte di [MangaSourceCatalog] (che resta per capitoli/URL) e permette
 * a preferiti, tracking e progressi di sopravvivere al cambio fonte.
 */
object SeriesIdentity {
    const val ANILIST_PREFIX = "anilist:"
    const val TITLE_PREFIX = "title:"

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Normalizzazione conservativa per il confronto titoli tra fonti/lingue: minuscole,
     * accenti rimossi (NFKD), tutto ciò che non è lettera/cifra diventa spazio, spazi collassati.
     */
    fun normalizeTitle(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
        return decomposed
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    fun keyForAniList(mediaId: Int): String = "$ANILIST_PREFIX$mediaId"

    fun keyForTitle(title: String): String? {
        val normalized = normalizeTitle(title).takeIf(String::isNotBlank) ?: return null
        return "$TITLE_PREFIX$normalized"
    }

    fun aniListIdFromKey(seriesKey: String): Int? {
        return seriesKey.removePrefix(ANILIST_PREFIX)
            .takeIf { it != seriesKey }
            ?.toIntOrNull()
    }
}
```

- [ ] **Step 4: eseguire il test e verificarne il successo**

Run: come Step 2. Expected: PASS.

---

### Task 2: AniListManga con sinonimi e titolo nativo

**Files:**
- Modify: `AniListClient.kt` (data class `AniListManga` righe 29-51, `SEARCH_QUERY` righe 240-256, `parseMangaObject` righe 331-354)
- Test: `AniListClientTest.kt` (aggiunte)

**Interfaces:**
- Consumes: niente di nuovo.
- Produces:
  - `AniListManga.titleNative: String?` e `AniListManga.synonyms: List<String>` (nuovi campi con default, nessun call-site esistente da toccare)
  - `AniListManga.allTitles(): List<String>` — tutti i titoli noti non-blank {english, romaji, native, sinonimi}

- [ ] **Step 1: test fallente** (aggiungere in `AniListClientTest.kt`)

```kotlin
    @Test
    fun `parseMediaResponse legge native e synonyms`() {
        val json = """
            {"data":{"Page":{"media":[{
                "id":53390,
                "title":{"romaji":"Shingeki no Kyojin","english":"Attack on Titan","native":"進撃の巨人"},
                "synonyms":["L'attacco dei giganti","AoT"],
                "coverImage":{"large":"https://img/aot.jpg"},
                "genres":["Action"],"averageScore":84,"description":"...","status":"FINISHED"
            }]}}}
        """.trimIndent()
        val parsed = AniListClient.parseMediaResponse(json).single()
        assertEquals("進撃の巨人", parsed.titleNative)
        assertEquals(listOf("L'attacco dei giganti", "AoT"), parsed.synonyms)
        assertEquals(
            listOf("Attack on Titan", "Shingeki no Kyojin", "進撃の巨人", "L'attacco dei giganti", "AoT"),
            parsed.allTitles(),
        )
    }

    @Test
    fun `native e synonyms assenti restano vuoti`() {
        val json = """
            {"data":{"Page":{"media":[{
                "id":1,"title":{"romaji":"X","english":null}
            }]}}}
        """.trimIndent()
        val parsed = AniListClient.parseMediaResponse(json).single()
        assertNull(parsed.titleNative)
        assertTrue(parsed.synonyms.isEmpty())
    }
```

- [ ] **Step 2: eseguirlo, verificarne il fallimento** (unresolved `titleNative`)

- [ ] **Step 3: implementazione**

In `AniListManga` aggiungere i campi (dopo `titleEnglish`) e il metodo:

```kotlin
    val titleNative: String? = null,
    /** Titoli alternativi noti ad AniList (spesso includono il titolo italiano). */
    val synonyms: List<String> = emptyList(),
```

```kotlin
    /** Tutti i titoli noti, in ordine di preferenza, per il matching tra fonti. */
    fun allTitles(): List<String> =
        (listOfNotNull(titleEnglish, titleRomaji, titleNative) + synonyms)
            .map(String::trim)
            .filter(String::isNotBlank)
```

Nota: i nuovi campi vanno inseriti PRIMA degli attuali `chapters`/`format` mantenendo tutti i default, così nessun call-site esistente si rompe.

In `SEARCH_QUERY` sostituire `title { romaji english }` con `title { romaji english native }` e aggiungere `synonyms` alla selezione (riga sotto `title`). Le altre query (QUERY, RECOMMENDATIONS_QUERY) restano invariate: i campi hanno default.

In `parseMangaObject` aggiungere (dopo il parsing di `english`):

```kotlin
            val native = title?.get("native")?.jsonPrimitive?.contentOrNull?.trim()
```

e nel costruttore di `AniListManga`:

```kotlin
                titleNative = native?.takeIf(String::isNotBlank),
                synonyms = obj["synonyms"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                    ?: emptyList(),
```

- [ ] **Step 4: eseguire `AniListClientTest`**, Expected: PASS (anche i test preesistenti).

---

### Task 3: SeriesGrouping — raggruppamento dei risultati di ricerca

**Files:**
- Create: `SeriesGrouping.kt`
- Test: `SeriesGroupingTest.kt`

**Interfaces:**
- Consumes: `SeriesIdentity` (Task 1), `AniListManga.allTitles()` (Task 2), `MangaSearchResult`.
- Produces:
  - `data class GroupedSearchResult(seriesKey: String, aniListId: Int?, title: String, coverUrl: String?, results: List<MangaSearchResult>)` con `val primary: MangaSearchResult get() = results.first()`
  - `SeriesGrouping.groupResults(results: List<MangaSearchResult>, aniListCandidates: List<AniListManga>): List<GroupedSearchResult>`

- [ ] **Step 1: test fallente**

```kotlin
package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesGroupingTest {

    private fun result(sourceId: String, title: String, url: String) =
        MangaSearchResult(sourceId = sourceId, title = title, mangaUrl = url, coverUrl = null)

    private fun candidate(id: Int, english: String?, romaji: String?, synonyms: List<String> = emptyList()) =
        AniListManga(
            id = id, titleRomaji = romaji, titleEnglish = english, titleNative = null,
            synonyms = synonyms, coverUrl = "https://anilist/cover$id.jpg", genres = emptyList(),
            averageScore = null, description = null, status = MangaPublicationStatus.UNKNOWN,
        )

    @Test
    fun `raggruppa ITA e ENG sotto lo stesso media via sinonimi`() {
        val results = listOf(
            result("mangapill", "Attack on Titan", "https://mangapill.com/manga/1"),
            result("manga_world", "L'Attacco dei Giganti", "https://www.mangaworld.mx/manga/2"),
        )
        val candidates = listOf(
            candidate(53390, "Attack on Titan", "Shingeki no Kyojin", synonyms = listOf("L'attacco dei giganti")),
        )
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals(1, groups.size)
        assertEquals("anilist:53390", groups.single().seriesKey)
        assertEquals(2, groups.single().results.size)
        assertEquals("Attack on Titan", groups.single().title)
        assertEquals("https://anilist/cover53390.jpg", groups.single().coverUrl)
    }

    @Test
    fun `senza candidati degrada al raggruppamento per titolo`() {
        val results = listOf(
            result("mangapill", "One Piece", "https://mangapill.com/manga/3"),
            result("vymanga", "One Piece!", "https://vymanga.com/manga/4"),
            result("manga_world", "Naruto", "https://www.mangaworld.mx/manga/5"),
        )
        val groups = SeriesGrouping.groupResults(results, emptyList())
        assertEquals(2, groups.size)
        assertEquals("title:one piece", groups[0].seriesKey)
        assertNull(groups[0].aniListId)
        assertEquals(2, groups[0].results.size)
        assertEquals("title:naruto", groups[1].seriesKey)
    }

    @Test
    fun `titolo non matchato resta card singola e ordine preservato`() {
        val results = listOf(
            result("mangapill", "Doujin Sconosciuto", "https://mangapill.com/manga/6"),
            result("mangapill", "Attack on Titan", "https://mangapill.com/manga/1"),
        )
        val candidates = listOf(candidate(53390, "Attack on Titan", null))
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals(2, groups.size)
        assertEquals("title:doujin sconosciuto", groups[0].seriesKey)
        assertEquals("anilist:53390", groups[1].seriesKey)
    }

    @Test
    fun `match solo per uguaglianza normalizzata esatta`() {
        val results = listOf(result("mangapill", "Attack on Titan: Before the Fall", "https://mangapill.com/manga/7"))
        val candidates = listOf(candidate(53390, "Attack on Titan", null))
        val groups = SeriesGrouping.groupResults(results, candidates)
        assertEquals("title:attack on titan before the fall", groups.single().seriesKey)
    }

    @Test
    fun `cover di gruppo ripiega sulla prima fonte se AniList non la ha`() {
        val results = listOf(
            MangaSearchResult("mangapill", "Attack on Titan", "https://mangapill.com/manga/1", "https://mp/cover.jpg"),
        )
        val noCover = candidate(53390, "Attack on Titan", null).copy(coverUrl = null)
        val groups = SeriesGrouping.groupResults(results, listOf(noCover))
        assertEquals("https://mp/cover.jpg", groups.single().coverUrl)
    }
}
```

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione**

```kotlin
package com.lorenzo.mangadownloader

/**
 * Un gruppo di risultati di ricerca che appartengono alla stessa serie (una card in UI).
 * [seriesKey] è la chiave canonica ([SeriesIdentity]); [results] preserva l'ordine di
 * arrivo dall'interleave delle fonti. Il raggruppamento è effimero: il [SeriesLink]
 * persistito nasce solo al tap sulla card.
 */
data class GroupedSearchResult(
    val seriesKey: String,
    val aniListId: Int?,
    val title: String,
    val coverUrl: String?,
    val results: List<MangaSearchResult>,
) {
    val primary: MangaSearchResult get() = results.first()
}

/**
 * Matcher conservativo fonte↔AniList: un risultato entra in un gruppo AniList solo se il suo
 * titolo normalizzato è ESATTAMENTE uno dei titoli/sinonimi del candidato. Niente fuzzy:
 * meglio due card separate che un raggruppamento sbagliato. Con [aniListCandidates] vuoto
 * (AniList giù) degrada al raggruppamento per solo titolo normalizzato.
 */
object SeriesGrouping {

    fun groupResults(
        results: List<MangaSearchResult>,
        aniListCandidates: List<AniListManga>,
    ): List<GroupedSearchResult> {
        // Primo candidato che rivendica un titolo vince (i candidati arrivano già in ordine
        // di rilevanza AniList; un eventuale media "pinnato" dal ponte Scopri è in testa).
        val titleToCandidate = LinkedHashMap<String, AniListManga>()
        aniListCandidates.forEach { candidate ->
            candidate.allTitles().forEach { title ->
                val normalized = SeriesIdentity.normalizeTitle(title)
                if (normalized.isNotBlank()) {
                    titleToCandidate.putIfAbsent(normalized, candidate)
                }
            }
        }

        val groups = LinkedHashMap<String, MutableList<MangaSearchResult>>()
        val candidateByKey = HashMap<String, AniListManga>()
        results.forEach { result ->
            val normalized = SeriesIdentity.normalizeTitle(result.title)
            val candidate = titleToCandidate[normalized]
            val key = when {
                candidate != null -> SeriesIdentity.keyForAniList(candidate.id)
                    .also { candidateByKey[it] = candidate }
                else -> SeriesIdentity.keyForTitle(result.title)
                    ?: MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
            }
            groups.getOrPut(key) { mutableListOf() }.add(result)
        }

        return groups.map { (key, members) ->
            val candidate = candidateByKey[key]
            GroupedSearchResult(
                seriesKey = key,
                aniListId = candidate?.id,
                title = candidate?.displayTitle() ?: members.first().title,
                coverUrl = candidate?.coverUrl ?: members.firstNotNullOfOrNull { it.coverUrl },
                results = members.toList(),
            )
        }
    }
}
```

- [ ] **Step 4: eseguire `SeriesGroupingTest`**, Expected: PASS.

---

### Task 4: SeriesLinksStore — persistenza serie → fonti collegate

**Files:**
- Create: `SeriesLinksStore.kt`
- Test: `SeriesLinksStoreTest.kt` (Robolectric, pattern degli altri store test tipo `FavoriteUpdatesStoreTest`)

**Interfaces:**
- Consumes: `SeriesIdentity`, `GroupedSearchResult`, `MangaSourceCatalog.normalizeSeriesUrl/identityKey`, `readJson`/`writeJson`.
- Produces (per i task 8-14):
  - `data class SeriesSourceBinding(sourceId: String, mangaUrl: String, addedAt: Long = 0L)`
  - `data class SeriesLink(seriesKey: String, aniListId: Int?, canonicalTitle: String, coverUrl: String?, sources: List<SeriesSourceBinding>, preferredSourceId: String? = null)`
  - `SeriesLink.initialBinding(scope: SearchScope): SeriesSourceBinding` — preferred → lingua dello scope → prima
  - `class SeriesLinksStore(prefs)` con: `readAll(): Map<String, SeriesLink>`, `linkFor(seriesKey): SeriesLink?`, `linkForBinding(sourceId, mangaUrl): SeriesLink?`, `upsert(link)`, `mergeFromGroup(group: GroupedSearchResult, now: Long): SeriesLink`, `setPreferredSource(seriesKey, sourceId)`, `addBinding(seriesKey, binding)`, `removeBinding(seriesKey, sourceId)`, `seriesKeyFor(sourceId, mangaUrl, title): String`
  - Chiave prefs: `"series_links_json"`

- [ ] **Step 1: test fallente**

```kotlin
package com.lorenzo.mangadownloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeriesLinksStoreTest {

    private lateinit var store: SeriesLinksStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        store = SeriesLinksStore(prefs)
    }

    private fun group(key: String, aniListId: Int?, vararg bindings: Pair<String, String>) =
        GroupedSearchResult(
            seriesKey = key,
            aniListId = aniListId,
            title = "Attack on Titan",
            coverUrl = "https://cover",
            results = bindings.map { (sourceId, url) ->
                MangaSearchResult(sourceId, "Attack on Titan", url, null)
            },
        )

    @Test
    fun `mergeFromGroup crea il link e round-trip su prefs`() {
        store.mergeFromGroup(group("anilist:53390", 53390, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        val link = store.linkFor("anilist:53390")!!
        assertEquals(53390, link.aniListId)
        assertEquals(1, link.sources.size)
        assertEquals("mangapill", link.sources.single().sourceId)
    }

    @Test
    fun `mergeFromGroup unisce i binding senza duplicati e conserva preferred`() {
        store.mergeFromGroup(group("anilist:53390", 53390, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        store.setPreferredSource("anilist:53390", "mangapill")
        store.mergeFromGroup(
            group(
                "anilist:53390", 53390,
                "mangapill" to "https://mangapill.com/manga/1",
                "manga_world" to "https://www.mangaworld.mx/manga/2",
            ),
            now = 200L,
        )
        val link = store.linkFor("anilist:53390")!!
        assertEquals(2, link.sources.size)
        assertEquals("mangapill", link.preferredSourceId)
    }

    @Test
    fun `mergeFromGroup con aniListId promuove il link title esistente`() {
        store.mergeFromGroup(group("title:attack on titan", null, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        store.mergeFromGroup(group("anilist:53390", 53390, "mangapill" to "https://mangapill.com/manga/1"), now = 200L)
        assertNull(store.linkFor("title:attack on titan"))
        assertEquals(1, store.linkFor("anilist:53390")!!.sources.size)
    }

    @Test
    fun `linkForBinding trova per sourceId e url normalizzato`() {
        store.mergeFromGroup(group("anilist:53390", 53390, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        assertEquals("anilist:53390", store.linkForBinding("mangapill", "https://mangapill.com/manga/1")?.seriesKey)
        assertNull(store.linkForBinding("vymanga", "https://vymanga.com/manga/9"))
    }

    @Test
    fun `seriesKeyFor ripiega sul titolo quando non esiste un link`() {
        assertEquals("title:one piece", store.seriesKeyFor("mangapill", "https://mangapill.com/manga/9", "One Piece"))
    }

    @Test
    fun `add e remove binding`() {
        store.mergeFromGroup(group("anilist:53390", 53390, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        store.addBinding("anilist:53390", SeriesSourceBinding("vymanga", "https://vymanga.com/manga/3", 300L))
        assertEquals(2, store.linkFor("anilist:53390")!!.sources.size)
        store.removeBinding("anilist:53390", "vymanga")
        assertEquals(1, store.linkFor("anilist:53390")!!.sources.size)
    }

    @Test
    fun `initialBinding preferred poi lingua poi prima`() {
        val link = SeriesLink(
            seriesKey = "anilist:1", aniListId = 1, canonicalTitle = "X", coverUrl = null,
            sources = listOf(
                SeriesSourceBinding("mangapill", "https://mangapill.com/manga/1"),
                SeriesSourceBinding("manga_world", "https://www.mangaworld.mx/manga/2"),
            ),
        )
        assertEquals("mangapill", link.initialBinding(SearchScope.ALL).sourceId)
        assertEquals("manga_world", link.initialBinding(SearchScope.ITA).sourceId)
        assertEquals("manga_world", link.copy(preferredSourceId = "manga_world").initialBinding(SearchScope.ENG).sourceId)
    }
}
```

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione**

```kotlin
package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.Serializable

/** Un aggancio serie→fonte: dove trovare questa serie su un certo server. */
data class SeriesSourceBinding(
    val sourceId: String,
    val mangaUrl: String,
    val addedAt: Long = 0L,
)

/**
 * Il legame persistito tra una serie canonica ([SeriesIdentity]) e le fonti su cui è stata
 * trovata. [preferredSourceId] è l'ultima fonte scelta nel selettore della scheda.
 */
data class SeriesLink(
    val seriesKey: String,
    val aniListId: Int?,
    val canonicalTitle: String,
    val coverUrl: String?,
    val sources: List<SeriesSourceBinding>,
    val preferredSourceId: String? = null,
)

/** Fonte iniziale all'apertura della scheda: preferita → coerente con lo scope → prima. */
fun SeriesLink.initialBinding(scope: SearchScope): SeriesSourceBinding {
    preferredSourceId?.let { preferred ->
        sources.firstOrNull { it.sourceId == preferred }?.let { return it }
    }
    scope.language?.let { language ->
        sources.firstOrNull { MangaSourceCatalog.languageOf(it.sourceId) == language }?.let { return it }
    }
    return sources.first()
}

/**
 * Persistenza dei [SeriesLink] su [SharedPreferences] (pattern degli altri store). Le
 * scritture avvengono solo su azioni esplicite (tap su card raggruppata, cambio fonte,
 * aggancio/scollegamento manuale), mai durante la digitazione in ricerca.
 */
class SeriesLinksStore(private val prefs: SharedPreferences) {

    fun readAll(): Map<String, SeriesLink> {
        return prefs.readJson<Map<String, LinkJson>>(KEY_SERIES_LINKS, emptyMap())
            .filterValues { it.sources.isNotEmpty() && it.seriesKey.isNotBlank() }
            .mapValues { (_, entry) ->
                SeriesLink(
                    seriesKey = entry.seriesKey,
                    aniListId = entry.aniListId,
                    canonicalTitle = entry.canonicalTitle,
                    coverUrl = entry.coverUrl,
                    sources = entry.sources.map { SeriesSourceBinding(it.sourceId, it.mangaUrl, it.addedAt) },
                    preferredSourceId = entry.preferredSourceId,
                )
            }
    }

    fun linkFor(seriesKey: String): SeriesLink? = readAll()[seriesKey]

    /** Il link che contiene il binding (sourceId, url), confrontando l'URL normalizzato. */
    fun linkForBinding(sourceId: String, mangaUrl: String): SeriesLink? {
        val targetKey = MangaSourceCatalog.identityKey(sourceId, mangaUrl)
        return readAll().values.firstOrNull { link ->
            link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey }
        }
    }

    /**
     * Chiave serie per una tripla (fonte, url, titolo): la chiave del link esistente se il
     * binding è già noto, altrimenti la chiave-titolo; ultima spiaggia l'identityKey legacy.
     */
    fun seriesKeyFor(sourceId: String, mangaUrl: String, title: String): String {
        linkForBinding(sourceId, mangaUrl)?.let { return it.seriesKey }
        return SeriesIdentity.keyForTitle(title)
            ?: MangaSourceCatalog.identityKey(sourceId, mangaUrl)
    }

    fun upsert(link: SeriesLink) {
        persistAll(readAll() + (link.seriesKey to link))
    }

    /**
     * Crea/aggiorna il link a partire da una card raggruppata tappata: unione dei binding
     * (dedup per identityKey), preferred conservato. Se il gruppo ha un aniListId e i suoi
     * binding vivevano sotto una chiave `title:`, quel link viene PROMOSSO (ri-chiavato).
     */
    fun mergeFromGroup(group: GroupedSearchResult, now: Long): SeriesLink {
        val all = readAll().toMutableMap()
        val existing = all[group.seriesKey]
            ?: group.results.firstNotNullOfOrNull { result ->
                // Promozione title: → anilist:: il vecchio link viene assorbito.
                all.values.firstOrNull { candidate ->
                    candidate.seriesKey.startsWith(SeriesIdentity.TITLE_PREFIX) &&
                        candidate.sources.any {
                            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) ==
                                MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
                        }
                }
            }?.also { all.remove(it.seriesKey) }

        val mergedSources = LinkedHashMap<String, SeriesSourceBinding>()
        existing?.sources?.forEach {
            mergedSources[MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl)] = it
        }
        group.results.forEach { result ->
            val key = MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
            mergedSources.putIfAbsent(key, SeriesSourceBinding(result.sourceId, result.mangaUrl, now))
        }

        val merged = SeriesLink(
            seriesKey = group.seriesKey,
            aniListId = group.aniListId ?: existing?.aniListId,
            canonicalTitle = group.title,
            coverUrl = group.coverUrl ?: existing?.coverUrl,
            sources = mergedSources.values.toList(),
            preferredSourceId = existing?.preferredSourceId,
        )
        all[group.seriesKey] = merged
        persistAll(all)
        return merged
    }

    fun setPreferredSource(seriesKey: String, sourceId: String) {
        val link = linkFor(seriesKey) ?: return
        upsert(link.copy(preferredSourceId = sourceId))
    }

    fun addBinding(seriesKey: String, binding: SeriesSourceBinding) {
        val link = linkFor(seriesKey) ?: return
        val key = MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl)
        if (link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == key }) return
        upsert(link.copy(sources = link.sources + binding))
    }

    fun removeBinding(seriesKey: String, sourceId: String) {
        val link = linkFor(seriesKey) ?: return
        val remaining = link.sources.filterNot { it.sourceId == sourceId }
        if (remaining.isEmpty() || remaining.size == link.sources.size) return
        upsert(link.copy(sources = remaining))
    }

    private fun persistAll(links: Map<String, SeriesLink>) {
        val payload = links.mapValues { (_, link) ->
            LinkJson(
                seriesKey = link.seriesKey,
                aniListId = link.aniListId,
                canonicalTitle = link.canonicalTitle,
                coverUrl = link.coverUrl,
                sources = link.sources.map { BindingJson(it.sourceId, it.mangaUrl, it.addedAt) },
                preferredSourceId = link.preferredSourceId,
            )
        }
        prefs.writeJson(KEY_SERIES_LINKS, payload)
    }

    @Serializable
    private data class BindingJson(
        val sourceId: String = "",
        val mangaUrl: String = "",
        val addedAt: Long = 0L,
    )

    @Serializable
    private data class LinkJson(
        val seriesKey: String = "",
        val aniListId: Int? = null,
        val canonicalTitle: String = "",
        val coverUrl: String? = null,
        val sources: List<BindingJson> = emptyList(),
        val preferredSourceId: String? = null,
    )

    private companion object {
        const val KEY_SERIES_LINKS = "series_links_json"
    }
}
```

- [ ] **Step 4: eseguire `SeriesLinksStoreTest`**, Expected: PASS.

---

### Task 5: impostazione `disabledSourceIds` + filtro nel catalogo

**Files:**
- Modify: `MangaViewModel.kt` (`AppSettings`, riga ~117), `BackupSchema.kt` (`SettingsBackup` DTO + `toBackup` riga 208 + `applyTo` riga 246), `MangaSources.kt` (`MangaSourceCatalog`, dopo `descriptorsForScope` riga 88)
- Test: `MangaSourcesTest.kt`, `BackupSchemaTest.kt` (aggiunte)

**Interfaces:**
- Produces:
  - `AppSettings.disabledSourceIds: Set<String> = emptySet()`
  - `SettingsBackup.disabledSourceIds: List<String> = emptyList()`
  - `MangaSourceCatalog.descriptorsForScope(scope: SearchScope, disabledSourceIds: Set<String>): List<MangaSourceDescriptor>` — filtra le disabilitate; se il filtro svuota l'elenco ritorna quello NON filtrato (la ricerca non deve mai interrogare zero fonti)

- [ ] **Step 1: test fallenti**

In `MangaSourcesTest.kt`:

```kotlin
    @Test
    fun `descriptorsForScope esclude le fonti disabilitate`() {
        val ids = MangaSourceCatalog
            .descriptorsForScope(SearchScope.ALL, disabledSourceIds = setOf(MangaSourceIds.VYMANGA))
            .map { it.id }
        assertFalse(MangaSourceIds.VYMANGA in ids)
        assertTrue(MangaSourceIds.MANGAPILL in ids)
    }

    @Test
    fun `filtro che svuota lo scope ripiega sull'elenco completo dello scope`() {
        val ids = MangaSourceCatalog
            .descriptorsForScope(
                SearchScope.ITA,
                disabledSourceIds = setOf(MangaSourceIds.HASTA_TEAM, MangaSourceIds.MANGA_WORLD),
            )
            .map { it.id }
        assertEquals(listOf(MangaSourceIds.HASTA_TEAM, MangaSourceIds.MANGA_WORLD), ids)
    }
```

In `BackupSchemaTest.kt`:

```kotlin
    @Test
    fun `disabledSourceIds sopravvive al round-trip e scarta id ignoti`() {
        val settings = AppSettings(disabledSourceIds = setOf(MangaSourceIds.VYMANGA))
        val restored = decodeSettingsBackup(encodeSettingsBackup(settings.toBackup()))!!
            .applyTo(AppSettings())
        assertEquals(setOf(MangaSourceIds.VYMANGA), restored.disabledSourceIds)

        val tampered = settings.toBackup().copy(disabledSourceIds = listOf("fonte_inesistente", MangaSourceIds.VYMANGA))
        assertEquals(setOf(MangaSourceIds.VYMANGA), tampered.applyTo(AppSettings()).disabledSourceIds)
    }

    @Test
    fun `backup che disabilita tutte le fonti viene azzerato`() {
        val allIds = MangaSourceCatalog.descriptors.map { it.id }
        val tampered = AppSettings().toBackup().copy(disabledSourceIds = allIds)
        assertEquals(emptySet<String>(), tampered.applyTo(AppSettings()).disabledSourceIds)
    }
```

- [ ] **Step 2: eseguirli, verificarne il fallimento**

- [ ] **Step 3: implementazione**

`AppSettings` (dopo `showHomeTab`):

```kotlin
    // Fonti escluse da ricerca aggregata e selettore fonte. Vuoto = tutte attive.
    val disabledSourceIds: Set<String> = emptySet(),
```

`MangaSourceCatalog` (sotto `descriptorsForScope` esistente):

```kotlin
    /**
     * Come [descriptorsForScope], escludendo le fonti disabilitate dall'utente. Se il filtro
     * svuotasse l'elenco (es. tutte le fonti dello scope disabilitate), ripiega sull'elenco
     * non filtrato: la ricerca non deve mai interrogare zero fonti.
     */
    fun descriptorsForScope(
        scope: SearchScope,
        disabledSourceIds: Set<String>,
    ): List<MangaSourceDescriptor> {
        val base = descriptorsForScope(scope)
        return base.filterNot { it.id in disabledSourceIds }.ifEmpty { base }
    }
```

`SettingsBackup` DTO (in `BackupSchema.kt`, il data class `@Serializable` — aggiungere campo con default):

```kotlin
    val disabledSourceIds: List<String> = emptyList(),
```

`toBackup()` (riga ~237, dopo `showHomeTab`):

```kotlin
    disabledSourceIds = disabledSourceIds.toList(),
```

`applyTo()` (dopo `showHomeTab = showHomeTab,`):

```kotlin
    disabledSourceIds = disabledSourceIds
        .filter { id -> MangaSourceCatalog.descriptors.any { it.id == id } }
        .toSet()
        .let { ids -> if (ids.size >= MangaSourceCatalog.descriptors.size) emptySet() else ids },
```

`SettingsStore.readLegacySettings` non va toccato: il campo nuovo esiste solo nel payload JSON e ha default.

- [ ] **Step 4: eseguire `MangaSourcesTest` e `BackupSchemaTest`**, Expected: PASS.

---

### Task 6: sezione impostazioni "Fonti" + azione ViewModel

**Files:**
- Modify: `SettingsComponents.kt` (nuovo composable in fondo), `SettingsScreen.kt` (nuova `SettingsSection` dopo la sezione "App", riga ~136; nuovo parametro callback), `MangaViewModel.kt` (nuova azione), `MainActivity.kt` (wiring del callback dove viene chiamato `SettingsScreen`)
- Test: `MangaViewModelSourceTogglesTest.kt` (nuovo, pattern `MangaViewModelShowHomeTabTest`)

**Interfaces:**
- Produces:
  - `MangaViewModel.setSourceEnabled(sourceId: String, enabled: Boolean)` — guardia: ignora la disattivazione che lascerebbe zero fonti attive
  - `SourceTogglesContent(disabledSourceIds: Set<String>, onToggle: (String, Boolean) -> Unit)` in `SettingsComponents.kt`

- [ ] **Step 1: test fallente** (nuovo file; copiare setup/regole dal `MangaViewModelShowHomeTabTest` esistente — stesso runner/rule per il ViewModel Robolectric)

```kotlin
package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

// Setup identico a MangaViewModelShowHomeTabTest (runner Robolectric, creazione ViewModel):
// replicare qui le stesse annotazioni e il costruttore usato in quel file.
class MangaViewModelSourceTogglesTest {

    @Test
    fun `disabilita e riabilita una fonte`() {
        val viewModel = createViewModel() // stesso helper del test di riferimento
        viewModel.setSourceEnabled(MangaSourceIds.VYMANGA, enabled = false)
        assertEquals(setOf(MangaSourceIds.VYMANGA), viewModel.state.value.settings.disabledSourceIds)
        viewModel.setSourceEnabled(MangaSourceIds.VYMANGA, enabled = true)
        assertEquals(emptySet<String>(), viewModel.state.value.settings.disabledSourceIds)
    }

    @Test
    fun `l'ultima fonte attiva non e disabilitabile`() {
        val viewModel = createViewModel()
        val allButOne = MangaSourceCatalog.descriptors.map { it.id }.drop(1)
        allButOne.forEach { viewModel.setSourceEnabled(it, enabled = false) }
        val lastActive = MangaSourceCatalog.descriptors.first().id
        viewModel.setSourceEnabled(lastActive, enabled = false)
        assertEquals(allButOne.toSet(), viewModel.state.value.settings.disabledSourceIds)
    }
}
```

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione**

`MangaViewModel` (vicino a `setFavoriteSort`, riga ~495):

```kotlin
    /** Attiva/disattiva una fonte. L'ultima fonte attiva non è disabilitabile. */
    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        updateSettings { settings ->
            val updated = if (enabled) {
                settings.disabledSourceIds - sourceId
            } else {
                settings.disabledSourceIds + sourceId
            }
            if (updated.size >= MangaSourceCatalog.descriptors.size) {
                settings
            } else {
                settings.copy(disabledSourceIds = updated)
            }
        }
    }
```

`SettingsComponents.kt` (in fondo al file):

```kotlin
/** Elenco delle fonti registrate con uno switch ciascuna (sezione impostazioni "Fonti"). */
@Composable
fun SourceTogglesContent(
    disabledSourceIds: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    Column {
        MangaSourceCatalog.descriptors.forEachIndexed { index, descriptor ->
            if (index > 0) SettingsDivider()
            SettingRow(
                title = descriptor.displayName,
                description = "Lingua: ${descriptor.language.displayName}",
                checked = descriptor.id !in disabledSourceIds,
                onCheckedChange = { enabled -> onToggle(descriptor.id, enabled) },
            )
        }
    }
}
```

Nota: `SettingRow` oggi è `private` in `SettingsComponents.kt` (riga ~100) — essendo il nuovo composable nello stesso file, resta accessibile; non cambiarne la visibilità.

`SettingsScreen.kt`: aggiungere al composable il parametro `onSetSourceEnabled: (String, Boolean) -> Unit` e, dopo la sezione "App" (riga ~136), la nuova sezione:

```kotlin
        SettingsSection(title = "Fonti", icon = Icons.Default.Language) {
            SourceTogglesContent(
                disabledSourceIds = settings.disabledSourceIds,
                onToggle = onSetSourceEnabled,
            )
        }
```

(import `androidx.compose.material.icons.filled.Language`; usare il riferimento a `settings` con lo stesso nome già in uso nel file — verificare se il parametro esistente si chiama `settings` o `state`).

`MainActivity.kt`: nel punto in cui viene invocato `SettingsScreen(...)`, aggiungere `onSetSourceEnabled = viewModel::setSourceEnabled`.

- [ ] **Step 4: eseguire il nuovo test + compile check**, Expected: PASS.

---

### Task 7: ricerca raggruppata nel ViewModel + ponte Scopri col media pinnato

**Files:**
- Modify: `MangaViewModel.kt` (`MangaUiState` riga ~228, `runAggregatedSearch` riga 2233, `onPickAniListManga` riga 2458, `onQueryChange` riga 466)
- Test: coperto dai test puri di `SeriesGroupingTest` (Task 3); questo task si verifica con compile check + test esistenti verdi

**Interfaces:**
- Consumes: `SeriesGrouping.groupResults` (Task 3), `descriptorsForScope(scope, disabled)` (Task 5), `aniListClient.searchManga` (esistente).
- Produces:
  - `MangaUiState.groupedResults: List<GroupedSearchResult> = emptyList()` (il flat `results` resta per retrocompatibilità di tutorial/altri usi)
  - campo privato `pendingAniListPick: AniListManga?` nel ViewModel — il media del ponte Scopri, messo in testa ai candidati del matcher

- [ ] **Step 1: implementazione** (niente nuovo test: logica pura già coperta; il VM cambia solo orchestrazione)

`MangaUiState`: dopo `val results: List<MangaSearchResult> = emptyList(),` aggiungere:

```kotlin
    val groupedResults: List<GroupedSearchResult> = emptyList(),
```

Nel ViewModel (vicino a `searchJob`): `private var pendingAniListPick: AniListManga? = null`.

`onQueryChange` — una modifica manuale della query invalida il pin del ponte Scopri:

```kotlin
    fun onQueryChange(text: String) {
        if (text != _state.value.query) {
            pendingAniListPick = null
        }
        updateState { copy(query = text) }
    }
```

`runAggregatedSearch`: sostituire il blocco `val results = withContext(...)` e l'`updateState` di successo con:

```kotlin
            try {
                val settings = _state.value.settings
                val (perSource, aniCandidates) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val sources = MangaSourceCatalog
                            .descriptorsForScope(settings.searchScope, settings.disabledSourceIds)
                            .map { descriptor ->
                                async {
                                    runCatching {
                                        sourceRegistry.requireById(descriptor.id).searchManga(query)
                                    }.getOrDefault(emptyList())
                                }
                            }
                        // AniList in parallelo al fan-out: serve solo a raggruppare. Un suo
                        // fallimento degrada al raggruppamento per titolo, mai a ricerca rotta.
                        val aniList = async {
                            runCatching { aniListClient.searchManga(query) }.getOrDefault(emptyList())
                        }
                        sources.awaitAll() to aniList.await()
                    }
                }
                val interleaved = MangaSourceCatalog.interleaveBySource(perSource)
                val pinned = pendingAniListPick?.let(::listOf).orEmpty()
                updateState {
                    copy(
                        results = interleaved,
                        groupedResults = SeriesGrouping.groupResults(interleaved, pinned + aniCandidates),
                        isSearching = false,
                    )
                }
            }
```

(nel ramo `catch` esistente nessun cambiamento; in `observeQueryChanges` il ramo query-vuota deve azzerare anche `groupedResults = emptyList()`).

`onPickAniListManga` (riga 2458): dopo `val title = manga.searchTitle() ?: return` aggiungere `pendingAniListPick = manga` (prima di `updateState`). Il resto invariato: il matcher ora è ancorato al mediaId anche se la ricerca AniList fallisce.

- [ ] **Step 2: compile check + suite test esistente**

Run: `& "android-app\gradlew.bat" -p android-app :app:testDebugUnitTest`
Expected: PASS (nessuna regressione).

---

### Task 8: SearchScreen a card raggruppate + `selectSeries`

**Files:**
- Modify: `SearchScreen.kt` (griglia risultati, righe 99-148), `UiComponents.kt` (`ResultCard` riga 356: parametro badge fonti), `MangaViewModel.kt` (nuova azione `selectSeries`, istanza `seriesLinksStore`), `MainActivity.kt` (callback `onSelect` della SearchScreen)
- Test: `SeriesLinksStoreTest` già copre `mergeFromGroup`/`initialBinding`; verifica con compile check + suite verde

**Interfaces:**
- Consumes: `GroupedSearchResult`, `SeriesLinksStore.mergeFromGroup`, `SeriesLink.initialBinding`, `selectManga` (esistente).
- Produces:
  - `MangaViewModel.selectSeries(group: GroupedSearchResult)` — persiste il link e apre la scheda sulla fonte iniziale
  - `MangaUiState.selectedSeriesLink: SeriesLink? = null`
  - istanza `private val seriesLinksStore = SeriesLinksStore(prefs)` nel ViewModel (accanto agli altri store, riga ~331)

- [ ] **Step 1: implementazione ViewModel**

In `MangaUiState`, dopo `selected`:

```kotlin
    val selectedSeriesLink: SeriesLink? = null,
```

Nel ViewModel (dopo `aniListStore`): `private val seriesLinksStore = SeriesLinksStore(prefs)`.

Nuova azione (accanto a `selectManga`, riga ~1301):

```kotlin
    /**
     * Tap su una card raggruppata: persiste/aggiorna il [SeriesLink] (unico momento in cui
     * il raggruppamento effimero della ricerca diventa stato) e apre la scheda sulla fonte
     * iniziale (preferita → lingua dello scope → prima).
     */
    fun selectSeries(group: GroupedSearchResult) {
        val link = seriesLinksStore.mergeFromGroup(group, now = System.currentTimeMillis())
        updateState { copy(selectedSeriesLink = link) }
        val binding = link.initialBinding(_state.value.settings.searchScope)
        val bindingResult = group.results.firstOrNull { it.sourceId == binding.sourceId }
            ?: group.primary
        selectManga(bindingResult)
    }
```

In `selectManga` (riga 1301), all'inizio, mantenere allineato il link quando si arriva da percorsi non-raggruppati (preferiti, tutorial, retry):

```kotlin
        if (_state.value.selectedSeriesLink?.sources
                ?.none { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) ==
                    MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl) } != false
        ) {
            updateState { copy(selectedSeriesLink = seriesLinksStore.linkForBinding(result.sourceId, result.mangaUrl)) }
        }
```

- [ ] **Step 2: implementazione UI**

`UiComponents.kt`, `ResultCard`: aggiungere parametro `extraBadge: (@Composable () -> Unit)? = null` e passarlo come `bottomEndBadge` a `MangaPosterCard` (verificare che `MangaPosterCard` abbia uno slot `bottomEndBadge`; se non esiste, aggiungere lo slot con lo stesso pattern di `bottomStartBadge`). Il badge conteggio fonti riusa `SourceBadge`:

```kotlin
        bottomEndBadge = extraBadge,
```

`SearchScreen.kt`: la griglia itera `state.groupedResults` invece di `state.results`:

```kotlin
                            items(
                                state.groupedResults,
                                key = { it.seriesKey },
                            ) { group ->
                                val primary = group.primary
                                val resultKey = MangaSourceCatalog.identityKey(primary.sourceId, primary.mangaUrl)
                                Box(modifier = if (group.seriesKey == state.groupedResults.first().seriesKey) {
                                    anchorFor(TutorialAnchor.SEARCH_RESULT_FIRST)
                                } else {
                                    Modifier
                                }) {
                                    ResultCard(
                                        result = primary.copy(title = group.title, coverUrl = group.coverUrl),
                                        isFavorite = resultKey in state.favoriteMangaKeys,
                                        onClick = { onSelectSeries(group) },
                                        onToggleFavorite = { onToggleFavorite(primary) },
                                        onShowInfo = { onShowInfo(primary) },
                                        // Gruppo multi-fonte: badge "N fonti"; singola: sigla fonte come oggi.
                                        sourceLabel = if (group.results.size > 1) {
                                            "${group.results.size} fonti"
                                        } else {
                                            MangaSourceCatalog.shortDisplayName(primary.sourceId)
                                        },
                                    )
                                }
                            }
```

Aggiornare la firma di `SearchScreen` con `onSelectSeries: (GroupedSearchResult) -> Unit` (il vecchio `onSelect` può restare per altri usi o essere rimosso se non referenziato), la caption dei risultati usa `state.groupedResults.size`, e le condizioni degli empty-state (`state.results.isNotEmpty()` → `state.groupedResults.isNotEmpty()`). In `MainActivity.kt` passare `onSelectSeries = viewModel::selectSeries`.

- [ ] **Step 3: compile check + suite test**, Expected: PASS.

---

### Task 9: stato del selettore fonte nel ViewModel (opzioni lazy + switch)

**Files:**
- Modify: `MangaViewModel.kt`
- Test: le parti pure sono coperte (Task 4); qui compile check + suite verde

**Interfaces:**
- Produces (consumati dalla UI in Task 10):
  - `data class SourceOptionUi(val sourceId: String, val mangaUrl: String, val chapterCount: Int? = null, val lastChapterLabel: String? = null, val isLoading: Boolean = false, val hasError: Boolean = false)` (definita in `MangaViewModel.kt` accanto agli altri UiState)
  - `MangaUiState.sourceOptions: List<SourceOptionUi> = emptyList()`
  - `MangaViewModel.loadSourceOptions()` — lazy, chiamata all'apertura del menu
  - `MangaViewModel.switchSource(option: SourceOptionUi)` — ricarica la scheda dalla fonte scelta e salva `preferredSourceId`

- [ ] **Step 1: implementazione**

`MangaUiState`: dopo `selectedSeriesLink` aggiungere `val sourceOptions: List<SourceOptionUi> = emptyList(),`.

Nel ViewModel:

```kotlin
    /** Cache di sessione dei dettagli per-fonte del selettore, chiave = identityKey. */
    private val sourceOptionDetailsCache = mutableMapOf<String, MangaDetails>()
    private var sourceOptionsJob: Job? = null

    /**
     * Popola le voci del selettore fonte (lazy, alla prima apertura del menu): per ogni
     * binding del link, capitoli disponibili e ultimo capitolo, in parallelo. Il fallimento
     * di una fonte marca solo quella voce, senza rompere le altre.
     */
    fun loadSourceOptions() {
        val link = _state.value.selectedSeriesLink ?: return
        val current = _state.value.sourceOptions
        if (current.isNotEmpty() && current.none { it.isLoading }) return
        sourceOptionsJob?.cancel()
        updateState {
            copy(
                sourceOptions = link.sources.map { binding ->
                    val cached = sourceOptionDetailsCache[
                        MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl),
                    ]
                    SourceOptionUi(
                        sourceId = binding.sourceId,
                        mangaUrl = binding.mangaUrl,
                        chapterCount = cached?.chapters?.size,
                        lastChapterLabel = cached?.chapters?.lastOrNull()?.displayShortLabel(),
                        isLoading = cached == null,
                    )
                },
            )
        }
        sourceOptionsJob = viewModelScope.launch {
            link.sources.forEach { binding ->
                val key = MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl)
                if (sourceOptionDetailsCache.containsKey(key)) return@forEach
                launch {
                    val details = withContext(Dispatchers.IO) {
                        runCatching {
                            sourceRegistry.resolve(binding.sourceId, binding.mangaUrl)
                                .fetchMangaDetails(binding.mangaUrl)
                        }.getOrNull()
                    }
                    if (details != null) sourceOptionDetailsCache[key] = details
                    updateState {
                        copy(
                            sourceOptions = sourceOptions.map { option ->
                                if (option.sourceId == binding.sourceId && option.mangaUrl == binding.mangaUrl) {
                                    option.copy(
                                        chapterCount = details?.chapters?.size,
                                        lastChapterLabel = details?.chapters?.lastOrNull()?.displayShortLabel(),
                                        isLoading = false,
                                        hasError = details == null,
                                    )
                                } else {
                                    option
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** Cambio fonte dal selettore: ricarica la scheda e ricorda la scelta per la serie. */
    fun switchSource(option: SourceOptionUi) {
        val link = _state.value.selectedSeriesLink ?: return
        if (option.sourceId == _state.value.selected?.sourceId) return
        seriesLinksStore.setPreferredSource(link.seriesKey, option.sourceId)
        updateState {
            copy(selectedSeriesLink = link.copy(preferredSourceId = option.sourceId))
        }
        selectManga(
            MangaSearchResult(
                sourceId = option.sourceId,
                title = link.canonicalTitle,
                mangaUrl = option.mangaUrl,
                coverUrl = link.coverUrl,
            ),
        )
    }
```

In `selectSeries` e `switchSource` azzerare le opzioni quando cambia la serie: aggiungere `sourceOptions = emptyList()` all'`updateState` di `selectSeries`. (La cache dettagli resta: è per identityKey, non per serie.)

- [ ] **Step 2: compile check + suite test**, Expected: PASS.

---

### Task 10: UI del selettore fonte nella scheda + matching libreria per binding

**Files:**
- Modify: `DetailScreen.kt` (nuovo composable `SourceSelector` sotto `SeriesHeader`, righe 96-107; nuovi parametri), `LibraryMatching.kt` (parametro `extraBindings`), `MainActivity.kt` (wiring righe 723-753)
- Test: `LibraryMatchingTest.kt` (aggiunte)

**Interfaces:**
- Consumes: `SourceOptionUi`, `loadSourceOptions`, `switchSource` (Task 9), `SeriesLink` (Task 4).
- Produces:
  - `DetailScreen` nuovi parametri: `sourceOptions: List<SourceOptionUi>`, `showSourceSelector: Boolean`, `onOpenSourceMenu: () -> Unit`, `onSwitchSource: (SourceOptionUi) -> Unit`, `onSearchOtherSources: () -> Unit`
  - `LibraryMatching.matchingDownloadedSeries(details, library, extraBindings: List<SeriesSourceBinding> = emptyList())` (e propagato a `downloadedChapterKeys`/`downloadedReadChapterIds`)

- [ ] **Step 1: test fallente** (in `LibraryMatchingTest.kt`)

```kotlin
    @Test
    fun `matcha una serie scaricata da un'altra fonte tramite i binding del link`() {
        val details = MangaDetails(
            sourceId = "vymanga", title = "Titolo VY", coverUrl = null,
            mangaUrl = "https://vymanga.com/manga/x", chapters = emptyList(),
        )
        // Serie scaricata a suo tempo da Mangapill, titolo diverso da quello della fonte attiva.
        val downloaded = downloadedSeries(sourceId = "mangapill", mangaUrl = "https://mangapill.com/manga/1", title = "Altro Titolo")
        val bindings = listOf(
            SeriesSourceBinding("mangapill", "https://mangapill.com/manga/1"),
            SeriesSourceBinding("vymanga", "https://vymanga.com/manga/x"),
        )
        assertEquals(
            downloaded,
            LibraryMatching.matchingDownloadedSeries(details, listOf(downloaded), extraBindings = bindings),
        )
    }
```

(`downloadedSeries(...)` è l'helper già usato nel file per costruire `DownloadedSeries`; riusarlo con i parametri nominati esistenti.)

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione LibraryMatching**

In `matchingDownloadedSeries` aggiungere il parametro e includere le chiavi dei binding in `detailsKeys`:

```kotlin
    fun matchingDownloadedSeries(
        details: MangaDetails,
        library: List<DownloadedSeries>,
        extraBindings: List<SeriesSourceBinding> = emptyList(),
    ): DownloadedSeries? {
        // ... detailsKey/detailsTitleKey invariati ...
        val detailsKeys = buildSet {
            add(detailsKey)
            detailsTitleKey?.let(::add)
            extraBindings.forEach { binding ->
                add(MangaSourceCatalog.identityKey(binding.sourceId, binding.mangaUrl))
            }
        }
        // ... resto invariato ...
    }
```

Propagare lo stesso parametro (default `emptyList()`) a `downloadedChapterKeys` e `downloadedReadChapterIds`, che lo passano a `matchingDownloadedSeries`.

Nota: con questo matching per binding il campo `seriesKey` in `series.json` previsto dalla spec NON serve (i binding coprono il caso senza migrare i file su disco) — semplificazione consapevole, da annotare nella spec a fine lavori.

- [ ] **Step 4: implementazione UI DetailScreen**

Nuovo composable in `DetailScreen.kt` (stile del `DownloadRangeDialog` per l'ExposedDropdown):

```kotlin
/**
 * Selettore della fonte attiva, sotto il titolo. Aprendo il menu si caricano (lazy) le
 * info comparative per fonte: capitoli disponibili e ultimo uscito. L'ultima voce apre
 * la ricerca su altre fonti non ancora collegate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelector(
    activeSourceId: String,
    options: List<SourceOptionUi>,
    onOpenMenu: () -> Unit,
    onSwitchSource: (SourceOptionUi) -> Unit,
    onSearchOtherSources: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            if (open) onOpenMenu()
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = "${MangaSourceCatalog.displayName(activeSourceId)} · " +
                MangaSourceCatalog.languageOf(activeSourceId).displayName,
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text("Fonte") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.large,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                "${MangaSourceCatalog.displayName(option.sourceId)} · " +
                                    MangaSourceCatalog.languageOf(option.sourceId).displayName,
                            )
                            Text(
                                text = when {
                                    option.isLoading -> "Carico…"
                                    option.hasError -> "Non raggiungibile"
                                    option.chapterCount != null ->
                                        "${option.chapterCount} capitoli" +
                                            (option.lastChapterLabel?.let { " · ultimo: $it" } ?: "")
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (option.hasError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!option.hasError && option.sourceId != activeSourceId) onSwitchSource(option)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            DropdownMenuItem(
                text = { Text("Cerca su altre fonti…") },
                onClick = {
                    expanded = false
                    onSearchOtherSources()
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}
```

In `DetailScreen`: aggiungere i parametri dell'interfaccia sopra e renderizzare subito dopo `SeriesHeader(...)` (riga ~107):

```kotlin
            if (showSourceSelector) {
                SourceSelector(
                    activeSourceId = details.sourceId,
                    options = sourceOptions,
                    onOpenMenu = onOpenSourceMenu,
                    onSwitchSource = onSwitchSource,
                    onSearchOtherSources = onSearchOtherSources,
                )
            }
```

`showSourceSelector` = il chiamante passa `state.selectedSeriesLink != null` (il selettore compare solo per serie con link; per schede aperte da percorsi legacy senza link resta tutto com'è oggi).

- [ ] **Step 5: wiring MainActivity** (righe 723-753)

```kotlin
                DetailScreen(
                    // ... parametri esistenti invariati ...
                    showSourceSelector = state.selectedSeriesLink != null,
                    sourceOptions = state.sourceOptions,
                    onOpenSourceMenu = viewModel::loadSourceOptions,
                    onSwitchSource = viewModel::switchSource,
                    onSearchOtherSources = viewModel::searchOtherSources,
                )
```

e i due `remember` del matching libreria diventano:

```kotlin
                val linkBindings = state.selectedSeriesLink?.sources.orEmpty()
                val downloadedChapterKeys = remember(selectedManga, state.library, linkBindings) {
                    LibraryMatching.downloadedChapterKeys(selectedManga, state.library, linkBindings)
                }
                val readChapterIds = remember(selectedManga, state.library, state.selectedMangaReadChapterIds, linkBindings) {
                    state.selectedMangaReadChapterIds +
                        LibraryMatching.downloadedReadChapterIds(selectedManga, state.library, linkBindings)
                }
```

(`viewModel::searchOtherSources` arriva nel Task 11: per far compilare questo task, aggiungere già nel ViewModel uno stub `fun searchOtherSources() {}` che il Task 11 completa.)

- [ ] **Step 6: eseguire `LibraryMatchingTest` + compile check**, Expected: PASS.

---

### Task 11: "Cerca su altre fonti…" — bottom sheet di aggancio/scollegamento

**Files:**
- Modify: `MangaViewModel.kt` (stato + azioni), `DetailScreen.kt` (bottom sheet), `MainActivity.kt` (wiring)
- Test: compile check + suite verde (le primitive store sono coperte da Task 4)

**Interfaces:**
- Produces:
  - `data class OtherSourcesUiState(val isLoading: Boolean = false, val results: List<MangaSearchResult> = emptyList(), val error: String? = null)`
  - `MangaUiState.otherSourcesSheet: OtherSourcesUiState? = null`
  - `MangaViewModel.searchOtherSources()` (sostituisce lo stub del Task 10), `linkSourceToSeries(result: MangaSearchResult)`, `unlinkSource(sourceId: String)`, `dismissOtherSources()`

- [ ] **Step 1: implementazione ViewModel**

```kotlin
    /**
     * Cerca il titolo della serie sulle fonti abilitate NON ancora collegate al link, per
     * agganciarne una a mano (o correggere un match mancato). Best-effort per fonte.
     */
    fun searchOtherSources() {
        val link = _state.value.selectedSeriesLink ?: return
        val boundSourceIds = link.sources.map { it.sourceId }.toSet()
        val candidates = MangaSourceCatalog
            .descriptorsForScope(SearchScope.ALL, _state.value.settings.disabledSourceIds)
            .filterNot { it.id in boundSourceIds }
        if (candidates.isEmpty()) {
            updateState { copy(otherSourcesSheet = OtherSourcesUiState(error = "Nessuna altra fonte disponibile")) }
            return
        }
        updateState { copy(otherSourcesSheet = OtherSourcesUiState(isLoading = true)) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    candidates.map { descriptor ->
                        async {
                            runCatching {
                                sourceRegistry.requireById(descriptor.id).searchManga(link.canonicalTitle)
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }
            updateState {
                copy(
                    otherSourcesSheet = OtherSourcesUiState(
                        isLoading = false,
                        results = MangaSourceCatalog.interleaveBySource(results),
                        error = null,
                    ),
                )
            }
        }
    }

    /** Aggancia manualmente un risultato come nuovo binding della serie corrente. */
    fun linkSourceToSeries(result: MangaSearchResult) {
        val link = _state.value.selectedSeriesLink ?: return
        seriesLinksStore.addBinding(
            link.seriesKey,
            SeriesSourceBinding(result.sourceId, result.mangaUrl, System.currentTimeMillis()),
        )
        updateState {
            copy(
                selectedSeriesLink = seriesLinksStore.linkFor(link.seriesKey),
                otherSourcesSheet = null,
                sourceOptions = emptyList(), // ricalcolate alla prossima apertura del menu
            )
        }
    }

    /** Scollega una fonte agganciata per errore (mai l'ultima: lo store la conserva). */
    fun unlinkSource(sourceId: String) {
        val link = _state.value.selectedSeriesLink ?: return
        seriesLinksStore.removeBinding(link.seriesKey, sourceId)
        updateState {
            copy(
                selectedSeriesLink = seriesLinksStore.linkFor(link.seriesKey),
                sourceOptions = emptyList(),
            )
        }
    }

    fun dismissOtherSources() {
        updateState { copy(otherSourcesSheet = null) }
    }
```

- [ ] **Step 2: implementazione UI**

In `DetailScreen.kt`, nuovi parametri `otherSourcesSheet: OtherSourcesUiState?`, `onPickOtherSource: (MangaSearchResult) -> Unit`, `onUnlinkSource: (String) -> Unit`, `onDismissOtherSources: () -> Unit`. In fondo al body (accanto al `pendingStart?.let`):

```kotlin
    otherSourcesSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = onDismissOtherSources) {
            Text(
                text = "Altre fonti per questa serie",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            when {
                sheet.isLoading -> FullScreenLoading()
                sheet.error != null -> Text(
                    text = sheet.error,
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sheet.results.isEmpty() -> Text(
                    text = "Nessun risultato sulle altre fonti.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(sheet.results, key = { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) }) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = {
                                Text(
                                    MangaSourceCatalog.displayName(result.sourceId) + " · " +
                                        MangaSourceCatalog.languageOf(result.sourceId).displayName,
                                )
                            },
                            modifier = Modifier.clickable { onPickOtherSource(result) },
                        )
                    }
                }
            }
        }
    }
```

(import: `ModalBottomSheet`, `ListItem`, `androidx.compose.foundation.clickable`.) Lo scollegamento: nelle voci del `SourceSelector` (Task 10) aggiungere un'icona trailing "rimuovi" per le fonti non attive quando `options.size > 1`, che chiama `onUnlinkSource(option.sourceId)` — trailing del `DropdownMenuItem` con `IconButton(Icons.Default.Close)`.

`MainActivity.kt`: passare i nuovi parametri (`otherSourcesSheet = state.otherSourcesSheet`, `onPickOtherSource = viewModel::linkSourceToSeries`, `onUnlinkSource = viewModel::unlinkSource`, `onDismissOtherSources = viewModel::dismissOtherSources`).

- [ ] **Step 3: compile check + suite test**, Expected: PASS.

---

### Task 12: progressi di lettura streaming per-serie

**Files:**
- Modify: `LibraryRepository.kt` (righe 453-474 + `streamingReadPrefKey` riga 805), `DetailScreen.kt` (`isRead` riga 334), `MangaViewModel.kt` (call-site di `streamingReadChapterIds`/`markStreamingChapterRead`)
- Test: `StreamingReadStateTest.kt` (aggiunte)

**Interfaces:**
- Produces:
  - `LibraryRepository.streamingReadChapterIds(seriesKey: String?, sourceId: String, mangaUrl: String): Set<String>` — unione del set per-serie e del set legacy per-fonte
  - `LibraryRepository.markStreamingChapterRead(seriesKey: String?, sourceId: String, mangaUrl: String, chapter: ChapterEntry): String` — scrive l'id stabile E la chiave `number:<label>` nel set per-serie (più il set legacy per rollback)
  - Prefisso pref nuovo: `"streaming_read_series::<seriesKey>"`

- [ ] **Step 1: test fallente** (in `StreamingReadStateTest.kt`, stesso runner/setup esistente del file)

```kotlin
    @Test
    fun `il progresso streaming sopravvive al cambio fonte tramite seriesKey e numero`() {
        val chapterMangapill = ChapterEntry("10", BigDecimal("10"), "https://mangapill.com/ch/10", "ch-10")
        repository.markStreamingChapterRead(
            seriesKey = "anilist:53390",
            sourceId = "mangapill",
            mangaUrl = "https://mangapill.com/manga/1",
            chapter = chapterMangapill,
        )
        // Stessa serie letta da un'altra fonte: URL diversi, stesso numero.
        val ids = repository.streamingReadChapterIds(
            seriesKey = "anilist:53390",
            sourceId = "vymanga",
            mangaUrl = "https://vymanga.com/manga/x",
        )
        assertTrue("number:10" in ids)
    }

    @Test
    fun `senza seriesKey resta il comportamento legacy per-fonte`() {
        val chapter = ChapterEntry("5", BigDecimal("5"), "https://mangapill.com/ch/5", "ch-5")
        repository.markStreamingChapterRead(
            seriesKey = null,
            sourceId = "mangapill",
            mangaUrl = "https://mangapill.com/manga/1",
            chapter = chapter,
        )
        val ids = repository.streamingReadChapterIds(null, "mangapill", "https://mangapill.com/manga/1")
        assertTrue(DownloadStorage.stableChapterId(chapter) in ids)
    }
```

(verificare nel file esistente come viene costruito `repository` e il nome esatto — riusare lo stesso setup; `DownloadStorage.normalizedChapterLabel("10")` produce la parte dopo `number:` — se l'assert su `"number:10"` non combacia, usare `"number:${DownloadStorage.normalizedChapterLabel("10")}"`).

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione**

`LibraryRepository.kt` — sostituire i metodi delle righe 453-474 con:

```kotlin
    fun streamingReadChapterIds(seriesKey: String?, sourceId: String, mangaUrl: String): Set<String> {
        val legacy = prefs.getStringSet(streamingReadPrefKey(sourceId, mangaUrl), emptySet()).orEmpty()
        val perSeries = seriesKey
            ?.let { prefs.getStringSet(streamingReadSeriesPrefKey(it), emptySet()).orEmpty() }
            .orEmpty()
        return legacy + perSeries
    }

    fun streamingReadChapterIds(plan: DownloadPlan): Set<String> {
        return streamingReadChapterIds(seriesKey = null, plan.sourceId, plan.mangaUrl)
    }

    fun markStreamingChapterRead(
        seriesKey: String?,
        sourceId: String,
        mangaUrl: String,
        chapter: ChapterEntry,
    ): String {
        val chapterId = DownloadStorage.stableChapterId(chapter)
        val numberKey = "number:${DownloadStorage.normalizedChapterLabel(chapter.displayNumber())}"
        prefs.edit {
            val legacyKey = streamingReadPrefKey(sourceId, mangaUrl)
            putStringSet(legacyKey, prefs.getStringSet(legacyKey, emptySet()).orEmpty() + chapterId)
            seriesKey?.let { key ->
                val seriesPrefKey = streamingReadSeriesPrefKey(key)
                putStringSet(
                    seriesPrefKey,
                    prefs.getStringSet(seriesPrefKey, emptySet()).orEmpty() + chapterId + numberKey,
                )
            }
        }
        return chapterId
    }
```

e accanto a `streamingReadPrefKey` (riga 805):

```kotlin
    private fun streamingReadSeriesPrefKey(seriesKey: String): String = "streaming_read_series::$seriesKey"
```

`DetailScreen.kt` — `isRead` (riga 334) allineato a `isDownloaded`:

```kotlin
private fun ChapterEntry.isRead(readChapterIds: Set<String>): Boolean {
    val numberKey = "number:${DownloadStorage.normalizedChapterLabel(displayNumber())}"
    return DownloadStorage.stableChapterId(this) in readChapterIds || numberKey in readChapterIds
}
```

`MangaViewModel.kt` — aggiornare i call-site:
- in `selectManga` (righe 1306 e 1332): `libraryRepository.streamingReadChapterIds(currentSeriesKey(result.sourceId, result.mangaUrl, result.title), result.sourceId, result.mangaUrl)` dove `currentSeriesKey` è il nuovo helper:

```kotlin
    /** SeriesKey della serie corrente: dal link selezionato, o derivata da fonte+titolo. */
    private fun currentSeriesKey(sourceId: String, mangaUrl: String, title: String): String {
        _state.value.selectedSeriesLink?.let { link ->
            val key = MangaSourceCatalog.identityKey(sourceId, mangaUrl)
            if (link.sources.any { MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == key }) {
                return link.seriesKey
            }
        }
        return seriesLinksStore.seriesKeyFor(sourceId, mangaUrl, title)
    }
```

- ogni chiamata a `markStreamingChapterRead(sourceId, mangaUrl, chapter)` (cercare con Grep nel ViewModel) diventa `markStreamingChapterRead(currentSeriesKey(sourceId, mangaUrl, titolo-della-serie-corrente), sourceId, mangaUrl, chapter)`.

- [ ] **Step 4: eseguire `StreamingReadStateTest` + suite completa**, Expected: PASS.

---

### Task 13: tracking AniList ancorato alla SeriesKey (migrazione + flussi)

**Files:**
- Modify: `AniListTracking.kt`/`AniListStore` (`readTrackings` riga 172), `MangaViewModel.kt` (`openAniListMatch`, `confirmAniListMatch`, `maybeSyncAniListOnChapterRead`, ~righe 2960-3200), `MainActivity.kt` (lookup tracking righe 731-737)
- Test: `AniListTrackingTest.kt` (aggiunte)

**Interfaces:**
- Produces:
  - `AniListStore.readTrackings()` migra in lettura: ogni chiave che non inizia con `anilist:`/`title:` e ha `mediaId > 0` viene ri-chiavata a `SeriesIdentity.keyForAniList(mediaId)` (merge: vince il progress più alto); il risultato migrato viene ri-persistito una volta
  - `MangaUiState.selectedSeriesKey: String? = null` — impostata da `selectManga`, usata da MainActivity per il lookup

- [ ] **Step 1: test fallente** (in `AniListTrackingTest.kt`, setup Robolectric come i test store esistenti; se il file attuale è JVM puro, creare la parte store in `SeriesLinksStoreTest`-style con prefs Robolectric)

```kotlin
    @Test
    fun `readTrackings migra le chiavi legacy identityKey ad anilist`() {
        val legacy = mapOf(
            "mangapill::https://mangapill.com/manga/1" to AniListTracking(mediaId = 53390, title = "AoT", progress = 12),
        )
        store.persistTrackings(legacy)
        val migrated = store.readTrackings()
        assertEquals(setOf("anilist:53390"), migrated.keys)
        assertEquals(12, migrated["anilist:53390"]!!.progress)
    }

    @Test
    fun `migrazione con duplicati tiene il progress piu alto`() {
        store.persistTrackings(
            mapOf(
                "mangapill::https://mangapill.com/manga/1" to AniListTracking(mediaId = 53390, title = "AoT", progress = 12),
                "vymanga::https://vymanga.com/manga/2" to AniListTracking(mediaId = 53390, title = "AoT", progress = 30),
            ),
        )
        assertEquals(30, store.readTrackings()["anilist:53390"]!!.progress)
    }
```

- [ ] **Step 2: eseguirlo, verificarne il fallimento**

- [ ] **Step 3: implementazione**

`AniListStore.readTrackings()`:

```kotlin
    fun readTrackings(): Map<String, AniListTracking> {
        val raw = prefs.readJson<Map<String, TrackingJson>>(KEY_TRACKINGS_JSON, emptyMap())
            .filterValues { it.mediaId > 0 }
            .mapValues { (_, entry) ->
                AniListTracking(
                    mediaId = entry.mediaId,
                    title = entry.title,
                    totalChapters = entry.totalChapters,
                    status = aniListStatusFromText(entry.status),
                    progress = entry.progress,
                    score = entry.score,
                    pendingProgress = entry.pendingProgress,
                )
            }
        // Migrazione lazy: le chiavi storiche erano identityKey per-fonte; il mediaId nel
        // valore permette di ri-ancorarle alla serie. Duplicati: vince il progress più alto.
        var migratedAny = false
        val migrated = LinkedHashMap<String, AniListTracking>()
        raw.forEach { (key, tracking) ->
            val newKey = if (
                key.startsWith(SeriesIdentity.ANILIST_PREFIX) || key.startsWith(SeriesIdentity.TITLE_PREFIX)
            ) {
                key
            } else {
                migratedAny = true
                SeriesIdentity.keyForAniList(tracking.mediaId)
            }
            val existing = migrated[newKey]
            if (existing == null || tracking.progress > existing.progress) {
                migrated[newKey] = tracking
            }
        }
        if (migratedAny) persistTrackings(migrated)
        return migrated
    }
```

`MangaUiState`: aggiungere `val selectedSeriesKey: String? = null,` e in `selectManga` impostarla:

```kotlin
        val seriesKey = currentSeriesKey(result.sourceId, result.mangaUrl, result.title)
        // ...nell'updateState iniziale:
        selectedSeriesKey = seriesKey,
```

`openAniListMatch` (riga ~2960): la chiave del match era `identityKeyOrNull(...)` → usare `_state.value.selectedSeriesKey` (se `null`, derivarla con `currentSeriesKey`).

`confirmAniListMatch` (riga ~3040): salvare SEMPRE sotto `SeriesIdentity.keyForAniList(media.id)`; se la chiave del dialog era una `title:` o legacy, spostare l'eventuale entry esistente (rimuovi vecchia chiave, scrivi nuova) e **promuovere il link**: se `_state.value.selectedSeriesLink?.seriesKey` inizia con `title:`, ri-chiavare il link via store (`upsert` del link con `seriesKey = keyForAniList(media.id)`, `aniListId = media.id` e rimozione della vecchia voce — aggiungere in `SeriesLinksStore`:

```kotlin
    /** Promuove un link `title:` ad `anilist:` (aggancio tracking arrivato dopo). */
    fun promoteToAniList(titleKey: String, aniListId: Int): SeriesLink? {
        val all = readAll().toMutableMap()
        val link = all.remove(titleKey) ?: return null
        val promoted = link.copy(seriesKey = SeriesIdentity.keyForAniList(aniListId), aniListId = aniListId)
        all[promoted.seriesKey] = promoted
        persistAll(all)
        return promoted
    }
```

e aggiornare `selectedSeriesLink`/`selectedSeriesKey` nello stato). Migrare nello stesso punto anche il set streaming per-serie: copiare le prefs `streaming_read_series::<titleKey>` nella nuova chiave (leggere, unire, scrivere via i metodi di `LibraryRepository` — aggiungere `fun migrateStreamingSeriesKey(oldKey: String, newKey: String)` che fa union e remove della vecchia).

`maybeSyncAniListOnChapterRead` (riga ~3150): il lookup del tracking usa `selectedSeriesKey`/`currentSeriesKey` invece di `identityKeyOrNull`.

`MainActivity.kt` (righe 731-737):

```kotlin
                val aniListTracking = remember(selectedManga, state.aniList.trackings, state.selectedSeriesKey) {
                    state.selectedSeriesKey?.let { state.aniList.trackings[it] }
                }
```

- [ ] **Step 4: eseguire `AniListTrackingTest` + suite completa**, Expected: PASS.

---

### Task 14: preferiti dedupe per SeriesKey

**Files:**
- Modify: `MangaViewModel.kt` (`identityKeys()` riga ~103, `toggleFavorite`, call-site di `favoriteMangaKeys`), `SearchScreen.kt` (check preferito per gruppo)
- Test: `FavoritesOrganizationTest.kt` o nuovo `FavoritesSeriesKeyTest.kt` (Robolectric ViewModel, pattern Task 6)

**Interfaces:**
- Consumes: `SeriesLinksStore.seriesKeyFor` (Task 4).
- Produces:
  - `MangaUiState.favoriteSeriesKeys: Set<String> = emptySet()` — campo NUOVO. `favoriteMangaKeys` (identityKey) resta invariato: Home/Libreria/altre schermate lo controllano per identityKey e non vanno toccate.
  - `toggleFavorite` deduplica per SeriesKey: aggiungere la stessa serie da due fonti diverse produce una voce sola

- [ ] **Step 1: test fallente**

```kotlin
    @Test
    fun `la stessa serie da due fonti e un solo preferito`() {
        val viewModel = createViewModel()
        viewModel.toggleFavorite(FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null))
        viewModel.toggleFavorite(FavoriteManga("vymanga", "One Piece!", "https://vymanga.com/manga/9", null))
        // Seconda aggiunta = stessa SeriesKey ("title:one piece") → toggle OFF, non doppione.
        assertEquals(0, viewModel.state.value.favorites.size)
    }
```

(usare il nome/firma reale di `toggleFavorite` nel ViewModel — verificarla con Grep prima di scrivere il test; l'esempio assume `fun toggleFavorite(favorite: FavoriteManga)`.)

- [ ] **Step 2: eseguirlo, verificarne il fallimento** (oggi le due voci hanno identityKey diverse → 2 preferiti)

- [ ] **Step 3: implementazione**

Nel ViewModel: aggiungere l'helper e popolarlo OVUNQUE venga già ricalcolato `favoriteMangaKeys` (stessi `updateState`, campo in più — cercare i punti con Grep su `favoriteMangaKeys =`):

```kotlin
    private fun favoriteSeriesKeys(favorites: List<FavoriteManga>): Set<String> =
        favorites.mapTo(linkedSetOf()) { seriesLinksStore.seriesKeyFor(it.sourceId, it.mangaUrl, it.title) }
```

In `MangaUiState`, dopo `favoriteMangaKeys`:

```kotlin
    val favoriteSeriesKeys: Set<String> = emptySet(),
```

In `toggleFavorite`: il confronto "già preferito?" usa `seriesLinksStore.seriesKeyFor(...)` dell'elemento contro `favoriteSeriesKeys(favorites)` — se presente rimuove la voce con la stessa SeriesKey (qualunque fonte), altrimenti aggiunge. `favoriteMangaKeys` continua a essere calcolato come oggi.

`SearchScreen.kt` (dal Task 8): il check `isFavorite` della card raggruppata diventa:

```kotlin
                                        isFavorite = group.seriesKey in state.favoriteSeriesKeys,
```

Nota di coerenza: `favoriteStatusByKey`/`favoriteSeenStates` restano su identityKey (alimentano le notifiche per-fonte): NON toccarli in questo task.

- [ ] **Step 4: eseguire il test nuovo + `FavoritesPersistenceTest` + `FavoritesOrganizationTest`**, Expected: PASS.

---

### Task 15: verifica finale end-to-end

- [ ] **Step 1: suite completa**

Run: `& "android-app\gradlew.bat" -p android-app :app:testDebugUnitTest`
Expected: PASS, nessuna regressione.

- [ ] **Step 2: build APK**

Run: `& "android-app\gradlew.bat" -p android-app :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: smoke manuale su device** (skill `verify` / `run`; VyManga funziona solo on-device, non da CI/sandbox)

1. Cerca "attack on titan" con scope Tutte → una card con badge "N fonti".
2. Tap sulla card → scheda con selettore fonte sotto il titolo; apri il menu → conteggi capitoli caricati in lazy.
3. Cambia fonte → lista capitoli ricaricata; capitoli letti/scaricati ancora marcati.
4. "Cerca su altre fonti…" → sheet con risultati; aggancia e scollega una fonte.
5. Impostazioni → sezione Fonti: disabilita una fonte → sparisce dalla ricerca; prova a disabilitarle tutte → l'ultima resta attiva.
6. Preferito da due fonti → una sola voce nei Preferiti.
7. Con account AniList: collega il tracking → cambia fonte → il tracking resta visibile.

- [ ] **Step 4: aggiornare la spec** con la semplificazione del Task 10 (matching per binding invece del campo `seriesKey` in `series.json`) e aggiungere le voci fatte a `MIGLIORIE.md` se richiesto dall'utente. **Nessun commit.**
