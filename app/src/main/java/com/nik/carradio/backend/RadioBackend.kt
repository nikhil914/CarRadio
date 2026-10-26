package com.nik.carradio.backend

import android.content.Context
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.Prefs
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.RadioException
import com.nik.carradio.core.RadioState

/** What a backend may use from the controller. */
interface BackendHost {
    val context: Context
    val prefs: Prefs
    val state: RadioState
    /** Push a state change (frequency reported by hardware, RDS text, …). Thread-safe. */
    fun update(transform: (RadioState) -> RadioState)

    /**
     * The hardware changed station on its own: on FYT, the steering-wheel next/prev keys are
     * handled inside the vendor service and jump to the MCU's preset list. The controller
     * redirects that to the neighbour in the user's station group.
     */
    fun onExternalStep(fromBand: Band, fromKhz: Int, up: Boolean)

    /** Ignition (ACC) changed, as reported by the vendor MCU. */
    fun onAccChanged(on: Boolean)
}

/**
 * One way of controlling a tuner. Every method may throw [RadioException] with a message that
 * is shown to the user as-is; any other exception is wrapped by the controller.
 * All calls arrive on one background thread, never concurrently.
 */
interface RadioBackend {
    val id: String
    val name: String
    /** Shown in diagnostics: what this method talks to. */
    val description: String
    val caps: Set<Cap>
    /** Probing must be safe: no native library loading, no audio switching. */
    val riskyStart: Boolean get() = false

    suspend fun probe(host: BackendHost): ProbeResult
    suspend fun powerOn(host: BackendHost, band: Band, khz: Int)
    suspend fun powerOff(host: BackendHost)
    suspend fun tune(host: BackendHost, band: Band, khz: Int)

    suspend fun seek(host: BackendHost, up: Boolean): Unit = notSupported("Seek")
    suspend fun step(host: BackendHost, up: Boolean) {
        val s = host.state
        val step = host.prefs.step(s.band)
        tune(host, s.band, s.freqKhz + if (up) step else -step)
    }
    suspend fun setBand(host: BackendHost, band: Band) = tune(host, band, host.prefs.lastFreq(band))
    suspend fun scan(host: BackendHost): Unit = notSupported("Auto scan")
    suspend fun setMute(host: BackendHost, mute: Boolean): Unit = notSupported("Mute")

    /** Release everything (app closing / switching backend). Must not throw. */
    fun release(host: BackendHost) {}

    fun notSupported(what: String): Nothing =
        throw RadioException("$what is not supported by \"$name\"", "Use another radio method or the stock radio app.")
}
