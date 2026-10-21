package com.nik.carradio.backend.mtc

import android.content.Intent
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException

/**
 * Microntek (MTCB/MTCD/MTCE) units. android.microntek.CarManager is in the firmware framework.
 * Source switching ("av_channel_enter=fm") is well known; direct tuning parameters differ
 * between MTC firmware generations, so tuning is delegated to the stock radio for now.
 */
class MtcBackend : RadioBackend {
    override val id = "mtc"
    override val name = "Microntek (MTC) CarManager"
    override val description = "android.microntek.CarManager setParameters(\"av_channel_enter=fm\")."
    override val caps = setOf(Cap.EXTERNAL_UI)

    private var cm: Any? = null

    override suspend fun probe(host: BackendHost): ProbeResult {
        if (!DeviceInfo.classExists("android.microntek.CarManager"))
            return ProbeResult(ProbeStatus.UNAVAILABLE, "Not a Microntek unit (CarManager class not in firmware)")
        return runCatching {
            manager().javaClass.getMethod("getParameters", String::class.java).invoke(cm, "av_channel=") as? String
        }.fold(
            { ProbeResult(ProbeStatus.AVAILABLE, "MTC CarManager responds (av_channel=$it)", "Tuning opens the stock MTC radio.") },
            { ProbeResult(ProbeStatus.UNAVAILABLE, "MTC CarManager failed: ${it.javaClass.simpleName}", details = listOf(it.toString())) },
        )
    }

    private fun manager(): Any = cm ?: Class.forName("android.microntek.CarManager").getConstructor().newInstance().also { cm = it }

    private fun set(p: String) {
        try { manager().javaClass.getMethod("setParameters", String::class.java).invoke(cm, p); AppLog.i("MTC: setParameters($p)") }
        catch (e: Throwable) { throw RadioException("MTC command \"$p\" failed", e.toString(), e) }
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        set("av_channel_enter=fm")
        val launch = host.context.packageManager.getLaunchIntentForPackage("com.microntek.radio")
        if (launch != null) host.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        host.update { it.copy(status = "MTC radio source active – tune in the stock MTC radio") }
    }

    override suspend fun powerOff(host: BackendHost) = set("av_channel_exit=fm")

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) =
        throw RadioException("Direct tuning is not implemented for MTC yet", "Use the stock MTC radio that was opened. Send the Diagnostics report to add support.")
}
