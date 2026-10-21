package com.nik.carradio.core

import java.util.Locale

enum class Band { FM, AM }

/** What a backend can do. The UI enables controls from this set. */
enum class Cap { TUNE, SEEK, STEP, BAND_AM, SCAN, PRESETS_HW, RDS, MUTE, EXTERNAL_UI, STREAM }

enum class ProbeStatus { AVAILABLE, UNAVAILABLE, BLOCKED, UNKNOWN, CRASHED_BEFORE }

data class ProbeResult(
    val status: ProbeStatus,
    /** One line a non-developer can read. */
    val summary: String,
    /** What to do about it. */
    val hint: String = "",
    /** Raw facts for the report: values read, exceptions, paths checked. */
    val details: List<String> = emptyList(),
) {
    val usable get() = status == ProbeStatus.AVAILABLE
}

/** A failure that already carries a user-readable explanation. */
class RadioException(
    val title: String,
    val hint: String = "",
    cause: Throwable? = null,
) : Exception(title, cause)

data class UiError(
    val title: String,
    val hint: String,
    val technical: String,
    val backendId: String?,
)

data class RadioState(
    val backendId: String? = null,
    val backendName: String = "No radio method selected",
    val caps: Set<Cap> = emptySet(),
    val powered: Boolean = false,
    val busy: Boolean = false,
    val busyText: String = "",
    val band: Band = Band.FM,
    val freqKhz: Int = 98_100,
    val stationName: String = "",
    val radioText: String = "",
    val stereo: Boolean? = null,
    val signal: Int? = null,
    val scanning: Boolean = false,
    val muted: Boolean = false,
    val status: String = "Starting…",
    val error: UiError? = null,
)

object Freq {
    const val FM_MIN = 87_500
    const val FM_MAX = 108_000
    const val AM_MIN = 522
    const val AM_MAX = 1_710

    fun clamp(band: Band, khz: Int): Int = when (band) {
        Band.FM -> khz.coerceIn(FM_MIN, FM_MAX)
        Band.AM -> khz.coerceIn(AM_MIN, AM_MAX)
    }

    fun bandOf(khz: Int): Band = if (khz >= 20_000) Band.FM else Band.AM

    fun format(band: Band, khz: Int): String = when (band) {
        Band.FM -> String.format(Locale.US, "%.2f", khz / 1000.0).let { if (it.endsWith("0")) it.dropLast(1) else it }
        Band.AM -> khz.toString()
    }

    fun unit(band: Band) = if (band == Band.FM) "MHz" else "kHz"

    /**
     * MCU services (FYT, TopWay) report FM in 10 kHz units (8750..10800) and AM in kHz
     * (522..1710) – FYT's own launcher uses exactly this split. Values 500..1800 are therefore
     * AM, never FM-in-100-kHz. Use [decodeFm] when the source is known to be FM-only.
     */
    fun decodeInt(v: Int): Int? = when (v) {
        in 8_750..10_800 -> v * 10
        in 87_500..108_000 -> v
        in 87_500_000..108_000_000 -> v / 1000
        in 500..1_800 -> v
        else -> null
    }

    fun decodeMhz(v: Float): Int? =
        if (v in 87.0f..108.5f) Math.round(v * 1000f) else null

    /** FM-only source (SoC chip): accepts MHz, 100 kHz, 10 kHz or kHz. */
    fun decodeFm(v: Float): Int? = when {
        v in 87.0f..108.5f -> Math.round(v * 10f) * 100
        v in 870f..1085f -> Math.round(v) * 100
        v in 8_700f..10_850f -> Math.round(v) * 10
        v in 87_000f..108_500f -> Math.round(v)
        else -> null
    }

    /** Parse what the user typed: "98.1", "98,1", "981", "1602". */
    fun parseUser(text: String): Int? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        t.toFloatOrNull()?.let { f ->
            if (f in 87.0f..108.5f) return Math.round(f * 10f) * 100
            if (f in 500f..1800f && !t.contains('.')) return f.toInt()
            if (f in 870f..1085f) return Math.round(f) * 100
        }
        return null
    }
}
