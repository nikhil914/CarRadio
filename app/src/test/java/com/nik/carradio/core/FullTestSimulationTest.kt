package com.nik.carradio.core

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.nik.carradio.backend.fyt.Syu
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemProperties
import java.io.File

/**
 * Runs the whole full radio test against a simulated com.syu.ms, speaking the real Syu binder
 * protocol, for the two kinds of FYT unit found in the firmware:
 *  - SoC FM (sys.fyt.radio_type 3/4): set-frequency only as mode 1, only while sys.app_id = 1,
 *    forwarded to the stock radio app as update 28; step/seek always work.
 *  - MCU tuner (radio_type 1): mode 3 is direct; mode 1 is dropped (no band limits yet).
 */
@RunWith(RobolectricTestRunner::class)
class FullTestSimulationTest {

    /** A fake radio module: keeps a frequency (10 kHz units) and notifies registered callbacks. */
    private class FakeRadio(val socFm: Boolean, private val appId: () -> Int) : Binder() {
        var freq = 9500
        val presets = intArrayOf(9200, 9810, 10110, 10440, 10620, 8880)
        private val stations = intArrayOf(8880, 9200, 9500, 9810, 10110, 10440, 10620)
        private val callbacks = mutableMapOf<Int, MutableList<IBinder>>()
        val received = mutableListOf<String>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.syu.ipc.IRemoteModule"); return true }
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            when (code) {
                1 -> { val c = data.readInt(); val ints = data.createIntArray(); data.createFloatArray(); data.createStringArray(); cmd(c, ints) }
                2 -> { data.readInt(); reply?.writeNoException(); reply?.writeInt(0) }
                3 -> { val cb = data.readStrongBinder(); val c = data.readInt(); val upd = data.readInt()
                    callbacks.getOrPut(c) { mutableListOf() } += cb
                    if (upd == 1 && c == Syu.U_FREQ) send(cb, c, intArrayOf(freq)) }
                4 -> { val cb = data.readStrongBinder(); val c = data.readInt(); callbacks[c]?.remove(cb) }
            }
            return true
        }

        private fun cmd(c: Int, ints: IntArray?) {
            received += "cmd($c,${ints?.contentToString()})"
            val v = ints?.getOrNull(1)
            when (c) {
                Syu.C_FREQ_UP -> { forward(3); tune(freq + 5) }
                Syu.C_FREQ_DOWN -> { forward(4); tune(freq - 5) }
                Syu.C_SEEK_UP -> { forward(5); tune(stations.firstOrNull { it > freq } ?: stations.first()) }
                Syu.C_SEEK_DOWN -> { forward(6); tune(stations.lastOrNull { it < freq } ?: stations.last()) }
                Syu.C_SELECT_CHANNEL -> ints?.firstOrNull()?.let { tune(presets[it.coerceIn(0, 5)]) }
                Syu.C_FREQ -> when {
                    socFm && ints?.size == 2 && ints[0] == 1 && appId() == 1 -> { forward(13, v!!); if (v in 8750..10800) tune(v) }
                    !socFm && ints?.size == 2 && ints[0] == 3 && v != null -> tune(v.coerceIn(0, 10800))
                }
            }
        }

        private fun tune(v: Int) { freq = v; callbacks[Syu.U_FREQ]?.toList()?.forEach { send(it, Syu.U_FREQ, intArrayOf(v)) } }
        private fun forward(vararg ints: Int) { if (socFm) callbacks[28]?.toList()?.forEach { send(it, 28, ints) } }

