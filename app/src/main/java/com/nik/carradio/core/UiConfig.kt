package com.nik.carradio.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StationLayout(val label: String) { GRID("Grid"), LIST("List"), CAROUSEL("Carousel") }

@Immutable
data class UiSettings(
    val layout: StationLayout = StationLayout.GRID,
    /** Grid columns, 2–6. */
    val gridColumns: Int = 3,
    val themeId: String = "ocean",
    /** Multiplies all text sizes: 0.85 / 1.0 / 1.15 / 1.3. */
    val textScale: Float = 1f,
    /** Seconds without a touch before full screen opens; 0 = only by double-tap. */
    val fullscreenAfterSec: Int = 0,
    val fullscreenClock: Boolean = true,
    val showLogos: Boolean = true,
    val nightMode: NightMode = NightMode.SUN,
    val nightThemeId: String = "nightred",
    /** TIMED window, minutes after midnight. */
    val nightStart: Int = 19 * 60,
    val nightEnd: Int = 6 * 60 + 30,
    /** GPS speed in full screen (needs location permission; GPS runs only while visible). */
    val fullscreenSpeed: Boolean = false,
    /** GPS speed on the Radio screen too (GPS runs only while that screen is visible). */
    val mainSpeed: Boolean = false,
    /** Floating mini player over other apps (navigation) while the radio plays. */
    val floatingPlayer: Boolean = false,
    /** Keep a history of song titles from radio text. */
    val songHistory: Boolean = true,
)

/** Appearance settings as a flow, so the UI re-themes the moment a setting changes. */
object UiConfig {
    private lateinit var sp: SharedPreferences
    private val _s = MutableStateFlow(UiSettings())
    val settings: StateFlow<UiSettings> = _s.asStateFlow()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        _s.value = UiSettings(
            layout = runCatching { StationLayout.valueOf(sp.getString("layout", "GRID")!!) }.getOrDefault(StationLayout.GRID),
            gridColumns = sp.getInt("cols", 3).coerceIn(2, 6),
            themeId = sp.getString("theme", "ocean") ?: "ocean",
            textScale = sp.getFloat("text", 1f).coerceIn(0.8f, 1.4f),
            fullscreenAfterSec = sp.getInt("fs_after", 0),
            fullscreenClock = sp.getBoolean("fs_clock", true),
            showLogos = sp.getBoolean("logos", true),
            nightMode = runCatching { NightMode.valueOf(sp.getString("night", "SUN")!!) }.getOrDefault(NightMode.SUN),
            nightThemeId = sp.getString("night_theme", "nightred") ?: "nightred",
            nightStart = sp.getInt("night_start", 19 * 60), nightEnd = sp.getInt("night_end", 6 * 60 + 30),
            fullscreenSpeed = sp.getBoolean("fs_speed", false),
            mainSpeed = sp.getBoolean("main_speed", false),
            floatingPlayer = sp.getBoolean("floating", false),
            songHistory = sp.getBoolean("song_history", true),
        )
    }

    fun update(transform: (UiSettings) -> UiSettings) {
        val n = transform(_s.value)
        _s.value = n
        if (::sp.isInitialized) sp.edit()
            .putString("layout", n.layout.name).putInt("cols", n.gridColumns).putString("theme", n.themeId)
            .putFloat("text", n.textScale).putInt("fs_after", n.fullscreenAfterSec)
            .putBoolean("fs_clock", n.fullscreenClock).putBoolean("logos", n.showLogos)
            .putString("night", n.nightMode.name).putString("night_theme", n.nightThemeId)
            .putInt("night_start", n.nightStart).putInt("night_end", n.nightEnd).putBoolean("fs_speed", n.fullscreenSpeed)
            .putBoolean("main_speed", n.mainSpeed).putBoolean("floating", n.floatingPlayer).putBoolean("song_history", n.songHistory).apply()
    }
}
