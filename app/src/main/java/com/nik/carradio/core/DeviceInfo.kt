package com.nik.carradio.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Collects everything useful for identifying the head-unit platform. Never throws. */
object DeviceInfo {

    private val PROPS = listOf(
        "ro.board.platform", "ro.product.model", "ro.product.board", "ro.product.device",
        "ro.product.manufacturer", "ro.build.version.release", "ro.build.version.sdk",
        "ro.build.id", "ro.build.display.id", "ro.build.tags", "ro.build.type",
        "ro.build.fingerprint", "ro.fyt.uiid", "ro.fyt.platform", "sys.fyt.platform",
        "ro.sys.fyt.version", "persist.fyt.platform", "ro.hardware", "ro.boot.hardware",
        "ro.product.cpu.abi", "ro.mtc.model", "ro.product.customer",
    )

    private val INTERESTING_PACKAGES = listOf(
        "com.syu.ms", "com.syu.ss", "com.syu.radio", "com.syu.music", "com.syu.canbus",
        "com.microntek.radio", "com.microntek.service", "android.microntek.service",
        "com.tw.radio", "com.tw.service", "com.android.fmradio", "com.caf.fmradio",
        "com.sprd.fmradio", "com.mediatek.fmradio", "com.ts.radio", "com.roco.radio",
    )

    private val LIB_PATHS = listOf(
        "/system/lib/libfmjni.so", "/system/lib64/libfmjni.so",
        "/vendor/lib/libfmjni.so", "/vendor/lib64/libfmjni.so",
        "/system/priv-app/FMRadio/lib/arm/libfmjni.so",
    )

    private val DEV_NODES = listOf("/dev/fm", "/dev/radio0", "/dev/tef6686", "/dev/si47xx", "/dev/mcu", "/dev/ttyS1")

    @SuppressLint("PrivateApi")
    fun prop(name: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    fun classExists(name: String): Boolean = runCatching { Class.forName(name); true }.getOrDefault(false)

    fun existingLibs(): List<String> = LIB_PATHS.filter { File(it).exists() }

    fun devNodes(): List<String> = DEV_NODES.mapNotNull { p ->
        val f = File(p)
        if (!f.exists()) null else "$p (read=${f.canRead()}, write=${f.canWrite()})"
    }

    /** All launchable apps whose package or label looks like a radio. */
    fun radioLikeApps(ctx: Context): List<Pair<String, String>> = runCatching {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                val l = (pkg + " " + label).lowercase()
                if (pkg != ctx.packageName && (l.contains("radio") || l.contains(" fm") || l.contains(".fm") || l.contains("fmradio") || l.contains("收音")))
                    pkg to label else null
            }.distinct()
    }.getOrDefault(emptyList())

    fun report(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        out += "App: ${ctx.packageName} ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()}"
        out += "Build.MODEL=${Build.MODEL} MANUFACTURER=${Build.MANUFACTURER} SDK_INT=${Build.VERSION.SDK_INT} RELEASE=${Build.VERSION.RELEASE}"
        out += "Real Android (from SDK_INT): ${realAndroid()}"
        PROPS.forEach { p -> prop(p).takeIf { it.isNotBlank() }?.let { out += "$p=$it" } }
        out += "Vendor packages: " + INTERESTING_PACKAGES.filter { isInstalled(ctx, it) }.ifEmpty { listOf("none") }.joinToString()
        out += "Radio-like apps: " + radioLikeApps(ctx).joinToString { "${it.second} (${it.first})" }.ifEmpty { "none" }
        out += "libfmjni: " + existingLibs().ifEmpty { listOf("not found") }.joinToString()
        out += "Device nodes: " + devNodes().ifEmpty { listOf("none visible") }.joinToString()
        out += "Classes: TWUtil=${classExists("android.tw.john.TWUtil")}, MTC CarManager=${classExists("android.microntek.CarManager")}, RadioManager=${classExists("android.hardware.radio.RadioManager")}"
        return out
    }

    fun realAndroid(): String = when (Build.VERSION.SDK_INT) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
        28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        else -> "API ${Build.VERSION.SDK_INT}"
    }
}
