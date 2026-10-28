package com.nik.carradio.core

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Optional: copies this unit's own radio firmware files (com.syu.ms, the stock radio app,
 * libfmjni) to a "device-dumps" branch of the user's repository, so the exact firmware can be
 * decoded instead of a similar one. Needs a token with "Contents: Read and write" on that repo.
 * Nothing is uploaded unless the user taps the button.
 */
object VendorUpload {
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private const val BRANCH = "device-dumps"

    fun candidates(ctx: Context): List<File> {
        val apks = listOf("com.syu.ms", "com.syu.radio", "com.syu.carradio", "com.syu.ss").mapNotNull { p ->
            runCatching { File(ctx.packageManager.getApplicationInfo(p, 0).sourceDir) }.getOrNull()?.takeIf { it.canRead() }
        }
        val libs = listOf("/system/lib/libfmjni.so", "/vendor/lib/libfmjni.so", "/system/lib64/libfmjni.so").map(::File).filter { it.canRead() }
        return apks + libs
    }

    suspend fun upload(ctx: Context): ReportSender.Upload = withContext(Dispatchers.IO) {
        val prefs = RadioController.prefs
        val token = prefs.githubToken.trim(); val repo = prefs.githubRepo.trim()
        if (token.isEmpty()) return@withContext ReportSender.Upload.Failed("No GitHub token saved", "Settings → Reports: paste a token first.")
        val files = candidates(ctx)
        if (files.isEmpty()) return@withContext ReportSender.Upload.Failed("No vendor radio files readable on this unit", "Nothing to upload.")
        val api = "https://api.github.com/repos/$repo/git"
        try {
            _progress.value = "Checking the repository…"
            val info = call("GET", "https://api.github.com/repos/$repo", token, null)
            if (info.first != 200) return@withContext failed(info, repo)
            val def = JSONObject(info.second).optString("default_branch", "main")
            val ref = call("GET", "$api/ref/heads/$def", token, null)
            if (ref.first != 200) return@withContext failed(ref, repo)
            val parent = JSONObject(ref.second).getJSONObject("object").getString("sha")
            // A new branch per upload: only POST calls (Android's HttpURLConnection cannot PATCH).
            val branch = BRANCH + "-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val baseTree = JSONObject(call("GET", "$api/commits/$parent", token, null).second).getJSONObject("tree").getString("sha")
            val dir = "dumps/" + "${Build.MANUFACTURER}_${Build.MODEL}_${Build.DISPLAY}".replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val entries = JSONArray()
            for ((i, f) in files.withIndex()) {
                _progress.value = "Uploading ${i + 1}/${files.size}: ${f.name} (${f.length() / 1024} KB)…"
                val (code, resp) = blob(api, token, f)
                if (code != 201) return@withContext failed(code to resp, repo)
                val name = if (f.name == "base.apk" || f.name.endsWith(".apk")) (f.parentFile?.name ?: "app") + "_" + f.name else f.name
                entries.put(JSONObject().put("path", "$dir/$name").put("mode", "100644").put("type", "blob").put("sha", JSONObject(resp).getString("sha")))
            }
            _progress.value = "Committing…"
            val tree = call("POST", "$api/trees", token, JSONObject().put("base_tree", baseTree).put("tree", entries))
            if (tree.first != 201) return@withContext failed(tree, repo)
            val commit = call("POST", "$api/commits", token, JSONObject().put("message", "Radio firmware files from ${Build.MODEL} (${Build.DISPLAY})")
                .put("tree", JSONObject(tree.second).getString("sha")).put("parents", JSONArray().put(parent)))
            if (commit.first != 201) return@withContext failed(commit, repo)
            val sha = JSONObject(commit.second).getString("sha")
            val moved = call("POST", "$api/refs", token, JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
            if (moved.first !in 200..201) return@withContext failed(moved, repo)
            _progress.value = "Uploaded ${files.size} files to branch $branch"
            AppLog.i("Vendor files uploaded to $repo@$branch: ${files.joinToString { it.path }}")
            ReportSender.Upload.Ok("https://github.com/$repo/tree/$branch/$dir")
        } catch (e: UnknownHostException) {
            _progress.value = null; ReportSender.Upload.Failed("No internet on the head unit", "Connect it to your phone's hotspot, then retry.")
        } catch (e: IOException) {
            _progress.value = null; ReportSender.Upload.Failed("Upload failed: ${e.javaClass.simpleName}", e.message ?: "")
        }
    }

    private fun failed(r: Pair<Int, String>, repo: String): ReportSender.Upload {
        _progress.value = null
        val m = runCatching { JSONObject(r.second).optString("message") }.getOrDefault("").take(200)
        return when (r.first) {
            403, 404 -> ReportSender.Upload.Failed("The token cannot write to $repo (HTTP ${r.first})",
                "On github.com → Settings → Developer settings → Fine-grained tokens, edit the token and add \"Contents: Read and write\" for $repo. $m")
            401 -> ReportSender.Upload.Failed("GitHub rejected the token (401)", "Paste a new token in Settings → Reports.")
            else -> ReportSender.Upload.Failed("GitHub answered HTTP ${r.first}", m)
        }
    }

    private fun open(method: String, url: String, token: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20_000; readTimeout = 120_000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun call(method: String, url: String, token: String, body: JSONObject?): Pair<Int, String> {
        val c = open(method, url, token)
        try {
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            return code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    /** POST /git/blobs streaming the file as base64, never holding it in memory. */
    private fun blob(api: String, token: String, f: File): Pair<Int, String> {
        val prefix = """{"encoding":"base64","content":"""".toByteArray()
        val suffix = """"}""".toByteArray()
        val b64Len = 4L * ((f.length() + 2) / 3)
        val c = open("POST", "$api/blobs", token)
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(prefix.size + b64Len + suffix.size)
            val out = c.outputStream
            out.write(prefix)
            val enc = Base64OutputStream(object : java.io.FilterOutputStream(out) { override fun close() { flush() } }, Base64.NO_WRAP)
            f.inputStream().use { it.copyTo(enc, 64 * 1024) }
            enc.close()
            out.write(suffix); out.close()
            val code = c.responseCode
            return code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
}