        private fun send(cb: IBinder, code: Int, ints: IntArray) {
            val p = Parcel.obtain()
            try {
                p.writeInterfaceToken(Syu.D_CALLBACK); p.writeInt(code); p.writeIntArray(ints); p.writeFloatArray(null); p.writeStringArray(null)
                cb.transact(1, p, null, IBinder.FLAG_ONEWAY)
            } finally { p.recycle() }
        }
    }

    /** Fake main module: cmd 0 [1] makes the radio the audio source (sys.app_id = 1). */
    private class FakeMain(val onSource: (Int) -> Unit) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.syu.ipc.IRemoteModule"); return true }
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            if (code == 1) { val c = data.readInt(); val ints = data.createIntArray(); if (c == 0) ints?.firstOrNull()?.let(onSource) }
            if (code == 2) { reply?.writeNoException(); reply?.writeInt(0) }
            return true
        }
    }

    private var appId = 0

    @Before fun setUp() {
        FullTest.timeScale = 0.02
        appId = 0
        ShadowSystemProperties.override("sys.app_id", "0")
        RadioController.prefs.fytTuneSpec = null
    }

    @After fun tearDown() { FullTest.connector = null; FullTest.timeScale = 1.0 }

    private fun runSimulated(socFm: Boolean): FakeRadio {
        val radio = FakeRadio(socFm) { appId }
        val main = FakeMain { v -> appId = v; ShadowSystemProperties.override("sys.app_id", v.toString()) }
        FullTest.connector = { Triple(Syu.Module(radio), Syu.Module(main), null) }
        FullTest.start(resume = false)
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end && !FullTest.status.value.finished) Thread.sleep(50)
        assertTrue("test did not finish: ${FullTest.status.value}", FullTest.status.value.finished)
        return radio
    }

    private fun results() = FullTest.lines.value.map { "${it.outcome} ${it.text}" }

    @Test fun socFmUnitNeedsSourceAndMode1() {
        val radio = runSimulated(socFm = true)
        val r = results()
        assertTrue(r.joinToString("\n"), r.any { it.startsWith("PASS sys.app_id 0 → 1") })
        assertTrue(r.any { it.startsWith("PASS tune cmd(13,[1,{v10}])") && "forwarded=u28 i=[13," in it })
        assertTrue(r.any { it.startsWith("FAIL tune cmd(13,[3,{v10}])") })
        assertTrue(r.any { it.startsWith("PASS step up cmd(3,[0])") })
        assertTrue(r.any { it.startsWith("PASS seek up cmd(5,[0])") })
        assertEquals("13|1,{v10}|||", RadioController.prefs.fytTuneSpec)
        assertEquals("restored to the starting station", 9500, radio.freq)
        val report = FullTest.report()
        assertTrue(report.contains("=== SUMMARY ===") && report.contains("Direct tuning that works: cmd(13,[1,{v10}])"))
        assertTrue(report.contains("=== Updates seen during the test ==="))
        File("build/fulltest-sim-report.txt").writeText(report)
    }

    @Test fun mcuUnitUsesMode3() {
        val radio = runSimulated(socFm = false)
        val r = results()
        assertTrue(r.joinToString("\n"), r.any { it.startsWith("FAIL tune cmd(13,[1,{v10}])") })
        assertTrue(r.any { it.startsWith("PASS tune cmd(13,[3,{v10}])") })
        assertEquals("13|3,{v10}|||", RadioController.prefs.fytTuneSpec)
        assertEquals(9500, radio.freq)
    }

    @Test fun resumesAfterAStepKilledTheApp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "fulltest.json").writeText("""{"running":true,"current":"tune.3","done":["facts.props"],"lines":[],"sections":{},"startedAt":1}""")
        FullTest.init(ctx)
        assertEquals("tune.3", FullTest.status.value.interruptedAt)
        val radio = FakeRadio(true) { appId }
        FullTest.connector = { Triple(Syu.Module(radio), Syu.Module(FakeMain { v -> appId = v; ShadowSystemProperties.override("sys.app_id", v.toString()) }), null) }
        FullTest.start(resume = true)
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end && !FullTest.status.value.finished) Thread.sleep(50)
        val r = results()
        assertTrue(r.joinToString("\n"), r.first().startsWith("CRASH"))
        val forCrashed = FullTest.lines.value.filter { it.step == "tune.3" }
        assertEquals("the crashed step is only reported, not run again", listOf(FullTest.Outcome.CRASH), forCrashed.map { it.outcome })
        assertTrue(FullTest.status.value.finished)
    }
}
