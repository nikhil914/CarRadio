package com.nik.carradio.core

import android.content.Context
import android.content.SharedPreferences

enum class NextPrevKey(val label: String) { STATION("Next saved station"), SEEK("Seek to the next signal"), STEP("Step one channel") }
enum class PlayKey(val label: String) { POWER("Radio on / off"), MUTE("Mute / unmute") }

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("carradio", Context.MODE_PRIVATE)

    var preferredBackend: String?
        get() = sp.getString("preferred_backend", null)
        set(v) = sp.edit().putString("preferred_backend", v).apply()

    var lastBand: Band
        get() = runCatching { Band.valueOf(sp.getString("last_band", "FM")!!) }.getOrDefault(Band.FM)
        set(v) = sp.edit().putString("last_band", v.name).apply()

    fun lastFreq(band: Band): Int = sp.getInt("last_freq_${band.name}", if (band == Band.FM) 98_100 else 999)
    fun setLastFreq(band: Band, khz: Int) = sp.edit().putInt("last_freq_${band.name}", khz).apply()

    var fmStepKhz: Int
        get() = sp.getInt("fm_step", 100)
        set(v) = sp.edit().putInt("fm_step", v).apply()

    var amStepKhz: Int
        get() = sp.getInt("am_step", 9)
        set(v) = sp.edit().putInt("am_step", v).apply()

    fun step(band: Band) = if (band == Band.FM) fmStepKhz else amStepKhz

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** Resume the radio when the unit boots or wakes (ACC on) if it was playing before. */
    var resumeOnStart: Boolean
        get() = sp.getBoolean("resume_on_start", true)
        set(v) = sp.edit().putBoolean("resume_on_start", v).apply()

    /** True while the radio is on; cleared only by an explicit "off" (ACC off keeps it). */
    var wasPlaying: Boolean
        get() = sp.getBoolean("was_playing", false)
        set(v) { sp.edit().putBoolean("was_playing", v).commit() }

    /** Steering-wheel next/prev move through the user's station group (FYT redirect). */
    var wheelUsesStations: Boolean
        get() = sp.getBoolean("wheel_stations", true)
        set(v) = sp.edit().putBoolean("wheel_stations", v).apply()

    /** What the steering-wheel / media next and previous keys do. */
    var nextPrevKey: NextPrevKey
        get() = UiConfig.enumOr(sp.getString("key_next_prev", null), NextPrevKey.STATION)
        set(v) = sp.edit().putString("key_next_prev", v.name).apply()

    /** What the play/pause key (and a launcher's play button) does. */
    var playKey: PlayKey
        get() = UiConfig.enumOr(sp.getString("key_play", null), PlayKey.POWER)
        set(v) = sp.edit().putString("key_play", v.name).apply()

    var streamUrl: String
        get() = sp.getString("stream_url", "") ?: ""
        set(v) = sp.edit().putString("stream_url", v.trim()).apply()

    /** Backend that last started successfully: probed alone at launch (fast path). */
    var lastWorking: String?
        get() = sp.getString("last_working", null)
        set(v) = sp.edit().putString("last_working", v).apply()

    var githubToken: String
        get() = sp.getString("gh_token", "") ?: ""
        set(v) = sp.edit().putString("gh_token", v.trim()).apply()

    var githubRepo: String
        get() = sp.getString("gh_repo", "nikhil914/CarRadio") ?: "nikhil914/CarRadio"
        set(v) = sp.edit().putString("gh_repo", v.trim()).apply()

    /** Learned FYT command format per operation ("tune", "step", "seek"); -1 = not learned. */
    fun fytVariant(op: String): Int = sp.getInt("fyt_v2_$op", -1)
    fun setFytVariant(op: String, v: Int) = sp.edit().putInt("fyt_v2_$op", v).apply()
    fun clearFytVariants() = sp.edit().remove("fyt_v2_tune").remove("fyt_v2_step").remove("fyt_v2_seek").remove("fyt_sens_map").apply()

    /** How the stock radio app was last started successfully on SoC-FM units: "service", "activity" or null. */
    var fytWake: String?
        get() = sp.getString("fyt_wake", null)
        set(v) = sp.edit().putString("fyt_wake", v).apply()

    /** May Car Radio open the stock radio app for a moment when its background start is not enough. */
    var allowStockFlash: Boolean
        get() = sp.getBoolean("allow_stock_flash", true)
        set(v) = sp.edit().putBoolean("allow_stock_flash", v).apply()

    /** Tune command format found by the full radio test ([TuneSpec] encoded); null = none. */
    var fytTuneSpec: String?
        get() = sp.getString("fyt_tune_spec", null)
        set(v) = sp.edit().putString("fyt_tune_spec", v).apply()

    /** FYT sensitivity command mapping learned on first use; -1 = not learned. */
    var fytSensMap: Int
        get() = sp.getInt("fyt_sens_map", -1)
        set(v) = sp.edit().putInt("fyt_sens_map", v).apply()

    /** Check GitHub Releases for a newer Car Radio once a day when online. */
    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("auto_update_check", true)
        set(v) = sp.edit().putBoolean("auto_update_check", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    // Presets: 12 slots, stored as kHz (0 = empty).
    fun preset(slot: Int): Int = sp.getInt("preset_$slot", 0)
    fun setPreset(slot: Int, khz: Int) = sp.edit().putInt("preset_$slot", khz).apply()
    fun presets(): List<Int> = (0 until PRESET_SLOTS).map { preset(it) }

    // ---- Crash guard ----
    // Set (with commit, i.e. synchronously) before a risky backend start and cleared after.
    // If it is still set at next launch, that backend killed the process.

    var inFlight: String?
        get() = sp.getString("in_flight", null)
        set(v) { sp.edit().putString("in_flight", v).commit() }

    fun crashedBackends(): Set<String> = sp.getStringSet("crashed", emptySet()) ?: emptySet()
    fun markCrashed(id: String) {
        sp.edit().putStringSet("crashed", crashedBackends() + id).commit()
    }
    fun clearCrashed() { sp.edit().remove("crashed").apply(); backends().forEach { clearStrikes(it) } }

    /**
     * Unfinished starts in a row. One can be the unit losing power (ACC off) mid-start;
     * only [CRASH_STRIKES] in a row disable a method.
     */
    fun crashStrikes(id: String): Int = sp.getInt("strikes_$id", 0)
    fun addStrike(id: String): Int = (crashStrikes(id) + 1).also { sp.edit().putInt("strikes_$id", it).commit() }
    fun clearStrikes(id: String) { if (crashStrikes(id) != 0) sp.edit().remove("strikes_$id").apply() }
    private fun backends() = sp.all.keys.filter { it.startsWith("strikes_") }.map { it.removePrefix("strikes_") }

    companion object { const val PRESET_SLOTS = 12; const val CRASH_STRIKES = 2 }
}
