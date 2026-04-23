package com.lorenzo.mangadownloader

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MangaDownloaderTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.AUTO -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val canUseDynamic = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme: ColorScheme = when {
        canUseDynamic && isDark -> dynamicDarkColorScheme(context)
        canUseDynamic && !isDark -> dynamicLightColorScheme(context)
        isDark -> AppDarkColorScheme
        else -> AppLightColorScheme
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
