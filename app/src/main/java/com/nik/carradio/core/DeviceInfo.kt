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
        listOf("com.syu.radio", "com.syu.ms", "android").forEach { p ->
            signing(ctx, p)?.let { s -> out += "Signer $p: ${s.aospKey ?: "vendor key"} sha256=${s.sha256.take(16)}… sharedUser=${s.sharedUser} vc=${s.versionCode} system=${s.system}" }
        }
        out += proxyVerdict(ctx)
        out += "Classes: TWUtil=${classExists("android.tw.john.TWUtil")}, MTC CarManager=${classExists("android.microntek.CarManager")}, RadioManager=${classExists("android.hardware.radio.RadioManager")}"
        return out
    }

    /** SHA-256 of the public AOSP keys the Radio-key proxy is built with. */
    val AOSP_KEYS = mapOf(
        "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc" to "aospTestkey",
        "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8" to "aospPlatform",
    )

    data class PkgSign(val pkg: String, val sha256: String, val sharedUser: String?, val versionCode: Long, val system: Boolean, val isProxy: Boolean) {
        val aospKey get() = AOSP_KEYS[sha256]
    }

    @Suppress("DEPRECATION")
    fun signing(ctx: Context, pkg: String): PkgSign? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA)
        val cert = pi.signatures?.firstOrNull()?.toByteArray() ?: return null
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        val sys = (pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val proxy = pi.applicationInfo?.metaData?.getBoolean("com.nik.carradio.PROXY", false) == true
        PkgSign(pkg, sha, pi.sharedUserId, vc, sys, proxy)
    }.getOrNull()

    /** Plain-language verdict on whether the Radio-key proxy can replace com.syu.radio. */
    fun proxyVerdict(ctx: Context): String {
        val s = signing(ctx, "com.syu.radio") ?: return "com.syu.radio not installed – the Radio-key proxy is not needed/possible"
        if (s.isProxy) return "Radio-key proxy is INSTALLED (Radio icon opens Car Radio). Remove: Settings → Apps → Radio → Uninstall updates"
        val key = s.aospKey
        val shared = s.sharedUser?.let { ", sharedUserId=$it" } ?: ""
        return when {
            key == null -> "Radio-key proxy will NOT install: com.syu.radio is signed with a private vendor key (sha256 ${s.sha256.take(16)}…$shared)"
            s.sharedUser != null -> "Radio-key proxy: signed with $key but uses sharedUserId ${s.sharedUser} – needs a proxy variant with that sharedUserId (send this report)"
            else -> "Radio-key proxy should install: use CarRadio-proxy-$key.apk (firmware app versionCode ${s.versionCode})"
        }
    }

    fun realAndroid(): String = when (Build.VERSION.SDK_INT) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
        28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        else -> "API ${Build.VERSION.SDK_INT}"
    }
}
