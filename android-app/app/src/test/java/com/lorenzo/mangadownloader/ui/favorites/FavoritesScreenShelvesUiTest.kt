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
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves
import com.lorenzo.mangadownloader.domain.series.FavoriteSort
import com.lorenzo.mangadownloader.testing.saveScreenshot
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Riga degli scaffali nei Preferiti: compare solo se ci sono scaffali e filtra la griglia. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FavoritesScreenShelvesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val onePiece = FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null)
    private val berserk = FavoriteManga("mangapill", "Berserk", "https://mangapill.com/manga/3", null)

    private fun render(shelves: FavoriteShelves) {
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
}
