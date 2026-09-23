package com.lorenzo.mangadownloader.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import com.lorenzo.mangadownloader.domain.reading.ResumeReadingItem
import com.lorenzo.mangadownloader.domain.reading.ResumeTarget
import com.lorenzo.mangadownloader.testing.saveScreenshot
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La card "Riprendi" della Home, nelle due taglie: riferimento visivo per il widget. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h400dp-xxhdpi")
class HomeResumeCardScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val item = ResumeReadingItem(
        seriesTitle = "Berserk",
        chapterLabel = "Capitolo 12",
        coverModel = null,
        pageIndex = 7,
        pageCount = 40,
        lastReadAtMillis = 1L,
        target = ResumeTarget.Streaming(
            ReadChapterMemory("s", "Berserk", "Capitolo 12", 8, 40, false, 1L),
        ),
    )

    private fun render(compact: Boolean, name: String) {
        composeRule.setContent {
            MangaDownloaderTheme {
                Box(Modifier.background(Color(0xFFF1F1F1)).padding(8.dp)) {
                    HomeResumeCard(item = item, onResume = {}, compact = compact)
                }
            }
        }
        composeRule.onNodeWithText("Berserk").assertExists()
        saveScreenshot(composeRule, name)
    }

    @Test
    fun compact() = render(compact = true, name = "home-resume-compact")

    @Test
    fun full() = render(compact = false, name = "home-resume-full")
}
