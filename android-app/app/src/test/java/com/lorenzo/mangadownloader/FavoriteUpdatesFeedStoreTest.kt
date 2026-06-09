package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round-trip e tolleranza agli errori di [FavoriteUpdatesFeedStore]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteUpdatesFeedStoreTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun roundTrip_writeThenRead() {
        val store = FavoriteUpdatesFeedStore(prefs())
        val events = listOf(
            FavoriteUpdateEvent(
                identityKey = "mangapill::https://mangapill.com/manga/1",
                title = "Berserk",
                sourceId = "mangapill",
                mangaUrl = "https://mangapill.com/manga/1",
                chapterLabel = "Capitolo 12",
                chapterNumber = "12",
                coverUrl = "https://cover/1.jpg",
                timestampMillis = 1_700_000_000_000L,
                seen = false,
            ),
            FavoriteUpdateEvent(
                identityKey = "manga_world::https://www.mangaworld.mx/manga/2",
                title = "One Piece",
                sourceId = "manga_world",
                mangaUrl = "https://www.mangaworld.mx/manga/2",
                chapterLabel = "Capitolo 1100",
                chapterNumber = "1100",
                coverUrl = null,
                timestampMillis = 1_700_000_500_000L,
                seen = true,
            ),
        )
        store.write(events)

        assertEquals(events, FavoriteUpdatesFeedStore(prefs()).read())
    }

    @Test
    fun read_emptyWhenAbsentOrCorrupt() {
        assertTrue(FavoriteUpdatesFeedStore(prefs()).read().isEmpty())

        prefs().edit().putString("favorite_updates_feed_json", "{ not json").apply()
        assertTrue(FavoriteUpdatesFeedStore(prefs()).read().isEmpty())
    }

    @Test
    fun update_readsFreshStateFromDisk_notAStaleCopy() {
        // Scenario della race worker/app: un'altra istanza (l'app) marca tutto come visto
        // mentre il "chiamante" ha in mano una copia vecchia. L'update deve partire dal
        // disco, quindi il flag seen sopravvive all'append del worker.
        val workerStore = FavoriteUpdatesFeedStore(prefs())
        val appStore = FavoriteUpdatesFeedStore(prefs())
        val original = FavoriteUpdateEvent(
            identityKey = "k1",
            title = "Berserk",
            chapterNumber = "12",
            timestampMillis = 1L,
            seen = false,
        )
        workerStore.write(listOf(original))

        appStore.update(::markAllSeen)

        val newEvent = FavoriteUpdateEvent(
            identityKey = "k2",
            title = "One Piece",
            chapterNumber = "1100",
            timestampMillis = 2L,
            seen = false,
        )
        val result = workerStore.update { events -> appendUpdateEvent(events, newEvent) }

        assertEquals(listOf(newEvent, original.copy(seen = true)), result)
        assertEquals(result, FavoriteUpdatesFeedStore(prefs()).read())
    }

    @Test
    fun read_toleratesUnknownExtraField() {
        // Avanti-compatibilità: un campo extra non deve impedire la lettura.
        prefs().edit().putString(
            "favorite_updates_feed_json",
            """[{"identityKey":"k","title":"T","chapterNumber":"5","futureField":"x"}]""",
        ).apply()
        val read = FavoriteUpdatesFeedStore(prefs()).read()
        assertEquals(1, read.size)
        assertEquals("k", read.first().identityKey)
        assertEquals("5", read.first().chapterNumber)
    }
}
