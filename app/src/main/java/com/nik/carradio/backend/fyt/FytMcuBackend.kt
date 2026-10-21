package com.nik.carradio.backend.fyt

import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.Freq
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * FYT / Syu head units (UIS8141E, UIS7862, SC9853i, PX5 FYT, …).
 * The tuner chip sits behind the MCU; com.syu.ms exposes it as "radio module" 1, and the main
 * module's command 0 switches the MCU audio source. No system permission is involved.
 */
class FytMcuBackend : RadioBackend {
    override val id = "fyt"
    override val name = "FYT / Syu MCU tuner"
    override val description = "Talks to com.syu.ms (radio module 1) – the same path the stock FYT radio and NavRadio+ use."
    override val caps = setOf(Cap.TUNE, Cap.SEEK, Cap.STEP, Cap.BAND_AM, Cap.SCAN, Cap.RDS)

    private var radio: Syu.Module? = null
    private var main: Syu.Module? = null
    @Volatile private var reportedKhz = 0
    @Volatile private var reportedBand: Band? = null
    @Volatile private var appId = -1
    @Volatile private var host: BackendHost? = null

    private val radioCb = Syu.Callback { code, ints, _, strs -> onRadioUpdate(code, ints, strs) }
    private val mainCb = Syu.Callback { code, ints, _, _ ->
        if (code == Syu.MAIN_U_APP_ID && ints != null && ints.isNotEmpty()) {
            if (appId != ints[0]) AppLog.i("FYT: MCU audio source is now appId=${ints[0]} (1 = radio)")
            appId = ints[0]
            host?.update { it.copy(status = if (appId == Syu.APP_ID_RADIO) "Playing via MCU tuner" else "MCU source changed to appId=$appId (another app took audio)") }
        }
    }

    override suspend fun probe(host: BackendHost): ProbeResult {
        val details = mutableListOf<String>()
        val endpoints = SyuConnection.installedEndpoints(host.context)
        details += "Installed endpoints: ${endpoints.ifEmpty { listOf("none") }}"
        if (endpoints.isEmpty()) return ProbeResult(ProbeStatus.UNAVAILABLE, "Not an FYT unit (com.syu.ms not installed)", details = details)
        val toolkit = try { SyuConnection.connect(host.context, 3500) } catch (e: RadioException) {
            details += SyuConnection.attempts
            return ProbeResult(ProbeStatus.UNAVAILABLE, e.title, e.hint, details)
        }
        details += "Connected: ${SyuConnection.connectedTo}"
        val r = runCatching { toolkit.module(Syu.MODULE_RADIO) }
        val m = runCatching { toolkit.module(Syu.MODULE_MAIN) }
        details += "Radio module: ${r.getOrNull()?.let { "present" } ?: ("missing " + (r.exceptionOrNull()?.message ?: ""))}"
        details += "Main module: ${m.getOrNull()?.let { "present" } ?: ("missing " + (m.exceptionOrNull()?.message ?: ""))}"
        val radioModule = r.getOrNull()
            ?: return ProbeResult(ProbeStatus.UNAVAILABLE, "FYT service has no radio module", "The firmware reports no MCU tuner. Try the FmNative method.", details)
        runCatching { radioModule.get(Syu.G_FREQ) }.onSuccess { details += "get(G_FREQ) -> $it" }.onFailure { details += "get(G_FREQ) failed: ${it.message}" }
        runCatching { radioModule.get(Syu.G_BAND) }.onSuccess { details += "get(G_BAND) -> $it" }.onFailure { details += "get(G_BAND) failed: ${it.message}" }
        return ProbeResult(ProbeStatus.AVAILABLE, "FYT radio module found", details = details)
    }

    private suspend fun ensure(host: BackendHost) {
        this.host = host
        val tk = SyuConnection.current()
        if (tk != null && radio != null && main != null) return
        val toolkit = SyuConnection.connect(host.context)
        radio = toolkit.module(Syu.MODULE_RADIO)
            ?: throw RadioException("FYT service has no radio module", "This firmware exposes no MCU tuner. Try the FmNative method in Diagnostics.")
        main = toolkit.module(Syu.MODULE_MAIN)
        Syu.RADIO_UPDATES.forEach { code -> runCatching { radio!!.register(radioCb, code, 1) } }
        main?.let { mm -> runCatching { mm.register(mainCb, Syu.MAIN_U_APP_ID, 1) } }
        AppLog.i("FYT: modules ready, callbacks registered")
    }

