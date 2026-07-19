package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round-trip, merge e promozione di [SeriesLinksStore], più [SeriesLink.initialBinding]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SeriesLinksStoreTest {

    private lateinit var application: Application
    private lateinit var store: SeriesLinksStore

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val prefs = application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
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
                "anilist:53390",
                53390,
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
    fun `promoteToAniList ri-chiava il link title`() {
        store.mergeFromGroup(group("title:attack on titan", null, "mangapill" to "https://mangapill.com/manga/1"), now = 100L)
        val promoted = store.promoteToAniList("title:attack on titan", 53390)!!
        assertEquals("anilist:53390", promoted.seriesKey)
        assertEquals(53390, promoted.aniListId)
        assertNull(store.linkFor("title:attack on titan"))
        assertEquals(1, store.linkFor("anilist:53390")!!.sources.size)
    }

    @Test
    fun `initialBinding preferred poi lingua poi prima`() {
        val link = SeriesLink(
            seriesKey = "anilist:1",
            aniListId = 1,
            canonicalTitle = "X",
            coverUrl = null,
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
