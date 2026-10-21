package com.nik.carradio.backend.fmnative

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.android.fmradio.FmNative
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.backend.fyt.Syu
import com.nik.carradio.backend.fyt.SyuConnection
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.Freq
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * FM receiver inside the SoC / connectivity chip, driven through the firmware's libfmjni.so
 * (FYT SoC-FM units, Unisoc/MTK-derived stacks). Only FM.
 *
 * Loading a vendor native library can kill the process if its JNI table does not match; the
 * controller's crash guard records that and disables this method automatically next launch.
 */
class FmNativeBackend : RadioBackend {
    override val id = "fmnative"
    override val name = "SoC FM chip (libfmjni)"
    override val description = "Opens /dev/fm through the firmware's libfmjni.so (com.android.fmradio.FmNative JNI)."
    override val caps = setOf(Cap.TUNE, Cap.SEEK, Cap.STEP, Cap.RDS, Cap.MUTE)
    override val riskyStart = true

    private val fm get() = FmNative.INSTANCE
    private val lock = Any()
    private var opened = false
    private var poller: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun probe(host: BackendHost): ProbeResult {
        val libs = DeviceInfo.existingLibs()
        val nodes = DeviceInfo.devNodes()
        val details = listOf("libfmjni candidates: ${libs.ifEmpty { listOf("none") }}", "device nodes: ${nodes.ifEmpty { listOf("none") }}",
            "already loaded: ${FmNative.loadedFrom() ?: "no"}")
        if (libs.isEmpty() && !FmNative.isLoaded()) {
            return ProbeResult(ProbeStatus.UNAVAILABLE, "No libfmjni.so in firmware", "This unit has no SoC FM stack; the tuner is MCU-driven.", details)
        }
        val fmNode = File("/dev/fm")
        if (fmNode.exists() && !(fmNode.canRead() && fmNode.canWrite())) {
            return ProbeResult(ProbeStatus.BLOCKED, "libfmjni found, but /dev/fm is not accessible to normal apps",
                "Needs root or system install, or FYT's own radio service to proxy it.", details)
        }
        return ProbeResult(ProbeStatus.UNKNOWN, "libfmjni found – can only be confirmed by starting it",
            "Press \"Test start\". If the app closes, it is disabled automatically on next launch.", details)
    }

    private fun loadOrThrow() {
        AppLog.step("FmNative: loading libfmjni.so (if the app closes now, this method is incompatible)")
        val err = FmNative.load()
        if (err != null) throw RadioException("Firmware FM library could not be loaded", "Android blocks this library for normal apps, or it is missing.\n$err")
        AppLog.i("FmNative: loaded from ${FmNative.loadedFrom()}")
    }

