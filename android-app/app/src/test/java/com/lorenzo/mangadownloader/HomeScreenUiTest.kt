package com.lorenzo.mangadownloader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Test Compose UI (Robolectric) su [HomeScreen]: l'empty state mostra la CTA di ricerca e, sotto
 * controllo parentale, il blocco Scopri non compare del tutto.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HomeScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(state: MangaUiState) {
        composeRule.setContent {
            MangaDownloaderTheme {
                HomeScreen(
                    state = state,
                    editMode = false,
                    padding = PaddingValues(),
                    onResume = {},
                    onOpenUpdate = {},
                    onOpenAllUpdates = {},
                    onOpenFavorite = {},
                    onOpenAllFavorites = {},
                    onOpenHistory = {},
                    onOpenSeries = {},
                    onPickDiscover = {},
                    onShowDiscoverInfo = {},
                    onDismissDiscoverInfo = {},
                    onLoadDiscover = {},
                    onSearchFirst = {},
                    onStartTutorial = {},
                    onDismissTutorial = {},
                    onMoveBlock = { _, _ -> },
                    onSetBlockHidden = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun emptyUser_showsSearchCta() {
        render(MangaUiState())
        composeRule.onNodeWithText("Cerca il primo manga").assertExists()
    }

    @Test
    fun parentalControl_hidesDiscoverBlock() {
        render(
            MangaUiState(
                settings = AppSettings(parentalControlEnabled = true),
                discovery = DiscoveryUiState(trending = listOf(sampleAniList())),
            ),
        )
        composeRule.onNodeWithText("Scopri").assertDoesNotExist()
    }

    @Test
    fun libraryWithReadChapters_showsStatsBlock() {
        render(
            MangaUiState(
                library = listOf(sampleSeries()),
            ),
        )
        composeRule.onNodeWithText("Statistiche").assertExists()
        composeRule.onNodeWithText("Capitoli letti").assertExists()
    }
}

private fun sampleAniList() = AniListManga(
    id = 1,
    titleRomaji = "Sample",
    titleEnglish = "Sample",
    coverUrl = null,
    genres = emptyList(),
    averageScore = 80,
    description = null,
    status = MangaPublicationStatus.UNKNOWN,
)

private fun sampleSeries() = DownloadedSeries(
    sourceId = MangaSourceIds.MANGAPILL,
    title = "Berserk",
    mangaUrl = "https://mangapill.com/manga/berserk",
    coverFile = null,
    directory = java.io.File("Berserk"),
    chapters = listOf(
        DownloadedChapter(
            title = "Capitolo 1",
            numberText = "1",
            numberValue = java.math.BigDecimal.ONE,
            volumeText = null,
            labelPrefix = "Capitolo",
            file = java.io.File("1.cbz"),
            relativePath = "b/1.cbz",
            chapterId = "id-1",
            isRead = true,
            readerPageIndex = null,
            readerPageCount = 20,
            lastReadAtMillis = 1_000L,
        ),
    ),
    totalChapterCount = 1,
    readChapterIds = setOf("id-1"),
)
