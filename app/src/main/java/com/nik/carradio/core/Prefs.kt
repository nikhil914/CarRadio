package com.nik.carradio.core

import android.content.Context
import android.content.SharedPreferences

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

    /** Which FYT direct-tune payload worked on this unit (learned at runtime). */
    var fytTuneMode: Int
        get() = sp.getInt("fyt_tune_mode", -1)
        set(v) = sp.edit().putInt("fyt_tune_mode", v).apply()

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
    fun clearCrashed() = sp.edit().remove("crashed").commit()

    companion object { const val PRESET_SLOTS = 12 }
}
