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

    /**
     * True when the stock radio app drives the FM chip (sys.fyt.radio_type 3/4). With
     * persist.lsec.radio.ms_handler set, com.syu.ms drives the chip itself and the stock app is
     * not needed (decoded by the DABplay+ FM project from the 2025 MainServer).
     */
    fun socFm(): Boolean = DeviceInfo.prop("sys.fyt.radio_type").let { it == "3" || it == "4" } && !msHandler()

    fun msHandler(): Boolean = DeviceInfo.prop("persist.lsec.radio.ms_handler").let { it.isNotEmpty() && it != "0" && it != "false" }

    /**
     * The stock app's radio screen. On radio types 3/4 only this screen carries out the commands
     * com.syu.ms forwards (Act_Radio → page.onNotify); its background service only powers the chip
     * up at the app's own last station. It opens tuned with action "com.syu.radio" and a data URI
     * whose query parameter freq is in 10 kHz units.
     */
    fun radioScreen(ctx: Context, pkg: String): ComponentName? {
        val pm = ctx.packageManager
        val names = listOf("com.syu.radio.act.ActRadio", "$pkg.act.ActRadio", "$pkg.ActRadio", "$pkg.act.Act_Radio", "$pkg.Act_Radio").distinct()
        names.firstOrNull { n -> runCatching { pm.getActivityInfo(ComponentName(pkg, n), 0) }.isSuccess }?.let { return ComponentName(pkg, it) }
        val listed = runCatching { pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities }.getOrNull()
        return listed?.firstOrNull { it.name.contains("ActRadio", true) || it.name.contains("Act_Radio", true) }?.let { ComponentName(pkg, it.name) }
    }

    /** Tests: sees each stock radio screen intent Car Radio starts. */
    @Volatile internal var openedForTest: ((Intent) -> Unit)? = null

    /** Opens the stock radio screen already tuned to [khz]; falls back to its launcher screen. */
    fun openTuned(ctx: Context, pkg: String, khz: Int): Boolean {
        val c = radioScreen(ctx, pkg)
        if (c != null) {
            val ok = runCatching {
                val i = Intent("com.syu.radio").setComponent(c)
                    .setData(android.net.Uri.parse("carradio://tune?freq=${khz / 10}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                ctx.startActivity(i)
                openedForTest?.invoke(i)
                true
            }.onFailure { AppLog.w("Stock radio: could not open ${c.className} tuned", it) }.getOrDefault(false)
            if (ok) { AppLog.i("Stock radio: opened ${c.shortClassName} at ${khz / 10}"); return true }
        }
        return openActivity(ctx, pkg)
    }

    /**
     * Car Radio's own Radio-key proxy replaced the stock radio app. On SoC-FM units that removes
     * the app that drives the FM chip, so no method can play FM until it is uninstalled.
     */
    fun proxyInstalled(ctx: Context): Boolean = DeviceInfo.signing(ctx, "com.syu.radio")?.isProxy == true

    fun pkg(ctx: Context): String? = listOf("com.syu.radio", "com.syu.carradio").firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    /**
     * App start: says whether this unit needs the stock radio app and, if so, starts its service
     * in the background (no screen). Returns true when the service was started.
     */
    fun prewarm(ctx: Context, start: Boolean = true): Boolean {
        val type = DeviceInfo.prop("sys.fyt.radio_type")
        if (type.isEmpty()) return false
        if (!socFm()) { AppLog.i("Radio check: FYT radio_type=$type${if (msHandler()) ", ms_handler set" else ""} – com.syu.ms drives the tuner, the stock radio app is not needed"); return false }
        if (proxyInstalled(ctx)) { AppLog.e("Radio check: FYT radio_type=$type – the FM chip is driven by the stock radio app, but Car Radio's Radio-key proxy replaced it. Uninstall the proxy (Settings → Apps → Radio → Uninstall updates)."); return false }
        val pkg = pkg(ctx) ?: run { AppLog.w("Radio check: FYT radio_type=$type (FM chip driven by the stock radio app), but no stock radio app is installed"); return false }
        AppLog.i("Radio check: FYT radio_type=$type – the FM chip is driven by $pkg (radio screen: ${radioScreen(ctx, pkg)?.className ?: "not found, launcher used"})${if (start) "; starting it in the background" else ""}")
        return start && startService(ctx, pkg)
    }

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
