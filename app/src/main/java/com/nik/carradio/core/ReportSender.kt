package com.nik.carradio.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Gets the diagnostics report off a head unit that has no USB:
 *  - QR pages: works offline, scan each page with the phone camera and paste the text.
 *  - GitHub issue: one tap once a fine-grained token (Issues: write) is saved in Settings.
 */
object ReportSender {

    /** ~1000 chars per page keeps each QR at version ≤ 25: easy to scan off a 720p screen. */
    private const val QR_PAGE_CHARS = 1000
    private const val ISSUE_MAX_CHARS = 60_000

    fun build(ctx: Context, full: Boolean): String = buildString {
        appendLine("=== Car Radio report ${com.nik.carradio.BuildConfig.VERSION_NAME} ===")
        DeviceInfo.report(ctx).forEach { appendLine(it) }
        appendLine()
        appendLine("=== Probes ===")
        RadioController.probes.value.forEach { (id, r) ->
            appendLine("[$id] ${r.status}: ${r.summary}")
            if (r.hint.isNotBlank()) appendLine("  hint: ${r.hint}")
            (if (full) r.details else r.details.take(4)).forEach { appendLine("  $it") }
        }
        val s = RadioController.state
        appendLine()
        appendLine("=== State ===")
        appendLine("backend=${s.backendId} powered=${s.powered} band=${s.band} freq=${s.freqKhz} status=${s.status}")
        appendLine("tuner: region=${s.region} ${s.bandInfo?.describe() ?: "no band info"} sensFm=${s.sensFm} sensAm=${s.sensAm} " +
            "opts=${s.opts.entries.joinToString { "${it.key}=${if (it.value) "on" else "off"}" }.ifEmpty { "none reported" }}")
        s.error?.let { appendLine("error=${it.title} | ${it.hint}"); if (full) appendLine(it.technical.take(1500)) }
        RadioController.learnResult.value?.let { l ->
            appendLine(); appendLine("=== Learn tuner commands ==="); l.forEach { appendLine(it) }
        }
        appendLine("FYT learned: tune=${RadioController.prefs.fytVariant("tune")} step=${RadioController.prefs.fytVariant("step")} seek=${RadioController.prefs.fytVariant("seek")}")
        appendLine()
        appendLine("=== Log ===")
        AppLog.lines.value.takeLast(if (full) 400 else 60).forEach { appendLine(it) }
    }

    /** Splits the short report into QR-sized pages, each labelled so the order survives. */
    fun qrPages(ctx: Context): List<String> = paginate(build(ctx, full = false))

    /** Pure function so it is unit-tested: ASCII only, so a page never outgrows one QR code. */
    fun paginate(text: String): List<String> {
        val chunks = toAscii(text).chunked(QR_PAGE_CHARS)
        return chunks.mapIndexed { i, c -> "CarRadio ${i + 1}/${chunks.size}\n$c" }
    }

    fun toAscii(s: String): String = buildString(s.length) {
        for (ch in s) append(when {
            ch.code in 32..126 || ch == '\n' -> ch.toString()
            ch == '▶' -> ">"; ch == '✔' -> "+"; ch == '✖' -> "x"; ch == '–' || ch == '—' -> "-"
            ch == '…' -> "..."; ch == '·' -> "."; ch == '→' -> "->"; ch == '\t' -> " "
            else -> "?"
        })
    }

