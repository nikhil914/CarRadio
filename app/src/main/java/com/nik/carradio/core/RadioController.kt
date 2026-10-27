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
import kotlinx.coroutines.delay
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

    override fun update(transform: (RadioState) -> RadioState) {
        var old: RadioState? = null
        _state.update { o -> old = o; transform(o) }
        old?.let { o -> val n = _state.value; if (n.radioText != o.radioText && n.radioText.isNotBlank()) SongHistory.onState(o, n) }
    }

    fun backend(id: String?) = backends.firstOrNull { it.id == id }

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        val band = prefs.lastBand
        _state.value = RadioState(band = band, freqKhz = prefs.lastFreq(band), status = "Ready")
        // Crash guard: a backend start that never finished means the process died inside it.
        // One unfinished start can be the ignition cutting power; only repeated ones disable it.
        prefs.inFlight?.let { id ->
            prefs.inFlight = null
            val n = backend(id)?.name ?: id
            val strikes = prefs.addStrike(id)
            if (strikes < Prefs.CRASH_STRIKES) {
                AppLog.w("Crash guard: \"$n\" did not finish starting last time ($strikes/${Prefs.CRASH_STRIKES}). Probably power loss – trying it again.")
            } else {
                prefs.markCrashed(id)
                AppLog.e("Crash guard: the app closed while starting \"$n\" $strikes times in a row. It is now skipped in automatic mode.")
                setError(UiError("\"$n\" closed the app $strikes times in a row", "It has been disabled for automatic mode. You can still test it manually in Diagnostics, or re-enable it in Settings.", "in-flight marker for '$id' found at startup", id))
            }
        }
    }

    // ------------------------------------------------------------------ probing / selection

    /**
     * Launch path. If a method worked last time, probe only that one (one bind, ~100 ms) and
     * start it. The full 7-method probe runs only on first launch, after a failure, or from
     * Diagnostics.
     */
    fun startup(forceStart: Boolean = false) = scope.launch {
        val autoStart = prefs.autoStart || forceStart
        // A method that only opens another app is never a reason to skip trying the real tuner
        // (1.0.0 remembered "stock" after an FYT tuning failure and never retried FYT).
        val last = backend(prefs.lastWorking)?.takeIf { it.id !in prefs.crashedBackends() && !it.caps.contains(Cap.EXTERNAL_UI) }
        if (last != null) {
            val t0 = System.currentTimeMillis()
            val r = try { withTimeout(6000) { last.probe(this@RadioController) } } catch (e: Throwable) {
                ProbeResult(ProbeStatus.UNAVAILABLE, "Probe failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            AppLog.i("Fast start: probed ${last.id} only (${System.currentTimeMillis() - t0} ms): ${r.status}")
            _probes.update { it + (last.id to r) }
            if (r.usable || r.status == ProbeStatus.UNKNOWN) {
                selectInternal(last, persist = false)
                if (!autoStart || last.caps.contains(Cap.EXTERNAL_UI)) return@launch
                powerOnInternal(autoFallback = false)
                if (state.powered) return@launch
                AppLog.w("Fast start: ${last.id} failed to start – running the full probe to find another method")
            } else {
                AppLog.w("Fast start: ${last.id} no longer works – running the full probe")
            }
        }
        probeAllInternal(thenAutoStart = true, forceStart = forceStart)
    }

    /** True when Diagnostics should run the full probe because only the fast path ran. */
    fun needsFullProbe() = _probes.value.size < backends.size && !_probing.value

    fun probeAll(thenAutoStart: Boolean = false) = scope.launch { probeAllInternal(thenAutoStart) }

    private suspend fun probeAllInternal(thenAutoStart: Boolean, forceStart: Boolean = false) {
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
                if ((prefs.autoStart || forceStart) && first.caps.none { it == Cap.EXTERNAL_UI }) powerOnInternal(autoFallback = true)
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
                prefs.clearStrikes(b.id)
                val temporary = b.id == "stream" && (b as StreamBackend).stationUrl != null
                if (!b.caps.contains(Cap.EXTERNAL_UI) && !temporary) { prefs.lastWorking = b.id; prefs.wasPlaying = true }
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
        prefs.wasPlaying = false
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

    /** The tune still running; a newer tune cancels it instead of queuing behind it. */
    @Volatile private var tuneJob: kotlinx.coroutines.Job? = null

    /**
     * Shows the target at once (the MCU reports back only after ~0.6–2 s), then tunes.
     * Tapping another station while one is still confirming cancels the first, so the
     * latest tap always wins and nothing queues up.
     */
    fun tune(khz: Int, band: Band = Freq.bandOf(khz)): kotlinx.coroutines.Job {
        val t = Freq.clamp(band, khz)
        val before = state.freqKhz to state.band
        if (state.powered) update { it.copy(freqKhz = t, band = band, stationName = if (it.freqKhz != t) "" else it.stationName, radioText = if (it.freqKhz != t) "" else it.radioText) }
        tuneJob?.let { if (it.isActive && state.powered) { AppLog.i("Tune: newer target ${t} kHz replaces the one still running"); it.cancel() } }
        val job = command("Tuning to ${Freq.format(band, t)} ${Freq.unit(band)}", onFail = {
            // Put the display back on what is really playing if the tuner refused.
            update { s -> if (s.freqKhz == t) s.copy(freqKhz = before.first, band = before.second) else s }
        }) { b ->
            b.tune(this, band, t)
            update { s -> if (b.id == "fyt" || b.id == "topway") s else s.copy(freqKhz = t, band = band) }
            prefs.lastBand = band; prefs.setLastFreq(band, t)
        }
        tuneJob = job
        return job
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

    // ------------------------------------------------------------------ tuner settings

    val activeBackend: RadioBackend? get() = active

    fun setOption(opt: TunerOpt, on: Boolean) = command("${opt.label}: ${if (on) "on" else "off"}") { it.setOption(this, opt, on) }

    fun setRegion(index: Int) = command("Region: ${active?.regions?.getOrNull(index) ?: index}") { it.setRegion(this, index) }

    fun sensitivity(up: Boolean) = command(if (up) "Sensitivity up" else "Sensitivity down") { it.sensitivity(this, up) }

    // ------------------------------------------------------------------ stations

    /** Target of the last station step still in flight, so fast wheel presses don't repeat one. */
    @Volatile private var pendingStep: Pair<Band, Int>? = null

    /** Steering-wheel / UI next-previous: moves through the selected group, wrapping. */
    fun stationStep(up: Boolean) {
        val (band, khz) = pendingStep ?: (state.band to state.freqKhz)
        val next = StationLogic.neighbour(StationStore.data.value, band, khz, up)
        if (next == null) {
            if (active?.caps?.contains(Cap.SEEK) == true) seek(up)
            else setError(UiError("No saved stations yet", "Run Scan, or tap \"+ Save\" on a station you like.", "", null))
            return
        }
        if (state.onlineStation != null) { AppLog.i("Station ${if (up) "next" else "previous"} (online) -> ${next.label()}"); playStation(next); return }
        pendingStep = next.band to next.freqKhz
        AppLog.i("Station ${if (up) "next" else "previous"} -> ${next.label()} (${next.freqKhz} kHz)")
        val target = next.band to next.freqKhz
        tune(next.freqKhz, next.band).invokeOnCompletion { if (pendingStep == target) pendingStep = null }
    }

    override fun onExternalStep(fromBand: Band, fromKhz: Int, up: Boolean) {
        if (!prefs.wheelUsesStations) { AppLog.i("Wheel redirect off in Settings – keeping the vendor's station"); return }
        if (_scanProgress.value != null) return
        val d = StationStore.data.value
        if (StationLogic.visible(d).size < 2) { AppLog.i("Wheel redirect skipped: fewer than 2 stations in this group"); return }
        val next = StationLogic.neighbour(d, fromBand, fromKhz, up) ?: return
        pendingStep = next.band to next.freqKhz
        AppLog.i("Wheel ${if (up) "next" else "previous"} -> ${next.label()} (${next.freqKhz} kHz)")
        val target = next.band to next.freqKhz
        tune(next.freqKhz, next.band).invokeOnCompletion { if (pendingStep == target) pendingStep = null }
    }

    override fun onAccChanged(on: Boolean) {
        if (!on) {
            // Power is about to go: a start interrupted now is not a crash of that method.
            if (prefs.inFlight != null) { AppLog.i("ACC off during a start – clearing the crash guard"); prefs.inFlight = null }
            AppLog.flush()
            AppLog.i("ACC off: radio state kept for resume (wasPlaying=${prefs.wasPlaying})"); return
        }
        if (!prefs.resumeOnStart || !prefs.wasPlaying) return
        AppLog.i("ACC on: re-claiming the radio source and last station")
        scope.launch { powerOnInternal(autoFallback = false) }
    }

    /** Boot: if the radio was on when the unit went down, bring it back. */
    fun resumeAfterBoot() {
        if (!prefs.resumeOnStart || !prefs.wasPlaying) { AppLog.i("Boot: not resuming (resume=${prefs.resumeOnStart}, wasPlaying=${prefs.wasPlaying})"); return }
        AppLog.i("Boot: resuming the radio")
        if (_probes.value.isEmpty()) startup(forceStart = true) else powerOn()
    }

    fun playStation(s: Station) {
        // While a station plays over the internet, the list keeps using the internet if it can.
        if (state.onlineStation != null) { if (s.streamUrl.isNotBlank()) listenOnline(s) else backToFm(s); return }
        tune(s.freqKhz, s.band)
    }

    // ------------------------------------------------------------------ FM ↔ internet

    /** The tuner method to return to after listening online. */
    @Volatile private var fmBackend: RadioBackend? = null

    /** Plays [st]'s internet stream instead of FM (weak signal, out of range). */
    fun listenOnline(st: Station) = scope.launch {
        if (st.streamUrl.isBlank()) { setError(UiError("No internet stream for ${st.label()}", "Settings → Stations → Find logos and streams online, or long-press the station and paste a stream URL.", "", null)); return@launch }
        val stream = backends.first { it.id == "stream" } as StreamBackend
        if (active?.id != "stream") fmBackend = active
        stream.stationUrl = st.streamUrl
        AppLog.i("Listen online: ${st.label()} -> ${st.streamUrl}")
        if (active?.id == "stream" && state.powered) runCatching { stream.powerOff(this@RadioController) }
        selectInternal(stream, persist = false)
        update { it.copy(stationName = st.name, radioText = "", freqKhz = st.freqKhz, band = st.band, onlineStation = st.id) }
        powerOnInternal(autoFallback = false)
        if (!state.powered) { update { it.copy(onlineStation = null) }; fmBackend?.let { fm -> selectInternal(fm, persist = false); powerOnInternal(autoFallback = false) } }
    }

    /** Back to the tuner, on [st] or the station that was playing online. */
    fun backToFm(st: Station? = null) = scope.launch {
        val fm = fmBackend ?: backends.firstOrNull { it.id == prefs.lastWorking } ?: return@launch
        val target = st ?: StationStore.data.value.stations.firstOrNull { it.id == state.onlineStation }
        (backends.first { it.id == "stream" } as StreamBackend).stationUrl = null
        update { it.copy(onlineStation = null) }
        selectInternal(fm, persist = false)
        if (target != null) { prefs.lastBand = target.band; prefs.setLastFreq(target.band, target.freqKhz) }
        powerOnInternal(autoFallback = false)
        AppLog.i("Back to FM${target?.let { ": ${it.label()}" } ?: ""}")
    }

    /** Double-tap on a station: move to its next alternative frequency (weak signal on the road). */
    fun cycleAf(s: Station) {
        val cur = if (s.owns(state.band, state.freqKhz)) state.freqKhz else s.freqKhz
        val next = StationLogic.nextAf(s, cur) ?: return playStation(s).let { }
        AppLog.i("AF: ${s.label()} $cur -> $next kHz")
        tune(next, s.band)
    }

    /** Saves what is playing now. Already saved → just adds it to [groupId]. */
    fun saveCurrentStation(groupId: String?) {
        val s = state
        val realGroup = groupId?.takeIf { g -> StationStore.data.value.groups.any { it.id == g } }
        StationStore.update { d ->
            val existing = StationLogic.find(d, s.band, s.freqKhz)
            if (existing != null) {
                d.copy(stations = d.stations.map { if (it.id == existing.id) it.copy(
                    groups = if (realGroup != null) it.groups + realGroup else it.groups,
                    favourite = it.favourite || groupId == StationLogic.FAVOURITES,
                    name = it.name.ifBlank { s.stationName }, verified = true) else it })
            } else {
                val seed = StationLogic.seedMatch(s.stationName, s.band, s.freqKhz)
                val groups = setOfNotNull(realGroup ?: seed?.group)
                d.copy(stations = d.stations + Station((d.stations.maxOfOrNull { it.id } ?: 0) + 1, s.band, s.freqKhz,
                    name = s.stationName.ifBlank { seed?.name ?: "" }, favourite = groupId == StationLogic.FAVOURITES,
                    groups = groups, position = (d.stations.maxOfOrNull { it.position } ?: 0) + 1))
            }
        }
        AppLog.i("Saved station ${s.freqKhz} kHz to ${groupId ?: "list"}")
        update { it.copy(status = "Saved ${Freq.format(s.band, s.freqKhz)} ${Freq.unit(s.band)}") }
    }

    @Volatile private var scanCancel = false
    private val _scanProgress = MutableStateFlow<String?>(null)
    /** Non-null while a station scan runs: a one-line progress text. */
    val scanProgress: StateFlow<String?> = _scanProgress.asStateFlow()

    fun cancelScan() { scanCancel = true }

    /**
     * Finds every receivable station by seeking up from the bottom of the band, staying
     * [dwellMs] on each to collect its RDS name, then merges the hits into the station list.
     * Works with any backend that can seek; independent of vendor preset tables.
     */
    fun scanStations(dwellMs: Long = 3000) = scope.launch {
        val b = active
        if (b == null || !b.caps.contains(Cap.SEEK)) {
            setError(UiError("This radio method cannot scan", "Choose the FYT method in Diagnostics, or save stations by hand.", "", b?.id)); return@launch
        }
        if (!state.powered) { powerOnInternal(autoFallback = false); if (!state.powered) return@launch }
        scanCancel = false
        val band = state.band
        val startKhz = state.freqKhz
        val hits = mutableListOf<ScanHit>()
        _scanProgress.value = "Starting scan of ${band.name}…"
        val ok = exec("Scanning ${band.name}", timeoutMs = 6 * 60_000) {
            b.tune(this@RadioController, band, if (band == Band.FM) Freq.FM_MIN else Freq.AM_MIN)
            delay(400)
            var last = state.freqKhz
            for (i in 0 until 80) {
                if (scanCancel) break
                b.seek(this@RadioController, true)
                if (!waitSettled(last, 9000)) { AppLog.i("Scan: no further station (stuck at $last)"); break }
                val f = state.freqKhz
                if (f <= last) { AppLog.i("Scan: wrapped to $f – band done"); break }
                val pct = scanPercent(band, f)
                _scanProgress.value = "$pct% · found ${hits.size} · listening on ${Freq.format(band, f)} ${Freq.unit(band)}…"
                waitForName(dwellMs)
                hits += ScanHit(band, state.freqKhz, state.stationName)
                AppLog.i("Scan hit: ${state.freqKhz} kHz \"${state.stationName}\"")
                last = state.freqKhz
            }
        }
        _scanProgress.value = null
        if (hits.isEmpty()) {
            if (ok) setError(UiError("Scan found no stations", "Check the antenna connection, or switch LOC/DX in the stock radio settings.", "", b.id))
            runCatching { b.tune(this@RadioController, band, startKhz) }
            return@launch
        }
        var added = 0
        StationStore.update { d -> StationLogic.mergeScan(d, hits).also { added = it.second }.first }
        AppLog.i("Scan merged: ${hits.size} found, $added new")
        update { it.copy(status = "Scan: ${hits.size} stations found, $added new${if (scanCancel) " (stopped)" else ""}") }
        exec("Returning to first station") { b.tune(this@RadioController, band, hits.first().freqKhz) }
    }

    private fun scanPercent(band: Band, khz: Int): Int {
        val (lo, hi) = if (band == Band.FM) Freq.FM_MIN to Freq.FM_MAX else Freq.AM_MIN to Freq.AM_MAX
        return ((khz - lo) * 100 / (hi - lo).coerceAtLeast(1)).coerceIn(0, 100)
    }

    /**
     * Stays on a found station until its RDS name has arrived and held for 400 ms, or
     * [maxMs] passed (no RDS). Most stations name themselves in under a second, so a full FM
     * scan drops from ~70 s to ~25 s.
     */
    private suspend fun waitForName(maxMs: Long) {
        val start = System.currentTimeMillis()
        var name = ""; var since = start
        delay(500)
        while (System.currentTimeMillis() - start < maxMs) {
            val n = state.stationName
            if (n != name) { name = n; since = System.currentTimeMillis() }
            else if (n.isNotBlank() && System.currentTimeMillis() - since >= 400) return
            delay(100)
        }
    }

    /** After a seek: wait until the frequency moved away from [from] and then held still. */
    private suspend fun waitSettled(from: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (state.freqKhz == from && System.currentTimeMillis() < end) delay(80)
        if (state.freqKhz == from) return false
        var seen = state.freqKhz; var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < end) {
            delay(100)
            if (state.freqKhz != seen) { seen = state.freqKhz; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince > 700 && !state.scanning) return true
        }
        return true
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

    private fun command(label: String, onFail: () -> Unit = {}, block: suspend (RadioBackend) -> Unit) = scope.launch {
        val b = active
        if (b == null) { setError(UiError("No radio method selected", "Open Diagnostics and pick one.", "", null)); return@launch }
        if (!state.powered && b.caps.contains(Cap.TUNE)) {
            AppLog.i("$label requested while off – powering on first")
            powerOnInternal(autoFallback = false)
            if (!state.powered) return@launch
        }
        if (!exec(label) { block(b) }) onFail()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Replaced by a newer command (another station tapped): not an error.
            AppLog.i("… $label replaced by a newer command")
            throw e
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

    private val _learn = MutableStateFlow<List<String>?>(null)
    /** Result of the last "Learn tuner commands" run, shown in Diagnostics and the report. */
    val learnResult: StateFlow<List<String>?> = _learn.asStateFlow()

    fun learnFytCommands() = scope.launch {
        val fyt = backends.first { it.id == "fyt" } as com.nik.carradio.backend.fyt.FytMcuBackend
        if (active?.id != "fyt") selectInternal(fyt, persist = false)
        if (!state.powered) powerOnInternal(autoFallback = false)
        _learn.value = listOf("Running… (about 30 s, the radio will jump around)")
        var lines: List<String> = emptyList()
        exec("Learning tuner commands", timeoutMs = 90_000) { lines = fyt.learnCommands(this@RadioController) }
        _learn.value = lines.ifEmpty { listOf("Failed – see the Log tab") }
    }

    fun reenableCrashed() { prefs.clearCrashed(); AppLog.i("Crash blacklist cleared"); probeAll() }
}
