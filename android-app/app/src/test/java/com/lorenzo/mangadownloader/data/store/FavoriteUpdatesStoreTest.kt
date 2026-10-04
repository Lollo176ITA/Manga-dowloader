package com.lorenzo.mangadownloader.data.store

import com.lorenzo.mangadownloader.platform.AndroidPreferencesSettings
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.model.MangaPublicationStatus
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
        val store = FavoriteUpdatesStore(AndroidPreferencesSettings(prefs()))
        val state = mapOf(
            "mangapill::https://mangapill.com/manga/1" to
                FavoriteSeenState("12", MangaPublicationStatus.ONGOING.name),
            "manga_world::https://www.mangaworld.mx/manga/2" to
                FavoriteSeenState("3.5", MangaPublicationStatus.COMPLETED.name),
        )
        store.write(state)

        val reloaded = FavoriteUpdatesStore(AndroidPreferencesSettings(prefs())).read()
        assertEquals(state, reloaded)
    }

    @Test
    fun read_emptyWhenAbsentOrCorrupt() {
        assertTrue(FavoriteUpdatesStore(AndroidPreferencesSettings(prefs())).read().isEmpty())

        prefs().edit().putString("favorite_updates_seen_json", "{ not json").apply()
        assertTrue(FavoriteUpdatesStore(AndroidPreferencesSettings(prefs())).read().isEmpty())
    }
}
