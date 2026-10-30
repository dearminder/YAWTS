package com.dearminder.yawts

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// Material3's baseline palette - used when dynamic color is off or unavailable (API < 31).
private val AppLightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFBFE), onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE), onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

private val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF1C1B1F), onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F), onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

/** The app's colors: the device's Material You palette when enabled and available, else the baseline. */
fun appColorScheme(context: Context, dark: Boolean, dynamicColor: Boolean): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> AppDarkColorScheme
    else -> AppLightColorScheme
}

/**
 * The web page's color variables (see style.css) filled from the app's light and dark schemes, so
 * the page wears the phone's Material You colors. Same selectors as style.css: the light/dark
 * toggle on the page keeps working, and the page's own choice still wins over the system's.
 */
fun webThemeCss(context: Context, dynamicColor: Boolean): String {
    val light = cssVars(appColorScheme(context, dark = false, dynamicColor))
    val dark = cssVars(appColorScheme(context, dark = true, dynamicColor))
    return ":root{$light}" +
        "@media (prefers-color-scheme: dark){:root:not([data-theme=\"light\"]){$dark}}" +
        ":root[data-theme=\"dark\"]{$dark}"
}

private fun cssVars(c: ColorScheme): String = listOf(
    "bg" to c.surface,
    "surface" to c.surfaceContainer,
    "surface-2" to c.surfaceContainerHighest,
    "text" to c.onSurface,
    "muted" to c.onSurfaceVariant,
    "accent" to c.primary,
    "on-accent" to c.onPrimary,
    "border" to c.outlineVariant,
    "danger" to c.error,
    "sel" to c.secondaryContainer
).joinToString("") { (name, color) -> "--$name:${hex(color)};" }

private fun hex(color: Color): String = "#%06x".format(color.toArgb() and 0xFFFFFF)
