package com.nik.carradio.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Flat dark themes for a car: dark backgrounds (no glare at night), one accent, no gradients
 * or shadows (cheap to draw on a Mali-T720). "Night red" keeps the screen dim and red for
 * night driving.
 */
@Immutable
data class AppTheme(val id: String, val name: String, val accent: Color, val background: Color, val surface: Color, val surfaceVariant: Color)

val THEMES = listOf(
    AppTheme("ocean", "Ocean", Color(0xFF42A5F5), Color(0xFF101418), Color(0xFF1A2027), Color(0xFF232B33)),
    AppTheme("emerald", "Emerald", Color(0xFF34C77B), Color(0xFF0E1512), Color(0xFF16211B), Color(0xFF1F2E26)),
    AppTheme("amber", "Amber", Color(0xFFFFB300), Color(0xFF14120D), Color(0xFF201C13), Color(0xFF2C271B)),
    AppTheme("crimson", "Crimson", Color(0xFFEF5350), Color(0xFF151011), Color(0xFF211819), Color(0xFF2E2123)),
    AppTheme("violet", "Violet", Color(0xFFB388FF), Color(0xFF120F17), Color(0xFF1C1724), Color(0xFF272031)),
    AppTheme("teal", "Teal", Color(0xFF26C6DA), Color(0xFF0D1516), Color(0xFF142022), Color(0xFF1C2C2F)),
    AppTheme("graphite", "Graphite", Color(0xFFE0E0E0), Color(0xFF111111), Color(0xFF1B1B1B), Color(0xFF262626)),
    AppTheme("nightred", "Night red", Color(0xFFD32F2F), Color(0xFF000000), Color(0xFF0C0505), Color(0xFF170909)),
)

fun themeById(id: String): AppTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

fun AppTheme.scheme(): ColorScheme = darkColorScheme(
    primary = accent, secondary = accent, tertiary = accent,
    background = background, surface = surface, surfaceVariant = surfaceVariant,
    onPrimary = Color.Black, onBackground = if (id == "nightred") Color(0xFFE57373) else Color(0xFFECEFF1),
    onSurface = if (id == "nightred") Color(0xFFE57373) else Color(0xFFECEFF1),
    onSurfaceVariant = if (id == "nightred") Color(0xFFB71C1C) else Color(0xFFB0BEC5),
    error = Color(0xFFEF5350),
)

/** Night cruise: pure black (OLED-dark, no glare), one tint for everything, dimmer tint for secondary text. */
fun cruiseScheme(tint: com.nik.carradio.core.CruiseTint): ColorScheme {
    val (main, dim) = when (tint) {
        com.nik.carradio.core.CruiseTint.AMBER -> Color(0xFFFFB300) to Color(0xFF8C6200)
        com.nik.carradio.core.CruiseTint.CYAN -> Color(0xFF26C6DA) to Color(0xFF14707C)
        com.nik.carradio.core.CruiseTint.RED -> Color(0xFFE53935) to Color(0xFF7F1D1B)
    }
    return darkColorScheme(
        primary = main, secondary = main, tertiary = main, onPrimary = Color.Black,
        background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF0D0D0D),
        onBackground = main, onSurface = main, onSurfaceVariant = dim, outline = dim, error = main,
    )
}

/** The Radio screen layout in use right now (setting, or Night cruise while it is night). */
val LocalScreenStyle = androidx.compose.runtime.staticCompositionLocalOf { com.nik.carradio.core.ScreenStyle.CLASSIC }