    fun qrBitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) { val o = y * m.width; for (x in 0 until m.width) px[o + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.RGB_565)
    }

    sealed class Upload {
        data class Ok(val url: String) : Upload()
        data class Failed(val title: String, val hint: String) : Upload()
    }

    suspend fun fileGitHubIssue(ctx: Context): Upload = withContext(Dispatchers.IO) {
        val prefs = RadioController.prefs
        val token = prefs.githubToken.trim()
        val repo = prefs.githubRepo.trim()
        if (token.isEmpty()) return@withContext Upload.Failed("No GitHub token saved",
            "Settings → GitHub report upload: paste a fine-grained token with Issues: Read and write on $repo.")
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) return@withContext Upload.Failed("Repository \"$repo\" is not owner/name", "Example: nikhil914/CarRadio")
        val body = build(ctx, full = true).take(ISSUE_MAX_CHARS)
        val title = "Report ${com.nik.carradio.BuildConfig.VERSION_NAME}: " +
            (RadioController.state.error?.title ?: RadioController.state.status).take(80)
        val json = JSONObject().put("title", title).put("body", "```\n$body\n```")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.github.com/repos/$repo/issues").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            AppLog.i("GitHub issue upload: HTTP $code")
            when (code) {
                201 -> Upload.Ok(JSONObject(text).optString("html_url", "https://github.com/$repo/issues"))
                401 -> Upload.Failed("GitHub rejected the token (401)", "The token is wrong or expired. Create a new fine-grained token and paste it again.")
                403 -> Upload.Failed("Token has no permission (403)", "Give the token \"Issues: Read and write\" on $repo. ${msg(text)}")
                404 -> Upload.Failed("Repository not found (404)", "Check the name \"$repo\", and that the token is allowed on it.")
                410 -> Upload.Failed("Issues are disabled on $repo (410)", "Enable Issues in the repo settings.")
                422 -> Upload.Failed("GitHub refused the content (422)", msg(text))
                else -> Upload.Failed("GitHub answered HTTP $code", msg(text))
            }
        } catch (e: UnknownHostException) {
            Upload.Failed("No internet on the head unit", "Connect it to your phone's hotspot, then retry. Or use the QR codes – they work offline.")
        } catch (e: IOException) {
            Upload.Failed("Upload failed: ${e.javaClass.simpleName}", (e.message ?: "") + " – check the hotspot connection, or use the QR codes.")
        } finally { conn?.disconnect() }
    }

    /**
     * Long text as one issue plus comments (GitHub allows 65,536 characters per body), each part
     * numbered so the order survives. Used for the full radio test report.
     */
    suspend fun fileLongIssue(title: String, text: String): Upload = withContext(Dispatchers.IO) {
        val prefs = RadioController.prefs
        val token = prefs.githubToken.trim(); val repo = prefs.githubRepo.trim()
        if (token.isEmpty()) return@withContext Upload.Failed("No GitHub token saved",
            "Settings → Reports: paste a fine-grained token with Issues: Read and write on $repo. Or use Share / Save to file.")
        val parts = text.chunked(60_000)
        fun body(i: Int) = "Part ${i + 1}/${parts.size}\n```\n${parts[i]}\n```"
        try {
            val (code, resp) = post("https://api.github.com/repos/$repo/issues", token, JSONObject().put("title", title).put("body", body(0)))
            if (code != 201) return@withContext fail(code, resp, repo)
            val o = JSONObject(resp)
            val url = o.optString("html_url"); val number = o.optInt("number")
            for (i in 1 until parts.size) {
                val (c, r) = post("https://api.github.com/repos/$repo/issues/$number/comments", token, JSONObject().put("body", body(i)))
                if (c != 201) { AppLog.w("Report part ${i + 1} not uploaded: HTTP $c ${msg(r)}"); return@withContext Upload.Failed("Only ${i} of ${parts.size} parts uploaded (HTTP $c)", "$url – ${msg(r)}") }
            }
            AppLog.i("Long report uploaded: $url (${parts.size} parts)")
            Upload.Ok(url)
        } catch (e: UnknownHostException) {
            Upload.Failed("No internet on the head unit", "Connect it to your phone's hotspot, then retry. Or use Share / Save to file.")
        } catch (e: IOException) {
            Upload.Failed("Upload failed: ${e.javaClass.simpleName}", (e.message ?: "") + " – check the hotspot connection.")
        }
    }

    private fun post(url: String, token: String, json: JSONObject): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            return code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { conn.disconnect() }
    }

    private fun fail(code: Int, text: String, repo: String): Upload = when (code) {
        401 -> Upload.Failed("GitHub rejected the token (401)", "The token is wrong or expired. Create a new fine-grained token and paste it again.")
        403 -> Upload.Failed("Token has no permission (403)", "Give the token \"Issues: Read and write\" on $repo. ${msg(text)}")
        404 -> Upload.Failed("Repository not found (404)", "Check the name \"$repo\", and that the token is allowed on it.")
        else -> Upload.Failed("GitHub answered HTTP $code", msg(text))
    }

    private fun msg(body: String) = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty().take(200)
}
