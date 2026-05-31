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

/** Round-trip e tolleranza agli errori di [FavoriteUpdatesStore]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteUpdatesStoreTest {

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
        val store = FavoriteUpdatesStore(prefs())
        val state = mapOf(
            "mangapill::https://mangapill.com/manga/1" to
                FavoriteSeenState("12", MangaPublicationStatus.ONGOING.name),
            "manga_world::https://www.mangaworld.mx/manga/2" to
                FavoriteSeenState("3.5", MangaPublicationStatus.COMPLETED.name),
        )
        store.write(state)

        val reloaded = FavoriteUpdatesStore(prefs()).read()
        assertEquals(state, reloaded)
    }

    @Test
    fun read_emptyWhenAbsentOrCorrupt() {
        assertTrue(FavoriteUpdatesStore(prefs()).read().isEmpty())

        prefs().edit().putString("favorite_updates_seen_json", "{ not json").apply()
        assertTrue(FavoriteUpdatesStore(prefs()).read().isEmpty())
    }
}
