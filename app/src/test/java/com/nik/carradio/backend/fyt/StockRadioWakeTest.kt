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
        val forwarded = mutableListOf<String>()
        private val cbs = mutableMapOf<Int, MutableList<IBinder>>()

        private fun checkAlive() {
            if (!alive && shadowOf(app).peekNextStartedService()?.component == ComponentName("com.syu.radio", StockRadio.SERVICE)) {
                alive = true; freq = 9730; notify(Syu.U_FREQ, freq)
            }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString("com.syu.ipc.IRemoteModule"); return true }
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            when (code) {
                1 -> { val c = data.readInt(); val ints = data.createIntArray(); data.createFloatArray(); data.createStringArray()
                    forwarded += "$c ${ints?.contentToString()}"
                    checkAlive()
                    if (alive && c == Syu.C_FREQ && ints?.size == 2 && ints[0] == 1) { freq = ints[1]; notify(Syu.U_FREQ, freq) } }
                2 -> { data.readInt(); reply?.writeNoException(); reply?.writeInt(0) }
                3 -> { val cb = data.readStrongBinder(); val c = data.readInt(); val upd = data.readInt()
                    cbs.getOrPut(c) { mutableListOf() } += cb
                    if (upd == 1 && c == Syu.U_BAND) send(cb, c, 65536)
                    if (upd == 1 && c == Syu.U_FREQ) send(cb, c, freq) }
                4 -> { data.readStrongBinder(); data.readInt() }
            }
            return true
        }
        private fun notify(c: Int, v: Int) = cbs[c]?.toList()?.forEach { send(it, c, v) }
        private fun send(cb: IBinder, c: Int, v: Int) {
            val p = Parcel.obtain()
            try { p.writeInterfaceToken(Syu.D_CALLBACK); p.writeInt(c); p.writeIntArray(intArrayOf(v)); p.writeFloatArray(null); p.writeStringArray(null)
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

    @After fun tearDown() { SyuConnection.useForTest(null) }

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
}
