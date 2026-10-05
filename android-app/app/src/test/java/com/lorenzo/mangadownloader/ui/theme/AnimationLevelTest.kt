package com.lorenzo.mangadownloader.ui.theme

import com.lorenzo.mangadownloader.app.AppSettings
import com.lorenzo.mangadownloader.data.backup.applyTo
import com.lorenzo.mangadownloader.data.backup.toBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationLevelTest {

    @After
    fun resetMotion() {
        AppMotion.level = AnimationLevel.FULL
        AppMotion.systemScale = 1f
    }

    @Test
    fun backupRoundTrip_keepsLevel() {
        val restored = AppSettings(animationLevel = AnimationLevel.NONE).toBackup().applyTo(AppSettings())
        assertEquals(AnimationLevel.NONE, restored.animationLevel)
        // Default per chi non ha mai scelto (o per un backup vecchio senza il campo).
        assertEquals(AnimationLevel.FULL, AppSettings().animationLevel)
    }

    @Test
    fun backupWithUnknownLevel_keepsCurrent() {
        val backup = AppSettings().toBackup().copy(animationLevel = "SPARKLY")
        val current = AppSettings(animationLevel = AnimationLevel.REDUCED)
        assertEquals(AnimationLevel.REDUCED, backup.applyTo(current).animationLevel)
    }

    @Test
    fun durationScale_noneIsZeroWhateverTheSystemSays() {
        AppMotion.level = AnimationLevel.NONE
        AppMotion.systemScale = 2f
        assertEquals(0f, AppMotion.durationScale.scaleFactor)
    }

    @Test
    fun durationScale_otherLevelsFollowTheSystem() {
        AppMotion.systemScale = 0.5f
        AppMotion.level = AnimationLevel.FULL
        assertEquals(0.5f, AppMotion.durationScale.scaleFactor)
        AppMotion.level = AnimationLevel.REDUCED
        assertEquals(0.5f, AppMotion.durationScale.scaleFactor)
        // "Rimuovi animazioni" di Android vale anche con le animazioni complete.
        AppMotion.systemScale = 0f
        AppMotion.level = AnimationLevel.FULL
        assertEquals(0f, AppMotion.durationScale.scaleFactor)
    }
}
