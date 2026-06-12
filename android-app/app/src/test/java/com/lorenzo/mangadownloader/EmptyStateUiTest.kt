package com.lorenzo.mangadownloader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Test Compose UI (sotto Robolectric) sull'EmptyState: verifica che titolo e
 * descrizione siano a schermo e che la CTA sia toccabile. Funge anche da test di
 * accessibilità di base (testo leggibile dallo screen reader, azione raggiungibile).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EmptyStateUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyState_showsTitleDescription_andFiresCta() {
        var clicked = false
        composeRule.setContent {
            MangaDownloaderTheme {
                EmptyState(
                    icon = Icons.Default.Search,
                    title = "Nessun preferito",
                    description = "Aggiungi un manga ai preferiti dalla ricerca.",
                    actionLabel = "Cerca manga",
                    onAction = { clicked = true },
                )
            }
        }

        composeRule.onNodeWithText("Nessun preferito").assertExists()
        composeRule.onNodeWithText("Aggiungi un manga ai preferiti dalla ricerca.").assertExists()
        composeRule.onNodeWithText("Cerca manga").performClick()

        assertTrue("La CTA dell'empty state deve invocare onAction", clicked)
    }
}
