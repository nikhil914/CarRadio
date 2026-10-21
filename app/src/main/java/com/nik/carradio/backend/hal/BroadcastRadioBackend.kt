package com.nik.carradio.backend.hal

import android.content.pm.PackageManager
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException

/**
 * Standard Android BroadcastRadio HAL (android.hardware.radio.RadioManager, Android Automotive
 * and some AOSP head units). Needs ACCESS_BROADCAST_RADIO, which is signature|privileged:
 * only a system / priv-app install gets it. This build detects and explains; tuning through
 * the HAL is a planned enhancement once a unit that grants it is available.
 */
class BroadcastRadioBackend : RadioBackend {
    override val id = "hal"
    override val name = "Android BroadcastRadio HAL"
    override val description = "android.hardware.radio.RadioManager (needs ACCESS_BROADCAST_RADIO)."
    override val caps = emptySet<Cap>()

    override suspend fun probe(host: BackendHost): ProbeResult {
        val details = mutableListOf<String>()
        val rm = runCatching { host.context.getSystemService("broadcastradio") }.getOrNull()
            ?: return ProbeResult(ProbeStatus.UNAVAILABLE, "No BroadcastRadio service on this firmware",
                "Normal for aftermarket units – their tuner is behind the MCU.")
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(host.context, PERMISSION) == PackageManager.PERMISSION_GRANTED
        details += listOf("service=${rm.javaClass.name}", "permission granted=$granted")
        val modules = ArrayList<Any?>()
        val res = runCatching { rm.javaClass.getMethod("listModules", java.util.List::class.java).invoke(rm, modules) }
        res.exceptionOrNull()?.let { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            details += "listModules: $cause"
            return if (cause is SecurityException) ProbeResult(ProbeStatus.BLOCKED,
                "Radio HAL present but blocked: system permission ACCESS_BROADCAST_RADIO",
                "Only possible by installing this app into /system/priv-app (root) or signing it with the firmware platform key.", details)
            else ProbeResult(ProbeStatus.UNAVAILABLE, "Radio HAL error: ${cause.javaClass.simpleName}", details = details)
        }
        details += "listModules -> ${res.getOrNull()} modules=${modules.size}"
        modules.forEach { details += it.toString().take(300) }
        return if (modules.isEmpty()) ProbeResult(ProbeStatus.UNAVAILABLE, "Radio HAL reports no tuner modules", details = details)
        else ProbeResult(ProbeStatus.BLOCKED, "Radio HAL usable (${modules.size} module(s)) – playback via HAL not in this build yet",
            "Send the Diagnostics report; HAL tuning can be added for this unit.", details)
    }

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) = unsupported()
    override suspend fun powerOff(host: BackendHost) {}
    override suspend fun tune(host: BackendHost, band: Band, khz: Int) = unsupported()

    private fun unsupported(): Nothing = throw RadioException("Radio HAL playback is not implemented in this build",
        "See Diagnostics for whether this unit allows it.")

    companion object { const val PERMISSION = "android.permission.ACCESS_BROADCAST_RADIO" }
}
