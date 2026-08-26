package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Passaggio dei preferiti alla chiave per-serie e promozione ad AniList: sono i due punti in
 * cui un errore costerebbe baseline perse (capitoli ri-notificati) o preferiti doppioni.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoritesSeriesMigrationTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var updatesStore: FavoriteUpdatesStore
    private lateinit var descriptionsStore: FavoriteDescriptionsStore
    private lateinit var feedStore: FavoriteUpdatesFeedStore
    private lateinit var linksStore: SeriesLinksStore
    private lateinit var healthStore: FavoriteSourceHealthStore

    @Before
    fun setUp() {
        val application: Application = ApplicationProvider.getApplicationContext()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        favoritesStore = FavoritesStore(prefs)
        updatesStore = FavoriteUpdatesStore(prefs)
        descriptionsStore = FavoriteDescriptionsStore(prefs)
        feedStore = FavoriteUpdatesFeedStore(prefs)
        linksStore = SeriesLinksStore(prefs)
        healthStore = FavoriteSourceHealthStore(prefs)
    }

    private fun resolver(
        attemptsStore: AniListResolutionAttemptsStore = AniListResolutionAttemptsStore(prefs),
        searchAniList: suspend (String) -> List<AniListManga>,
    ) = FavoriteIdentityResolver(
        favoritesStore = favoritesStore,
        favoriteUpdatesStore = updatesStore,
        favoriteDescriptionsStore = descriptionsStore,
        favoriteSourceHealthStore = healthStore,
        seriesLinksStore = linksStore,
        attemptsStore = attemptsStore,
        searchAniList = searchAniList,
    )

    private fun migration() = FavoritesSeriesMigration(
        prefs = prefs,
        favoritesStore = favoritesStore,
        favoriteUpdatesStore = updatesStore,
        favoriteDescriptionsStore = descriptionsStore,
        favoriteUpdatesFeedStore = feedStore,
        seriesLinksStore = linksStore,
    )

    private fun legacyFavorite(sourceId: String, title: String, url: String) = FavoriteManga(
        sourceId = sourceId,
        title = title,
        mangaUrl = url,
        coverUrl = null,
        addedAt = 1_000L,
    )

    private fun aniListManga(id: Int, romaji: String, english: String) = AniListManga(
        id = id,
        titleRomaji = romaji,
        titleEnglish = english,
        coverUrl = null,
        genres = emptyList(),
        averageScore = null,
        description = null,
        status = MangaPublicationStatus.ONGOING,
    )

    @Test
    fun laBaselineSegueLaSerieNonLaFonte() {
        val favorite = legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")
        favoritesStore.persist(listOf(favorite))
        updatesStore.write(mapOf(favorite.identityKey() to FavoriteSeenState("1050")))

        migration().migrateIfNeeded()

        val baseline = updatesStore.read()
        assertNull("La chiave vecchia per-fonte non deve sopravvivere", baseline[favorite.identityKey()])
        assertEquals("1050", baseline["title:one piece"]?.latestChapterNumber)
    }

    @Test
    fun ogniPreferitoEsceDallaMigrazioneConUnLink() {
        favoritesStore.persist(
            listOf(legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")),
        )

        migration().migrateIfNeeded()

        val link = linksStore.linkFor("title:one piece")
        assertNotNull("Il preferito deve avere un contenitore per i mirror", link)
        assertEquals("mangapill", link?.sources?.single()?.sourceId)
    }

    @Test
    fun duePreferitiDellaStessaSerieSiFondonoTenendoLaDataPiuVecchia() {
        favoritesStore.persist(
            listOf(
                legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")
                    .copy(addedAt = 5_000L),
                legacyFavorite("vymanga", "One Piece", "https://vymanga.com/manga/9")
                    .copy(addedAt = 2_000L),
            ),
        )

        val migrated = migration().migrateIfNeeded()

        assertEquals(1, migrated.size)
        assertEquals(2_000L, migrated.single().addedAt)
    }

    @Test
    fun laFusioneTieneLaBaselinePiuAvanti() {
        val primo = legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")
        val secondo = legacyFavorite("vymanga", "One Piece", "https://vymanga.com/manga/9")
        favoritesStore.persist(listOf(primo, secondo))
        updatesStore.write(
            mapOf(
                primo.identityKey() to FavoriteSeenState("1040"),
                secondo.identityKey() to FavoriteSeenState("1050"),
            ),
        )

        migration().migrateIfNeeded()

        assertEquals("1050", updatesStore.read()["title:one piece"]?.latestChapterNumber)
    }

    @Test
    fun laMigrazioneGiraUnaVoltaSola() {
        favoritesStore.persist(
            listOf(legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")),
        )
        migration().migrateIfNeeded()

        // Una baseline scritta DOPO la migrazione, già con la chiave nuova: un secondo giro
        // la butterebbe via (nessuna chiave vecchia da mappare).
        updatesStore.write(mapOf("title:one piece" to FavoriteSeenState("1100")))
        migration().migrateIfNeeded()

        assertEquals("1100", updatesStore.read()["title:one piece"]?.latestChapterNumber)
    }

    @Test
    fun gliEventiDelFeedRicevonoLaChiaveSerie() {
        val favorite = legacyFavorite("mangapill", "One Piece", "https://mangapill.com/manga/2")
        favoritesStore.persist(listOf(favorite))
        feedStore.write(
            listOf(
                FavoriteUpdateEvent(
                    identityKey = favorite.identityKey(),
                    title = "One Piece",
                    chapterNumber = "1050",
                ),
            ),
        )

        migration().migrateIfNeeded()

        assertEquals("title:one piece", feedStore.read().single().seriesKey)
    }

    @Test
    fun promozioneAniListFondeLeDueMetaDellaStessaSerie() = runBlocking {
        favoritesStore.persist(
            listOf(
                legacyFavorite("mangapill", "Attack on Titan", "https://mangapill.com/manga/7"),
                legacyFavorite("vymanga", "Shingeki no Kyojin", "https://vymanga.com/manga/3"),
            ),
        )
        val favorites = migration().migrateIfNeeded()
        assertEquals("Titoli diversi: prima della promozione restano due preferiti", 2, favorites.size)

        val aot = aniListManga(53390, "Shingeki no Kyojin", "Attack on Titan")
        val resolved = resolver { listOf(aot) }.resolve(favorites)

        assertEquals("Le due metà sono la stessa serie", 1, resolved.size)
        assertEquals("anilist:53390", resolved.single().canonicalKey())
        // I mirror di entrambe le metà finiscono nello stesso link.
        val link = linksStore.linkFor("anilist:53390")
        assertEquals(
            setOf("mangapill", "vymanga"),
            link?.sources?.mapTo(mutableSetOf()) { it.sourceId },
        )
    }

    @Test
    fun laPromozioneNonPerdeLaBaseline() = runBlocking {
        val favorite = legacyFavorite("mangapill", "Attack on Titan", "https://mangapill.com/manga/7")
        favoritesStore.persist(listOf(favorite))
        val favorites = migration().migrateIfNeeded()
        updatesStore.write(mapOf("title:attack on titan" to FavoriteSeenState("139")))

        resolver { listOf(aniListManga(53390, "Shingeki no Kyojin", "Attack on Titan")) }
            .resolve(favorites)

        assertEquals("139", updatesStore.read()["anilist:53390"]?.latestChapterNumber)
        assertNull(updatesStore.read()["title:attack on titan"])
    }

    /**
     * L'avviso "nessuna fonte raggiungibile" non deve sparire dalla card solo perché nel
     * frattempo l'identità della serie è stata promossa ad AniList.
     */
    @Test
    fun laPromozionePortaConSeAncheLAvvisoSullaFonte() = runBlocking {
        favoritesStore.persist(
            listOf(legacyFavorite("mangapill", "Attack on Titan", "https://mangapill.com/manga/7")),
        )
        val favorites = migration().migrateIfNeeded()
        healthStore.write(
            mapOf("title:attack on titan" to FavoriteSourceHealth(consecutiveFailures = 3)),
        )

        resolver { listOf(aniListManga(53390, "Shingeki no Kyojin", "Attack on Titan")) }
            .resolve(favorites)

        assertEquals(3, healthStore.read()["anilist:53390"]?.consecutiveFailures)
        assertNull(healthStore.read()["title:attack on titan"])
    }

    @Test
    fun unTitoloCheAniListNonRiconosceNonVienePiuRicercato() = runBlocking {
        favoritesStore.persist(
            listOf(legacyFavorite("mangapill", "Serie Ignota", "https://mangapill.com/manga/99")),
        )
        val favorites = migration().migrateIfNeeded()
        val attemptsStore = AniListResolutionAttemptsStore(prefs)
        var searches = 0

        repeat(2) {
            resolver(attemptsStore) { searches++; emptyList() }.resolve(favorites)
        }

        assertEquals("Il tentativo non si ripete a ogni giro", 1, searches)
    }

    @Test
    fun unErrorediReteNonConsumaIlTentativo() = runBlocking {
        favoritesStore.persist(
            listOf(legacyFavorite("mangapill", "Serie Ignota", "https://mangapill.com/manga/99")),
        )
        val favorites = migration().migrateIfNeeded()
        val attemptsStore = AniListResolutionAttemptsStore(prefs)

        resolver(attemptsStore) { throw java.io.IOException("rete assente") }.resolve(favorites)

        assertTrue("Con la rete giù si deve poter riprovare", attemptsStore.read().isEmpty())
    }

    @Test
    fun ilMatchAniListRichiedeUnTitoloEsatto() {
        val candidate = aniListManga(21, "One Piece", "One Piece")
        assertNotNull(matchAniListCandidate("one piece", listOf(candidate)))
        assertNull(
            "Niente fuzzy: un'edizione diversa non è la stessa serie",
            matchAniListCandidate("One Piece Colored", listOf(candidate)),
        )
    }
}
