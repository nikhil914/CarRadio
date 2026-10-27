package com.nik.carradio.core

import android.content.Context
import android.net.Uri
import com.nik.carradio.BuildConfig
import com.nik.carradio.ui.LogoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * Finds a logo and an internet stream for each named station in radio-browser.info, a free,
 * community-run station directory (no key needed). Runs only when the user asks; logos are
 * downloaded from the station's own favicon address and kept on this unit.
 */
object StationLookup {
    data class Hit(val name: String, val stream: String, val favicon: String, val country: String, val codec: String, val bitrate: Int, val votes: Int, val ok: Boolean)

    /** Mirrors of the directory; "all" is its round-robin DNS name, used last. */
    private val HOSTS = listOf("de1", "nl1", "at1", "fi1", "de2", "all")
    private const val MAX_LOGO_BYTES = 1_500_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow<String?>(null)
    /** Non-null while running or after a run: one line for the UI. */
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun cancel() { job?.cancel() }

    /** All named stations missing a logo or a stream (or [only] that one). */
    fun run(ctx: Context, country: String = "AE", only: Long? = null) {
        if (job?.isActive == true) return
        job = scope.launch {
            _running.value = true
            val all = StationStore.data.value.stations.filter { it.name.isNotBlank() && (only == null || it.id == only) }
            val todo = all.filter { only != null || it.streamUrl.isBlank() || it.id !in LogoStore.ids.value }
            if (todo.isEmpty()) { _progress.value = if (all.isEmpty()) "Give stations a name first – the search uses names." else "Every named station already has a logo and a stream."; _running.value = false; return@launch }
            var logos = 0; var streams = 0; var notFound = 0
            try {
                for ((i, st) in todo.withIndex()) {
                    if (!isActive) break
                    _progress.value = "Looking up ${i + 1} of ${todo.size}: ${st.name}…"
                    val hits = search(st.name, country).ifEmpty { search(st.name, null) }
                    val best = pickBest(st.name, hits, country)
                    if (best == null) { notFound++; AppLog.i("Lookup: no match for \"${st.name}\" (${hits.size} results)"); continue }
                    AppLog.i("Lookup: \"${st.name}\" -> \"${best.name}\" ${best.country} ${best.codec} ${best.bitrate}k")
                    if (best.stream.isNotBlank() && (st.streamUrl.isBlank() || only != null)) {
                        StationStore.update { d -> d.copy(stations = d.stations.map { if (it.id == st.id) it.copy(streamUrl = best.stream) else it }) }
                        streams++
                    }
                    if (best.favicon.isNotBlank() && (st.id !in LogoStore.ids.value || only != null) && downloadLogo(ctx, st.id, best.favicon)) logos++
                }
                _progress.value = "Done: $logos logos and $streams streams added" + (if (notFound > 0) ", $notFound stations not in the directory" else "") + "."
            } catch (e: UnknownHostException) {
                _progress.value = "No internet on the head unit. Connect it to your phone's hotspot and try again."
            } catch (e: IOException) {
                _progress.value = "Lookup stopped: ${e.javaClass.simpleName} ${e.message ?: ""} – try again."
            } finally {
                _running.value = false
                AppLog.i("Lookup: ${_progress.value}")
            }
        }
    }

    private fun search(name: String, country: String?): List<Hit> {
        val q = "name=" + URLEncoder.encode(cleanName(name), "UTF-8") + (country?.let { "&countrycode=$it" } ?: "") +
            "&hidebroken=true&order=votes&reverse=true&limit=15"
        var last: IOException? = null
        for (h in HOSTS) {
            try { return parse(JSONArray(get("https://$h.api.radio-browser.info/json/stations/search?$q"))) }
            catch (e: UnknownHostException) { throw e }
            catch (e: IOException) { last = e; AppLog.w("Lookup: $h failed: ${e.message}") }
        }
        throw last ?: IOException("all directory servers failed")
    }

    private fun downloadLogo(ctx: Context, id: Long, url: String): Boolean {
        if (!url.startsWith("http") || url.substringBefore('?').endsWith(".svg", ignoreCase = true)) return false
        val tmp = File(ctx.cacheDir, "logo-dl")
        return try {
            val c = open(url, 15_000)
            try {
                c.inputStream.use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024); var total = 0
                    while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > MAX_LOGO_BYTES) error("logo too large"); out.write(buf, 0, n) }
                } }
            } finally { c.disconnect() }
            kotlinx.coroutines.runBlocking { LogoStore.importFor(ctx, id, Uri.fromFile(tmp)) }
        } catch (e: Exception) {
            AppLog.w("Lookup: logo download failed ($url): ${e.message}"); false
        } finally { tmp.delete() }
    }

    // ------------------------------------------------------------------ pure (unit-tested)

    /** "Virgin Radio 104.4 FM" → "Virgin Radio": a frequency and "FM" confuse the directory search. Whole numbers stay ("Dubai 92"). */
    fun cleanName(name: String): String =
        name.replace(Regex("""\b\d{2,3}[.,]\d\b"""), " ").replace(Regex("""\b(FM|MHz)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ").trim().ifBlank { name.trim() }

    /** Words for matching, without frequencies and "fm". */
    private fun words(s: String): Set<String> =
        cleanName(s).lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() && it != "fm" }.toSet()

    fun parse(a: JSONArray): List<Hit> = (0 until a.length()).map { a.getJSONObject(it) }.map { o: JSONObject ->
        Hit(o.optString("name").trim(), o.optString("url_resolved").ifBlank { o.optString("url") }, o.optString("favicon"),
            o.optString("countrycode"), o.optString("codec"), o.optInt("bitrate"), o.optInt("votes"), o.optInt("lastcheckok", 1) == 1)
    }

    /**
     * Best directory entry for [name]: the names must share the station's name (either way),
     * working streams first, then the user's country, then a logo, exact name and votes.
     */
    fun pickBest(name: String, hits: List<Hit>, country: String?): Hit? {
        val w = words(name)
        if (w.joinToString("").length < 3) return null
        // Whole words: "Hum" must not match "Humraaz"; "Dubai 92" must not match "Dubai Eye".
        return hits.filter { h -> val hw = words(h.name); hw.isNotEmpty() && (hw.containsAll(w) || w.containsAll(hw)) && h.stream.startsWith("http") }
            .maxByOrNull { h ->
                var score = 0
                if (h.ok) score += 1000
                if (country != null && h.country.equals(country, ignoreCase = true)) score += 500
                if (h.favicon.isNotBlank()) score += 200
                if (words(h.name) == w) score += 100
                if (h.codec.equals("AAC", true) || h.codec.equals("MP3", true)) score += 50
                score + h.votes.coerceAtMost(49)
            }
    }

    // ------------------------------------------------------------------ http

    private fun open(url: String, timeout: Int): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CarRadio/${BuildConfig.VERSION_NAME}")
        }
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); throw IOException("HTTP $code") }
        return c
    }

    private fun get(url: String): String { val c = open(url, 12_000); try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() } }
}
