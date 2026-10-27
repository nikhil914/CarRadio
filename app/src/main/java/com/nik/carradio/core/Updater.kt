package com.nik.carradio.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nik.carradio.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Updates from this app's own GitHub Releases, so no store or Obtainium is needed.
 * Check → download the APK into the app's cache → hand it to Android's package installer.
 * The release APK is signed with the same sideload key, so it installs over the current one.
 */
object Updater {
    data class Release(val tag: String, val version: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val notes: String, val sha256: String?)

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val version: String) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val percent: Int) : State()
        data class Ready(val release: Release, val file: File) : State()
        /** Downloaded, but Android 8+ needs "Install unknown apps" allowed for Car Radio first. */
        data class NeedsPermission(val release: Release, val file: File) : State()
        data class Failed(val title: String, val hint: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private const val DAY_MS = 20 * 60 * 60 * 1000L

    /** Launch path: at most once every ~day, silent if offline. */
    fun autoCheck(ctx: Context) {
        val p = RadioController.prefs
        if (!p.autoCheckUpdates || System.currentTimeMillis() - p.lastUpdateCheck < DAY_MS) return
        scope.launch {
            // Give the unit time to join the hotspot after boot.
            kotlinx.coroutines.delay(20_000)
            val r = checkNow(silent = true)
            if (r is State.Available) AppLog.i("Update ${r.release.version} is available (installed ${BuildConfig.VERSION_NAME})")
        }
    }

    fun check() { scope.launch { checkNow(silent = false) } }

    private fun checkNow(silent: Boolean): State {
        val repo = RadioController.prefs.githubRepo.trim()
        if (!silent) _state.value = State.Checking
        val result = try {
            val json = JSONObject(get("https://api.github.com/repos/$repo/releases/latest"))
            val rel = parse(json)
            RadioController.prefs.lastUpdateCheck = System.currentTimeMillis()
            when {
                rel == null -> State.Failed("The latest release has no Car Radio APK", "Open github.com/$repo/releases on a phone.")
                isNewer(rel.version, BuildConfig.VERSION_NAME) -> State.Available(rel)
                else -> State.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: UnknownHostException) {
            State.Failed("No internet on the head unit", "Connect it to your phone's hotspot, then check again.")
        } catch (e: HttpError) {
            when (e.code) {
                404 -> State.Failed("No release found on $repo", "Check the repository name in Settings → Reports.")
                403 -> State.Failed("GitHub is rate-limiting this network", "Try again in an hour.")
                else -> State.Failed("GitHub answered HTTP ${e.code}", e.message ?: "")
            }
        } catch (e: IOException) {
            State.Failed("Update check failed: ${e.javaClass.simpleName}", (e.message ?: "") + " – check the hotspot connection.")
        } catch (e: Exception) {
            State.Failed("Could not read the release info", "${e.javaClass.simpleName}: ${e.message}")
        }
        if (silent && result is State.Failed) { AppLog.i("Auto update check: ${result.title}"); return result }
        // Do not hide a download already in progress or finished.
        val now = _state.value
        if (now !is State.Downloading && now !is State.Ready && now !is State.NeedsPermission) _state.value = result
        AppLog.i("Update check: ${describe(result)}")
        return result
    }

    fun download(ctx: Context, rel: Release) {
        if (_state.value is State.Downloading) return
        scope.launch {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, rel.apkName)
            _state.value = State.Downloading(rel, 0)
            AppLog.i("Downloading ${rel.apkName} (${rel.sizeBytes / 1024} KB)")
            try {
                val md = MessageDigest.getInstance("SHA-256")
                val conn = open(rel.apkUrl, accept = "application/octet-stream", readTimeout = 60_000)
                try {
                    val total = conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: rel.sizeBytes
                    conn.inputStream.use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(32 * 1024); var done = 0L; var lastPct = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                o.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(rel, pct) }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (rel.sha256 != null && !rel.sha256.equals(sha, ignoreCase = true)) {
                    out.delete()
                    fail("The download is damaged", "Its checksum does not match the release. Try again.")
                    return@launch
                }
                if (rel.sizeBytes > 0 && out.length() != rel.sizeBytes) {
                    out.delete(); fail("The download is incomplete", "Got ${out.length()} of ${rel.sizeBytes} bytes. Try again on a steadier connection."); return@launch
                }
                AppLog.i("Downloaded ${out.name}, SHA-256 $sha")
                _state.value = State.Ready(rel, out)
                install(ctx, out)
            } catch (e: UnknownHostException) {
                out.delete(); fail("No internet on the head unit", "Connect it to your phone's hotspot, then retry.")
            } catch (e: IOException) {
                out.delete(); fail("Download failed: ${e.javaClass.simpleName}", (e.message ?: "") + " – retry.")
            }
        }
    }

    /**
     * Opens Android's installer. On Android 8+ the user must first allow Car Radio to
     * install apps once ("Install unknown apps"); we open that page if it is not allowed.
     */
    fun install(ctx: Context, file: File) {
        val rel = when (val st = _state.value) { is State.Ready -> st.release; is State.NeedsPermission -> st.release; else -> null }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            AppLog.w("Install blocked: \"Install unknown apps\" is off for Car Radio – opening that setting")
            if (rel != null) _state.value = State.NeedsPermission(rel, file)
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { AppLog.w("Could not open the unknown-sources setting", it) }
            return
        }
        if (rel != null) _state.value = State.Ready(rel, file)
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file) else Uri.fromFile(file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i); AppLog.i("Installer opened for ${file.name}") }
            .onFailure { AppLog.e("No package installer answered", it); fail("This unit has no package installer screen", "Copy the APK from the release page with a file manager instead.") }
    }

    /** Back from the "Install unknown apps" page: carry on if it was switched on. */
    fun onResume(ctx: Context) {
        val st = _state.value as? State.NeedsPermission ?: return
        if (Build.VERSION.SDK_INT >= 26 && ctx.packageManager.canRequestPackageInstalls() && st.file.exists()) install(ctx, st.file)
    }

    private fun fail(title: String, hint: String) {
        _state.value = State.Failed(title, hint)
        AppLog.w("Update: $title – $hint")
    }

    fun describe(s: State) = when (s) {
        State.Idle -> "Not checked yet"
        State.Checking -> "Checking GitHub…"
        is State.UpToDate -> "Car Radio ${s.version} is the latest version"
        is State.Available -> "Car Radio ${s.release.version} is available (you have ${BuildConfig.VERSION_NAME})"
        is State.Downloading -> "Downloading ${s.release.version}… ${s.percent}%"
        is State.Ready -> "Downloaded ${s.release.version}. If the installer did not open, tap Install."
        is State.NeedsPermission -> "Downloaded ${s.release.version}. Switch on \"Allow from this source\" for Car Radio on the page that opened, come back, then tap Install."
        is State.Failed -> "${s.title}. ${s.hint}"
    }

    // ------------------------------------------------------------------ pure helpers (unit-tested)

    private val APK = Regex("""^CarRadio-[0-9][0-9A-Za-z.\-]*\.apk$""")

    fun parse(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!APK.matches(name) || name.contains("proxy")) continue
            val body = json.optString("body")
            val sha = Regex("""SHA-256:\s*`?([0-9a-fA-F]{64})""").find(body)?.groupValues?.get(1)
            return Release(tag, tag.removePrefix("v"), a.optString("browser_download_url"), name, a.optLong("size"), body, sha)
        }
        return null
    }

    /** "1.1.0" > "1.0.10" > "1.0.2"; non-numeric parts count as 0. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ------------------------------------------------------------------ http

    private class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun open(url: String, accept: String, readTimeout: Int = 20_000): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; this.readTimeout = readTimeout; instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "CarRadio/${BuildConfig.VERSION_NAME}")
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw HttpError(code, runCatching { JSONObject(msg).optString("message") }.getOrDefault("").take(200))
        }
        return c
    }

    private fun get(url: String): String {
        val c = open(url, "application/vnd.github+json")
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
