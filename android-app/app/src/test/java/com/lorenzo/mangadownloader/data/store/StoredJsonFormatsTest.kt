package com.lorenzo.mangadownloader.data.store

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.sources.SourceHealthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I JSON qui sotto sono **congelati**: sono la forma che le versioni già installate hanno
 * scritto sul telefono. Se uno di questi test fallisce dopo aver toccato un modello
 * `@Serializable`, non va aggiornato il JSON: il modello ha smesso di leggere i dati degli
 * utenti (tipicamente un campo nuovo senza valore di default) e al primo salvataggio li
 * cancellerebbe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoredJsonFormatsTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun unreadableJson_isSetAsideBeforeTheDefaultCanOverwriteIt() {
        val broken = """[{"title":"Berserk","mangaUrl":"""
        prefs().edit().putString("favorites_json", broken).commit()
        val store = FavoritesStore(prefs())

        assertTrue(store.read().isEmpty())
        // La scrittura che prima cancellava tutto: la copia deve sopravviverle.
        store.persist(emptyList())

        assertEquals(broken, prefs().getString("favorites_json.illeggibile", null))
        assertEquals(listOf("favorites_json"), prefs().unreadableJsonKeys())
    }

    @Test
    fun readableJson_leavesNoCopyBehind() {
        prefs().edit().putString("recent_searches_json", """["Berserk"]""").commit()

        assertEquals(listOf("Berserk"), RecentSearchesStore(prefs()).read())
        assertTrue(prefs().unreadableJsonKeys().isEmpty())
    }

    @Test
    fun favorites_readTheShapeWrittenBeforeSeriesKeys() {
        stored(
            "favorites_json",
            """[{"sourceId":"mangapill","title":"Berserk","mangaUrl":"https://mangapill.com/manga/1","coverUrl":null,"addedAt":42}]""",
        )

        val favorite = FavoritesStore(prefs()).read().single()

        assertEquals("Berserk", favorite.title)
        assertEquals(42L, favorite.addedAt)
        assertNothingUnreadable()
    }

    @Test
    fun favorites_readTheCurrentShape() {
        stored(
            "favorites_json",
            """[{"sourceId":"mangapill","title":"Berserk","mangaUrl":"https://mangapill.com/manga/1","addedAt":42,"seriesKey":"anilist:30002"}]""",
        )

        assertEquals("anilist:30002", FavoritesStore(prefs()).read().single().seriesKey)
        assertNothingUnreadable()
    }

    @Test
    fun seriesLinks_readTheCurrentShape() {
        stored(
            "series_links_json",
            """{"anilist:30002":{"seriesKey":"anilist:30002","aniListId":30002,"canonicalTitle":"Berserk","sources":[{"sourceId":"mangapill","mangaUrl":"https://mangapill.com/manga/1","addedAt":1}],"preferredSourceId":"mangapill"}}""",
        )

        val link = SeriesLinksStore(prefs()).readAll().values.single()

        assertEquals(30002, link.aniListId)
        assertEquals(1, link.sources.size)
        assertNothingUnreadable()
    }

    @Test
    fun favoriteUpdates_readBaselinesWithAndWithoutStatus() {
        stored(
            "favorite_updates_seen_json",
            """{"anilist:30002":{"latestChapterNumber":"380"},"title:vagabond":{"latestChapterNumber":"327","status":"COMPLETED"}}""",
        )

        val seen = FavoriteUpdatesStore(prefs()).read()

        assertEquals("380", seen.getValue("anilist:30002").latestChapterNumber)
        assertEquals("COMPLETED", seen.getValue("title:vagabond").status)
        assertNothingUnreadable()
    }

    @Test
    fun updatesFeed_readsEventsWrittenBeforeSeriesKeys() {
        stored(
            "favorite_updates_feed_json",
            """[{"identityKey":"mangapill|https://mangapill.com/manga/1","title":"Berserk","sourceId":"mangapill","mangaUrl":"https://mangapill.com/manga/1","chapterLabel":"Capitolo 381","chapterNumber":"381","timestampMillis":7,"seen":false}]""",
        )

        assertEquals("Capitolo 381", FavoriteUpdatesFeedStore(prefs()).read().single().chapterLabel)
        assertNothingUnreadable()
    }

    @Test
    fun shelves_readTheCurrentShape() {
        stored(
            "favorite_shelves_json",
            """{"shelves":[{"id":"s1","name":"Seinen"}],"assignments":{"anilist:30002":["s1"]}}""",
        )

        val shelves = FavoriteShelvesStore(prefs()).read()

        assertEquals("Seinen", shelves.shelves.single().name)
        assertEquals(setOf("s1"), shelves.assignments["anilist:30002"])
        assertNothingUnreadable()
    }

    @Test
    fun sourceHealth_readsTheCurrentShapes() {
        stored(
            "source_reachability_json",
            """{"mangapill":{"consecutiveFailures":2,"failingSinceMillis":1,"lastFailureAtMillis":2,"lastSuccessAtMillis":0}}""",
        )
        stored(
            "favorite_source_health_json",
            """{"anilist:30002":{"consecutiveFailures":1,"switchedToSourceId":"weeb_central","switchedAtMillis":5}}""",
        )

        assertEquals(2, SourceHealthStore(prefs()).read().getValue("mangapill").consecutiveFailures)
        assertEquals(
            "weeb_central",
            FavoriteSourceHealthStore(prefs()).read().getValue("anilist:30002").switchedToSourceId,
        )
        assertNothingUnreadable()
    }

    private fun stored(key: String, json: String) {
        prefs().edit().putString(key, json).commit()
    }

    private fun assertNothingUnreadable() {
        assertEquals(emptyList<String>(), prefs().unreadableJsonKeys())
    }

    private fun prefs() =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
}
