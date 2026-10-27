package com.nik.sendtocar

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        if (Prefs(this).role == Prefs.CAR) CarService.start(this)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sendtocar", Context.MODE_PRIVATE)

    var role: String?
        get() = sp.getString("role", null)
        set(v) = sp.edit().putString("role", v).apply()

    /** The car makes its own code once; the phone types it in. It is the shared secret. */
    val carCode: String
        get() = sp.getString("carCode", null) ?: randomCode().also { sp.edit().putString("carCode", it).apply() }
    var phoneCode: String
        get() = sp.getString("phoneCode", "").orEmpty()
        set(v) = sp.edit().putString("phoneCode", normalizeCode(v)).apply()
    val code: String get() = if (role == CAR) carCode else phoneCode

    var autoGo: Boolean
        get() = sp.getBoolean("autoGo", true)
        set(v) = sp.edit().putBoolean("autoGo", v).apply()
    var countdown: Int
        get() = sp.getInt("countdown", 5)
        set(v) = sp.edit().putInt("countdown", v).apply()

    var lastIp: String?
        get() = sp.getString("lastIp", null)
        set(v) = sp.edit().putString("lastIp", v).apply()
    var lastBt: String?
        get() = sp.getString("lastBt", null)
        set(v) = sp.edit().putString("lastBt", v).apply()
    var relaySince: String
        get() = sp.getString("relaySince", "12h") ?: "12h"
        set(v) = sp.edit().putString("relaySince", v).apply()
    var lastBeat: Long
        get() = sp.getLong("lastBeat", 0)
        set(v) = sp.edit().putLong("lastBeat", v).apply()

    /** Newest first, at most 30 places. */
    var inbox: List<Msg>
        get() = try {
            val a = JSONArray(sp.getString("inbox", "[]"))
            (0 until a.length()).mapNotNull { Msg.fromJson(a.getString(it)) }
        } catch (e: Exception) { emptyList() }
        set(v) = sp.edit().putString("inbox", JSONArray(v.take(30).map { it.toJson() }).toString()).apply()

    companion object {
        const val CAR = "car"
        const val PHONE = "phone"
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun randomCode(): String {
            val r = java.security.SecureRandom()
            return (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }
        fun normalizeCode(s: String) = s.uppercase(Locale.US).filter { it in ALPHABET }
        fun pretty(code: String) = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
    }
}

/** One message on any channel. The same JSON travels over Wi-Fi, Bluetooth and the relay. */
data class Msg(val id: String, val type: String, val code: String, val place: Place?, val sentAt: Long) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1); put("id", id); put("type", type); put("code", code); put("sentAt", sentAt)
        place?.let { p ->
            p.lat?.let { put("lat", it) }; p.lon?.let { put("lon", it) }
            put("name", p.name); p.query?.let { put("q", it) }
        }
    }.toString()

    companion object {
        const val PING = "ping"
        const val PLACE = "place"
        fun ping(code: String) = Msg(UUID.randomUUID().toString(), PING, code, null, System.currentTimeMillis())
        fun place(code: String, p: Place) = Msg(UUID.randomUUID().toString(), PLACE, code, p, System.currentTimeMillis())
        fun fromJson(s: String): Msg? = try {
            val o = JSONObject(s)
            val place = if (o.has("lat") || o.has("q")) Place(
                if (o.has("lat")) o.getDouble("lat") else null,
                if (o.has("lon")) o.getDouble("lon") else null,
                o.optString("name"), if (o.has("q")) o.getString("q") else null
            ) else null
            Msg(o.getString("id"), o.optString("type", PLACE), o.optString("code"), place, o.optLong("sentAt"))
        } catch (e: Exception) { null }
    }
}

object Wire {
    const val PORT = 47800
    val BT_UUID: UUID = UUID.fromString("6f1c1a52-8c1e-4a3e-9d4e-5e6d2c0b7a11")
    private const val NTFY = "https://ntfy.sh"
    fun topicUrl(code: String, suffix: String = "") = "$NTFY/stc-${code.lowercase(Locale.US)}$suffix"
}

/** Tiny blocking HTTP helpers; every caller is already on a background thread. */
object Http {
    class Res(val code: Int, val body: String)

    fun post(url: String, body: String, connectMs: Int = 5000, readMs: Int = 8000): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Res(code, text)
        } finally { c.disconnect() }
    }

    fun get(url: String, connectMs: Int = 5000, readMs: Int = 10000): Res {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Res(code, text)
        } finally { c.disconnect() }
    }

    /** Follows a short link (maps.app.goo.gl …) one hop at a time and returns the final URL. */
    fun resolve(url: String): String {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code !in 300..399 || loc == null) return u
                u = URL(URL(u), loc).toString()
            } finally { c.disconnect() }
        }
        return u
    }

    /** Parse, and if needed follow the short link online. Null when nothing usable was shared. */
    fun placeFrom(text: String): Place? = when (val r = Parser.parse(text)) {
        is Parsed.Found -> r.place
        is Parsed.NeedsResolve -> {
            val full = try { resolve(r.url) } catch (e: Exception) { ProbeLog.i("Resolve failed: $e"); r.url }
            ProbeLog.i("Resolved ${r.url} → ${full.take(160)}")
            when (val again = Parser.parse(full)) {
                is Parsed.Found -> again.place.copy(name = r.name.ifBlank { again.place.name })
                else -> r.name.takeIf { it.isNotBlank() }?.let { Place(null, null, it, it) }
            }
        }
        Parsed.Nothing -> null
    }
}

/** Probe log: kept in memory and in a file so it survives the service being killed. */
object ProbeLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "log.txt")
        file = f
        try { if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) } } catch (e: Exception) { }
        try { f.writeText(lines.joinToString("\n", postfix = "\n")) } catch (e: Exception) { }
    }

    fun i(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        Log.i("SendToCar", msg)
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            try { file?.appendText(line + "\n") } catch (e: Exception) { }
        }
        listener?.let { l -> main.post { l() } }
    }

    @Synchronized fun snapshot(): List<String> = lines.toList()
}
