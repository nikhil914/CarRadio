package com.nik.carradio.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class NightMode(val label: String) {
    OFF("Always day"), ON("Always night"), TIMED("By time"), SUN("Sunset to sunrise"), HEADLIGHTS("With headlights"),
}

/** Live car signals that are not radio state. null = this unit does not report it. */
object CarSignals {
    private val _lights = MutableStateFlow<Boolean?>(null)
    val headlights: StateFlow<Boolean?> = _lights.asStateFlow()
    fun setHeadlights(on: Boolean) { if (_lights.value != on) { _lights.value = on; AppLog.i("Headlights ${if (on) "ON" else "OFF"}") } }
}

/** Pure day/night decision, unit-tested. Times are minutes after local midnight. */
object DayNight {
    /** Abu Dhabi: default location for the sun calculation (no GPS needed). */
    const val DEFAULT_LAT = 24.4539
    const val DEFAULT_LON = 54.3773

    fun isNight(mode: NightMode, nowMin: Int, timedStart: Int, timedEnd: Int,
                sun: Pair<Int, Int>?, headlights: Boolean?): Boolean = when (mode) {
        NightMode.OFF -> false
        NightMode.ON -> true
        NightMode.TIMED -> inWindow(nowMin, timedStart, timedEnd)
        NightMode.SUN -> sun?.let { (rise, set) -> inWindow(nowMin, set, rise) } ?: inWindow(nowMin, timedStart, timedEnd)
        // Unit doesn't report the lights wire → behave like sunset-to-sunrise.
        NightMode.HEADLIGHTS -> headlights ?: (sun?.let { (rise, set) -> inWindow(nowMin, set, rise) } ?: false)
    }

    /** Window may wrap midnight (19:00 → 06:30). */
    fun inWindow(now: Int, start: Int, end: Int): Boolean =
        if (start <= end) now in start until end else now >= start || now < end

    /**
     * Sunrise and sunset (NOAA solar position algorithm, ±2 min at these latitudes) as local
     * minutes after midnight, for [dayOfYear] (1–366), [utcOffsetMin] e.g. +240 for UAE.
     * null in polar day/night.
     */
    fun sunTimes(dayOfYear: Int, lat: Double, lon: Double, utcOffsetMin: Int): Pair<Int, Int>? {
        val g = 2 * Math.PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val zenith = Math.toRadians(90.833)
        val cosH = cos(zenith) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val noon = 720 - 4 * lon - eqTime
        val rise = noon - 4 * ha + utcOffsetMin
        val set = noon + 4 * ha + utcOffsetMin
        fun norm(m: Double) = Math.floorMod(Math.round(m).toInt(), 1440)
        return norm(rise) to norm(set)
    }

}
