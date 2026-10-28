package com.nik.carradio.core

import android.content.Context
import com.android.fmradio.FmNative
import android.content.Intent
import com.nik.carradio.BuildConfig
import com.nik.carradio.backend.fyt.Syu
import com.nik.carradio.backend.fyt.SyuConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Full radio test: one run in the car tries every known way to control the tuner and records
 * what happened, so the next version can use exactly what works on this unit.
 *
 * Phases: unit facts (read-only) → listen to everything the radio service reports → audio
 * source → baseline moves (step/seek/channel) → direct-tune matrix (every known command format
 * and unit) → the same with the stock radio app in front → the SoC FM library → restore.
 *
 * Each step's outcome is checked against the frequency the tuner reports, never assumed.
 * Progress is saved after every step; if a step kills the app, the next start says which one
 * and the run can continue after it.
 */
object FullTest {
    enum class Outcome { PASS, FAIL, INFO, SKIP, CRASH }
    data class Line(val step: String, val outcome: Outcome, val text: String)
    data class Status(val running: Boolean = false, val phase: String = "", val step: String = "", val index: Int = 0, val total: Int = 0,
                      val finished: Boolean = false, val interruptedAt: String? = null, val startedAt: Long = 0, val endedAt: Long = 0)

    private class Step(val id: String, val phase: String, val label: String, val risky: Boolean = false, val timeoutMs: Long = 25_000,
                       val run: suspend Run.() -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var file: File
    private lateinit var app: Context

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()
    /** Raw dumps (unit facts, updates seen); only in the report, not on screen. */
    private val sections = linkedMapOf<String, List<String>>()
    private var done = mutableSetOf<String>()

    /** Test hooks: a fake radio service instead of com.syu.ms, and faster waits. */
    internal var connector: (suspend (Context) -> Triple<Syu.Module, Syu.Module?, Syu.Module?>)? = null
    internal var timeScale = 1.0

    /** True while running: the controller rejects commands and the FYT backend does not redirect. */
    @Volatile var active = false; private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "fulltest.json")
        runCatching { load() }.onFailure { AppLog.w("Full test state unreadable", it) }
    }

    // ------------------------------------------------------------------ control

    fun start(resume: Boolean) {
        if (job?.isActive == true) return
        if (!resume) { _lines.value = emptyList(); sections.clear(); done.clear() }
        else _status.value.interruptedAt?.let { crashed ->
            if (crashed !in done) { add(crashed, Outcome.CRASH, "The app closed during this step (it is skipped now)"); done += crashed }
        }
        // Synchronously, so nobody sees the previous run's "finished" state after pressing Start.
        _status.value = Status(running = true, phase = "Starting", startedAt = if (resume) _status.value.startedAt else 0)
        active = true
        job = scope.launch { runAll() }
    }

    fun stop() { job?.cancel() }

    private suspend fun runAll() {
        active = true
        val steps = steps()
        val r = Run(app)
        val started = if (_status.value.startedAt > 0 && done.isNotEmpty()) _status.value.startedAt else System.currentTimeMillis()
        if (done.isEmpty()) RadioController.prefs.fytTuneSpec = null   // a fresh run decides again
        _status.value = Status(running = true, total = steps.size, startedAt = started)
        AppLog.i("Full test: start (${steps.size} steps, ${done.size} already done)")
        try {
            for ((i, st) in steps.withIndex()) {
                if (st.id in done) continue
                _status.value = _status.value.copy(phase = st.phase, step = st.label, index = i + 1)
                save(current = st.id)
                AppLog.step("Full test ${i + 1}/${steps.size}: ${st.label}")
                try {
                    withTimeout(st.timeoutMs) { st.run(r.also { it.stepId = st.id }) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    add(st.id, Outcome.FAIL, "${st.label}: took longer than ${st.timeoutMs / 1000} s")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    add(st.id, Outcome.SKIP, "Stopped by the user"); throw e
                } catch (e: Throwable) {
                    add(st.id, Outcome.FAIL, "${st.label}: ${e.javaClass.simpleName}: ${e.message}")
                }
                done += st.id
                save(current = null)
            }
            r.summary()
            _status.value = _status.value.copy(running = false, finished = true, interruptedAt = null, endedAt = System.currentTimeMillis())
            AppLog.i("Full test: finished")
        } finally {
            r.release()
            active = false
            if (_status.value.running) _status.value = _status.value.copy(running = false, endedAt = System.currentTimeMillis())
            save(current = null)
        }
    }

    private fun add(step: String, o: Outcome, text: String) {
        _lines.value = _lines.value + Line(step, o, text)
        AppLog.i("Full test [$o] $text")
    }

    // ------------------------------------------------------------------ the run

    /** Everything one run needs: Syu modules, an observer of every update, helpers. */
    class Run(val ctx: Context) {
        var stepId = ""
        var toolkit: Syu.Toolkit? = null
        var radio: Syu.Module? = null
        var main: Syu.Module? = null
        var steer: Syu.Module? = null
        @Volatile var freq = 0
        @Volatile var band: Band = Band.FM
        @Volatile var bandInfo: BandInfo? = null
        var startFreq = 0
        val events = ConcurrentLinkedQueue<String>()
        @Volatile var eventCount = 0
        /** Code 28 = commands com.syu.ms forwards to the stock radio app (SoC FM units). */
        val forwarded = ConcurrentLinkedQueue<Pair<Long, String>>()
        val replay = linkedMapOf<String, String>()
        val tuneWins = mutableListOf<TuneSpec>()
        val moveWins = mutableListOf<String>()
        private val t0 = System.currentTimeMillis()
        private var listening = false

        fun log(o: Outcome, text: String) = add(stepId, o, text)
        fun section(name: String, l: List<String>) { sections[name] = (sections[name] ?: emptyList()) + l }

        private fun fmt(code: Int, ints: IntArray?, f: FloatArray?, s: Array<String?>?) =
            "u$code" + (ints?.let { " i=" + it.take(12).joinToString(",", "[", "]") } ?: "") +
                (f?.let { " f=" + it.take(8).joinToString(",", "[", "]") } ?: "") +
                (s?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { " s=" + it.take(4).joinToString("|", "[", "]") { x -> x.take(40) } } ?: "")

        private fun note(module: String, code: Int, ints: IntArray?, f: FloatArray?, s: Array<String?>?) {
            val line = "$module.${fmt(code, ints, f, s)}"
            val at = System.currentTimeMillis() - t0
            if (!listening) { synchronized(replay) { replay["$module.$code"] = line } ; return }
            if (eventCount < 1500) { events += "+${at}ms $line"; eventCount++ }
        }

        private val radioCb = Syu.Callback { code, ints, f, s ->
            if (code == Syu.U_FREQ) ints?.firstOrNull()?.let { v -> Freq.decodeInt(v)?.let { freq = it; band = Freq.bandOf(it) } }
            if (code == Syu.U_EXTRA_FREQ_INFO) Syu.bandInfo(ints)?.let { bandInfo = it }
            if (code == 28) forwarded += System.currentTimeMillis() to fmt(code, ints, f, s)
            note("radio", code, ints, f, s)
        }
        private val mainCb = Syu.Callback { code, ints, f, s -> note("main", code, ints, f, s) }
        private val steerCb = Syu.Callback { code, ints, f, s -> note("steer", code, ints, f, s) }

        suspend fun connect() {
            if (radio != null) return
            val hook = connector
            if (hook != null) { val (r, m, st) = hook(ctx); radio = r; main = m; steer = st } else {
                val tk = SyuConnection.connect(ctx)
                toolkit = tk
                radio = tk.module(Syu.MODULE_RADIO) ?: error("no radio module")
                main = tk.module(Syu.MODULE_MAIN)
                steer = runCatching { tk.module(Syu.MODULE_STEER) }.getOrNull()
            }
            // update=1: the service replays the current value of every code it knows.
            for (c in 0..255) runCatching { radio!!.register(radioCb, c, 1) }
            main?.let { m -> for (c in 0..255) runCatching { m.register(mainCb, c, 1) } }
            steer?.let { st -> for (c in 0..255) runCatching { st.register(steerCb, c, 0) } }
        }

        fun startListening() { listening = true }

        fun release() {
            radio?.let { r -> for (c in 0..255) runCatching { r.unregister(radioCb, c) } }
            main?.let { m -> for (c in 0..255) runCatching { m.unregister(mainCb, c) } }
            steer?.let { st -> for (c in 0..255) runCatching { st.unregister(steerCb, c) } }
            radio = null; main = null; steer = null
        }

        fun appId() = DeviceInfo.prop("sys.app_id").ifEmpty { "?" }

        suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + (ms * timeScale).toLong()
            while (System.currentTimeMillis() < end) { if (cond()) return true; delay(20) }
            return cond()
        }

        suspend fun pause(ms: Long) = delay((ms * timeScale).toLong())

        /** Two targets valid on every region's grid (50/100/200 kHz); alternate so each try must move. */
        fun target(): Int = if (abs(freq - 98_100) < 60) 101_100 else 98_100

        fun near(a: Int, b: Int) = abs(a - b) <= 30

        fun forwardedSince(t: Long) = forwarded.filter { it.first >= t }.joinToString(" ") { it.second }.ifEmpty { "none" }

        suspend fun claimSource() {
            main?.cmd(Syu.MAIN_C_APP_ID, intArrayOf(Syu.APP_ID_RADIO))
            waitFor(1500) { appId() == "1" }
        }

        /** Sends [spec] towards a fresh target and classifies what the tuner did. */
        suspend fun tryTune(spec: TuneSpec, note: String = "") {
            val m = radio ?: error("not connected")
            if (freq <= 0) { log(Outcome.SKIP, "${spec.label()}: tuner reports no frequency"); return }
            if (spec.claimSource) claimSource()
            val from = freq; val to = target()
            val bi = bandInfo
            val min = bi?.minKhz ?: 87_500; val max = bi?.maxKhz ?: 108_000; val stp = bi?.stepKhz ?: 50
            val r = spec.render(Band.FM, to, min, max, stp)
            val t = System.currentTimeMillis(); val id0 = appId()
            m.cmd(spec.code, r.ints, r.floats, r.strings?.let { a -> Array(a.size) { a[it] } })
            val ok = waitFor(3500) { near(freq, to) }
            val fw = forwardedSince(t)
            val where = when { ok -> "reached ${Freq.format(Band.FM, freq)}"; freq != from -> "moved to ${Freq.format(Band.FM, freq)} (wanted ${Freq.format(Band.FM, to)})"; else -> "no change" }
            if (ok) tuneWins += spec
            log(if (ok) Outcome.PASS else Outcome.FAIL, "tune ${spec.label()}$note: $where · app_id=$id0 · forwarded=$fw")
        }

        /** A command that should move the tuner somewhere (step/seek/channel). */
        suspend fun tryMove(label: String, code: Int, ints: IntArray?, waitMs: Long = 2500) {
            val m = radio ?: error("not connected")
            val from = freq; val t = System.currentTimeMillis()
            m.cmd(code, ints)
            val moved = waitFor(waitMs) { freq != from }
            if (moved) { var last = freq; while (waitFor(900) { freq != last }) last = freq }   // let a seek settle
            if (moved) moveWins += label
            log(if (moved) Outcome.PASS else Outcome.FAIL, "$label: ${if (moved) "moved ${Freq.format(Band.FM, from)} → ${Freq.format(Band.FM, freq)}" else "no change at ${Freq.format(Band.FM, from)}"} · forwarded=${forwardedSince(t)}")
        }

        fun stockRadioPackage(): String? = listOf("com.syu.radio", "com.syu.carradio").firstOrNull { p ->
            ctx.packageManager.getLaunchIntentForPackage(p) != null
        }

        fun bringBack() {
            ctx.startActivity(Intent(ctx, com.nik.carradio.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        suspend fun summary() {
            val wins = tuneWins.distinct()
            val lines = mutableListOf<String>()
            lines += UnitFacts.header()
            lines += "Start ${Freq.format(Band.FM, startFreq)} MHz, now ${Freq.format(Band.FM, freq)} MHz, band info: ${bandInfo?.describe() ?: "not reported"}"
            lines += "Direct tuning that works: " + (wins.joinToString("; ") { it.label() }.ifEmpty { "NONE" })
            lines += "Moves that work: " + moveWins.distinct().joinToString("; ").ifEmpty { "NONE" }
            lines += "Commands forwarded to the stock radio app (code 28): ${forwarded.size}"
            // The plain mode-1/3 formats are what the app tries anyway; save anything else that worked.
            val best = wins.firstOrNull()
            if (best != null) {
                RadioController.prefs.fytTuneSpec = best.encode()
                lines += "Saved for Car Radio: ${best.label()} – tapping a station now uses it first."
            }
            sections["SUMMARY"] = lines
            add("summary", if (best != null) Outcome.PASS else Outcome.INFO,
                if (best != null) "Direct tuning works with ${best.label()} – saved, Car Radio uses it now"
                else "No direct-tune format worked on this unit; see the report for what the tuner did")
        }
    }

    // ------------------------------------------------------------------ the steps

    private fun steps(): List<Step> {
        val s = mutableListOf<Step>()
        fun step(id: String, phase: String, label: String, risky: Boolean = false, timeout: Long = 25_000, run: suspend Run.() -> Unit) =
            s.add(Step(id, phase, label, risky, timeout, run))

        // A. Unit facts – read-only.
        step("facts.props", "Unit facts", "System properties") { val p = UnitFacts.props(); section("Properties", p); log(Outcome.INFO, "Properties: ${p.size} lines; " + UnitFacts.header().drop(2).joinToString("; ")) }
        step("facts.packages", "Unit facts", "Radio-related apps") { val p = UnitFacts.packages(ctx); section("Packages", p); log(Outcome.INFO, "Radio-related apps: ${p.size - 1}") }
        step("facts.components", "Unit facts", "Stock radio components") { val c = UnitFacts.components(ctx); section("Components", c); log(Outcome.INFO, "Vendor components listed: ${c.size}") }
        step("facts.files", "Unit facts", "FM libraries and devices") { val f = UnitFacts.files(); section("Files", f); log(Outcome.INFO, f.take(3).joinToString(" | ").take(300)) }
        step("facts.binder", "Unit facts", "System services") { val b = UnitFacts.binderServices(); section("Binder services", b); log(Outcome.INFO, b.first()) }
        step("facts.dex", "Unit facts", "Firmware fingerprint (radio app strings)", timeout = 60_000) { val d = UnitFacts.dexStrings(ctx); section("Firmware strings", d); log(Outcome.INFO, d.filter { it.startsWith("[") }.joinToString(" | ").ifEmpty { "no vendor radio APK readable" }) }

        // B. Connect and listen.
        step("syu.modules", "Radio service", "Modules of com.syu.ms") {
            if (connector != null) { log(Outcome.INFO, "Simulated radio service"); return@step }
            val tk = SyuConnection.connect(ctx)
            val l = (0..30).map { c -> c to runCatching { tk.module(c) }.getOrNull() }.filter { it.second != null }
                .map { (c, m) -> "module $c ${m!!.descriptor() ?: ""}" }
            section("Syu modules", l); log(Outcome.INFO, "Connected to ${SyuConnection.connectedTo}; modules: ${l.joinToString(", ") { it.substringAfter("module ").substringBefore(' ') }}")
        }
        step("syu.listen", "Radio service", "Current values of every update code", timeout = 30_000) {
            connect(); pause(3000)
            val r = synchronized(replay) { replay.values.toList() }
            section("Replayed values", r); startListening()
            startFreq = freq
            log(Outcome.INFO, "Tuner at ${if (freq > 0) Freq.format(Band.FM, freq) else "?"} · ${r.size} codes replayed · radio codes: " +
                r.filter { it.startsWith("radio.") }.joinToString(" ") { it.substringAfter("radio.u").substringBefore(' ') })
        }
        step("syu.get", "Radio service", "Radio get(0…40)") {
            val m = radio ?: error("not connected")
            val l = (0..40).mapNotNull { c -> runCatching { m.get(c) }.getOrNull()?.let { "get($c) = $it" } }
            section("Radio get()", l); log(Outcome.INFO, "get() answered for ${l.size} codes")
        }
        step("source", "Audio source", "Claim the radio source") {
            val before = appId()
            claimSource(); pause(500)
            log(if (appId() == "1") Outcome.PASS else Outcome.FAIL, "sys.app_id $before → ${appId()} after main cmd 0 [1] (set-frequency on SoC-FM units needs 1)")
        }

        // C. Baseline moves – they also prove the tuner reports its frequency.
        step("move.stepup", "Step and seek", "Step up cmd(3,[0])") { tryMove("step up cmd(3,[0])", Syu.C_FREQ_UP, intArrayOf(0)) }
        step("move.stepdown", "Step and seek", "Step down cmd(4,[0])") { tryMove("step down cmd(4,[0])", Syu.C_FREQ_DOWN, intArrayOf(0)) }
        step("move.stepnull", "Step and seek", "Step up cmd(3)") { tryMove("step up cmd(3)", Syu.C_FREQ_UP, null) }
        step("move.step1", "Step and seek", "Step up cmd(3,[1])") { tryMove("step up cmd(3,[1])", Syu.C_FREQ_UP, intArrayOf(1)) }
        step("move.seekup", "Step and seek", "Seek up cmd(5,[0])") { tryMove("seek up cmd(5,[0])", Syu.C_SEEK_UP, intArrayOf(0), 7000) }
        step("move.seekdown", "Step and seek", "Seek down cmd(6,[0])") { tryMove("seek down cmd(6,[0])", Syu.C_SEEK_DOWN, intArrayOf(0), 7000) }
        step("move.next", "Step and seek", "Next preset cmd(0,[0])") { tryMove("next preset cmd(0,[0])", Syu.C_NEXT_CHANNEL, intArrayOf(0)) }
        step("move.prev", "Step and seek", "Previous preset cmd(1,[0])") { tryMove("previous preset cmd(1,[0])", Syu.C_PREV_CHANNEL, intArrayOf(0)) }
        step("move.select", "Step and seek", "Select presets 0…5 cmd(7,[n])", timeout = 40_000) {
            val m = radio ?: error("not connected")
            val got = (0..5).map { n -> val from = freq; m.cmd(Syu.C_SELECT_CHANNEL, intArrayOf(n)); waitFor(2500) { freq != from }; "P${n + 1}=${Freq.format(Band.FM, freq)}" }
            log(Outcome.INFO, "Preset select: ${got.joinToString(" ")}")
        }
        step("move.band", "Step and seek", "Band select FM cmd(11,[-3])") {
            val t = System.currentTimeMillis()
            radio?.cmd(Syu.C_BAND, intArrayOf(Syu.BAND_SWITCH_FM)); pause(1500)
            log(Outcome.INFO, "Band FM selected · band info: ${bandInfo?.describe() ?: "not reported"} · forwarded=${forwardedSince(t)}")
        }

        // D. Direct tune: every known command format and unit.
        val matrix = listOf(
            TuneSpec(13, "1,{v10}"), TuneSpec(13, "1,{khz}"), TuneSpec(13, "1,{v100}"), TuneSpec(13, "1,{idx}"),
            TuneSpec(13, "3,{v10}"), TuneSpec(13, "3,{khz}"), TuneSpec(13, "0,{idx}"), TuneSpec(13, "2,{ratio}"),
            TuneSpec(13, "{v10}"), TuneSpec(13, "{khz}"), TuneSpec(13, "1,0,{v10}"), TuneSpec(13, "1,1,{v10}"),
            TuneSpec(13, "1", floats = "{fmhz}"), TuneSpec(13, "", floats = "{fmhz}"), TuneSpec(13, "1", strings = "{fmhz}"),
            TuneSpec(33, "13,1,{v10}"), TuneSpec(33, "13,3,{v10}"), TuneSpec(2, "{v10}"), TuneSpec(2, "1,{v10}"),
            TuneSpec(13, "1,{v10}", claimSource = true), TuneSpec(13, "1,{khz}", claimSource = true), TuneSpec(13, "3,{v10}", claimSource = true),
        )
        matrix.forEachIndexed { i, spec -> step("tune.$i", "Direct tune", "Tune ${spec.label()}") { tryTune(spec) } }

        // E. With the stock radio app in front (SoC-FM units: it executes the forwarded commands).
        step("stock.open", "Stock radio app", "Open the stock radio app") {
            val pkg = stockRadioPackage() ?: run { log(Outcome.SKIP, "No stock radio app with a launcher entry"); return@step }
            ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            pause(5000)
            log(Outcome.INFO, "$pkg opened · sys.app_id=${appId()} · tuner at ${Freq.format(Band.FM, freq)}")
        }
        listOf(TuneSpec(13, "1,{v10}"), TuneSpec(13, "1,{khz}"), TuneSpec(13, "1,{v100}"), TuneSpec(13, "3,{v10}"), TuneSpec(13, "1,{idx}"))
            .forEachIndexed { i, spec -> step("stock.tune.$i", "Stock radio app", "Tune ${spec.label()} (stock app open)") {
                if (stockRadioPackage() == null) { log(Outcome.SKIP, "no stock radio app"); return@step }
                tryTune(spec, " with stock app open")
            } }
        step("stock.extras", "Stock radio app", "Open the stock app with a frequency") {
            val pkg = stockRadioPackage() ?: run { log(Outcome.SKIP, "no stock radio app"); return@step }
            val to = target(); val from = freq
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("freq", to / 10).putExtra("frequency", to / 1000f).putExtra("FREQ", to / 10).putExtra("fm_freq", to / 10).putExtra("band", 0)
            ctx.startActivity(i)
            val ok = waitFor(4000) { near(freq, to) }
            log(if (ok) Outcome.PASS else Outcome.FAIL, "Launch extras freq/frequency/FREQ/fm_freq: ${if (ok) "reached" else if (freq != from) "moved to ${Freq.format(Band.FM, freq)}" else "no change"}")
        }
        step("stock.back", "Stock radio app", "Back to Car Radio") { bringBack(); pause(1500); log(Outcome.INFO, "Back · sys.app_id=${appId()} · tuner at ${Freq.format(Band.FM, freq)}") }

        // F. The SoC FM library directly (may conflict with the stock app: last, crash-guarded).
        step("native.load", "FM chip library", "Load libfmjni", risky = true) {
            val err = FmNative.load()
            log(if (err == null) Outcome.PASS else Outcome.FAIL, if (err == null) "libfmjni loaded from ${FmNative.loadedFrom()}" else "libfmjni: $err")
        }
        step("native.tune", "FM chip library", "FM chip open + tune", risky = true) {
            if (!FmNative.isLoaded()) { log(Outcome.SKIP, "library not loaded"); return@step }
            val fm = FmNative.INSTANCE
            val open = runCatching { fm.openDev() }.getOrElse { log(Outcome.FAIL, "openDev: ${it.javaClass.simpleName} ${it.message}"); return@step }
            val to = target(); val from = freq
            val up = runCatching { fm.powerUp(from / 1000f) }.getOrElse { false }
            val tuned = runCatching { fm.tune(to / 1000f) }.getOrElse { false }
            val rssi = runCatching { fm.readRssi() }.getOrNull()
            val seen = waitFor(3000) { near(freq, to) }
            log(if (tuned) Outcome.PASS else Outcome.FAIL, "openDev=$open powerUp=$up tune(${to / 1000f})=$tuned rssi=$rssi · service reports ${Freq.format(Band.FM, freq)}${if (seen) " (followed)" else ""}")
        }

        // G. Put things back.
        step("restore", "Restore", "Back to the starting station", timeout = 60_000) {
            if (startFreq <= 0 || freq <= 0) { log(Outcome.SKIP, "start frequency unknown"); return@step }
            val m = radio ?: return@step
            val spec = tuneWins.firstOrNull()
            if (spec != null) {
                val bi = bandInfo
                val r = spec.render(Band.FM, startFreq, bi?.minKhz ?: 87_500, bi?.maxKhz ?: 108_000, bi?.stepKhz ?: 50)
                if (spec.claimSource) claimSource()
                m.cmd(spec.code, r.ints, r.floats, r.strings?.let { a -> Array(a.size) { a[it] } })
                waitFor(3500) { near(freq, startFreq) }
            }
            var guard = 0
            while (!near(freq, startFreq) && guard++ < 60) {
                val from = freq; m.cmd(if (startFreq > freq) Syu.C_FREQ_UP else Syu.C_FREQ_DOWN, intArrayOf(0))
                if (!waitFor(2000) { freq != from }) break
                if ((startFreq > from) != (startFreq > freq) && !near(freq, startFreq)) break
            }
            log(if (near(freq, startFreq)) Outcome.PASS else Outcome.INFO, "Restored to ${Freq.format(Band.FM, freq)} (start ${Freq.format(Band.FM, startFreq)})")
            section("Updates seen during the test", events.toList())
        }
        return s
    }

    // ------------------------------------------------------------------ report

    fun report(): String = buildString {
        val st = _status.value
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        appendLine("=== Car Radio full radio test ${BuildConfig.VERSION_NAME} ===")
        appendLine("Started ${if (st.startedAt > 0) f.format(Date(st.startedAt)) else "?"}, ${if (st.finished) "finished" else if (st.running) "RUNNING" else "NOT FINISHED"}" +
            (if (st.endedAt > st.startedAt && st.startedAt > 0) " in ${(st.endedAt - st.startedAt) / 1000} s" else "") +
            (st.interruptedAt?.let { " – app closed during step $it" } ?: ""))
        sections["SUMMARY"]?.let { appendLine(); appendLine("=== SUMMARY ==="); it.forEach { l -> appendLine(l) } }
        appendLine(); appendLine("=== RESULTS ===")
        _lines.value.forEach { appendLine("${it.outcome} ${it.text}") }
        for ((name, l) in sections) {
            if (name == "SUMMARY") continue
            appendLine(); appendLine("=== $name ===")
            l.forEach { appendLine(it) }
        }
        appendLine(); appendLine("=== App log (last 120 lines) ===")
        AppLog.lines.value.takeLast(120).forEach { appendLine(it) }
    }

    // ------------------------------------------------------------------ persistence

    private fun save(current: String?) {
        runCatching {
            val o = JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("running", current != null)
                .put("current", current ?: "")
                .put("finished", _status.value.finished)
                .put("startedAt", _status.value.startedAt).put("endedAt", _status.value.endedAt)
                .put("done", JSONArray(done.toList()))
                .put("lines", JSONArray().apply { _lines.value.forEach { put(JSONObject().put("s", it.step).put("o", it.outcome.name).put("t", it.text)) } })
                .put("sections", JSONObject().apply { sections.forEach { (k, v) -> put(k, JSONArray(v)) } })
            file.writeText(o.toString())
        }.onFailure { AppLog.w("Full test progress not saved", it) }
    }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        done = o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf()
        _lines.value = o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { Line(it.getString("s"), runCatching { Outcome.valueOf(it.getString("o")) }.getOrDefault(Outcome.INFO), it.getString("t")) } } ?: emptyList()
        sections.clear()
        o.optJSONObject("sections")?.let { s -> s.keys().forEach { k -> val a = s.getJSONArray(k); sections[k] = (0 until a.length()).map { a.getString(it) } } }
        val crashed = o.optString("current").takeIf { o.optBoolean("running") && it.isNotEmpty() }
        _status.value = Status(finished = o.optBoolean("finished"), interruptedAt = crashed, startedAt = o.optLong("startedAt"), endedAt = o.optLong("endedAt"),
            total = 0, index = done.size)
        if (crashed != null) AppLog.w("Full test: the app closed during step $crashed last time")
    }

    fun hasResults() = _lines.value.isNotEmpty()
}
