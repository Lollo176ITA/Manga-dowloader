package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Coordinatore backup su store reali (Robolectric): buildBackup, export e restore merge/replace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupManagerTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    private fun manager() = BackupManager(
        favoritesStore = FavoritesStore(prefs()),
        favoriteUpdatesStore = FavoriteUpdatesStore(prefs()),
        favoriteDescriptionsStore = FavoriteDescriptionsStore(prefs()),
        recentSearchesStore = RecentSearchesStore(prefs()),
        settingsStore = SettingsStore(prefs()),
        appVersionName = "1.9.0",
    )

    private fun favorite(title: String, n: Int) =
        FavoriteManga("mangapill", title, "https://mangapill.com/manga/$n", null)

    private fun seedFavoriteA() {
        FavoritesStore(prefs()).persist(listOf(favorite("A", 1)))
        RecentSearchesStore(prefs()).persist(listOf("x"))
        FavoriteUpdatesStore(prefs()).write(mapOf("k" to FavoriteSeenState("3")))
        FavoriteDescriptionsStore(prefs()).write(mapOf("k" to "desc"))
        SettingsStore(prefs()).persist(AppSettings(highResImages = true))
    }

    @Test
    fun buildBackup_capturesSeededData() {
        seedFavoriteA()
        val backup = manager().buildBackup(nowMs = 42L)
        assertEquals(42L, backup.exportedAtMs)
        assertEquals("1.9.0", backup.appVersionName)
        assertEquals(listOf("A"), backup.favorites.map { it.title })
        assertEquals(listOf("x"), backup.recentSearches)
        assertEquals("3", backup.favoriteUpdates["k"]?.latestChapterNumber)
        assertEquals("desc", backup.favoriteDescriptions["k"])
        assertTrue(backup.settings.highResImages)
    }

    @Test
    fun export_emitsDecodableBytes() {
        seedFavoriteA()
        val out = ByteArrayOutputStream()
        manager().export(out, nowMs = 7L)
        val decoded = decodeBackup(out.toString(Charsets.UTF_8.name()))
        assertEquals(7L, decoded?.exportedAtMs)
        assertEquals(listOf("A"), decoded?.favorites?.map { it.title })
    }

    @Test
    fun restore_replaceOverwritesStores() {
        seedFavoriteA()
        val backup = MangaBackup(
            favorites = listOf(FavoriteBackupEntry("mangapill", "B", "https://mangapill.com/manga/2", null)),
            recentSearches = listOf("nuovo"),
            settings = AppSettings(highResImages = false, discoveryEnabled = true).toBackup(),
        )
        val result = manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.REPLACE)

        assertEquals(BackupRestoreMode.REPLACE, result?.mode)
        assertEquals(listOf("B"), FavoritesStore(prefs()).read().map { it.title })
        assertEquals(listOf("nuovo"), RecentSearchesStore(prefs()).read())
        assertTrue(SettingsStore(prefs()).read().discoveryEnabled)
    }

    @Test
    fun restore_mergeKeepsExistingAndAdds() {
        seedFavoriteA()
        val backup = MangaBackup(
            favorites = listOf(
                FavoriteBackupEntry("mangapill", "A dup", "https://mangapill.com/manga/1", null),
                FavoriteBackupEntry("mangapill", "B", "https://mangapill.com/manga/2", null),
            ),
        )
        val result = manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.MERGE)

        assertEquals(1, result?.favoritesAdded)
        assertEquals(listOf("A", "B"), FavoritesStore(prefs()).read().map { it.title })
    }

    @Test
    fun restore_invalidJsonReturnsNullAndLeavesStoresUntouched() {
        seedFavoriteA()
        val result = manager().restore("garbage".byteInputStream(), BackupRestoreMode.MERGE)
        assertNull(result)
        assertEquals(listOf("A"), FavoritesStore(prefs()).read().map { it.title })
    }
}
