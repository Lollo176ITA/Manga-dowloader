package com.lorenzo.mangadownloader.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.io.File

/**
 * Salva uno screenshot di ogni finestra della composizione (schermata, poi eventuali dialog)
 * in `$SCREENSHOT_DIR/<name>[-n].png`. Senza la variabile d'ambiente non fa niente: serve a
 * guardare a occhio un layout, non è un test di regressione.
 */
fun saveScreenshot(rule: ComposeContentTestRule, name: String) {
    val dir = System.getenv("SCREENSHOT_DIR")?.takeIf(String::isNotBlank) ?: return
    rule.waitForIdle()
    File(dir).mkdirs()
    val roots = rule.onAllNodes(isRoot())
    val count = roots.fetchSemanticsNodes().size
    repeat(count) { index ->
        val bitmap = roots[index].captureToImage().asAndroidBitmap()
        val suffix = if (index == 0) "" else "-$index"
        File(dir, "$name$suffix.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
