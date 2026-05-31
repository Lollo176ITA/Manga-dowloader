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

    private fun details() = MangaDetails(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "X",
        coverUrl = null,
        mangaUrl = "https://mangapill.com/manga/1",
        chapters = emptyList(),
    )

    private fun series() = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "X",
        mangaUrl = "https://mangapill.com/manga/1",
        coverFile = null,
        directory = File("X"),
        chapters = emptyList(),
        totalChapterCount = 0,
        readChapterIds = emptySet(),
    )
}
