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
import com.nik.carradio.core.TunerOpt
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
    private var steer: Syu.Module? = null
    /** Frequency changes before this time were caused by our own commands. */
    @Volatile private var expectUntil = 0L
    /** Last steering-wheel key seen on the FYT steer module: (next?, time). */
    @Volatile private var lastSteer: Pair<Boolean, Long>? = null

    /** A vendor-made jump not yet explained by a key: (from kHz, time). */
    @Volatile private var unexplained: Pair<Int, Long>? = null

    private fun expect(ms: Long = 2500) { expectUntil = System.currentTimeMillis() + ms }

    /** Last frequency we asked for and when: (kHz, time). */
    @Volatile private var commanded: Pair<Int, Long>? = null

    private val steerCb = Syu.Callback { code, ints, _, _ ->
        val dir = when {
            ints?.any { it == Syu.STEER_NEXT } == true -> true
            ints?.any { it == Syu.STEER_PREV } == true -> false
            code == Syu.STEER_NEXT && ints?.firstOrNull() == 1 -> true
            code == Syu.STEER_PREV && ints?.firstOrNull() == 1 -> false
            else -> null
        }
        if (dir != null) {
            lastSteer = dir to System.currentTimeMillis()
            AppLog.i("FYT steer key: ${if (dir) "NEXT" else "PREVIOUS"} (update=$code ints=${ints?.contentToString()})")
            // Key reported just after the jump it caused: redirect now.
            val jump = unexplained?.takeIf { System.currentTimeMillis() - it.second < 1500 }
            val h = host
            if (jump != null && h != null) { unexplained = null; lastSteer = null; redirect(h, jump.first, dir) }
        } else if (ints != null && ints.isNotEmpty()) {
            AppLog.i("FYT steer update=$code ints=${ints.contentToString()}")
        }
    }

    private val radioCb = Syu.Callback { code, ints, _, strs -> onRadioUpdate(code, ints, strs) }
    @Volatile private var acc = -1

    private val mainCb = Syu.Callback { code, ints, _, _ ->
        if (code == Syu.MAIN_U_LAMPLET && ints != null && ints.isNotEmpty()) com.nik.carradio.core.CarSignals.setHeadlights(ints[0] != 0)
        if (code == Syu.MAIN_U_ACC_ON && ints != null && ints.isNotEmpty()) {
            val on = ints[0]
            if (acc != -1 && acc != on) { AppLog.i("FYT: ACC ${if (on == 1) "ON" else "OFF"}"); host?.onAccChanged(on == 1) }
            acc = on
        }
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
        main?.let { mm -> runCatching { mm.register(mainCb, Syu.MAIN_U_APP_ID, 1); mm.register(mainCb, Syu.MAIN_U_ACC_ON, 1); mm.register(mainCb, Syu.MAIN_U_LAMPLET, 1) } }
        steer = runCatching { toolkit.module(Syu.MODULE_STEER) }.getOrNull()
        // update=0: only future key events, no replay of stale values.
        steer?.let { st -> for (c in 0..255) runCatching { st.register(steerCb, c, 0) } }
        AppLog.i("FYT: modules ready (radio, main=${main != null}, steer=${steer != null}), callbacks registered")
    }

    private fun onRadioUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
        val h = host ?: return
        val v = ints?.firstOrNull()
        when (code) {
            Syu.U_FREQ -> if (v != null) {
                val khz = Freq.decodeInt(v)
                if (khz == null) { AppLog.w("FYT: unknown frequency value $v"); return }
                val prev = reportedKhz
                reportedKhz = khz
                // A slow MCU can report our own target after the expect window: never treat that
                // as a wheel jump, or one press would move two stations.
                val ours = commanded?.let { (t, at) -> abs(t - khz) < 5 && System.currentTimeMillis() - at < 8000 } == true
                if (prev > 0 && khz != prev && !ours && System.currentTimeMillis() > expectUntil) onVendorTune(h, prev, khz)
                val band = Freq.bandOf(khz)
                h.update { it.copy(freqKhz = khz, band = band, stationName = if (it.freqKhz != khz) "" else it.stationName, radioText = if (it.freqKhz != khz) "" else it.radioText, pty = if (it.freqKhz != khz) 0 else it.pty) }
                h.prefs.setLastFreq(band, khz)
            }
            Syu.U_BAND -> if (v != null) {
                reportedBand = if (Syu.isFmBand(v)) Band.FM else Band.AM
                h.update { it.copy(band = reportedBand!!) }
            }
            Syu.U_PS_TEXT, Syu.U_RDS_CHANNEL_TEXT -> strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> h.update { it.copy(stationName = s) } }
            Syu.U_RDS_TEXT -> strs?.firstOrNull()?.trim()?.let { s -> h.update { it.copy(radioText = s) } }
            Syu.U_STEREO -> if (v != null) h.update { it.copy(stereo = v != 0, opts = it.opts + (TunerOpt.STEREO to (v != 0))) }
            Syu.U_SEARCH_STATE -> if (v != null) h.update { it.copy(scanning = v != 0) }
            Syu.U_PTY_ID -> if (v != null) h.update { it.copy(pty = v and 0x1f) }
            Syu.U_RDS_TA -> if (v != null) h.update { it.copy(ta = v != 0) }
            Syu.U_RDS_TP -> if (v != null) h.update { it.copy(tp = v != 0) }
            Syu.U_RDS_AF_ENABLE -> if (v != null) h.update { it.copy(af = v != 0, opts = it.opts + (TunerOpt.AF to (v != 0))) }
            Syu.U_LOC -> if (v != null) h.update { it.copy(loc = v != 0, opts = it.opts + (TunerOpt.LOCAL to (v != 0))) }
            Syu.U_RDS_TA_ENABLE -> if (v != null) h.update { it.copy(opts = it.opts + (TunerOpt.TA to (v != 0))) }
            Syu.U_RDS_ENABLE -> if (v != null) h.update { it.copy(opts = it.opts + (TunerOpt.RDS to (v != 0))) }
            Syu.U_AUTO_SENSITY -> if (v != null) h.update { it.copy(opts = it.opts + (TunerOpt.AUTO_SENS to (v != 0))) }
            Syu.U_AREA -> if (v != null) {
                if (h.state.region != v) AppLog.i("FYT: tuner region = $v (${Syu.AREAS.getOrElse(v) { "unknown" }})")
                h.update { it.copy(region = v) }
            }
            Syu.U_EXTRA_FREQ_INFO -> Syu.bandInfo(ints)?.let { bi ->
                if (h.state.bandInfo != bi) AppLog.i("FYT: band limits ${bi.describe()}")
                h.update { it.copy(bandInfo = bi) }
            }
            Syu.U_SENSITY_AM -> if (v != null) h.update { it.copy(sensAm = v) }
            Syu.U_SENSITY_FM -> if (v != null) h.update { it.copy(sensFm = v) }
            Syu.U_CHANNEL_FREQ -> if (ints != null && ints.isNotEmpty()) AppLog.i("FYT: preset frequencies ${ints.take(24)}")
        }
    }

    /**
     * The tuner moved without a command from us. With a fresh steering-wheel key it is the
     * vendor's preset jump → hand it to the controller to go to the user's next station
     * instead. Without key evidence (someone used the stock radio) it is left alone.
     */
    private fun onVendorTune(h: BackendHost, fromKhz: Int, toKhz: Int) {
        val key = lastSteer?.takeIf { System.currentTimeMillis() - it.second < 2500 }
        if (key == null) {
            unexplained = fromKhz to System.currentTimeMillis()
            AppLog.i("FYT: tuner moved $fromKhz -> $toKhz kHz by itself (no wheel key yet) – following it unless a key arrives")
            return
        }
        lastSteer = null
        AppLog.i("FYT: wheel ${if (key.first) "NEXT" else "PREVIOUS"} made the MCU jump $fromKhz -> $toKhz")
        redirect(h, fromKhz, key.first)
    }

    private fun redirect(h: BackendHost, fromKhz: Int, up: Boolean) {
        AppLog.i("FYT: redirecting wheel ${if (up) "next" else "previous"} from $fromKhz kHz to the station list")
        h.onExternalStep(Freq.bandOf(fromKhz), fromKhz, up)
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        expect()
        AppLog.step("FYT: switching MCU audio source to RADIO (main cmd 0, [1])")
        main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(Syu.APP_ID_RADIO))
            ?: AppLog.w("FYT: main module missing – cannot switch audio source; sound may stay on the previous source")
        val confirmed = waitFor(2000) { appId == Syu.APP_ID_RADIO }
        if (!confirmed) AppLog.w("FYT: MCU did not confirm radio source (appId=$appId). Continuing; if there is no sound open the stock radio once.")
        host.update { it.copy(status = if (confirmed) "Playing via MCU tuner" else "Radio command sent (source not confirmed)") }
        // The radio is on once the MCU plays radio. A tuning problem must not throw the whole
        // method away (1.0.0 fell back to the stock app here): report it and stay on FYT.
        try { tune(host, band, khz) } catch (e: RadioException) {
            if (!confirmed && reportedKhz <= 0) throw e
            AppLog.w("FYT: radio is on at $reportedKhz kHz but tuning to $khz failed: ${e.title}")
            host.update { it.copy(status = "Playing ${Freq.format(Freq.bandOf(reportedKhz.coerceAtLeast(1)), reportedKhz)} – tuning commands not accepted yet (Diagnostics → Learn tuner commands)") }
        }
    }

    override suspend fun powerOff(host: BackendHost) {
        ensure(host)
        main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(Syu.APP_ID_NULL))
        AppLog.i("FYT: audio source released (appId 0)")
    }

    override suspend fun setBand(host: BackendHost, band: Band) {
        ensure(host)
        if (currentBand() == band) return
        expect(4000)
        radio!!.cmd(Syu.C_BAND, intArrayOf(if (band == Band.FM) Syu.BAND_SWITCH_FM else Syu.BAND_SWITCH_AM))
        if (waitFor(1500) { currentBand() == band }) return
        AppLog.w("FYT: band command with explicit band not confirmed, trying toggle (-1)")
        expect(4000)
        radio!!.cmd(Syu.C_BAND, intArrayOf(-1))
        if (!waitFor(1500) { currentBand() == band }) {
            throw RadioException("Tuner did not switch to ${band.name}", "Some FYT units have no ${band.name} band in this region setting (Settings → Radio region).")
        }
    }

    private fun currentBand(): Band? = reportedBand ?: reportedKhz.takeIf { it > 0 }?.let { Freq.bandOf(it) }

    // ------------------------------------------------------------ command formats
    // FYT firmwares differ in the payload each radio command expects. The FYT launcher always
    // sends a value (next = cmd(0,[0]), band = cmd(11,[-1])); 1.0.0 sent step/seek with none
    // and the UIS8141E service ignored them. Each operation now tries the known formats,
    // checks the tuner really moved, and remembers the one that works on this unit.

    private class Variant(val label: String, val send: (Syu.Module) -> Unit)
    private companion object { const val DIRECT_BROKEN = -2 }

    /**
     * Direct tune. From com.syu.ms (radio module command 13 → setFreq(mode, value) of the MCU
     * radio, module.k.a): mode 3 sends MCU packet 0x25 hi lo with the frequency in 10 kHz units
     * (0…10800) – no conversion, works at once. Mode 1 converts via band limits the service only
     * knows after the MCU has sent its band-range packet (often only when the stock radio app
     * asked); until then it is silently dropped. That is why 1.0.0/1.0.1 fell back to stepping.
     */
    private fun tuneVariants(band: Band, target: Int): List<Variant> {
        val v10 = if (band == Band.FM) target / 10 else target      // 98.1 MHz → 9810; AM in kHz
        return listOf(
            Variant("cmd(13,[3,$v10]) direct MCU") { it.cmd(Syu.C_FREQ, intArrayOf(Syu.FREQ_MCU_DIRECT, v10)) },
            Variant("cmd(13,[1,$v10]) via band limits") { it.cmd(Syu.C_FREQ, intArrayOf(Syu.FREQ_DIRECT, v10)) },
            Variant("cmd(13,[1,$target])") { it.cmd(Syu.C_FREQ, intArrayOf(Syu.FREQ_DIRECT, target)) },
        )
    }

    private fun stepVariants(up: Boolean): List<Variant> {
        val c = if (up) Syu.C_FREQ_UP else Syu.C_FREQ_DOWN
        val dir = if (up) 1 else -1
        // com.syu.ms ignores the value for 3/4 (step) – any form works; [0] matches the FYT launcher.
        return listOf(
            Variant("cmd($c,[0])") { it.cmd(c, intArrayOf(0)) },
            Variant("cmd($c)") { it.cmd(c) },
            Variant("cmd(13,[0,$dir])") { it.cmd(Syu.C_FREQ, intArrayOf(Syu.FREQ_BY_STEP, dir)) },
        )
    }

    private fun seekVariants(up: Boolean): List<Variant> {
        val c = if (up) Syu.C_SEEK_UP else Syu.C_SEEK_DOWN
        return listOf(
            Variant("cmd($c,[0])") { it.cmd(c, intArrayOf(0)) },
            Variant("cmd($c)") { it.cmd(c) },
        )
    }

    /**
     * Sends the learned variant of [op] (or tries all, learned first) until [done] is true.
     * Returns the label that worked, or null.
     */
    private suspend fun runLearning(h: BackendHost, op: String, variants: List<Variant>, waitMs: Long, done: () -> Boolean): String? {
        val learned = h.prefs.fytVariant(op)
        val order = (listOfNotNull(variants.getOrNull(learned)?.let { learned }) + variants.indices).distinct()
        for (i in order) {
            val v = variants[i]
            expect(waitMs + 1500)
            runCatching { v.send(radio!!) }.onFailure { AppLog.w("FYT $op ${v.label}: send failed", it) }
            if (waitFor(waitMs, done)) {
                if (learned != i) { h.prefs.setFytVariant(op, i); AppLog.i("FYT: $op works with ${v.label} – remembered") }
                return v.label
            }
            AppLog.w("FYT: $op ${v.label} – no change (reported=$reportedKhz)")
        }
        if (learned >= 0) h.prefs.setFytVariant(op, -1)
        return null
    }

    /** Tunes since direct tuning was found not to work here; it is retried every 10th tune. */
    private var tunesSinceDirectFailed = 0
    private var bandInfoAsked = false

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        val target = Freq.clamp(band, khz)
        if (currentBand() != null && currentBand() != band) setBand(host, band)
        if (abs(reportedKhz - target) < 5) return
        if (reportedKhz <= 0) {
            throw RadioException("Tuner does not report its frequency", "The radio module accepted commands but never reported back. Open the stock radio once, then retry.")
        }
        commanded = target to System.currentTimeMillis()

        // 1) Direct. Mode 1 needs the band limits, which com.syu.ms only has after the MCU sent
        //    its band-range packet; selecting the band asks the MCU for it (update 16 = arrived).
        if (host.state.bandInfo == null && !bandInfoAsked) {
            bandInfoAsked = true
            AppLog.i("FYT: no band limits yet – selecting ${band.name} to make the MCU send them")
            expect(3000)
            radio!!.cmd(Syu.C_BAND, intArrayOf(if (band == Band.FM) Syu.BAND_SWITCH_FM else Syu.BAND_SWITCH_AM))
            AppLog.i("FYT: band limits ${if (waitFor(1500) { host.state.bandInfo != null }) "received: ${host.state.bandInfo?.describe()}" else "not reported"}")
            if (abs(reportedKhz - target) < 5) return
        }
        val directKnownBroken = host.prefs.fytVariant("tune") == DIRECT_BROKEN
        if (!directKnownBroken || ++tunesSinceDirectFailed % 10 == 0) {
            val ok = runLearning(host, "tune", tuneVariants(band, target), 2500) { abs(reportedKhz - target) < 5 } != null
            if (ok) { tunesSinceDirectFailed = 0; return }
            host.prefs.setFytVariant("tune", DIRECT_BROKEN)
            AppLog.w("FYT: no direct-tune format works on this unit – seeking and stepping instead (direct retried every 10th tune)")
        }

        // 2) Seek toward it: saved stations are real stations, so seek stops on or near them.
        //    A few seeks replace hundreds of 50 kHz steps (each needs the MCU's ~0.6 s report).
        // cmd(5/6,[0]) is the FYT launcher's own format; used until seek has been learned.
        val seekLearned = host.prefs.fytVariant("seek").coerceAtLeast(0)
        if (abs(reportedKhz - target) > 300) {
            val up = target > reportedKhz
            var seeks = 0
            while (seeks < 25) {
                val before = reportedKhz
                expect(9000)
                seekVariants(up)[seekLearned].send(radio!!)
                if (!waitSettledReport(before, 7000)) break
                seeks++
                val now = reportedKhz
                if (abs(now - target) < 5) { AppLog.i("FYT: reached $now kHz by seeking ($seeks seeks)"); return }
                if ((up && now > target) || (!up && now < target) || (up && now < before) || (!up && now > before)) break  // passed it or wrapped
            }
            AppLog.i("FYT: seek stopped at $reportedKhz kHz after $seeks seeks, stepping the rest")
        }

        // 3) Step the rest, by the tuner's own step (Europe: 50 kHz) measured from the first step.
        var stepKhz = host.state.bandInfo?.stepKhz?.takeIf { it > 0 } ?: 0
        var guard = 0
        while (abs(reportedKhz - target) >= 5 && guard < 400) {
            val before = reportedKhz
            val up = target > reportedKhz
            if (stepKhz > 0 && abs(target - before) < stepKhz) break
            val ok = if (host.prefs.fytVariant("step") < 0) runLearning(host, "step", stepVariants(up), 2000) { reportedKhz != before } != null
                else { expect(); stepVariants(up)[host.prefs.fytVariant("step")].send(radio!!); waitFor(2000) { reportedKhz != before } }
            if (!ok) throw RadioException("The tuner ignores tuning commands from Car Radio",
                "It plays ${Freq.format(band, reportedKhz)}. Run Settings → Advanced → Diagnostics → Learn tuner commands and send the report.")
            if (stepKhz == 0) stepKhz = abs(reportedKhz - before)
            if ((up && reportedKhz > target) || (!up && reportedKhz < target)) break   // between two steps
            guard++
        }
        AppLog.i("FYT: reached $reportedKhz kHz by stepping ($guard steps of $stepKhz kHz)")
        if (abs(reportedKhz - target) >= 5) host.update { it.copy(status = "Nearest the tuner allows: ${Freq.format(band, reportedKhz)}") }
    }

    /** After a seek: the frequency moved away from [from] and then held still for 700 ms. */
    private suspend fun waitSettledReport(from: Int, ms: Long): Boolean {
        if (!waitFor(ms) { reportedKhz != from }) return false
        var seen = reportedKhz; var since = System.currentTimeMillis()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            delay(100)
            if (reportedKhz != seen) { seen = reportedKhz; since = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - since >= 700) return true
        }
        return true
    }

    override suspend fun seek(host: BackendHost, up: Boolean) {
        ensure(host)
        val before = reportedKhz
        if (runLearning(host, "seek", seekVariants(up), 4000) { reportedKhz != before } == null)
            throw RadioException("Seek is not accepted by this tuner", "Run Diagnostics → Learn tuner commands and send the report.")
        expect(12_000)   // the seek keeps moving after the first change
    }

    override suspend fun step(host: BackendHost, up: Boolean) {
        ensure(host)
        val before = reportedKhz
        if (runLearning(host, "step", stepVariants(up), 2000) { reportedKhz != before } == null) super.step(host, up)
    }

    override suspend fun scan(host: BackendHost) {
        ensure(host); expect(120_000); radio!!.cmd(Syu.C_SEARCH, intArrayOf(Syu.SEARCH_START))
        host.update { it.copy(scanning = true) }
    }

    // ------------------------------------------------------------ tuner settings (1.2)

    override val tunerOptions = setOf(TunerOpt.STEREO, TunerOpt.LOCAL, TunerOpt.AF, TunerOpt.TA, TunerOpt.RDS, TunerOpt.AUTO_SENS)
    override val regions get() = Syu.AREAS
    override val hasSensitivity = true

    /**
     * Stereo, LOC, AF and TA are toggles in com.syu.ms (only value 2 acts), so they are sent
     * only when the reported state differs. RDS and auto sensitivity take 0/1 directly.
     */
    override suspend fun setOption(host: BackendHost, opt: TunerOpt, on: Boolean) {
        ensure(host)
        val cur = host.state.opts[opt]
        if (cur == on) return
        val m = radio!!
        when (opt) {
            TunerOpt.RDS -> m.cmd(Syu.C_RDS_ENABLE, intArrayOf(if (on) 1 else 0))
            TunerOpt.AUTO_SENS -> m.cmd(Syu.C_AUTO_SENSITY, intArrayOf(if (on) 1 else 0))
            else -> {
                if (cur == null) AppLog.w("FYT: ${opt.label} state not reported yet – toggling once")
                val c = when (opt) {
                    TunerOpt.STEREO -> Syu.C_STEREO; TunerOpt.LOCAL -> Syu.C_LOC
                    TunerOpt.AF -> Syu.C_RDS_AF_ENABLE; else -> Syu.C_RDS_TA_ENABLE
                }
                m.cmd(c, intArrayOf(Syu.TOGGLE))
            }
        }
        if (waitFor(2000) { host.state.opts[opt] == on }) { AppLog.i("FYT: ${opt.label} = ${if (on) "on" else "off"} (confirmed)"); return }
        if (cur == null) {
            // Nothing is reported for it on this firmware: show what we asked for.
            AppLog.w("FYT: ${opt.label} sent, the tuner does not report it – showing it as ${if (on) "on" else "off"}")
            host.update { it.copy(opts = it.opts + (opt to on)) }
            return
        }
        throw RadioException("The tuner did not change \"${opt.label}\"", "It still reports ${if (cur) "on" else "off"}. Some units only allow this in the stock radio's settings.")
    }

    override suspend fun setRegion(host: BackendHost, index: Int) {
        ensure(host)
        require(index in Syu.AREAS.indices)
        if (host.state.region == index) return
        expect(5000)
        radio!!.cmd(Syu.C_AREA, intArrayOf(index))
        if (!waitFor(3000) { host.state.region == index }) {
            if (host.state.region == null) { host.update { it.copy(region = index) }; AppLog.w("FYT: region sent, not reported back"); return }
            throw RadioException("The tuner kept its region (${Syu.AREAS.getOrElse(host.state.region!!) { "?" }})",
                "Some units lock the region. Set it in Settings → Factory settings → Radio instead.")
        }
        AppLog.i("FYT: region is now ${Syu.AREAS[index]}")
    }

    /**
     * cmd 14 takes [band index, -1 or -2]; which index is FM and which value is "higher" is not
     * in the firmware's names. The first press checks which reported value moved and
     * remembers the mapping (pref fyt_sens_map: bit 0 = band swapped, bit 1 = direction swapped).
     */
    override suspend fun sensitivity(host: BackendHost, up: Boolean) {
        ensure(host)
        val fm = (currentBand() ?: host.state.band) == Band.FM
        val map = host.prefs.fytSensMap
        val learned = map >= 0
        var bits = if (learned) map else 0
        fun send(b: Int) {
            val bandIdx = (if (fm) 1 else 0) xor (b and 1)
            val dir = if (up xor ((b shr 1) and 1 == 1)) -1 else -2
            radio!!.cmd(Syu.C_SENSITY, intArrayOf(bandIdx, dir))
        }
        fun mine() = if (fm) host.state.sensFm else host.state.sensAm
        fun other() = if (fm) host.state.sensAm else host.state.sensFm
        val (beforeMine, beforeOther) = mine() to other()
        send(bits)
        val moved = waitFor(1500) { mine() != beforeMine || other() != beforeOther }
        if (!moved) {
            if (beforeMine == null) { AppLog.w("FYT: sensitivity sent, the tuner does not report it"); return }
            throw RadioException("Seek sensitivity did not change", "It may be at its limit, or automatic sensitivity is on.")
        }
        if (learned) return
        if (mine() == beforeMine && other() != beforeOther) {
            // The other band moved: undo it and use the other index.
            bits = bits or 1
            AppLog.i("FYT: sensitivity band index is swapped on this unit – correcting")
            send((bits xor 1) xor 2); waitFor(1200) { other() == beforeOther }   // same band as before, other way
            send(bits); waitFor(1500) { mine() != beforeMine }
        }
        val now = mine(); val was = beforeMine
        if (now != null && was != null && now != was && ((now > was) != up)) {
            bits = bits or 2
            AppLog.i("FYT: sensitivity direction is swapped on this unit – correcting")
            send(bits); waitFor(1200) { mine() == was }   // back to where it was …
            send(bits); waitFor(1500) { mine() != was }   // … then the intended way
        }
        host.prefs.fytSensMap = bits
        AppLog.i("FYT: sensitivity mapping learned ($bits): ${if (fm) "FM" else "AM"} now ${mine()}")
    }

    /**
     * Diagnostics: tries every format of step, seek and direct tune once and reports which
     * moved the tuner. Learned formats are saved. Ends back near the starting frequency.
     */
    suspend fun learnCommands(host: BackendHost): List<String> {
        ensure(host)
        host.prefs.clearFytVariants()
        val out = mutableListOf<String>()
        val start = reportedKhz
        out += "Start: $start kHz, appId=$appId"
        for ((label, v) in stepVariants(true).map { it.label to it }) {
            val before = reportedKhz; expect(3000); v.send(radio!!)
            out += "step up $label -> ${if (waitFor(2000) { reportedKhz != before }) "MOVED to $reportedKhz" else "no change"}"
        }
        for ((label, v) in seekVariants(true).map { it.label to it }) {
            val before = reportedKhz; expect(15_000); v.send(radio!!)
            out += "seek up $label -> ${if (waitFor(5000) { reportedKhz != before }) "MOVED to $reportedKhz" else "no change"}"
            delay(1500)
        }
        val target = if (start in 87_600..107_900) start else 98_100
        for (v in tuneVariants(Band.FM, target)) {
            if (abs(reportedKhz - target) < 5) { expect(3000); runCatching { stepVariants(true)[0].send(radio!!) }; waitFor(1200) { reportedKhz != target } }
            val before = reportedKhz; expect(3000); v.send(radio!!)
            out += "tune ${v.label} -> ${if (waitFor(2500) { abs(reportedKhz - target) < 5 }) "WORKS" else "no (at $reportedKhz, from $before)"}"
        }
        out += "Next channel cmd(0,[0]) -> " + run { val b = reportedKhz; expect(3000); radio!!.cmd(Syu.C_NEXT_CHANNEL, intArrayOf(0)); if (waitFor(2000) { reportedKhz != b }) "MOVED to $reportedKhz" else "no change" }
        val st = host.state
        out += "Tuner: region=${st.region?.let { Syu.AREAS.getOrNull(it) ?: it } ?: "not reported"}, ${st.bandInfo?.describe() ?: "no band info"}, " +
            "sensFm=${st.sensFm} sensAm=${st.sensAm}, opts=${st.opts.entries.joinToString { "${it.key}=${if (it.value) "on" else "off"}" }.ifEmpty { "none reported" }}"
        out.forEach { AppLog.i("Learn: $it") }
        // Save the first format of each kind that worked.
        stepVariants(true).indexOfFirst { v -> out.any { it.startsWith("step up ${v.label} -> MOVED") } }.takeIf { it >= 0 }?.let { host.prefs.setFytVariant("step", it) }
        seekVariants(true).indexOfFirst { v -> out.any { it.startsWith("seek up ${v.label} -> MOVED") } }.takeIf { it >= 0 }?.let { host.prefs.setFytVariant("seek", it) }
        tuneVariants(Band.FM, target).indexOfFirst { v -> out.any { it.startsWith("tune ${v.label} -> WORKS") } }.takeIf { it >= 0 }?.let { host.prefs.setFytVariant("tune", it) }
        return out
    }

    override fun release(host: BackendHost) {
        radio?.let { r -> Syu.RADIO_UPDATES.forEach { runCatching { r.unregister(radioCb, it) } } }
        main?.let { m -> runCatching { m.unregister(mainCb, Syu.MAIN_U_APP_ID); m.unregister(mainCb, Syu.MAIN_U_ACC_ON); m.unregister(mainCb, Syu.MAIN_U_LAMPLET) } }
        steer?.let { st -> for (c in 0..255) runCatching { st.unregister(steerCb, c) } }
        radio = null; main = null; steer = null; this.host = null
    }

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; delay(60) }
        return cond()
    }
}
