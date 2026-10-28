package com.nik.carradio.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Read-only survey of the head unit for the full radio test: nothing here changes the radio.
 * Everything a fix for another firmware could need, in one place.
 */
object UnitFacts {
    /** Package names of known head-unit radio stacks (FYT, Microntek, TopWay, Junsun/HCN, NWD, TS, SPD, Reglink, AOSP/MTK/Sprd FM). */
    val KNOWN_RADIO_PACKAGES = listOf(
        "com.syu.ms", "com.syu.ss", "com.syu.radio", "com.syu.carradio", "com.syu.music", "com.syu.av", "com.syu.mcukey",
        "com.microntek.radio", "com.microntek.service", "com.tw.radio", "com.tw.service",
        "com.hcn.autoradio", "com.nwd.radio", "com.nwd.radio.service", "com.nwd.kernel", "com.ts.MainUI",
        "com.spd.radio", "com.reglink.services", "com.reglink.apps.radio", "com.reglink.apps.mtkradio",
        "com.android.fmradio", "com.mediatek.fmradio", "com.sprd.fmradio", "com.caf.fmradio", "com.android.radio",
        "com.navimods.radio", "com.navimods.radio_free",
    )

    /** Strings worth reading in the firmware's radio apps. */
    val DEX_KEYWORDS = listOf("radio", "freq", "fmjni", "fmnative", "fmservice", "/dev/", "sys.fyt", "ro.fyt", "persist.", "action", "tuner",
        "band", "seek", "syu", "mcu", "appid", "app_id", "rds")

    fun props(): List<String> {
        val all = runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop")).inputStream.bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
        val keep = Regex("""fyt|syu|radio|\bfm|_fm|fm_|app_id|mcu|platform|board|product\.(model|name|device|board)|build\.(display|version\.release|version\.sdk|fingerprint|date)|hardware|chip|sprd|uis|ro\.boot\.hardware|audio""", RegexOption.IGNORE_CASE)
        val out = all.filter { keep.containsMatchIn(it) }.map { it.take(200) }
        return if (out.isNotEmpty()) out else listOf(
            "getprop not readable; via reflection:",
            "sys.fyt.radio_type=${DeviceInfo.prop("sys.fyt.radio_type")}", "sys.app_id=${DeviceInfo.prop("sys.app_id")}",
            "ro.fyt.platform=${DeviceInfo.prop("ro.fyt.platform")}", "sys.fyt.radio_support_am=${DeviceInfo.prop("sys.fyt.radio_support_am")}",
        )
    }

    fun packages(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val out = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val interesting = installed.filter { p ->
            val n = p.packageName.lowercase()
            p.packageName in KNOWN_RADIO_PACKAGES || n.startsWith("com.syu.") || n.contains("radio") || n.contains("fm") || n.contains("tuner") ||
                n.startsWith("com.microntek") || n.startsWith("com.tw.") || n.startsWith("com.hcn.") || n.startsWith("com.nwd.") || n.startsWith("com.fyt")
        }.sortedBy { it.packageName }
        for (p in interesting) {
            val ai = p.applicationInfo
            val sys = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            @Suppress("DEPRECATION")
            out += "${p.packageName} v${p.versionName} (${p.versionCode})${if (sys) " system" else ""}${if (ai?.enabled == false) " DISABLED" else ""} " +
                "${ai?.sourceDir ?: ""} ${ai?.sourceDir?.let { File(it).length() / 1024 } ?: "?"}KB uid=${ai?.uid} shared=${p.sharedUserId ?: "-"}"
        }
        out += "(${installed.size} packages installed in total)"
        return out
    }

