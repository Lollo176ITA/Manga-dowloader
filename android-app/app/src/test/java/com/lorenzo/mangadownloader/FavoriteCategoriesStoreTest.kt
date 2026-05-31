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

/** Round-trip e seeding di default di [FavoriteCategoriesStore]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoriteCategoriesStoreTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun readCategories_seedsDefaultsWhenAbsentWithoutPersisting() {
        val store = FavoriteCategoriesStore(prefs())
        assertEquals(DefaultFavoriteCategories.items, store.readCategories())
        // Non deve aver persistito nulla finché non si scrive.
        assertTrue(prefs().getString("favorite_categories_json", null) == null)
    }

    @Test
    fun categories_roundTrip() {
        val store = FavoriteCategoriesStore(prefs())
        val categories = listOf(
            FavoriteCategory("cat_a", "Alfa", 0),
            FavoriteCategory("cat_b", "Beta", 1),
        )
        store.writeCategories(categories)
        assertEquals(categories, FavoriteCategoriesStore(prefs()).readCategories())
    }

    @Test
    fun assignments_roundTrip() {
        val store = FavoriteCategoriesStore(prefs())
        val assignments = mapOf(
            "mangapill::https://mangapill.com/manga/1" to "cat_reading",
            "manga_world::https://www.mangaworld.mx/manga/2" to "cat_completed",
        )
        store.writeAssignments(assignments)
        assertEquals(assignments, FavoriteCategoriesStore(prefs()).readAssignments())
    }

    @Test
    fun read_toleratesCorruptJson() {
        prefs().edit()
            .putString("favorite_categories_json", "{ not json")
            .putString("favorite_category_assignments_json", "{ not json")
            .apply()
        val store = FavoriteCategoriesStore(prefs())
        assertEquals(DefaultFavoriteCategories.items, store.readCategories())
        assertTrue(store.readAssignments().isEmpty())
    }
}
