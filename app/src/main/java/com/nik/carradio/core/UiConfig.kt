package com.nik.carradio.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StationLayout(val label: String) { GRID("Grid"), LIST("List"), CAROUSEL("Carousel") }

/**
 * How the Radio screen is laid out. Switching only recomposes the UI: the tuner, the service and
 * the audio route never see it.
 */
enum class ScreenStyle(val label: String, val hint: String) {
    MODERN("Modern clean", "Big frequency, large controls on the driver's side, favourites rail. Tools behind ⋯."),
    CLASSIC("Classic dense", "Every control on one screen: status, Scan, Enter, Tuner, RDS flags."),
    CRUISE("Night cruise", "Pure black, one colour, only frequency, name and three big buttons."),
}

/** Which side the driver sits: big controls go on that side in Modern clean. */
enum class DriverSide(val label: String) { LEFT("Left-hand drive"), RIGHT("Right-hand drive") }

/** Night cruise text colour. */
enum class CruiseTint(val label: String) { AMBER("Amber"), CYAN("Cyan"), RED("Red") }

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
    val screenStyle: ScreenStyle = ScreenStyle.MODERN,
    /** Use Night cruise on the Radio screen whenever the night theme is active. */
    val cruiseAtNight: Boolean = true,
    val cruiseTint: CruiseTint = CruiseTint.AMBER,
    val driverSide: DriverSide = DriverSide.LEFT,
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
            screenStyle = enumOr(sp.getString("style", null), ScreenStyle.MODERN),
            cruiseAtNight = sp.getBoolean("cruise_night", true),
            cruiseTint = enumOr(sp.getString("cruise_tint", null), CruiseTint.AMBER),
            driverSide = enumOr(sp.getString("driver", null), DriverSide.LEFT),
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
            .putBoolean("main_speed", n.mainSpeed).putBoolean("floating", n.floatingPlayer).putBoolean("song_history", n.songHistory)
            .putString("style", n.screenStyle.name).putBoolean("cruise_night", n.cruiseAtNight)
            .putString("cruise_tint", n.cruiseTint.name).putString("driver", n.driverSide.name).apply()
    }

    inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
