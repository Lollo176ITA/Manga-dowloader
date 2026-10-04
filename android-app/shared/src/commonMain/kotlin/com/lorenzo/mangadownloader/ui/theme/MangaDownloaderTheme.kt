package com.lorenzo.mangadownloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.lorenzo.mangadownloader.data.model.ThemeMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MangaDownloaderTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.AUTO -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme: ColorScheme = (if (useDynamicColor) dynamicColorScheme(isDark) else null)
        ?: if (isDark) AppDarkColorScheme else AppLightColorScheme

    SystemBarsAppearance(isDark)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** I colori presi dallo sfondo (Material You), se la piattaforma li offre; altrimenti `null`. */
@Composable
internal expect fun dynamicColorScheme(isDark: Boolean): ColorScheme?

/** Icone di barra di stato e navigazione leggibili sul tema corrente. */
@Composable
internal expect fun SystemBarsAppearance(isDark: Boolean)
