package com.nik.carradio.backend.fyt

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Hand-written binder client for FYT's com.syu.ms service (no AIDL files needed).
 *
 * Protocol (from FYT launcher sources and NavRadio-compatible apps):
 *   bind  action "com.syu.ms.toolkit", component com.syu.ms/app.ToolkitService
 *   IRemoteToolkit.getRemoteModule(int)            transact 1
 *   IRemoteModule.cmd(int, int[], float[], String[])  transact 1 (oneway)
 *   IRemoteModule.get(int, int[], float[], String[])  transact 2
 *   IRemoteModule.register(IModuleCallback, int code, int update) transact 3 (oneway)
 *   IRemoteModule.unregister(IModuleCallback, int code)          transact 4 (oneway)
 *   IModuleCallback.update(int code, int[], float[], String[])   transact 1
 */
object Syu {
    const val ACTION_TOOLKIT = "com.syu.ms.toolkit"
    const val TOOLKIT_SERVICE = "app.ToolkitService"
    val ENDPOINTS = listOf("com.syu.ms", "com.syu.ss")

    const val MODULE_MAIN = 0
    const val MODULE_RADIO = 1
    const val MODULE_SOUND = 4
    /** Steering-wheel key module; 87 = next, 88 = previous (seen on FYT/Dudu firmware). */
    const val MODULE_STEER = 10
    const val STEER_NEXT = 87
    const val STEER_PREV = 88

    // Main module
    const val MAIN_C_APP_ID = 0
    const val MAIN_U_APP_ID = 0
    const val MAIN_U_ACC_ON = 50
    /** Illumination wire (headlights / ILL) state. */
    const val MAIN_U_LAMPLET = 4
    const val APP_ID_NULL = 0
    const val APP_ID_RADIO = 1

    // Radio module commands (FinalRadio)
    const val C_NEXT_CHANNEL = 0
    const val C_PREV_CHANNEL = 1
    const val C_FREQ_UP = 3
    const val C_FREQ_DOWN = 4
    const val C_SEEK_UP = 5
    const val C_SEEK_DOWN = 6
    const val C_SELECT_CHANNEL = 7
    const val C_SAVE_CHANNEL = 8
    const val C_SCAN = 9
    const val C_BAND = 11
    const val C_AREA = 12
    const val C_FREQ = 13
    const val C_SEARCH = 22
    // Settings (FinalRadio names; see docs/fyt-radio-protocol.md). Toggles act only on value 2.
    const val C_SENSITY = 14
    const val C_AUTO_SENSITY = 15
    const val C_RDS_ENABLE = 16
    const val C_STEREO = 17
    const val C_LOC = 18
    const val C_RDS_TA_ENABLE = 19
    const val C_RDS_AF_ENABLE = 20
    const val TOGGLE = 2
    /** Area index → region, from the band-limit table in com.syu.ms (module.k.f.b). */
    val AREAS = listOf("USA", "Latin America", "Europe", "Russia", "Japan")

    const val FREQ_BY_STEP = 0
    const val FREQ_DIRECT = 1
    /** setFreq mode 3: raw frequency straight to the MCU (packet 0x25), see FytMcuBackend.tuneVariants. */
    const val FREQ_MCU_DIRECT = 3
    const val BAND_SWITCH_AM = -2
    const val BAND_SWITCH_FM = -3
    /** C_SEARCH starts/stops the MCU auto search only with value 2 (1 is ignored). */
    const val SEARCH_START = 2

    // Radio module gets / updates
    const val G_BAND = 0
    const val G_FREQ = 1
    const val U_BAND = 0
    const val U_FREQ = 1
    const val U_CHANNEL = 3
    const val U_PTY_ID = 5
    const val U_RDS_AF_ENABLE = 6
    const val U_RDS_TA = 7
    const val U_RDS_TP = 8
    const val U_LOC = 23
    const val U_RDS_TEXT = 13
    const val U_RDS_CHANNEL_TEXT = 14
    const val U_STEREO = 21
    const val U_SEARCH_STATE = 22
    const val U_PS_TEXT = 26
    const val U_AREA = 2
    const val U_CHANNEL_FREQ = 4
    const val U_RDS_TA_ENABLE = 9
    const val U_RDS_ENABLE = 15
    /** [min, max, step, count] of the current band; FM in 10 kHz units, AM in kHz. */
    const val U_EXTRA_FREQ_INFO = 16
    const val U_SENSITY_AM = 17
    const val U_SENSITY_FM = 18
    const val U_AUTO_SENSITY = 19
    val RADIO_UPDATES = intArrayOf(U_BAND, U_FREQ, U_AREA, U_CHANNEL, U_CHANNEL_FREQ, U_PTY_ID, U_RDS_AF_ENABLE, U_RDS_TA, U_RDS_TP,
        U_RDS_TA_ENABLE, U_RDS_TEXT, U_RDS_CHANNEL_TEXT, U_RDS_ENABLE, U_EXTRA_FREQ_INFO, U_SENSITY_AM, U_SENSITY_FM, U_AUTO_SENSITY,
        U_STEREO, U_SEARCH_STATE, U_LOC, U_PS_TEXT)

