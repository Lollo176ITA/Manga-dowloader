package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.data.store.FavoriteDescriptionsStore
import com.lorenzo.mangadownloader.data.store.FavoriteSeenState
import com.lorenzo.mangadownloader.data.store.FavoriteSourceHealth
import com.lorenzo.mangadownloader.data.store.FavoriteSourceHealthStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesFeedStore
import com.lorenzo.mangadownloader.data.store.FavoriteUpdatesStore
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import com.lorenzo.mangadownloader.data.store.SeriesLink
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import com.lorenzo.mangadownloader.data.store.SeriesSourceBinding
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il giro capitoli del worker dei preferiti, con le fonti e le notifiche sostituite da lambda:
 * niente rete, store veri sulle preferenze.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteUpdatesRunTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var updatesStore: FavoriteUpdatesStore
    private lateinit var descriptionsStore: FavoriteDescriptionsStore
    private lateinit var feedStore: FavoriteUpdatesFeedStore
    private lateinit var seriesLinksStore: SeriesLinksStore
    private lateinit var healthStore: FavoriteSourceHealthStore

    /** (preferito notificato, chiave serie, etichetta capitolo). */
    private val notified = mutableListOf<Triple<FavoriteManga, String, String>>()
    private val summaries = mutableListOf<List<FavoriteUpdateEvent>>()
    private val fetchedSourceIds = mutableListOf<String>()

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        favoritesStore = FavoritesStore(prefs)
        updatesStore = FavoriteUpdatesStore(prefs)
        descriptionsStore = FavoriteDescriptionsStore(prefs)
        feedStore = FavoriteUpdatesFeedStore(prefs)
        seriesLinksStore = SeriesLinksStore(prefs)
        healthStore = FavoriteSourceHealthStore(prefs)
        notified.clear()
        summaries.clear()
        fetchedSourceIds.clear()
    }

    @Test
    fun `al primo giro registra la baseline senza notificare`() = runBlocking {
        val berserk = favorite(1, "Berserk")

        run(listOf(berserk)) { details(it, latest = "380", description = "  Guts e la sua spada.  ") }

        assertEquals("380", updatesStore.read()[BERSERK_KEY]?.latestChapterNumber)
        assertEquals("Guts e la sua spada.", descriptionsStore.read()[BERSERK_KEY])
        assertTrue("la baseline non è una novità", notified.isEmpty())
        assertTrue(feedStore.read().isEmpty())
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `un capitolo nuovo aggiorna la baseline, entra nel feed e viene notificato`() = runBlocking {
        val berserk = favorite(1, "Berserk")
        seen(BERSERK_KEY, "380")

        run(listOf(berserk)) { details(it, latest = "381") }

        assertEquals("381", updatesStore.read()[BERSERK_KEY]?.latestChapterNumber)
        assertEquals(listOf(Triple(berserk, BERSERK_KEY, "Capitolo 381")), notified)
        val event = feedStore.read().single()
        assertEquals(BERSERK_KEY, event.seriesKey)
        assertEquals("Berserk", event.title)
        assertEquals("381", event.chapterNumber)
        assertEquals("Capitolo 381", event.chapterLabel)
        assertEquals(NOW, event.timestampMillis)
        assertEquals(false, event.seen)
    }

    @Test
    fun `nessun capitolo nuovo non notifica e non tocca il feed`() = runBlocking {
        seen(BERSERK_KEY, "380")

        run(listOf(favorite(1, "Berserk"))) { details(it, latest = "380") }

        assertTrue(notified.isEmpty())
        assertTrue(feedStore.read().isEmpty())
    }

    @Test
    fun `le serie concluse non vengono nemmeno scaricate`() = runBlocking {
        seen(BERSERK_KEY, "380", MangaPublicationStatus.COMPLETED)

        run(listOf(favorite(1, "Berserk"))) { details(it, latest = "381") }

        assertEquals(emptyList<String>(), fetchedSourceIds)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `se la fonte del preferito non risponde si ripiega sul mirror e lo si promuove`() = runBlocking {
        val berserk = favorite(1, "Berserk")
        favoritesStore.persist(listOf(berserk))
        linkWithMirror(BERSERK_KEY, berserk)
        seen(BERSERK_KEY, "380")

        run(listOf(berserk)) { binding ->
            if (binding.sourceId == MangaSourceIds.MANGAPILL) throw IOException("sito giù")
            details(binding, latest = "381")
        }

        assertEquals(listOf(MangaSourceIds.MANGAPILL, MangaSourceIds.MANGA_WORLD), fetchedSourceIds)
        val stored = favoritesStore.read().single()
        assertEquals("il mirror che ha risposto diventa la fonte del preferito", MangaSourceIds.MANGA_WORLD, stored.sourceId)
        assertEquals(MIRROR_URL, stored.mangaUrl)
        assertEquals("la serie resta la stessa", BERSERK_KEY, stored.seriesKey)
        assertEquals(MangaSourceIds.MANGA_WORLD, seriesLinksStore.linkFor(BERSERK_KEY)?.preferredSourceId)
        assertEquals(
            FavoriteSourceHealth(switchedToSourceId = MangaSourceIds.MANGA_WORLD, switchedAtMillis = NOW),
            healthStore.read()[BERSERK_KEY],
        )
        // Baseline per serie: il cambio di mirror non la fa ripartire, quindi il 381 è una novità.
        val event = feedStore.read().single()
        assertEquals("il tap deve portare dove il capitolo c'è", MangaSourceIds.MANGA_WORLD, event.sourceId)
        assertEquals(MIRROR_URL, event.mangaUrl)
        val (notifiedFavorite, key, label) = notified.single()
        assertEquals(MangaSourceIds.MANGA_WORLD, notifiedFavorite.sourceId)
        assertEquals(MIRROR_URL, notifiedFavorite.mangaUrl)
        assertEquals(BERSERK_KEY, key)
        assertEquals("Capitolo 381", label)
    }

    @Test
    fun `se nessun mirror risponde si conta il fallimento senza cambiare fonte né bloccare gli altri`() = runBlocking {
        val berserk = favorite(1, "Berserk")
        val vagabond = favorite(2, "Vagabond")
        favoritesStore.persist(listOf(berserk, vagabond))
        linkWithMirror(BERSERK_KEY, berserk)
        seen(BERSERK_KEY, "380")
        seen(VAGABOND_KEY, "326")
        healthStore.write(mapOf(BERSERK_KEY to FavoriteSourceHealth(consecutiveFailures = 1)))

        run(listOf(berserk, vagabond)) { binding ->
            if (binding.mangaUrl == vagabond.mangaUrl) details(binding, latest = "327") else throw IOException("rete assente")
        }

        assertEquals(2, healthStore.read()[BERSERK_KEY]?.consecutiveFailures)
        assertEquals(listOf(berserk, vagabond), favoritesStore.read())
        assertNull(seriesLinksStore.linkFor(BERSERK_KEY)?.preferredSourceId)
        assertEquals("380", updatesStore.read()[BERSERK_KEY]?.latestChapterNumber)
        assertEquals("il preferito dopo viene controllato lo stesso", listOf(VAGABOND_KEY), notified.map { it.second })
    }

    @Test
    fun `una risposta buona azzera i fallimenti precedenti`() = runBlocking {
        seen(BERSERK_KEY, "380")
        healthStore.write(mapOf(BERSERK_KEY to FavoriteSourceHealth(consecutiveFailures = 2)))

        run(listOf(favorite(1, "Berserk"))) { details(it, latest = "380") }

        assertNull(healthStore.read()[BERSERK_KEY])
    }

    @Test
    fun `un'interruzione a metà giro conserva i progressi già fatti`() {
        val berserk = favorite(1, "Berserk")
        val vagabond = favorite(2, "Vagabond")
        favoritesStore.persist(listOf(berserk, vagabond))
        linkWithMirror(BERSERK_KEY, berserk)
        seen(BERSERK_KEY, "380")
        seen(VAGABOND_KEY, "326")

        var interrupted = false
        runBlocking {
            try {
                run(listOf(berserk, vagabond)) { binding ->
                    when {
                        binding.mangaUrl == vagabond.mangaUrl -> throw CancellationException("worker fermato")
                        binding.sourceId == MangaSourceIds.MANGAPILL -> throw IOException("sito giù")
                        else -> details(binding, latest = "381", description = "Trama")
                    }
                }
            } catch (_: CancellationException) {
                interrupted = true
            }
        }

        assertTrue("l'annullamento non va inghiottito come un errore di rete", interrupted)
        assertEquals(
            "senza baseline salvata il 381 verrebbe rinotificato al giro dopo",
            "381",
            updatesStore.read()[BERSERK_KEY]?.latestChapterNumber,
        )
        assertEquals("326", updatesStore.read()[VAGABOND_KEY]?.latestChapterNumber)
        assertEquals("Trama", descriptionsStore.read()[BERSERK_KEY])
        assertEquals(listOf(BERSERK_KEY), feedStore.read().map { it.seriesKey })
        assertEquals(listOf(BERSERK_KEY), notified.map { it.second })
        assertEquals(
            "il cambio di mirror già deciso va salvato comunque",
            listOf(MangaSourceIds.MANGA_WORLD, MangaSourceIds.MANGAPILL),
            favoritesStore.read().map { it.sourceId },
        )
        assertEquals(MangaSourceIds.MANGA_WORLD, healthStore.read()[BERSERK_KEY]?.switchedToSourceId)
        assertTrue("il riepilogo si manda solo a giro finito", summaries.isEmpty())
    }

    @Test
    fun `una serie per adulti col filtro attivo finisce nel feed ma non viene notificata`() = runBlocking {
        seen(BERSERK_KEY, "380")

        run(listOf(favorite(1, "Berserk")), adultKeys = setOf(BERSERK_KEY)) { details(it, latest = "381") }

        assertEquals("spegnendo il filtro lo stato deve essere in pari", "381", updatesStore.read()[BERSERK_KEY]?.latestChapterNumber)
        assertEquals(listOf(BERSERK_KEY), feedStore.read().map { it.seriesKey })
        assertTrue(notified.isEmpty())
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `il riepilogo raccoglie i capitoli non visti ed esclude le serie per adulti`() = runBlocking {
        val favorites = listOf(favorite(1, "Berserk"), favorite(2, "Vagabond"), favorite(3, "Vinland Saga"))
        seen(BERSERK_KEY, "380")
        seen(VAGABOND_KEY, "326")
        seen(VINLAND_KEY, "210")

        run(favorites, adultKeys = setOf(VAGABOND_KEY)) { binding ->
            details(binding, latest = if (binding.mangaUrl == favorites[0].mangaUrl) "381" else "999")
        }

        assertEquals(listOf(BERSERK_KEY, VINLAND_KEY), notified.map { it.second })
        assertEquals(3, feedStore.read().size)
        assertEquals(
            setOf(BERSERK_KEY, VINLAND_KEY),
            summaries.single().map { it.seriesKey }.toSet(),
        )
    }

    @Test
    fun `un preferito tolto durante il giro non risorge per il cambio di mirror`() = runBlocking {
        val berserk = favorite(1, "Berserk")
        favoritesStore.persist(listOf(berserk))
        linkWithMirror(BERSERK_KEY, berserk)
        seen(BERSERK_KEY, "380")

        run(listOf(berserk)) { binding ->
            if (binding.sourceId == MangaSourceIds.MANGAPILL) throw IOException("sito giù")
            // L'utente lo toglie dai preferiti mentre il worker è in rete.
            favoritesStore.update { emptyList() }
            details(binding, latest = "381")
        }

        assertEquals(emptyList<FavoriteManga>(), favoritesStore.read())
    }

    @Test
    fun `le fonti spente in impostazioni non vengono interrogate`() = runBlocking {
        val berserk = favorite(1, "Berserk")
        linkWithMirror(BERSERK_KEY, berserk)
        seen(BERSERK_KEY, "380")

        run(listOf(berserk), disabledSourceIds = setOf(MangaSourceIds.MANGAPILL)) { details(it, latest = "380") }

        assertEquals(listOf(MangaSourceIds.MANGA_WORLD), fetchedSourceIds)
    }

    private suspend fun run(
        favorites: List<FavoriteManga>,
        adultKeys: Set<String> = emptySet(),
        disabledSourceIds: Set<String> = emptySet(),
        fetch: suspend (SeriesSourceBinding) -> MangaDetails,
    ) = FavoriteUpdatesRun(
        favoritesStore = favoritesStore,
        updatesStore = updatesStore,
        descriptionsStore = descriptionsStore,
        feedStore = feedStore,
        seriesLinksStore = seriesLinksStore,
        healthStore = healthStore,
        disabledSourceIds = disabledSourceIds,
        adultKeys = adultKeys,
        fetchDetails = { binding ->
            fetchedSourceIds += binding.sourceId
            fetch(binding)
        },
        notifyNewChapter = { favorite, key, label -> notified += Triple(favorite, key, label) },
        notifySummary = { summaries += it },
        now = { NOW },
    ).run(favorites)

    private fun seen(
        key: String,
        latest: String,
        status: MangaPublicationStatus = MangaPublicationStatus.ONGOING,
    ) {
        updatesStore.write(updatesStore.read() + (key to FavoriteSeenState(latest, status.name)))
    }

    /** Aggancia alla serie, oltre alla fonte del preferito, un mirror su MangaWorld. */
    private fun linkWithMirror(key: String, favorite: FavoriteManga) {
        seriesLinksStore.upsert(
            SeriesLink(
                seriesKey = key,
                aniListId = SeriesIdentity.aniListIdFromKey(key),
                canonicalTitle = favorite.title,
                coverUrl = null,
                sources = listOf(
                    SeriesSourceBinding(favorite.sourceId, favorite.mangaUrl),
                    SeriesSourceBinding(MangaSourceIds.MANGA_WORLD, MIRROR_URL),
                ),
            ),
        )
    }

    private fun favorite(mediaId: Int, title: String) = FavoriteManga(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/$mediaId/serie",
        coverUrl = null,
        seriesKey = SeriesIdentity.keyForAniList(mediaId),
    )

    private fun details(
        binding: SeriesSourceBinding,
        latest: String,
        description: String? = null,
    ) = MangaDetails(
        sourceId = binding.sourceId,
        title = "Titolo dalla fonte",
        coverUrl = null,
        mangaUrl = binding.mangaUrl,
        chapters = listOf(chapter("1"), chapter(latest)),
        description = description,
        status = MangaPublicationStatus.ONGOING,
    )

    private fun chapter(number: String) = ChapterEntry(
        numberText = number,
        numberValue = BigDecimal(number),
        url = "https://example.invalid/chapter/$number",
        slug = "chapter-$number",
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MIRROR_URL = "https://www.mangaworld.mx/manga/123/berserk"
        val BERSERK_KEY = SeriesIdentity.keyForAniList(1)
        val VAGABOND_KEY = SeriesIdentity.keyForAniList(2)
        val VINLAND_KEY = SeriesIdentity.keyForAniList(3)
    }
}
