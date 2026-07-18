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
        readingMemoryStore = ReadingMemoryStore(prefs()),
        readingDiaryStore = ReadingDiaryStore(prefs()),
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
            settings = AppSettings(themeMode = ThemeMode.DARK).toBackup(),
        )
        val result = manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.REPLACE)

        assertEquals(BackupRestoreMode.REPLACE, result?.mode)
        assertEquals(listOf("B"), FavoritesStore(prefs()).read().map { it.title })
        assertEquals(listOf("nuovo"), RecentSearchesStore(prefs()).read())
        assertEquals(ThemeMode.DARK, SettingsStore(prefs()).read().themeMode)
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

    @Test
    fun backup_roundTripsReadingMemory() {
        val record = ReadChapterMemory(
            seriesKey = "Berserk",
            seriesTitle = "Berserk",
            chapterLabel = "Capitolo 1",
            pagesRead = 20,
            pageCount = 20,
            isRead = true,
            lastReadAtMillis = 1_000L,
        )
        ReadingMemoryStore(prefs()).persist(mapOf("Berserk/chapter_1.cbz" to record))

        val backup = manager().buildBackup(nowMs = 1L)
        assertEquals(20, backup.readingMemory["Berserk/chapter_1.cbz"]?.pagesRead)

        prefs().edit().clear().commit()
        manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.REPLACE)
        assertEquals(mapOf("Berserk/chapter_1.cbz" to record), ReadingMemoryStore(prefs()).read())
    }

    @Test
    fun backup_roundTripsReadingDiary_withMonotoneMerge() {
        ReadingDiaryStore(prefs()).persist(
            mapOf("2026-07-15" to ReadingDayStats(chaptersRead = 2, pagesRead = 30)),
        )
        val backup = manager().buildBackup(nowMs = 1L)
        assertEquals(2, backup.readingDiary["2026-07-15"]?.chaptersRead)

        // Restore di un backup più vecchio: per giorno vince il massimo, mai regressioni.
        val older = MangaBackup(
            readingDiary = mapOf(
                "2026-07-15" to ReadingDiaryBackupEntry(chaptersRead = 1, pagesRead = 99),
                "2026-07-10" to ReadingDiaryBackupEntry(chaptersRead = 5, pagesRead = 50),
            ),
        )
        manager().restore(encodeBackup(older).byteInputStream(), BackupRestoreMode.REPLACE)
        val restored = ReadingDiaryStore(prefs()).read()
        assertEquals(ReadingDayStats(2, 99), restored["2026-07-15"])
        assertEquals(ReadingDayStats(5, 50), restored["2026-07-10"])
    }

    @Test
    fun restore_mergeReadingMemoryIsMonotone() {
        ReadingMemoryStore(prefs()).persist(
            mapOf(
                "S/c1.cbz" to ReadChapterMemory("S", "S", "Capitolo 1", 10, 20, false, 5_000L),
            ),
        )
        val backup = MangaBackup(
            readingMemory = mapOf(
                "S/c1.cbz" to ReadingMemoryBackupEntry("S", "S", "Capitolo 1", 20, 20, true, 1_000L),
                "S/c2.cbz" to ReadingMemoryBackupEntry("S", "S", "Capitolo 2", 3, 30, false, 2_000L),
            ),
        )
        manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.MERGE)

        val restored = ReadingMemoryStore(prefs()).read()
        // Merge monotono: pagine/letto al massimo, timestamp più recente vince.
        assertEquals(20, restored["S/c1.cbz"]?.pagesRead)
        assertEquals(true, restored["S/c1.cbz"]?.isRead)
        assertEquals(5_000L, restored["S/c1.cbz"]?.lastReadAtMillis)
        assertEquals(3, restored["S/c2.cbz"]?.pagesRead)
    }

    @Test
    fun backup_roundTripsHomeBlockConfig() {
        SettingsStore(prefs()).persist(
            AppSettings(
                homeBlockOrder = listOf(
                    HomeBlock.DISCOVER, HomeBlock.RESUME, HomeBlock.FAVORITE_UPDATES,
                    HomeBlock.RECOMMENDED, HomeBlock.STATS, HomeBlock.HISTORY,
                ),
                hiddenHomeBlocks = setOf(HomeBlock.DISCOVER),
            ),
        )
        val backup = manager().buildBackup(nowMs = 1L)
        assertEquals(
            listOf(
                "DISCOVER", "RESUME", "FAVORITE_UPDATES",
                "RECOMMENDED", "STATS", "HISTORY",
            ),
            backup.settings.homeBlockOrder,
        )

        prefs().edit().clear().commit()
        manager().restore(encodeBackup(backup).byteInputStream(), BackupRestoreMode.REPLACE)
        val restored = SettingsStore(prefs()).read()
        assertEquals(
            listOf(
                HomeBlock.DISCOVER, HomeBlock.RESUME, HomeBlock.FAVORITE_UPDATES,
                HomeBlock.RECOMMENDED, HomeBlock.STATS, HomeBlock.HISTORY,
            ),
            restored.homeBlockOrder,
        )
        assertEquals(setOf(HomeBlock.DISCOVER), restored.hiddenHomeBlocks)
    }
}
