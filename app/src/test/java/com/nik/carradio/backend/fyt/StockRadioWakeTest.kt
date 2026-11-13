package com.nik.carradio.backend.fyt

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.nik.carradio.core.AppState
import com.nik.carradio.core.Band
import com.nik.carradio.core.RadioController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemProperties

/**
 * The UIS8141E case from the full radio test: com.syu.ms forwards every radio command to the
 * stock radio app (update 28) and nothing happens until that app runs. Car Radio must start
 * the app's exported service, then tune with cmd(13,[1, 10 kHz units]).
 */
@RunWith(RobolectricTestRunner::class)
class StockRadioWakeTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** com.syu.ms on a radio_type 4 unit, with the stock radio app's state simulated. */
    private inner class SocFmRadio : Binder() {
        var alive = false
        var freq = 522          // com.syu.ms placeholder until the stock app reports
        /** Commands a cold stock radio app misses after its service was started (still opening the FM chip). */
        var coldCmds = 0
        /** Like the real unit: the service powers the chip at the stock app's last station (88.0), only its screen obeys commands. */
        var screenOnly = false
        var screenFreq: String? = null
        val forwarded = mutableListOf<String>()
        private val cbs = mutableMapOf<Int, MutableList<IBinder>>()

        private fun checkAlive() {
            if (!alive && shadowOf(app).peekNextStartedService()?.component == ComponentName("com.syu.radio", StockRadio.SERVICE)) {
                if (coldCmds-- > 0) return
                alive = true; freq = if (screenOnly) 8800 else 9730; notify(Syu.U_FREQ, freq)
            }
        }
        init {
            StockRadio.openedForTest = { i -> if (i.component?.className?.contains("ActRadio") == true) {
                screenFreq = i.data?.getQueryParameter("freq"); alive = true
                screenFreq?.toIntOrNull()?.let { freq = it; notify(Syu.U_FREQ, it) }   // the screen tunes itself on open
            } }
        }
        private fun obeys() = alive && (!screenOnly || screenFreq != null)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.syu.ipc.IRemoteModule"); return true }
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            when (code) {
                1 -> { val c = data.readInt(); val ints = data.createIntArray(); data.createFloatArray(); data.createStringArray()
                    forwarded += "$c ${ints?.contentToString()}"
                    checkAlive()
                    if (obeys() && c == Syu.C_FREQ && ints?.size == 2 && ints[0] == 1) { freq = ints[1]; notify(Syu.U_FREQ, freq) } }
                2 -> { data.readInt(); reply?.writeNoException(); reply?.writeInt(0) }
                3 -> { val cb = data.readStrongBinder(); val c = data.readInt(); val upd = data.readInt()
                    cbs.getOrPut(c) { mutableListOf() } += cb
                    if (upd == 1 && c == Syu.U_BAND) send(cb, c, 65536)
                    if (upd == 1 && c == Syu.U_FREQ) send(cb, c, freq) }
                4 -> { data.readStrongBinder(); data.readInt() }
            }
            return true
        }
        /** The stock radio app changes station on its own (a wheel press it handled). */
        fun vendorJump(v10: Int) { freq = v10; notify(Syu.U_FREQ, freq) }
        /** com.syu.ms forwards a command to the stock radio app (update 28). */
        fun forward(vararg ints: Int) = cbs[Syu.U_FORWARD]?.toList()?.forEach { send(it, Syu.U_FORWARD, *ints) }

        private fun notify(c: Int, v: Int) = cbs[c]?.toList()?.forEach { send(it, c, v) }
        private fun send(cb: IBinder, c: Int, vararg v: Int) {
            val p = Parcel.obtain()
            try { p.writeInterfaceToken(Syu.D_CALLBACK); p.writeInt(c); p.writeIntArray(v); p.writeFloatArray(null); p.writeStringArray(null)
                cb.transact(1, p, null, IBinder.FLAG_ONEWAY) } finally { p.recycle() }
        }
    }

    private class Plain : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("x"); return true }
            if (code == 2) { reply?.writeNoException(); reply?.writeInt(0) }
            return true
        }
    }

    private class Toolkit(val modules: Map<Int, IBinder>) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.syu.ipc.IRemoteToolkit"); return true }
            data.enforceInterface("com.syu.ipc.IRemoteToolkit")
            val m = data.readInt()
            reply?.writeNoException(); reply?.writeStrongBinder(modules[m])
            return true
        }
    }

    /** Records what the backend asks the controller to do about wheel presses. */
    private inner class FakeHost : com.nik.carradio.backend.BackendHost {
        override val context get() = app
        override val prefs get() = RadioController.prefs
        @Volatile override var state = com.nik.carradio.core.RadioState()
        val steps = mutableListOf<String>()
        val overrides = mutableListOf<Int>()
        override fun update(transform: (com.nik.carradio.core.RadioState) -> com.nik.carradio.core.RadioState) { state = transform(state) }
        override fun onExternalStep(fromBand: Band, fromKhz: Int, up: Boolean) { steps += "${if (up) "next" else "prev"} from $fromKhz" }
        override fun onVendorOverride(band: Band, khz: Int) { overrides += khz }
        override fun onAccChanged(on: Boolean) {}
    }

    private fun connected(): Pair<SocFmRadio, FytMcuBackend> {
        val radio = SocFmRadio()
        SyuConnection.useForTest(Syu.Toolkit(Toolkit(mapOf(Syu.MODULE_RADIO to radio, Syu.MODULE_MAIN to Plain()))))
        return radio to FytMcuBackend()
    }

    @Before fun setUp() {
        ShadowSystemProperties.override("sys.fyt.radio_type", "4")
        ShadowSystemProperties.override("sys.app_id", "1")
        AppState.foreground = false
        RadioController.prefs.fytTuneSpec = null
        RadioController.prefs.fytWake = null
        // The stock radio app, with a launcher entry.
        val spm = shadowOf(app.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = "com.syu.radio"; applicationInfo = ApplicationInfo().apply { packageName = "com.syu.radio" } })
        val launch = ComponentName("com.syu.radio", "com.syu.radio.Launch")
        spm.addActivityIfNotPresent(launch)
        spm.addIntentFilterForActivity(launch, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @After fun tearDown() { SyuConnection.useForTest(null); StockRadio.openedForTest = null }

    @Test fun startsTheStockRadioServiceThenTunesDirectly() = runBlocking {
        val radio = SocFmRadio()
        SyuConnection.useForTest(Syu.Toolkit(Toolkit(mapOf(Syu.MODULE_RADIO to radio, Syu.MODULE_MAIN to Plain()))))
        val fyt = FytMcuBackend()
        fyt.tune(RadioController, Band.FM, 101_100)
        assertTrue("stock radio started: ${radio.forwarded}", radio.alive)
        assertEquals(10110, radio.freq)
        assertEquals("service", RadioController.prefs.fytWake)
        assertTrue(radio.forwarded.contains("13 [1, 10110]"))
        fyt.release(RadioController)
    }

    /** Wheel NEXT forwarded to the stock radio app, which jumps to its first preset: go to the next saved station instead. */
    @Test fun forwardedWheelKeyIsRedirectedEvenRightAfterATune() = runBlocking {
        val (radio, fyt) = connected()
        val h = FakeHost()
        fyt.tune(h, Band.FM, 101_100)
        radio.forward(Syu.C_NEXT_CHANNEL, 0)
        radio.vendorJump(8800)
        assertEquals(listOf("next from 101100"), h.steps)
        assertTrue("no snap-back when a key explains it: ${h.overrides}", h.overrides.isEmpty())
        fyt.release(h)
    }

    /** The stock radio app acts on a press Car Radio already handled: Car Radio's station wins. */
    @Test fun jumpRightAfterOurTuneIsUndone() = runBlocking {
        val (radio, fyt) = connected()
        val h = FakeHost()
        fyt.tune(h, Band.FM, 101_100)
        radio.vendorJump(8800)
        assertEquals(listOf(101_100), h.overrides)
        assertTrue(h.steps.isEmpty())
        fyt.release(h)
    }

    /** No key reported at all: landing on the same frequency again from another station is the wheel. */
    @Test fun repeatedLandingWithoutKeyIsTheWheel() = runBlocking {
        val (radio, fyt) = connected()
        val h = FakeHost()
        fyt.tune(h, Band.FM, 101_100)
        kotlinx.coroutines.delay(4200)
        radio.vendorJump(8800)              // first time: followed
        assertTrue(h.steps.isEmpty())
        fyt.tune(h, Band.FM, 104_000)
        kotlinx.coroutines.delay(4200)
        radio.vendorJump(8800)              // again from another station
        assertEquals(listOf("next from 104000"), h.steps)
        fyt.release(h)
    }

    /** The service alone powers the chip but ignores commands: open the stock radio screen tuned, then it obeys. */
    @Test fun opensTheStockRadioScreenTunedWhenTheServiceDoesNotObey() = runBlocking {
        AppState.foreground = true
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName("com.syu.radio", "com.syu.radio.act.ActRadio"))
        val (radio, fyt) = connected()
        radio.screenOnly = true
        val started = runCatching { fyt.powerOn(RadioController, Band.FM, 101_100) }
        val why = "powerOn=${started.exceptionOrNull()} status=${RadioController.state.status} started=${shadowOf(app).peekNextStartedActivity()} " +
            "screen=${StockRadio.radioScreen(app, "com.syu.radio")} forwarded=${radio.forwarded}\nlog:\n" +
            com.nik.carradio.core.AppLog.lines.value.filter { "FYT" in it || "Stock" in it }.takeLast(30).joinToString("\n")
        assertEquals("screen opened tuned: $why", "10110", radio.screenFreq)
        assertEquals("freq: $why", 10110, radio.freq)
        assertTrue("needsScreen: $why", !fyt.needsScreen)
        fyt.release(RadioController)
    }

    /** Full radio test 1.6.3 on the unit: cmd(13,[1,98100]) (kHz) moved the tuner to 87.5 MHz. */
    @Test fun socFmTuneFallbackNeverSendsKilohertz() {
        val labels = FytMcuBackend().tuneVariants(Band.FM, 101_100, socFm = true).map { it.label }
        assertEquals(listOf("cmd(13,[1,10110]) via band limits"), labels)
        val mcu = FytMcuBackend().tuneVariants(Band.FM, 101_100, socFm = false).map { it.label }
        assertEquals(3, mcu.size)
    }

    /** A cold stock radio app misses the first nudge; 1.6.0 gave up there and needed a manual radio off/on. */
    @Test fun radioOnWaitsForASlowStockRadioApp() = runBlocking {
        val radio = SocFmRadio().apply { coldCmds = 1 }
        SyuConnection.useForTest(Syu.Toolkit(Toolkit(mapOf(Syu.MODULE_RADIO to radio, Syu.MODULE_MAIN to Plain()))))
        val fyt = FytMcuBackend()
        fyt.powerOn(RadioController, Band.FM, 101_100)
        assertTrue("stock radio started: ${radio.forwarded}", radio.alive)
        assertEquals(10110, radio.freq)
        fyt.release(RadioController)
    }
}
