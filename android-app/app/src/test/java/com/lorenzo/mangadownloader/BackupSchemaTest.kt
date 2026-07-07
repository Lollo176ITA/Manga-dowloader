package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core puro del backup: round-trip, tolleranza, mapping impostazioni e logica di merge. JVM. */
class BackupSchemaTest {

    private fun sampleBackup() = MangaBackup(
        exportedAtMs = 123L,
        appVersionName = "1.9.0",
        favorites = listOf(
            FavoriteBackupEntry("mangapill", "Berserk", "https://mangapill.com/manga/1", "c1"),
        ),
        favoriteUpdates = mapOf("k1" to FavoriteSeenState("12", MangaPublicationStatus.ONGOING.name)),
        favoriteDescriptions = mapOf("k1" to "Una trama"),
        recentSearches = listOf("berserk", "naruto"),
        settings = AppSettings(themeMode = ThemeMode.AUTO).toBackup(),
    )

    @Test
    fun encodeDecode_roundTripsAllFields() {
        val backup = sampleBackup()
        val decoded = decodeBackup(encodeBackup(backup))
        assertEquals(backup, decoded)
    }

    @Test
    fun decode_returnsNullOnMalformedJson() {
        assertNull(decodeBackup("{ not json"))
    }

    @Test
    fun decode_ignoresUnknownKeysAndFillsMissingDefaults() {
        val decoded = decodeBackup("""{"appVersionName":"x","futureField":42}""")
        assertEquals("x", decoded?.appVersionName)
        assertTrue(decoded!!.favorites.isEmpty())
        assertEquals(BACKUP_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun decode_rejectsHigherSchemaVersion() {
        assertNull(decodeBackup("""{"schemaVersion":999}"""))
        assertEquals(0, decodeBackup("""{"schemaVersion":1}""")?.favorites?.size)
    }

    @Test
    fun settings_toBackupThenApplyTo_isIdentityForDefaults() {
        assertEquals(AppSettings(), AppSettings().toBackup().applyTo(AppSettings()))
    }

    @Test
    fun applyTo_neverOverwritesSecretsOrTutorial() {
        val current = AppSettings(
            parentalPinHash = "hash",
            parentalPinSalt = "salt",
            parentalPinConfigured = true,
            tutorialCompleted = true,
        )
        val restored = AppSettings(
            parentalPinHash = null,
            parentalPinSalt = null,
            parentalPinConfigured = false,
            tutorialCompleted = false,
        ).toBackup().applyTo(current)
        assertEquals("hash", restored.parentalPinHash)
        assertEquals("salt", restored.parentalPinSalt)
        assertTrue(restored.parentalPinConfigured)
        assertTrue(restored.tutorialCompleted)
    }

    @Test
    fun applyTo_reCoercesOutOfRangeValues() {
        val tampered = SettingsBackup(
            readerBrightness = 5f,
            autoDownloadTriggerChapters = 0,
            autoDownloadBatchSize = -2,
            smartCleanupKeepPreviousChapters = -1,
        )
        val restored = tampered.applyTo(AppSettings())
        assertEquals(1f, restored.readerBrightness, 0.0001f)
        assertEquals(1, restored.autoDownloadTriggerChapters)
        assertEquals(1, restored.autoDownloadBatchSize)
        assertEquals(0, restored.smartCleanupKeepPreviousChapters)
    }

    @Test
    fun applyTo_fallsBackToCurrentEnumOnUnknownName() {
        val restored = SettingsBackup(themeMode = "NOPE", readingMode = "WAT")
            .applyTo(AppSettings(themeMode = ThemeMode.AUTO, readingMode = ReadingMode.VERTICAL))
        assertEquals(ThemeMode.AUTO, restored.themeMode)
        assertEquals(ReadingMode.VERTICAL, restored.readingMode)
    }

    @Test
    fun applyTo_restoresSearchScopeAndFallsBackOnUnknown() {
        // Le chip per fonte singola non esistono più: uno scope SOURCE in un backup di
        // una versione precedente torna alla lingua della fonte selezionata.
        val restored = AppSettings(
            searchScope = SearchScope.SOURCE,
            searchSourceId = MangaSourceIds.MANGA_WORLD,
        ).toBackup().applyTo(AppSettings())
        assertEquals(SearchScope.ITA, restored.searchScope)
        assertEquals(MangaSourceIds.MANGA_WORLD, restored.searchSourceId)

        // Scope sconosciuto (backup manomesso o versione futura) → resta quello corrente.
        val tampered = SettingsBackup(searchScope = "KLINGON")
            .applyTo(AppSettings(searchScope = SearchScope.ENG))
        assertEquals(SearchScope.ENG, tampered.searchScope)
    }

    @Test
    fun applyTo_doesNotEnableParentalControlWithoutPin() {
        val restored = SettingsBackup(parentalControlEnabled = true)
            .applyTo(AppSettings(parentalPinConfigured = false))
        assertFalse(restored.parentalControlEnabled)

        val withPin = SettingsBackup(parentalControlEnabled = true)
            .applyTo(AppSettings(parentalPinConfigured = true))
        assertTrue(withPin.parentalControlEnabled)
    }

    @Test
    fun mergeFavorites_dedupesByIdentityKeyAndKeepsOrder() {
        val current = listOf(FavoriteManga("mangapill", "A", "https://mangapill.com/manga/1", null))
        val incoming = listOf(
            FavoriteBackupEntry("mangapill", "A dup", "https://mangapill.com/manga/1", null),
            FavoriteBackupEntry("mangapill", "B", "https://mangapill.com/manga/2", null),
        )
        val merged = mergeFavorites(current, incoming)
        assertEquals(listOf("A", "B"), merged.map { it.title })
    }

    @Test
    fun mergeRecentSearches_dedupesCaseInsensitiveAndCaps() {
        val current = listOf("a", "b")
        val incoming = listOf("B", "c", "d", "e", "f", "g", "h", "i", "j")
        val merged = mergeRecentSearches(current, incoming)
        assertEquals(RecentSearchesStore.MAX_RECENT_SEARCHES, merged.size)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h"), merged)
    }

    @Test
    fun mergeFavoriteUpdates_keepsHigherChapterNumber() {
        val current = mapOf("k" to FavoriteSeenState("5", MangaPublicationStatus.ONGOING.name))
        val lower = mergeFavoriteUpdates(current, mapOf("k" to FavoriteSeenState("3")))
        assertEquals("5", lower["k"]?.latestChapterNumber)
        val higher = mergeFavoriteUpdates(current, mapOf("k" to FavoriteSeenState("9")))
        assertEquals("9", higher["k"]?.latestChapterNumber)
        val added = mergeFavoriteUpdates(current, mapOf("k2" to FavoriteSeenState("1")))
        assertEquals(2, added.size)
    }
}
