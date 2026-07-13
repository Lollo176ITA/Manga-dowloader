package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences("settings_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SettingsStore(prefs)
    }

    @Test
    fun persistAndRead_roundTripsEverySetting() {
        val expected = AppSettings(
            searchScope = SearchScope.ALL,
            searchSourceId = MangaSourceIds.MANGA_WORLD,
            autoDownloadEnabled = true,
            autoDownloadTriggerChapters = 7,
            autoDownloadBatchSize = 2,
            smartCleanupEnabled = true,
            smartCleanupKeepPreviousChapters = 1,
            streamingReaderEnabled = true,
            parentalControlEnabled = true,
            parentalPinConfigured = true,
            parentalBiometricEnabled = true,
            parentalPinSalt = "salt",
            parentalPinHash = "hash",
            labsEnabled = true,
            downloadDevUpdates = true,
            highResImages = true,
            privacyBrightnessEnabled = true,
            readerBrightness = 0.4f,
            readingMode = ReadingMode.PAGED_RTL,
            readerPageSpacingDp = 8,
            doubleTapZoomEnabled = true,
            keepScreenOnEnabled = false,
            allowLandscapeRotation = true,
            themeMode = ThemeMode.DARK,
            useDynamicColor = true,
            tutorialCompleted = true,
            favoriteNewChapterNotificationsEnabled = true,
            favoriteSort = FavoriteSort.TITLE_ASC,
            librarySort = LibrarySort.LAST_READ,
            aniListSyncEnabled = false,
            homeBlockOrder = listOf(
                HomeBlock.DISCOVER,
                HomeBlock.RECENT_FAVORITES,
                HomeBlock.FAVORITE_UPDATES,
                HomeBlock.RESUME,
            ),
            hiddenHomeBlocks = setOf(HomeBlock.DISCOVER, HomeBlock.RECENT_FAVORITES),
        )

        store.persist(expected)

        assertEquals(expected, store.read())
        assertNotNull(decodeSettingsBackup(prefs.getString(SettingsStore.KEY_SETTINGS_JSON, null)!!))
    }

    @Test
    fun read_migratesLegacyKeysOnceAndKeepsTheirValues() {
        prefs.edit()
            .putString(SettingsStore.KEY_SEARCH_SCOPE, SearchScope.ENG.name)
            .putString(SettingsStore.KEY_SEARCH_SOURCE_ID, MangaSourceIds.MANGAPILL)
            .putBoolean(SettingsStore.KEY_AUTO_DOWNLOAD_ENABLED, true)
            .putInt(SettingsStore.KEY_AUTO_DOWNLOAD_TRIGGER, 5)
            .putBoolean(SettingsStore.KEY_HIGH_RES_IMAGES, true)
            .putString(SettingsStore.KEY_THEME_MODE, ThemeMode.DARK.name)
            .putString(SettingsStore.KEY_HOME_BLOCK_ORDER, "[\"DISCOVER\",\"RESUME\"]")
            .putString(SettingsStore.KEY_HOME_HIDDEN_BLOCKS, "[\"DISCOVER\"]")
            .putBoolean(SettingsStore.KEY_TUTORIAL_COMPLETED, true)
            .apply()

        val migrated = store.read()

        assertEquals(SearchScope.ENG, migrated.searchScope)
        assertTrue(migrated.autoDownloadEnabled)
        assertEquals(5, migrated.autoDownloadTriggerChapters)
        assertTrue(migrated.highResImages)
        assertEquals(ThemeMode.DARK, migrated.themeMode)
        assertEquals(HomeBlock.DISCOVER, migrated.homeBlockOrder.first())
        assertEquals(setOf(HomeBlock.DISCOVER), migrated.hiddenHomeBlocks)
        assertTrue(migrated.tutorialCompleted)
        assertNotNull(decodeSettingsBackup(prefs.getString(SettingsStore.KEY_SETTINGS_JSON, null)!!))

        // Da questo momento il payload JSON è la fonte di verità delle impostazioni portabili.
        prefs.edit()
            .remove(SettingsStore.KEY_AUTO_DOWNLOAD_ENABLED)
            .remove(SettingsStore.KEY_AUTO_DOWNLOAD_TRIGGER)
            .remove(SettingsStore.KEY_HIGH_RES_IMAGES)
            .remove(SettingsStore.KEY_THEME_MODE)
            .remove(SettingsStore.KEY_HOME_BLOCK_ORDER)
            .remove(SettingsStore.KEY_HOME_HIDDEN_BLOCKS)
            .apply()
        assertEquals(migrated, store.read())
    }

    @Test
    fun portableJson_cannotOverwritePinTutorialOrAniListPreference() {
        store.persist(
            AppSettings(
                parentalPinConfigured = true,
                parentalPinSalt = "device-salt",
                parentalPinHash = "device-hash",
                tutorialCompleted = true,
                aniListSyncEnabled = false,
            ),
        )
        prefs.edit().putString(
            SettingsStore.KEY_SETTINGS_JSON,
            """{
                "themeMode":"LIGHT",
                "parentalPinConfigured":false,
                "parentalPinSalt":"foreign-salt",
                "parentalPinHash":"foreign-hash",
                "tutorialCompleted":false,
                "aniListSyncEnabled":true
            }""".trimIndent(),
        ).apply()

        val restored = store.read()

        assertEquals(ThemeMode.LIGHT, restored.themeMode)
        assertTrue(restored.parentalPinConfigured)
        assertEquals("device-salt", restored.parentalPinSalt)
        assertEquals("device-hash", restored.parentalPinHash)
        assertTrue(restored.tutorialCompleted)
        assertFalse(restored.aniListSyncEnabled)
    }

    @Test
    fun corruptJson_fallsBackToLegacyAndPreservesLocalFields() {
        prefs.edit()
            .putString(SettingsStore.KEY_SETTINGS_JSON, "{broken")
            .putBoolean(SettingsStore.KEY_HIGH_RES_IMAGES, true)
            .putString(SettingsStore.KEY_THEME_MODE, ThemeMode.DARK.name)
            .putBoolean(SettingsStore.KEY_PARENTAL_PIN_CONFIGURED, true)
            .putString(SettingsStore.KEY_PARENTAL_PIN_SALT, "salt")
            .putString(SettingsStore.KEY_PARENTAL_PIN_HASH, "hash")
            .putBoolean(SettingsStore.KEY_TUTORIAL_COMPLETED, true)
            .putBoolean(SettingsStore.KEY_ANILIST_SYNC_ENABLED, false)
            .apply()

        val restored = store.read()

        assertTrue(restored.highResImages)
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertTrue(restored.parentalPinConfigured)
        assertEquals("salt", restored.parentalPinSalt)
        assertEquals("hash", restored.parentalPinHash)
        assertTrue(restored.tutorialCompleted)
        assertFalse(restored.aniListSyncEnabled)
        assertNotNull(decodeSettingsBackup(prefs.getString(SettingsStore.KEY_SETTINGS_JSON, null)!!))
    }
}
