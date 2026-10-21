package com.nik.carradio.backend.topway

import android.os.Handler
import android.os.Looper
import android.os.Message
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
import java.lang.reflect.Method

/**
 * TopWay (TS7/TS10/TS18, some Dudu) units: android.tw.john.TWUtil is part of the firmware
 * framework and talks to the MCU. Called by reflection so the app runs everywhere.
 * Register map from the open-source dipcore/Radio-MST768 app.
 */
class TopWayBackend : RadioBackend {
    override val id = "topway"
    override val name = "TopWay TWUtil tuner"
    override val description = "Firmware class android.tw.john.TWUtil (MCU registers 1025/1026)."
    override val caps = setOf(Cap.TUNE, Cap.SEEK, Cap.STEP, Cap.BAND_AM, Cap.SCAN, Cap.RDS)

    private var tw: Any? = null
    private var write2: Method? = null
    private var write3: Method? = null
    @Volatile private var reportedKhz = 0
    @Volatile private var host: BackendHost? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val h = host ?: return
            when (msg.what) {
                1026 -> decode(msg.arg2)?.let { k -> reportedKhz = k; h.update { it.copy(freqKhz = k, band = Freq.bandOf(k)) }; h.prefs.setLastFreq(Freq.bandOf(k), k) }
                1025 -> when (msg.arg1) {
                    1 -> h.update { it.copy(band = if (msg.arg2 == 2) Band.AM else Band.FM) }
                    2 -> decode(msg.arg2)?.let { k -> reportedKhz = k; h.update { it.copy(freqKhz = k, band = Freq.bandOf(k)) } }
                }
                1029 -> (msg.obj as? String)?.trim()?.let { s -> h.update { it.copy(radioText = s) } }
            }
        }
    }

    private fun decode(v: Int): Int? = Freq.decodeInt(v)

    override suspend fun probe(host: BackendHost): ProbeResult {
        val cls = runCatching { Class.forName("android.tw.john.TWUtil") }.getOrNull()
            ?: return ProbeResult(ProbeStatus.UNAVAILABLE, "Not a TopWay unit (TWUtil class not in firmware)")
        return runCatching {
            val o = cls.getConstructor(Int::class.java).newInstance(1)
            val r = cls.getMethod("open", ShortArray::class.java).invoke(o, SEQUENCE) as Int
            runCatching { cls.getMethod("close").invoke(o) }
            if (r == 0) ProbeResult(ProbeStatus.AVAILABLE, "TWUtil radio channel opens")
            else ProbeResult(ProbeStatus.BLOCKED, "TWUtil present but open() returned $r", "Another app (stock radio) may hold the channel; close it and retry.")
        }.getOrElse { ProbeResult(ProbeStatus.UNAVAILABLE, "TWUtil present but unusable: ${it.javaClass.simpleName}", details = listOf(it.toString())) }
    }

    private fun ensure(host: BackendHost) {
        this.host = host
        if (tw != null) return
        try {
            val cls = Class.forName("android.tw.john.TWUtil")
            val o = cls.getConstructor(Int::class.java).newInstance(1)
            val r = cls.getMethod("open", ShortArray::class.java).invoke(o, SEQUENCE) as Int
            if (r != 0) throw RadioException("TopWay radio channel is busy (open=$r)", "Close the stock radio app and retry.")
            cls.getMethod("start").invoke(o)
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(o, "carradio", handler)
            write2 = cls.getMethod("write", Int::class.java, Int::class.java)
            write3 = cls.getMethod("write", Int::class.java, Int::class.java, Int::class.java)
            tw = o
            listOf(265, 769, 1025, 1028, 1029).forEach { w(it, 255) }
            w(1030, 0)
        } catch (e: RadioException) { throw e } catch (e: Throwable) {
            throw RadioException("TopWay TWUtil could not be opened", e.toString(), e)
        }
    }

    private fun w(a: Int, b: Int) { write2!!.invoke(tw, a, b) }
    private fun w(a: Int, b: Int, c: Int) { write3!!.invoke(tw, a, b, c) }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        w(769, 192, 1); w(40465, 192, 1) // request MCU audio focus for radio
        host.update { it.copy(status = "Playing via TopWay MCU tuner") }
        tune(host, band, khz)
    }

    override suspend fun powerOff(host: BackendHost) {
        if (tw == null) return
        runCatching { w(769, 192, 0); w(40465, 192, 129) }
    }

    override suspend fun setBand(host: BackendHost, band: Band) {
        ensure(host); w(1025, 5, if (band == Band.FM) 0 else 2); delay(400)
    }

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) {
        ensure(host)
        if (host.state.band != band) setBand(host, band)
        val t = Freq.clamp(band, khz)
        w(1026, 255, if (band == Band.FM) t / 10 else t)
        val end = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < end && reportedKhz != t) delay(80)
        if (reportedKhz != t) AppLog.w("TopWay: tune to $t not confirmed (reported=$reportedKhz)")
        host.update { it.copy(freqKhz = if (reportedKhz > 0) reportedKhz else t, band = band) }
    }

    override suspend fun seek(host: BackendHost, up: Boolean) { ensure(host); w(1025, 1, if (up) 0 else 1) }
    override suspend fun scan(host: BackendHost) { ensure(host); w(1025, 0, 0) }

    override fun release(host: BackendHost) {
        val o = tw ?: return
        runCatching {
            o.javaClass.getMethod("removeHandler", String::class.java).invoke(o, "carradio")
            o.javaClass.getMethod("stop").invoke(o)
            o.javaClass.getMethod("close").invoke(o)
        }
        tw = null; this.host = null
    }

    companion object { val SEQUENCE = shortArrayOf(265, 513, 769, 1025, 1026, 1028, 1029, 1030) }
}
