package com.nik.carradio.backend.stock

import android.content.ComponentName
import android.content.Intent
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException

/** Fallback that always works when the unit has any radio app: open it. */
class StockAppBackend : RadioBackend {
    override val id = "stock"
    override val name = "Open the unit's own radio app"
    override val description = "Launches the firmware radio app (com.syu.radio, com.microntek.radio, …)."
    override val caps = setOf(Cap.EXTERNAL_UI)

    private val known = listOf(
        "com.syu.radio" to "com.syu.radio.Launch",
        "com.microntek.radio" to null, "com.tw.radio" to null, "com.ts.radio" to null,
        "com.android.fmradio" to null, "com.caf.fmradio" to null, "com.sprd.fmradio" to null,
        "com.mediatek.fmradio" to null,
    )

    private fun launchIntent(host: BackendHost): Pair<Intent, String>? {
        val pm = host.context.packageManager
        for ((pkg, cls) in known) {
            if (!DeviceInfo.isInstalled(host.context, pkg)) continue
            // Our own Radio-key proxy sits under com.syu.radio: launching it would loop back here.
            if (DeviceInfo.signing(host.context, pkg)?.isProxy == true) continue
            val i = if (cls != null) Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, cls)) else pm.getLaunchIntentForPackage(pkg)
            if (i != null && pm.resolveActivity(i, 0) != null) return i to pkg
            pm.getLaunchIntentForPackage(pkg)?.let { return it to pkg }
        }
        DeviceInfo.radioLikeApps(host.context).firstOrNull { DeviceInfo.signing(host.context, it.first)?.isProxy != true }?.let { (pkg, _) -> pm.getLaunchIntentForPackage(pkg)?.let { return it to pkg } }
        return null
    }

    override suspend fun probe(host: BackendHost): ProbeResult {
        val found = launchIntent(host)
        return if (found != null) ProbeResult(ProbeStatus.AVAILABLE, "Stock radio app found: ${found.second}")
        else ProbeResult(ProbeStatus.UNAVAILABLE, "No radio app installed on the unit")
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        val (intent, pkg) = launchIntent(host) ?: throw RadioException("No radio app found on this unit")
        try { host.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { throw RadioException("Could not open $pkg", e.toString(), e) }
        host.update { it.copy(status = "Opened $pkg") }
    }

    override suspend fun powerOff(host: BackendHost) {}
    override suspend fun tune(host: BackendHost, band: Band, khz: Int) = powerOn(host, band, khz)
}
