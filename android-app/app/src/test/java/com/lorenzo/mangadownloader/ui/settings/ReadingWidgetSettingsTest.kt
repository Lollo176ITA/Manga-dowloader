package com.lorenzo.mangadownloader.ui.settings

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Voce del widget nelle impostazioni: pulsante se il launcher lo permette, istruzioni se no. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingWidgetSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setPinSupported(supported: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(AppWidgetManager.getInstance(context)).setRequestPinAppWidgetSupported(supported)
    }

    private fun render() {
        composeRule.setContent { MangaDownloaderTheme { ReadingWidgetSettingsContent() } }
    }

    @Test
    fun launcherSupportsPinning_offersTheButton() {
        setPinSupported(true)
        render()
        composeRule.onNodeWithText("Aggiungi il widget alla Home").assertHasClickAction()
    }

    @Test
    fun launcherWithoutPinning_explainsHowToAddIt() {
        setPinSupported(false)
        render()
        composeRule.onNodeWithText("Widget \"Continua a leggere\"").assertHasNoClickAction()
        composeRule.onNodeWithText(
            "Tieni premuto su uno spazio vuoto della schermata Home, scegli Widget e cerca MangApp.",
        ).assertExists()
    }
}
