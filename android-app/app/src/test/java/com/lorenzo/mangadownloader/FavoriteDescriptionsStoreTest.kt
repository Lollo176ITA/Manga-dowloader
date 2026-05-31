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

/** Round-trip e tolleranza agli errori di [FavoriteDescriptionsStore] (trame preferiti persistite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteDescriptionsStoreTest {

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
        val store = FavoriteDescriptionsStore(prefs())
        val descriptions = mapOf(
            "mangapill::https://mangapill.com/manga/1" to "Una storia di vendetta.",
            "vymanga::https://vymanga.com/manga/x" to "Slow life in un altro mondo.",
        )
        store.write(descriptions)

        assertEquals(descriptions, FavoriteDescriptionsStore(prefs()).read())
    }

    @Test
    fun read_emptyWhenAbsentOrCorrupt() {
        assertTrue(FavoriteDescriptionsStore(prefs()).read().isEmpty())

        prefs().edit().putString("favorite_descriptions_json", "{ not json").apply()
        assertTrue(FavoriteDescriptionsStore(prefs()).read().isEmpty())
    }
}
