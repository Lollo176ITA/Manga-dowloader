package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il giro completo di riconciliazione dei preferiti, con AniList e le fonti sostituiti da
 * lambda: niente rete, comportamento deterministico.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AniListFavoritesSynchronizerTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var syncStore: AniListFavoritesSyncStore
    private lateinit var seriesLinksStore: SeriesLinksStore
    private val toggled = mutableListOf<Int>()

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        syncStore = AniListFavoritesSyncStore(prefs)
        seriesLinksStore = SeriesLinksStore(prefs)
        toggled.clear()
    }

    @Test
    fun `il preferito presente solo in app viene spinto su AniList`() = runBlocking {
        val synchronizer = synchronizer(favourites = emptyList())

        val imported = synchronizer.sync(listOf(appFavorite(30002, "Berserk")))

        assertEquals(listOf(30002), toggled)
        assertEquals(emptyList<FavoriteManga>(), imported)
        assertEquals(setOf(30002), syncStore.readReconciledIds())
    }

    @Test
    fun `il favourite presente solo su AniList entra nei preferiti`() = runBlocking {
        val synchronizer = synchronizer(
            favourites = listOf(media(30002, "Berserk")),
            sourceResults = mapOf("Berserk" to listOf(searchResult("Berserk"))),
        )

        val imported = synchronizer.sync(emptyList())

        assertEquals(1, imported.size)
        assertEquals("Berserk", imported.single().title)
        assertEquals(SeriesIdentity.keyForAniList(30002), imported.single().canonicalKey())
        assertEquals(emptyList<Int>(), toggled)
        assertEquals(setOf(30002), syncStore.readReconciledIds())
        assertNotNull(
            "l'import crea il link della serie, come un preferito aggiunto a mano",
            seriesLinksStore.linkFor(SeriesIdentity.keyForAniList(30002)),
        )
    }

    @Test
    fun `un preferito tolto in app non risorge al giro successivo`() = runBlocking {
        val favourites = listOf(media(30002, "Berserk"))
        val results = mapOf("Berserk" to listOf(searchResult("Berserk")))

        // Primo giro: arriva da AniList ed entra in app.
        val imported = synchronizer(favourites, results).sync(emptyList())
        assertEquals(1, imported.size)

        // L'utente lo toglie dai preferiti: al giro dopo l'app non lo ha più, AniList sì.
        val second = synchronizer(favourites, results).sync(emptyList())

        assertEquals(emptyList<FavoriteManga>(), second)
    }

    @Test
    fun `un favourite tolto su AniList non viene rispinto`() = runBlocking {
        val favorites = listOf(appFavorite(30002, "Berserk"))

        // Primo giro: l'app lo spinge su AniList.
        synchronizer(favourites = emptyList()).sync(favorites)
        assertEquals(listOf(30002), toggled)

        // L'utente lo toglie dai favourites: non deve tornare su per conto suo.
        toggled.clear()
        synchronizer(favourites = emptyList()).sync(favorites)

        assertEquals(emptyList<Int>(), toggled)
    }

    @Test
    fun `un titolo che nessuna fonte espone finisce tra gli import falliti`() = runBlocking {
        val synchronizer = synchronizer(
            favourites = listOf(media(30002, "Serie Introvabile")),
            sourceResults = emptyMap(),
        )

        val imported = synchronizer.sync(emptyList())

        assertEquals(emptyList<FavoriteManga>(), imported)
        assertEquals(setOf(30002), syncStore.readFailedImports())
        assertTrue(
            "non essendo riuscito, non va archiviato come riconciliato",
            30002 !in syncStore.readReconciledIds(),
        )
    }

    @Test
    fun `se cambiano le fonti attive gli import falliti vengono riprovati`() = runBlocking {
        val favourites = listOf(media(30002, "Berserk"))
        val results = mapOf("Berserk" to listOf(searchResult("Berserk")))

        // Primo giro con la sola Mangapill (che non ce l'ha): il titolo è "introvabile".
        AniListFavoritesSynchronizer(
            syncStore = syncStore,
            seriesLinksStore = seriesLinksStore,
            fetchFavourites = { favourites },
            toggleFavourite = { toggled += it },
            searchSources = { emptyList() },
            sourcesSignature = { "mangapill" },
        ).sync(emptyList())

        assertEquals(setOf(30002), syncStore.readFailedImports())

        // Seconda fonte accesa (o tornata su): la conclusione di prima non vale più.
        val imported = AniListFavoritesSynchronizer(
            syncStore = syncStore,
            seriesLinksStore = seriesLinksStore,
            fetchFavourites = { favourites },
            toggleFavourite = { toggled += it },
            searchSources = { query -> results[query].orEmpty() },
            sourcesSignature = { "mangapill|manga_world" },
        ).sync(emptyList())

        assertEquals(1, imported.size)
        assertEquals("Berserk", imported.single().title)
        assertEquals(emptySet<Int>(), syncStore.readFailedImports())
    }

    @Test
    fun `con le stesse fonti l'import fallito non viene ricercato di nuovo`() = runBlocking {
        val favourites = listOf(media(30002, "Serie Introvabile"))
        val searched = mutableListOf<String>()
        fun run(results: Map<String, List<MangaSearchResult>>) = AniListFavoritesSynchronizer(
            syncStore = syncStore,
            seriesLinksStore = seriesLinksStore,
            fetchFavourites = { favourites },
            toggleFavourite = { toggled += it },
            searchSources = { query ->
                searched += query
                results[query].orEmpty()
            },
            sourcesSignature = { "mangapill|manga_world" },
        )

        runBlocking { run(emptyMap()).sync(emptyList()) }
        val afterFirst = searched.size
        runBlocking { run(emptyMap()).sync(emptyList()) }

        assertEquals(
            "l'elenco serve proprio a non ricercare ogni giro ciò che non esiste",
            afterFirst,
            searched.size,
        )
    }

    @Test
    fun `una ricerca fallita per la rete viene ritentata al giro dopo`() = runBlocking {
        val failing = AniListFavoritesSynchronizer(
            syncStore = syncStore,
            seriesLinksStore = seriesLinksStore,
            fetchFavourites = { listOf(media(30002, "Berserk")) },
            toggleFavourite = { toggled += it },
            searchSources = { throw IOException("rete assente") },
        )

        failing.sync(emptyList())

        assertEquals(
            "un errore di rete non è un titolo introvabile",
            emptySet<Int>(),
            syncStore.readFailedImports(),
        )
        assertEquals(emptySet<Int>(), syncStore.readReconciledIds())

        val imported = synchronizer(
            favourites = listOf(media(30002, "Berserk")),
            sourceResults = mapOf("Berserk" to listOf(searchResult("Berserk"))),
        ).sync(emptyList())

        assertEquals(1, imported.size)
    }

    @Test
    fun `un push fallito resta da riprovare`() = runBlocking {
        val failing = AniListFavoritesSynchronizer(
            syncStore = syncStore,
            seriesLinksStore = seriesLinksStore,
            fetchFavourites = { emptyList() },
            toggleFavourite = { throw IOException("rete assente") },
            searchSources = { emptyList() },
        )

        failing.sync(listOf(appFavorite(30002, "Berserk")))

        assertEquals(emptySet<Int>(), syncStore.readReconciledIds())
    }

    @Test
    fun `i preferiti non ancora agganciati ad AniList vengono ignorati`() = runBlocking {
        val titleOnly = FavoriteManga(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Serie senza id",
            mangaUrl = "https://mangapill.com/manga/999",
            coverUrl = null,
            seriesKey = SeriesIdentity.keyForTitle("Serie senza id").orEmpty(),
        )

        synchronizer(favourites = emptyList()).sync(listOf(titleOnly))

        assertEquals(emptyList<Int>(), toggled)
    }

    private fun synchronizer(
        favourites: List<AniListManga>,
        sourceResults: Map<String, List<MangaSearchResult>> = emptyMap(),
    ) = AniListFavoritesSynchronizer(
        syncStore = syncStore,
        seriesLinksStore = seriesLinksStore,
        fetchFavourites = { favourites },
        toggleFavourite = { toggled += it },
        searchSources = { query -> sourceResults[query].orEmpty() },
    )

    private fun media(id: Int, title: String) = AniListManga(
        id = id,
        titleRomaji = title,
        titleEnglish = title,
        coverUrl = null,
        genres = emptyList(),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    private fun searchResult(title: String) = MangaSearchResult(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/${title.hashCode()}",
        coverUrl = null,
    )

    private fun appFavorite(mediaId: Int, title: String) = FavoriteManga(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/$mediaId",
        coverUrl = null,
        seriesKey = SeriesIdentity.keyForAniList(mediaId),
    )
}