    private inline fun <T> native(what: String, block: () -> T): T = synchronized(lock) {
        try { block() } catch (e: UnsatisfiedLinkError) {
            throw RadioException("Firmware FM library has no function \"$what\"", "This libfmjni.so uses a different interface than expected.", e)
        }
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        if (band != Band.FM) throw RadioException("The SoC FM chip only supports FM", "Switch to FM.")
        loadOrThrow()
        routeAudio(host.context, true)
        val mhz = Freq.clamp(Band.FM, khz) / 1000f
        AppLog.step("FmNative: openDev()")
        val open = native("openDev") { fm.openDev() }
        AppLog.step("FmNative: powerUp($mhz)")
        val power = native("powerUp") { fm.powerUp(mhz) }
        val tuned = native("tune") { fm.tune(mhz) }
        runCatching { native("setMute") { fm.setMute(false) } }
        runCatching { native("setRds") { fm.setRds(true) } }
        AppLog.i("FmNative: open=$open power=$power tune=$tuned")
        if (!open) throw RadioException("FM device could not be opened (openDev=false)", "/dev/fm is probably owned by the system radio service. Use the FYT method or the stock radio app.")
        if (!power) throw RadioException("FM chip did not power up (powerUp=false)", "Another app may hold the FM chip. Close the stock radio and retry.")
        opened = true
        host.update { it.copy(band = Band.FM, freqKhz = Freq.clamp(Band.FM, khz), status = "Playing via SoC FM chip") }
        startPolling(host)
    }

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) {
        if (band != Band.FM) throw RadioException("The SoC FM chip only supports FM")
        if (!opened) return powerOn(host, band, khz)
        val t = Freq.clamp(Band.FM, khz)
        val ok = native("tune") { fm.tune(t / 1000f) }
        if (!ok) throw RadioException("Tuning to ${Freq.format(Band.FM, t)} MHz failed", "The chip rejected the frequency.")
        host.update { it.copy(freqKhz = t, stationName = "", radioText = "") }
        host.prefs.setLastFreq(Band.FM, t)
    }

    override suspend fun seek(host: BackendHost, up: Boolean) {
        if (!opened) throw RadioException("Radio is off")
        val cur = host.state.freqKhz / 1000f
        val res = native("seek") { fm.seek(cur, up) }
        val khz = res?.firstOrNull()?.let { Freq.decodeFm(it) }
            ?: throw RadioException("Seek found no station", "Try again or tune manually.")
        native("tune") { fm.tune(khz / 1000f) }
        host.update { it.copy(freqKhz = khz, stationName = "", radioText = "") }
        host.prefs.setLastFreq(Band.FM, khz)
    }

    override suspend fun setMute(host: BackendHost, mute: Boolean) {
        native("setMute") { fm.setMute(mute) }
        host.update { it.copy(muted = mute) }
    }

    override suspend fun powerOff(host: BackendHost) {
        poller?.cancel()
        if (opened) {
            runCatching { native("setMute") { fm.setMute(true) } }
            runCatching { native("powerDown") { fm.powerDown(0) } }
            runCatching { native("closeDev") { fm.closeDev() } }
        }
        opened = false
        routeAudio(host.context, false)
    }

    override fun release(host: BackendHost) {
        poller?.cancel()
        if (opened) runCatching { synchronized(lock) { fm.powerDown(0); fm.closeDev() } }
        opened = false
    }

    private fun startPolling(host: BackendHost) {
        poller?.cancel()
        poller = scope.launch {
            while (isActive && opened) {
                delay(2000)
                val ps = runCatching { synchronized(lock) { fm.getPs() } }.getOrNull()?.let { decode(it) }
                val rt = runCatching { synchronized(lock) { fm.getLrText() } }.getOrNull()?.let { decode(it) }
                val rssi = runCatching { synchronized(lock) { fm.readRssi() } }.getOrNull()
                val st = runCatching { synchronized(lock) { fm.stereoMono() } }.getOrNull()
                host.update { s -> s.copy(
                    stationName = ps?.takeIf { it.isNotBlank() } ?: s.stationName,
                    radioText = rt?.takeIf { it.isNotBlank() } ?: s.radioText,
                    signal = rssi ?: s.signal, stereo = st ?: s.stereo) }
            }
        }
    }

    private fun decode(b: ByteArray): String =
        String(b, Charsets.ISO_8859_1).replace(Regex("[\\u0000-\\u001f]"), " ").trim()

    /**
     * Ask the vendor stack to route FM audio to the speakers. Every step is best-effort.
     * The hidden AudioManager.setParameter is only reachable on Android <= 8.1 (no hidden-API
     * policy); newer firmware falls through to the public setParameters below.
     */
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private suspend fun routeAudio(ctx: Context, on: Boolean) {
        if (SyuConnection.installedEndpoints(ctx).isNotEmpty()) {
            runCatching {
                val main = SyuConnection.connect(ctx, 2500).module(Syu.MODULE_MAIN)
                main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(if (on) Syu.APP_ID_RADIO else Syu.APP_ID_NULL))
                AppLog.i("FmNative: Syu audio source -> ${if (on) "radio" else "none"}")
            }.onFailure { AppLog.w("FmNative: Syu source switch failed", it) }
            runCatching { ctx.startService(Intent(if (on) "com.syu.music.switch_fm" else "com.syu.music.switch_none").setPackage("com.syu.music")) }
                .onFailure { AppLog.w("FmNative: com.syu.music switch failed: ${it.message}") }
            if (on) runCatching { ctx.sendBroadcast(Intent("com.action.ACTION_OPEN_RADIO")) }
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = runCatching { am.getStreamVolume(10).takeIf { it > 0 } ?: 15 }.getOrDefault(15)
        val value = if (on) vol.toString() else "0"
        runCatching {
            val m = AudioManager::class.java.getDeclaredMethod("setParameter", String::class.java, String::class.java)
            m.isAccessible = true; m.invoke(am, "FM_Volume", value)
        }.onFailure { AppLog.i("FmNative: hidden setParameter unavailable (${it.javaClass.simpleName})") }
        runCatching { am.setParameters("FM_Volume=$value") }
    }
}