    /** Decodes update 16. FM limits are ≥ 6500 (65 MHz in 10 kHz units); AM ones stay below 2000 kHz. */
    fun bandInfo(ints: IntArray?): com.nik.carradio.core.BandInfo? {
        if (ints == null || ints.size < 3) return null
        val (min, max, step) = Triple(ints[0], ints[1], ints[2])
        if (min <= 0 || max <= min || step <= 0) return null
        return if (min >= 6000) com.nik.carradio.core.BandInfo(com.nik.carradio.core.Band.FM, min * 10, max * 10, step * 10)
        else com.nik.carradio.core.BandInfo(com.nik.carradio.core.Band.AM, min, max, step)
    }

    /** FYT encodes FM bands as 65536+n and AM as 0+n. */
    fun isFmBand(v: Int) = v >= 65536

    private const val D_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val D_MODULE = "com.syu.ipc.IRemoteModule"
    const val D_CALLBACK = "com.syu.ipc.IModuleCallback"

    class Obj(val ints: IntArray?, val flts: FloatArray?, val strs: Array<String?>?) {
        override fun toString() = "ints=${ints?.contentToString()} flts=${flts?.contentToString()} strs=${strs?.contentToString()}"
    }

    class Toolkit(private val binder: IBinder) {
        fun module(code: Int): Module? {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(D_TOOLKIT)
                data.writeInt(code)
                if (!binder.transact(1, data, reply, 0)) error("getRemoteModule($code): transact returned false")
                reply.readException()
                return reply.readStrongBinder()?.let { Module(it) }
            } finally { reply.recycle(); data.recycle() }
        }
        val alive get() = binder.isBinderAlive
    }

    class Module(private val binder: IBinder) {
        fun cmd(code: Int, ints: IntArray? = null, flts: FloatArray? = null, strs: Array<String>? = null) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(D_MODULE)
                data.writeInt(code)
                data.writeIntArray(ints)
                data.writeFloatArray(flts)
                data.writeStringArray(strs)
                if (!binder.transact(1, data, null, IBinder.FLAG_ONEWAY)) error("cmd($code): transact returned false")
            } finally { data.recycle() }
        }

        fun get(code: Int, ints: IntArray? = null): Obj? {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(D_MODULE)
                data.writeInt(code)
                data.writeIntArray(ints)
                data.writeFloatArray(null)
                data.writeStringArray(null)
                if (!binder.transact(2, data, reply, 0)) error("get($code): transact returned false")
                reply.readException()
                if (reply.readInt() == 0) return null
                val i = reply.createIntArray()
                val f = reply.createFloatArray()
                @Suppress("UNCHECKED_CAST")
                val s = reply.createStringArray() as Array<String?>?
                return Obj(i, f, s)
            } finally { reply.recycle(); data.recycle() }
        }

        fun register(cb: Callback, code: Int, update: Int = 1) = sendCb(3, cb, code, update)
        fun unregister(cb: Callback, code: Int) = sendCb(4, cb, code, null)

        private fun sendCb(tx: Int, cb: Callback, code: Int, update: Int?) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(D_MODULE)
                data.writeStrongBinder(cb)
                data.writeInt(code)
                if (update != null) data.writeInt(update)
                binder.transact(tx, data, null, IBinder.FLAG_ONEWAY)
            } finally { data.recycle() }
        }
    }

    /** IModuleCallback.Stub equivalent. */
    class Callback(private val onUpdate: (Int, IntArray?, FloatArray?, Array<String?>?) -> Unit) : Binder() {
        init { attachInterface(null, D_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(D_CALLBACK); return true }
            if (code == 1) {
                data.enforceInterface(D_CALLBACK)
                val updateCode = data.readInt()
                val ints = data.createIntArray()
                val flts = data.createFloatArray()
                @Suppress("UNCHECKED_CAST")
                val strs = data.createStringArray() as Array<String?>?
                runCatching { onUpdate(updateCode, ints, flts, strs) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
}
