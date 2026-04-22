package com.lorenzo.mangadownloader

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val AppLightColorScheme = lightColorScheme(
    primary = Color(0xFF8B4A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF126683),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0E8FF),
    onSecondaryContainer = Color(0xFF001E2B),
    tertiary = Color(0xFF715187),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2D8FF),
    onTertiaryContainer = Color(0xFF2A0D3F),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF5DED2),
    onSurfaceVariant = Color(0xFF53443B),
    outline = Color(0xFF85736A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun MangaDownloaderTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(context)
        darkTheme -> AppDarkColorScheme
        else -> AppLightColorScheme
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
