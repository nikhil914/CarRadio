package com.nik.sendtocar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.URLEncoder

/** Car side: checks, de-duplicates and stores every message, then opens the takeover card. */
object Inbox {
    /** Places older than this go to the inbox only, so a replay after a long sleep never hijacks the route. */
    private const val FRESH_MS = 15 * 60_000L
    private val seen = LinkedHashSet<String>()

    @Volatile var lastShown = "never"

    @Synchronized
    fun handle(ctx: Context, body: String, via: String): String {
        val prefs = Prefs(ctx)
        val m = Msg.fromJson(body.trim()) ?: run { ProbeLog.i("Unreadable message via $via"); return "bad" }
        if (m.code != prefs.carCode) { ProbeLog.i("Wrong pairing code via $via"); return "denied" }
        if (m.type == Msg.PING) { ProbeLog.i("Ping via $via"); return "pong" }
        if (m.id in seen || prefs.inbox.any { it.id == m.id }) { ProbeLog.i("Duplicate ignored via $via"); return "dup" }
        seen.add(m.id)
        if (seen.size > 200) seen.remove(seen.first())
        val place = m.place ?: return "bad"
        prefs.inbox = listOf(m) + prefs.inbox
        val age = System.currentTimeMillis() - m.sentAt
        ProbeLog.i("Place \"${place.label()}\" via $via, ${age / 1000} s after sending")
        if (age > FRESH_MS) ProbeLog.i("Older than 15 min: kept in the inbox, no pop-up")
        else show(ctx, place, via)
        return "ok"
    }

    fun show(ctx: Context, place: Place, via: String) {
        val i = Intent(ctx, TakeoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("lat", place.lat ?: Double.NaN).putExtra("lon", place.lon ?: Double.NaN)
            .putExtra("name", place.name).putExtra("q", place.query).putExtra("via", via)
        try { ctx.startActivity(i); ProbeLog.i("Pop-up requested ($via)") }
        catch (e: Exception) { ProbeLog.i("Pop-up refused ($via): $e") }
    }

    /** Fallback channel: a place sent as a file over Bluetooth file transfer. Reads the newest one. */
    fun checkBluetoothFiles(ctx: Context): String {
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf(File(root, "bluetooth"), File(root, "Bluetooth"), File(root, "Download/bluetooth"),
            File(root, "Download/Bluetooth"), File(root, "Download"))
        val files = dirs.filter { it.isDirectory }.flatMap { d -> d.listFiles()?.filter { it.isFile }.orEmpty() }
            .distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        if (files.isEmpty()) return "No files found in ${dirs.filter { it.isDirectory }.joinToString { it.path }.ifBlank { "any Bluetooth folder" }} (storage permission granted?)"
        val report = files.take(5).joinToString("\n") { "  ${it.path} (${(System.currentTimeMillis() - it.lastModified()) / 60000} min old)" }
        ProbeLog.i("Bluetooth folder, newest files:\n$report")
        val newest = files.firstOrNull { it.extension.lowercase() in setOf("txt", "html", "htm", "vcf", "url") && it.length() < 32_000 }
            ?: return "No text file to read. Newest:\n$report"
        val text = newest.readText()
        Thread {
            val place = Http.placeFrom(text)
            if (place == null) ProbeLog.i("No place found in ${newest.name}")
            else {
                ProbeLog.i("Place from Bluetooth file ${newest.name}: ${place.label()}")
                show(ctx, place, "Bluetooth file")
            }
        }.start()
        return "Reading ${newest.name}…"
    }
}

object Waze {
    fun installed(ctx: Context): String = try {
        "installed, version " + ctx.packageManager.getPackageInfo("com.waze", 0).versionName
    } catch (e: Exception) { "NOT installed" }

    /** Opens Waze navigating to the place; falls back to any map app through geo:. */
    fun navigate(ctx: Context, p: Place): String {
        val waze = if (p.hasCoords) "waze://?ll=${p.lat},${p.lon}&navigate=yes"
        else "waze://?q=${URLEncoder.encode(p.query ?: p.name, "UTF-8")}&navigate=yes"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waze)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ProbeLog.i("Opened Waze: $waze")
            return "Waze"
        } catch (e: ActivityNotFoundException) { ProbeLog.i("Waze link not handled, trying geo:") }
        val geo = if (p.hasCoords) "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})"
        else "geo:0,0?q=${Uri.encode(p.query ?: p.name)}"
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ProbeLog.i("Opened map app: $geo"); "map app"
        } catch (e: ActivityNotFoundException) { ProbeLog.i("No app opens geo: links"); "nothing" }
    }
}

/** The full-screen "Navigate to X?" card. Auto-go after a countdown, or wait for a tap (a setting). */
class TakeoverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var place: Place
    private lateinit var countdownView: TextView
    private var left = 0
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            if (left <= 0) { go(); return }
            countdownView.text = "Navigating in $left s"
            left--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setup(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handler.removeCallbacks(tick)
        done = false
        setup(intent)
    }

    private fun setup(i: Intent) {
        val lat = i.getDoubleExtra("lat", Double.NaN)
        val lon = i.getDoubleExtra("lon", Double.NaN)
        place = Place(lat.takeUnless { it.isNaN() }, lon.takeUnless { it.isNaN() }, i.getStringExtra("name").orEmpty(), i.getStringExtra("q"))
        val via = i.getStringExtra("via").orEmpty()
        Inbox.lastShown = "shown ${java.text.DateFormat.getTimeInstance().format(java.util.Date())} ($via)"
        ProbeLog.i("Pop-up SHOWN on screen ($via)")

        val prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(48, 48, 48, 48)
        }
        fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(0, 12, 0, 12)
        }
        col.addView(text("Navigate to", 22f, Color.LTGRAY))
        col.addView(text(place.label(), 36f))
        if (place.hasCoords) col.addView(text("%.5f, %.5f".format(place.lat, place.lon), 18f, Color.GRAY))
        countdownView = text("", 24f, Color.parseColor("#64B5F6"))
        col.addView(countdownView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun big(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 26f; setPadding(64, 32, 64, 32); setOnClickListener { onClick() }
        }
        row.addView(big("Go") { go() })
        row.addView(big("Cancel") { done = true; handler.removeCallbacks(tick); ProbeLog.i("Pop-up cancelled"); finish() })
        col.addView(row)
        setContentView(col)

        if (prefs.autoGo) { left = prefs.countdown; handler.post(tick) }
        else countdownView.text = "Tap Go to navigate"
    }

    private fun go() {
        if (done) return
        done = true
        handler.removeCallbacks(tick)
        Waze.navigate(this, place)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
