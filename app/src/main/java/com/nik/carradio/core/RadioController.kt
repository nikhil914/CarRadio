package com.nik.carradio.core

import android.content.Context
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.backend.fmnative.FmNativeBackend
import com.nik.carradio.backend.fyt.FytMcuBackend
import com.nik.carradio.backend.hal.BroadcastRadioBackend
import com.nik.carradio.backend.mtc.MtcBackend
import com.nik.carradio.backend.stock.StockAppBackend
import com.nik.carradio.backend.stream.StreamBackend
import com.nik.carradio.backend.topway.TopWayBackend
import com.nik.carradio.service.RadioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

/**
 * Owns the backends and the single source of truth [state]. Every hardware call runs on one
 * worker thread (never two at once), with a timeout, and every failure becomes a [UiError]
 * with a plain-language title, a hint and the technical cause.
 */
object RadioController : BackendHost {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "radio-worker") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private lateinit var app: Context
    override val context: Context get() = app
    override lateinit var prefs: Prefs

    /** Ordered by how much control they give. Auto mode walks this list. */
    val backends: List<RadioBackend> = listOf(
        FytMcuBackend(), TopWayBackend(), FmNativeBackend(), MtcBackend(),
        BroadcastRadioBackend(), StockAppBackend(), StreamBackend(),
    )

    private val _state = MutableStateFlow(RadioState())
    val stateFlow: StateFlow<RadioState> = _state.asStateFlow()
    override val state: RadioState get() = _state.value

    private val _probes = MutableStateFlow<Map<String, ProbeResult>>(emptyMap())
    val probes: StateFlow<Map<String, ProbeResult>> = _probes.asStateFlow()
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private var active: RadioBackend? = null
    private var initialized = false

    override fun update(transform: (RadioState) -> RadioState) = _state.update(transform)

    fun backend(id: String?) = backends.firstOrNull { it.id == id }

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        val band = prefs.lastBand
        _state.value = RadioState(band = band, freqKhz = prefs.lastFreq(band), status = "Ready")
        // Crash guard: a backend start that never finished means the process died inside it.
        prefs.inFlight?.let { id ->
            prefs.markCrashed(id)
            prefs.inFlight = null
            val n = backend(id)?.name ?: id
            AppLog.e("Crash guard: the app closed while starting \"$n\" last time. It is now skipped in automatic mode.")
            setError(UiError("\"$n\" closed the app last time", "It has been disabled for automatic mode. You can still test it manually in Diagnostics, or re-enable it in Settings.", "in-flight marker for '$id' found at startup", id))
        }
    }

    // ------------------------------------------------------------------ probing / selection

    fun probeAll(thenAutoStart: Boolean = false) = scope.launch {
        _probing.value = true
        AppLog.i("Probing all radio methods…")
        DeviceInfo.report(app).forEach { AppLog.i("  $it") }
        val crashed = prefs.crashedBackends()
        val results = linkedMapOf<String, ProbeResult>()
        for (b in backends) {
            val r = if (b.id in crashed) ProbeResult(ProbeStatus.CRASHED_BEFORE, "Closed the app on a previous start", "Disabled for auto mode. Re-enable in Settings.")
            else try { withTimeout(8000) { b.probe(this@RadioController) } }
            catch (e: TimeoutCancellationException) { ProbeResult(ProbeStatus.UNKNOWN, "Probe timed out after 8 s") }
            catch (e: Throwable) { ProbeResult(ProbeStatus.UNAVAILABLE, "Probe failed: ${e.javaClass.simpleName}: ${e.message}", details = listOf(AppLog.stack(e))) }
            results[b.id] = r
            AppLog.i("Probe ${b.id}: ${r.status} – ${r.summary}")
            r.details.forEach { AppLog.i("    $it") }
            _probes.value = LinkedHashMap(results)
        }
        _probing.value = false
        if (thenAutoStart) {
            val first = candidates().firstOrNull()
            if (first == null) {
                setError(UiError("No radio method works on this unit",
                    "Open Diagnostics and use \"Share report\" – it lists everything that was checked.",
                    results.entries.joinToString("\n") { "${it.key}: ${it.value.status} ${it.value.summary}" }, null))
            } else {
                selectInternal(first, persist = false)
                if (prefs.autoStart && first.caps.none { it == Cap.EXTERNAL_UI }) powerOnInternal(autoFallback = true)
            }
        }
    }

    /** Backends worth trying automatically, best first. */
    fun candidates(): List<RadioBackend> {
        val p = _probes.value
        val crashed = prefs.crashedBackends()
        val preferred = backend(prefs.preferredBackend)?.takeIf { it.id !in crashed && p[it.id]?.status != ProbeStatus.UNAVAILABLE }
        val tuners = backends.filter { it.id !in crashed && it.caps.contains(Cap.TUNE) && p[it.id]?.status == ProbeStatus.AVAILABLE }
        val maybe = backends.filter { it.id !in crashed && it.caps.contains(Cap.TUNE) && p[it.id]?.status == ProbeStatus.UNKNOWN }
        val rest = backends.filter { it.id !in crashed && !it.caps.contains(Cap.TUNE) && p[it.id]?.status == ProbeStatus.AVAILABLE }
        return (listOfNotNull(preferred) + tuners + maybe + rest).distinct()
    }

    fun select(id: String, persist: Boolean = true) {
        val b = backend(id) ?: return
        scope.launch { selectInternal(b, persist) }
    }

    /** Clears the saved choice so the app picks automatically again. */
    fun useAutomatic() { prefs.preferredBackend = null; AppLog.i("Backend choice: automatic"); probeAll(thenAutoStart = false) }

    private suspend fun selectInternal(b: RadioBackend, persist: Boolean) {
        if (persist) prefs.preferredBackend = b.id
        if (active?.id == b.id) return
        active?.let { old ->
            if (state.powered) runCatching { old.powerOff(this) }
            old.release(this)
            RadioService.stop(app)
        }
        active = b
        update { it.copy(backendId = b.id, backendName = b.name, caps = b.caps, powered = false, error = null, status = "Selected: ${b.name}") }
        AppLog.i("Selected backend ${b.id}${if (persist) " (saved as preferred)" else ""}")
    }

    // ------------------------------------------------------------------ commands

    fun powerToggle() = if (state.powered) powerOff() else powerOn()
    fun powerOn() = scope.launch { powerOnInternal(autoFallback = prefs.preferredBackend == null) }

    private suspend fun powerOnInternal(autoFallback: Boolean) {
        val order = buildList {
            active?.let { add(it) }
            if (autoFallback) addAll(candidates().filter { it.id != active?.id })
        }
        if (order.isEmpty()) {
            setError(UiError("No radio method selected", "Open Diagnostics, run the checks, and pick a method.", "", null)); return
        }
        val failures = mutableListOf<String>()
        for (b in order) {
            if (active?.id != b.id) {
                active?.release(this)
                active = b
                update { it.copy(backendId = b.id, backendName = b.name, caps = b.caps) }
            }
            val ok = exec("Starting ${b.name}", timeoutMs = 50_000, guard = true) {
                b.powerOn(this, state.band, prefs.lastFreq(state.band))
            }
            if (ok) {
                update { it.copy(powered = true, error = null) }
                if (b.caps.contains(Cap.EXTERNAL_UI)) update { it.copy(powered = false) } else RadioService.start(app)
                return
            }
            failures += "${b.name}: ${state.error?.title}"
            if (!autoFallback) return
            AppLog.w("Auto fallback: ${b.name} failed, trying next method")
        }
        if (failures.size > 1) setError(UiError("None of the radio methods could start",
            "Open Diagnostics → Share report and send it. Each method's reason is below.", failures.joinToString("\n"), null))
    }

    fun powerOff() = scope.launch {
        val b = active ?: return@launch
        exec("Stopping") { b.powerOff(this@RadioController) }
        update { it.copy(powered = false, status = "Radio off", scanning = false) }
        RadioService.stop(app)
    }

    /** Called when the error card's "Try next method" is pressed. */
    fun tryNextMethod() = scope.launch {
        val list = candidates()
        val idx = list.indexOfFirst { it.id == active?.id }
        val next = list.getOrNull(idx + 1) ?: list.firstOrNull { it.id != active?.id }
        if (next == null) { setError(UiError("No other working method found", "Run Diagnostics again or share the report.", "", null)); return@launch }
        active?.let { runCatching { it.powerOff(this@RadioController) }; it.release(this@RadioController) }
        active = next
        update { it.copy(backendId = next.id, backendName = next.name, caps = next.caps, powered = false, error = null) }
        powerOnInternal(autoFallback = false)
    }

    fun tune(khz: Int, band: Band = Freq.bandOf(khz)) = command("Tuning to ${Freq.format(band, khz)} ${Freq.unit(band)}") { b ->
        val t = Freq.clamp(band, khz)
        b.tune(this, band, t)
        update { s -> if (b.id == "fyt" || b.id == "topway") s else s.copy(freqKhz = t, band = band) }
        prefs.lastBand = band; prefs.setLastFreq(band, t)
    }

    fun seek(up: Boolean) = command(if (up) "Seeking up" else "Seeking down") { it.seek(this, up) }
    fun step(up: Boolean) = command("Step") { it.step(this, up) }
    fun scan() = command("Scanning") { it.scan(this) }
    fun setMute(m: Boolean) = command(if (m) "Mute" else "Unmute") { it.setMute(this, m) }
    fun setBand(band: Band) = command("Switching to ${band.name}") { b ->
        b.setBand(this, band)
        prefs.lastBand = band
        update { it.copy(band = band) }
    }

    fun recallPreset(slot: Int) {
        val khz = prefs.preset(slot)
        if (khz <= 0) { setError(UiError("Preset ${slot + 1} is empty", "Long-press a preset to save the current station.", "", null)); return }
        tune(khz)
    }

    fun savePreset(slot: Int) {
        prefs.setPreset(slot, state.freqKhz)
        AppLog.i("Saved preset ${slot + 1} = ${state.freqKhz} kHz")
        update { it.copy(status = "Saved ${Freq.format(state.band, state.freqKhz)} to preset ${slot + 1}") }
    }

    fun dismissError() = update { it.copy(error = null) }

    /** Runs one backend start from Diagnostics, ignoring auto mode and the crash blacklist. */
    fun testBackend(id: String) {
        val b = backend(id) ?: return
        scope.launch {
            active?.let { old -> if (state.powered) runCatching { old.powerOff(this@RadioController) }; old.release(this@RadioController) }
            active = b
            update { it.copy(backendId = b.id, backendName = b.name, caps = b.caps, powered = false, error = null) }
            powerOnInternal(autoFallback = false)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun command(label: String, block: suspend (RadioBackend) -> Unit) = scope.launch {
        val b = active
        if (b == null) { setError(UiError("No radio method selected", "Open Diagnostics and pick one.", "", null)); return@launch }
        if (!state.powered && b.caps.contains(Cap.TUNE)) {
            AppLog.i("$label requested while off – powering on first")
            powerOnInternal(autoFallback = false)
            if (!state.powered) return@launch
        }
        exec(label) { block(b) }
    }

    // 45 s: worst case is walking the whole FM band with single MCU steps (~200 steps).
    private suspend fun exec(label: String, timeoutMs: Long = 45_000, guard: Boolean = false, block: suspend () -> Unit): Boolean {
        val b = active
        update { it.copy(busy = true, busyText = label) }
        AppLog.i("▶ $label [${b?.id}]")
        if (guard && b != null) prefs.inFlight = b.id
        try {
            withTimeout(timeoutMs) { block() }
            AppLog.i("✔ $label")
            update { it.copy(error = null) }
            return true
        } catch (e: RadioException) {
            AppLog.e("✖ $label: ${e.title}", e.cause)
            setError(UiError(e.title, e.hint, e.cause?.let { AppLog.stack(it).take(1500) } ?: "", b?.id))
        } catch (e: TimeoutCancellationException) {
            AppLog.e("✖ $label: timed out after ${timeoutMs / 1000}s")
            setError(UiError("\"$label\" took longer than ${timeoutMs / 1000} s", "The vendor service did not answer. Reboot the head unit (ACC off/on) and retry, or try another method.", "timeout", b?.id))
        } catch (e: SecurityException) {
            AppLog.e("✖ $label: permission denied", e)
            setError(UiError("Android blocked this (permission denied)", "This method needs system privileges on this firmware. Try another method.", AppLog.stack(e).take(1500), b?.id))
        } catch (e: Throwable) {
            AppLog.e("✖ $label: unexpected ${e.javaClass.simpleName}", e)
            setError(UiError("Unexpected error: ${e.javaClass.simpleName}", e.message ?: "Share the log from the Log tab.", AppLog.stack(e).take(1500), b?.id))
        } finally {
            if (guard && b != null) prefs.inFlight = null
            update { it.copy(busy = false, busyText = "") }
        }
        return false
    }

    private fun setError(e: UiError) = update { it.copy(error = e, status = e.title) }

    fun reenableCrashed() { prefs.clearCrashed(); AppLog.i("Crash blacklist cleared"); probeAll() }
}
