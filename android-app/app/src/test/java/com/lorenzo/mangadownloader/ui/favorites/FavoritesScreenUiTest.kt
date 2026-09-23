package com.lorenzo.mangadownloader.ui.favorites

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.anilist.UnmatchedAniListFavorite
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves
import com.lorenzo.mangadownloader.domain.series.FavoriteSort
import com.lorenzo.mangadownloader.testing.saveScreenshot
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Preferiti: riga degli scaffali (solo se esistono, filtra la griglia) e gruppo "Senza scan". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FavoritesScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val onePiece = FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null)
    private val berserk = FavoriteManga("mangapill", "Berserk", "https://mangapill.com/manga/3", null)

    private fun render(
        shelves: FavoriteShelves,
        unmatched: List<UnmatchedAniListFavorite> = emptyList(),
        onPickUnmatched: (UnmatchedAniListFavorite) -> Unit = {},
    ) {
        composeRule.setContent {
            var filter by remember { mutableStateOf<String?>(null) }
            MangaDownloaderTheme {
                FavoritesScreen(
                    favorites = listOf(onePiece, berserk),
                    query = "",
                    filterReadingState = null,
                    sort = FavoriteSort.TITLE_ASC,
                    statusByKey = emptyMap(),
                    seenByKey = emptyMap(),
                    readingStateByKey = emptyMap(),
                    noticesByKey = emptyMap(),
                    padding = PaddingValues(),
                    onQueryChange = {},
                    onSelect = {},
                    onBrowse = {},
                    onSelectSort = {},
                    onSelectReadingState = {},
                    onReadNow = {},
                    onRemoveFavorite = {},
                    shelves = shelves,
                    filterShelfId = filter,
                    onSelectShelf = { filter = it },
                    unmatchedAniList = unmatched,
                    onPickUnmatched = onPickUnmatched,
                )
            }
        }
    }

    @Test
    fun noShelves_noShelfRow() {
        render(FavoriteShelves())
        composeRule.onNodeWithContentDescription("Gestisci scaffali").assertDoesNotExist()
    }

    @Test
    fun shelfChip_filtersTheGrid() {
        val shelves = FavoriteShelves()
            .withNewShelf("Da rileggere", "a")!!
            .withNewShelf("Vacanze", "b")!!
            .withShelvesFor(onePiece, setOf("a"))
        render(shelves)
        composeRule.onNodeWithText("Berserk").assertExists()
        saveScreenshot(composeRule, "favorites-shelves")

        composeRule.onNodeWithText("Da rileggere · 1").performClick()

        composeRule.onNodeWithText("One Piece").assertExists()
        composeRule.onNodeWithText("Berserk").assertDoesNotExist()
        composeRule.onNodeWithText("Vacanze · 0").assertExists()
    }

    @Test
    fun actionsDialog_offersShelves() {
        render(FavoriteShelves().withNewShelf("Vacanze", "b")!!)
        composeRule.onNodeWithText("Berserk").assertExists()
        // Ordine A-Z: la prima card è Berserk.
        composeRule.onAllNodesWithContentDescription("Altre azioni", useUnmergedTree = true)[0]
            .performClick()
        composeRule.onNodeWithText("Su nessuno scaffale").assertExists()
        composeRule.onNodeWithText("Scaffali").performClick()
        composeRule.onNodeWithText("Nuovo scaffale").assertExists()
    }

    @Test
    fun unmatchedAniList_collapsedByDefault_expandsAndPicks() {
        val picked = mutableListOf<Int>()
        render(
            FavoriteShelves(),
            unmatched = listOf(UnmatchedAniListFavorite(id = 7, titleEnglish = "Titolo introvabile")),
            onPickUnmatched = { picked += it.id },
        )
        composeRule.onNodeWithText("Senza scan · 1").assertExists()
        composeRule.onNodeWithText("Titolo introvabile").assertDoesNotExist()

        composeRule.onNodeWithText("Senza scan · 1").performClick()
        saveScreenshot(composeRule, "favorites-unmatched")
        composeRule.onNodeWithText("Titolo introvabile").performClick()

        assertEquals(listOf(7), picked)
    }
}
