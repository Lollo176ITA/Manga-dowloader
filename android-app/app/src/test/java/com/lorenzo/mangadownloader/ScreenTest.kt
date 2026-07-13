package com.lorenzo.mangadownloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Derivazione della schermata in primo piano dallo stato: gerarchia di priorità e tasto
 * indietro. Pura → testabile su JVM (niente Robolectric).
 */
class ScreenTest {

    @Test
    fun currentScreen_emptyStateIsTabs() {
        assertEquals(Screen.Tabs, MangaUiState().currentScreen())
    }

    @Test
    fun currentScreen_storageManagerSitsAboveSettings() {
        assertEquals(Screen.Settings, MangaUiState(showSettings = true).currentScreen())
        assertEquals(
            Screen.StorageManager,
            MangaUiState(showSettings = true, showStorageManager = true).currentScreen(),
        )
    }

    @Test
    fun currentScreen_detailWhenSelected() {
        assertEquals(Screen.Detail, MangaUiState(selected = details()).currentScreen())
    }

    @Test
    fun currentScreen_downloadedSeriesOnlyInLibraryTab() {
        assertEquals(
            Screen.DownloadedSeries,
            MangaUiState(currentTab = AppTab.LIBRARY, selectedDownloadedSeries = series()).currentScreen(),
        )
        // Stessa selezione ma in un'altra tab → restiamo sulle tab.
        assertEquals(
            Screen.Tabs,
            MangaUiState(currentTab = AppTab.SEARCH, selectedDownloadedSeries = series()).currentScreen(),
        )
    }

    @Test
    fun currentScreen_backupAboveSettingsBelowStorage() {
        assertEquals(
            Screen.Backup,
            MangaUiState(showSettings = true, showBackup = true).currentScreen(),
        )
        assertEquals(
            Screen.StorageManager,
            MangaUiState(showBackup = true, showStorageManager = true).currentScreen(),
        )
    }

    @Test
    fun currentScreen_updatesAboveTabsBelowDetail() {
        assertEquals(Screen.Updates, MangaUiState(showUpdates = true).currentScreen())
        // Aprendo un manga dal feed: showUpdates viene azzerato e selected impostato → Detail.
        assertEquals(
            Screen.Detail,
            MangaUiState(showUpdates = false, selected = details()).currentScreen(),
        )
        // Le impostazioni stanno sopra il feed.
        assertEquals(
            Screen.Settings,
            MangaUiState(showUpdates = true, showSettings = true).currentScreen(),
        )
    }

    @Test
    fun canHandleBack_trueWhenUpdatesOpen() {
        assertTrue(MangaUiState(showUpdates = true).canHandleBack())
    }

    @Test
    fun currentScreen_readerWinsOverEverything() {
        assertEquals(
            Screen.Reader,
            MangaUiState(
                readerChapter = ReaderChapter(title = "Cap 1", relativePath = "s/1.cbz"),
                showSettings = true,
                showStorageManager = true,
                selected = details(),
            ).currentScreen(),
        )
    }

    @Test
    fun canHandleBack_falseOnlyOnTabs() {
        assertFalse(MangaUiState().canHandleBack())
        assertTrue(MangaUiState(showSettings = true).canHandleBack())
        assertTrue(MangaUiState(selected = details()).canHandleBack())
        assertTrue(
            MangaUiState(readerChapter = ReaderChapter(title = "x", relativePath = "y")).canHandleBack(),
        )
    }

    @Test
    fun saveableScreenKey_distinctPerScreenType() {
        // Ogni schermata "semplice" ha una chiave stabile e diversa dalle altre: così il
        // SaveableStateHolder tiene separate le loro posizioni di scroll.
        val keys = listOf(
            MangaUiState().saveableScreenKey(),
            MangaUiState(showSettings = true).saveableScreenKey(),
            MangaUiState(showStorageManager = true).saveableScreenKey(),
            MangaUiState(showUpdates = true).saveableScreenKey(),
            MangaUiState(showBackup = true).saveableScreenKey(),
            MangaUiState(showChangelog = true).saveableScreenKey(),
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun saveableScreenKey_detailQualifiedByManga() {
        // Due manga diversi → chiavi diverse: tornando indietro non si scambiano lo scroll.
        val a = MangaUiState(selected = details("https://mangapill.com/manga/1")).saveableScreenKey()
        val b = MangaUiState(selected = details("https://mangapill.com/manga/2")).saveableScreenKey()
        assertTrue(a.startsWith("Detail:"))
        assertEquals("Detail:https://mangapill.com/manga/1", a)
        assertTrue(a != b)
    }

    @Test
    fun saveableScreenKey_downloadedSeriesQualifiedByDirectory() {
        val a = MangaUiState(
            currentTab = AppTab.LIBRARY,
            selectedDownloadedSeries = series(File("A")),
        ).saveableScreenKey()
        val b = MangaUiState(
            currentTab = AppTab.LIBRARY,
            selectedDownloadedSeries = series(File("B")),
        ).saveableScreenKey()
        assertTrue(a.startsWith("DownloadedSeries:"))
        assertTrue(a != b)
    }

    @Test
    fun saveableScreenKey_readerStableAcrossChapters() {
        // Il reader resta a chiave stabile (la posizione la ripristina il ViewModel): cambiare
        // capitolo NON deve cambiare la chiave, o si romperebbe la transizione animata.
        val cap1 = MangaUiState(
            readerChapter = ReaderChapter(title = "Cap 1", relativePath = "s/1.cbz"),
        ).saveableScreenKey()
        val cap2 = MangaUiState(
            readerChapter = ReaderChapter(title = "Cap 2", relativePath = "s/2.cbz"),
        ).saveableScreenKey()
        assertEquals(cap1, cap2)
    }

    @Test
    fun showHistory_isHistoryScreen_andBelowReader() {
        val state = MangaUiState(showHistory = true)
        assertEquals(Screen.History, state.currentScreen())
        assertTrue(state.canHandleBack())
        // Il reader aperto dalla cronologia sta sopra.
        assertEquals(
            Screen.Reader,
            state.copy(
                readerChapter = ReaderChapter(title = "Cap 1", relativePath = "s/1.cbz"),
            ).currentScreen(),
        )
    }

    @Test
    fun visibleTabs_withHomeDisabled_excludesHomeAndReindexes() {
        val state = MangaUiState(settings = AppSettings(showHomeTab = false))
        assertEquals(listOf(AppTab.SEARCH, AppTab.FAVORITES, AppTab.LIBRARY), state.visibleTabs())
        assertEquals(0, state.tabPageIndex(AppTab.SEARCH))
        assertEquals(0, state.tabPageIndex(AppTab.HOME)) // non visibile → coerce a 0
    }

    @Test
    fun selectedGenre_isDiscoverGenreScreen_withPerGenreSaveableKey() {
        val state = MangaUiState(
            discovery = DiscoveryUiState(selectedGenre = DiscoverGenre.FANTASY),
        )
        assertEquals(Screen.DiscoverGenre, state.currentScreen())
        assertTrue(state.canHandleBack())
        assertEquals("DiscoverGenre:Fantasy", state.saveableScreenKey())
    }

    private fun details(mangaUrl: String = "https://mangapill.com/manga/1") = MangaDetails(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "X",
        coverUrl = null,
        mangaUrl = mangaUrl,
        chapters = emptyList(),
    )

    private fun series(directory: File = File("X")) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "X",
        mangaUrl = "https://mangapill.com/manga/1",
        coverFile = null,
        directory = directory,
        chapters = emptyList(),
        totalChapterCount = 0,
        readChapterIds = emptySet(),
    )
}
