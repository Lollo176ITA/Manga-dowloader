package com.lorenzo.mangadownloader

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF241300),
    primaryContainer = Color(0xFF5F3A00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFF8BC6FF),
    onSecondary = Color(0xFF00213A),
    secondaryContainer = Color(0xFF0F3B5C),
    onSecondaryContainer = Color(0xFFD0E4FF),
    tertiary = Color(0xFFE6B8FF),
    onTertiary = Color(0xFF42006B),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF181B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2E35),
    onSurfaceVariant = Color(0xFFC6C7D0),
    outline = Color(0xFF8C919B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun MangaDownloaderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        AppDarkColorScheme
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
