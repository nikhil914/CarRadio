package com.nik.carradio.backend.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.DeviceInfo

/**
 * The stock FYT radio app on SoC-FM units (sys.fyt.radio_type 3/4).
 *
 * There, com.syu.ms does not drive the FM chip: it forwards every radio command (update code 28)
 * to the stock radio app, which owns the chip through its own FmService. The full radio test on
 * a UIS8141E unit showed it: with the stock app not running, every command was forwarded and
 * nothing moved; with it running, cmd(13,[1,9810]) reached 98.1 MHz within 7 ms. So Car Radio
 * keeps the stock app running in the background and sends its commands through com.syu.ms.
 */
object StockRadio {
    /** True while the stock app is being started; the floating player stays hidden meanwhile. */
    @Volatile var waking = false
    /** Exported service of com.syu.radio: starting it starts the app's process without its screen. */
    const val SERVICE = "com.syu.broadcast.MyService"

    fun socFm(): Boolean = DeviceInfo.prop("sys.fyt.radio_type").let { it == "3" || it == "4" }

    fun pkg(ctx: Context): String? = listOf("com.syu.radio", "com.syu.carradio").firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun startService(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.startService(Intent().setComponent(ComponentName(pkg, SERVICE))) != null
    }.onFailure { AppLog.w("Stock radio: could not start $SERVICE", it) }.getOrDefault(false)

    fun openActivity(ctx: Context, pkg: String): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        true
    }.onFailure { AppLog.w("Stock radio: could not open $pkg", it) }.getOrDefault(false)

    fun bringCarRadioBack(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(ctx, com.nik.carradio.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { AppLog.w("Could not bring Car Radio back to the front", it) }
    }
}