    /** Services, receivers and activities of the vendor radio packages (names and exported flag). */
    fun components(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val out = mutableListOf<String>()
        for (pkg in listOf("com.syu.ms", "com.syu.radio", "com.syu.carradio", "com.syu.av", "com.syu.ss")) {
            @Suppress("DEPRECATION")
            val p = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS) }.getOrNull() ?: continue
            out += "[$pkg]"
            p.services?.forEach { out += "  service ${it.name}${if (it.exported) " exported" else ""}${it.permission?.let { pr -> " perm=$pr" } ?: ""}" }
            p.receivers?.forEach { out += "  receiver ${it.name}${if (it.exported) " exported" else ""}" }
            p.activities?.take(25)?.forEach { out += "  activity ${it.name}${if (it.exported) " exported" else ""}" }
            p.providers?.forEach { out += "  provider ${it.name} ${it.authority}" }
            pm.getLaunchIntentForPackage(pkg)?.component?.let { out += "  launch ${it.flattenToShortString()}" }
        }
        return out
    }

    /** FM/radio libraries, HALs and device nodes present on the unit. */
    fun files(): List<String> {
        val out = mutableListOf<String>()
        for (f in listOf("/dev/fm", "/dev/radio0", "/dev/radio", "/dev/fm_radio", "/dev/tea5767", "/dev/si470x", "/dev/i2c-fm", "/dev/ttyS1", "/dev/ttyS3", "/dev/ttyMT1")) {
            val file = File(f)
            if (file.exists()) out += "$f exists r=${file.canRead()} w=${file.canWrite()}"
        }
        val dirs = listOf("/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64", "/system/lib/hw", "/vendor/lib/hw", "/system/app", "/system/priv-app", "/vendor/app", "/oem/app", "/system/etc", "/vendor/etc")
        val pat = Regex("""fm|radio|tuner|syu|fyt""", RegexOption.IGNORE_CASE)
        for (d in dirs) {
            val names = File(d).list()?.filter { pat.containsMatchIn(it) }?.sorted() ?: continue
            if (names.isNotEmpty()) out += "$d: ${names.joinToString(", ").take(600)}"
        }
        return out.ifEmpty { listOf("no FM device nodes or libraries visible") }
    }

    /** Binder services registered with the system (hidden API, readable on Android 8). */
    fun binderServices(): List<String> = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        @Suppress("UNCHECKED_CAST")
        val names = (sm.getMethod("listServices").invoke(null) as Array<String>).sorted()
        val radioish = names.filter { Regex("""fm|radio|tuner|syu|fyt|mcu|car|source|broadcast""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        listOf("${names.size} services; radio-related: ${radioish.joinToString(", ").ifEmpty { "none" }}", "all: " + names.joinToString(" ").take(3500))
    }.getOrElse { listOf("ServiceManager.listServices not available: ${it.javaClass.simpleName}") }

    fun dexStrings(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        for (pkg in listOf("com.syu.radio", "com.syu.carradio", "com.syu.ms")) {
            val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val apk = File(ai.sourceDir)
            val sha = runCatching { sha256(apk).take(16) }.getOrDefault("unreadable")
            val strings = runCatching { DexStrings.fromApk(apk, DEX_KEYWORDS, if (pkg == "com.syu.ms") 350 else 500) }
                .getOrElse { listOf("could not read: ${it.javaClass.simpleName}: ${it.message}") }
            out += "[$pkg] ${apk.path} sha256=$sha… ${strings.size} strings"
            // Radio-app strings first and in full: they hold the intents and units the tuner uses.
            strings.forEach { out += "  $it" }
        }
        return out
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun header(): List<String> = listOf(
        "Model ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}/${Build.BOARD}/${Build.HARDWARE}) Android ${DeviceInfo.realAndroid()} SDK ${Build.VERSION.SDK_INT}",
        "Build ${Build.DISPLAY}",
        "sys.fyt.radio_type=${DeviceInfo.prop("sys.fyt.radio_type").ifEmpty { "(unset)" }}  (1 MCU tuner, 2 none, 3/4 SoC FM via stock radio app)",
        "sys.app_id=${DeviceInfo.prop("sys.app_id").ifEmpty { "(unset)" }}  ro.fyt.platform=${DeviceInfo.prop("ro.fyt.platform")}",
    )
}
