package com.lorenzo.mangadownloader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// iOS non ha colori dinamici dallo sfondo: si usa sempre la palette dell'app.
@Composable
internal actual fun dynamicColorScheme(isDark: Boolean): ColorScheme? = null

// Lo stile della barra di stato lo decide il view controller che ospita Compose (iosApp).
@Composable
internal actual fun SystemBarsAppearance(isDark: Boolean) = Unit