    private fun onRadioUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
        val h = host ?: return
        val v = ints?.firstOrNull()
        when (code) {
            Syu.U_FREQ -> if (v != null) {
                val khz = Freq.decodeInt(v)
                if (khz == null) { AppLog.w("FYT: unknown frequency value $v"); return }
                reportedKhz = khz
                val band = Freq.bandOf(khz)
                h.update { it.copy(freqKhz = khz, band = band, stationName = if (it.freqKhz != khz) "" else it.stationName, radioText = if (it.freqKhz != khz) "" else it.radioText) }
                h.prefs.setLastFreq(band, khz)
            }
            Syu.U_BAND -> if (v != null) {
                reportedBand = if (Syu.isFmBand(v)) Band.FM else Band.AM
                h.update { it.copy(band = reportedBand!!) }
            }
            Syu.U_PS_TEXT, Syu.U_RDS_CHANNEL_TEXT -> strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> h.update { it.copy(stationName = s) } }
            Syu.U_RDS_TEXT -> strs?.firstOrNull()?.trim()?.let { s -> h.update { it.copy(radioText = s) } }
            Syu.U_STEREO -> if (v != null) h.update { it.copy(stereo = v != 0) }
            Syu.U_SEARCH_STATE -> if (v != null) h.update { it.copy(scanning = v != 0) }
        }
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        AppLog.step("FYT: switching MCU audio source to RADIO (main cmd 0, [1])")
        main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(Syu.APP_ID_RADIO))
            ?: AppLog.w("FYT: main module missing – cannot switch audio source; sound may stay on the previous source")
        val confirmed = waitFor(2000) { appId == Syu.APP_ID_RADIO }
        if (!confirmed) AppLog.w("FYT: MCU did not confirm radio source (appId=$appId). Continuing; if there is no sound open the stock radio once.")
        host.update { it.copy(status = if (confirmed) "Playing via MCU tuner" else "Radio command sent (source not confirmed)") }
        tune(host, band, khz)
    }

    override suspend fun powerOff(host: BackendHost) {
        ensure(host)
        main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(Syu.APP_ID_NULL))
        AppLog.i("FYT: audio source released (appId 0)")
    }

    override suspend fun setBand(host: BackendHost, band: Band) {
        ensure(host)
        if (currentBand() == band) return
        radio!!.cmd(Syu.C_BAND, intArrayOf(if (band == Band.FM) Syu.BAND_SWITCH_FM else Syu.BAND_SWITCH_AM))
        if (waitFor(1500) { currentBand() == band }) return
        AppLog.w("FYT: band command with explicit band not confirmed, trying toggle (-1)")
        radio!!.cmd(Syu.C_BAND, intArrayOf(-1))
        if (!waitFor(1500) { currentBand() == band }) {
            throw RadioException("Tuner did not switch to ${band.name}", "Some FYT units have no ${band.name} band in this region setting (Settings → Radio region).")
        }
    }

    private fun currentBand(): Band? = reportedBand ?: reportedKhz.takeIf { it > 0 }?.let { Freq.bandOf(it) }

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        val target = Freq.clamp(band, khz)
        if (currentBand() != null && currentBand() != band) setBand(host, band)
        if (reportedKhz == target) return
        val value = if (band == Band.FM) target / 10 else target
        val learned = host.prefs.fytTuneMode
        val modes = (listOf(learned) + listOf(0, 1)).filter { it >= 0 }.distinct()
        for (mode in modes) {
            when (mode) {
                0 -> radio!!.cmd(Syu.C_FREQ, intArrayOf(Syu.FREQ_DIRECT, value))
                1 -> radio!!.cmd(Syu.C_FREQ, intArrayOf(value))
            }
            if (waitFor(1300) { abs(reportedKhz - target) < 5 }) {
                if (learned != mode) { host.prefs.fytTuneMode = mode; AppLog.i("FYT: direct tune works with payload mode $mode – remembered") }
                return
            }
            AppLog.w("FYT: direct tune mode $mode not confirmed (reported=$reportedKhz, target=$target)")
        }
        // Last resort: walk there with single steps. Always works if step commands work.
        if (reportedKhz <= 0) {
            throw RadioException("Tuner does not report its frequency", "The radio module accepted commands but never reported back. Open the stock radio once, then retry.")
        }
        val stepKhz = host.prefs.step(band)
        var guard = 0
        while (abs(reportedKhz - target) >= stepKhz && guard < 400) {
            val before = reportedKhz
            radio!!.cmd(if (target > reportedKhz) Syu.C_FREQ_UP else Syu.C_FREQ_DOWN)
            if (!waitFor(600) { reportedKhz != before }) {
                throw RadioException("Tuner stopped responding while stepping to ${Freq.format(band, target)}", "Current ${Freq.format(band, reportedKhz)}. Try seek, or reconnect via Diagnostics.")
            }
            guard++
        }
        AppLog.i("FYT: reached ${reportedKhz} kHz by stepping ($guard steps)")
    }

    override suspend fun seek(host: BackendHost, up: Boolean) {
        ensure(host); radio!!.cmd(if (up) Syu.C_SEEK_UP else Syu.C_SEEK_DOWN)
    }

    override suspend fun step(host: BackendHost, up: Boolean) {
        ensure(host)
        val before = reportedKhz
        radio!!.cmd(if (up) Syu.C_FREQ_UP else Syu.C_FREQ_DOWN)
        if (!waitFor(800) { reportedKhz != before }) super.step(host, up)
    }

    override suspend fun scan(host: BackendHost) {
        ensure(host); radio!!.cmd(Syu.C_SEARCH, intArrayOf(Syu.SEARCH_STATE_AUTO))
        host.update { it.copy(scanning = true) }
    }

    override fun release(host: BackendHost) {
        radio?.let { r -> Syu.RADIO_UPDATES.forEach { runCatching { r.unregister(radioCb, it) } } }
        main?.let { m -> runCatching { m.unregister(mainCb, Syu.MAIN_U_APP_ID) } }
        radio = null; main = null; this.host = null
    }

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; delay(60) }
        return cond()
    }
}
