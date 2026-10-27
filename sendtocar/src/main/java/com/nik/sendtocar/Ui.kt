package com.nik.sendtocar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Plain-View helpers: the probe needs no AndroidX, so it stays tiny and installs on any unit. */
private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun LinearLayout.label(s: String, size: Float = 16f, bold: Boolean = false): TextView =
    TextView(context).apply {
        text = s; textSize = size; setPadding(0, 8, 0, 8)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }.also { addView(it) }

private fun LinearLayout.button(s: String, onClick: (Button) -> Unit): Button =
    Button(context).apply { text = s; isAllCaps = false; setOnClickListener { onClick(this) } }.also { addView(it) }

private fun bg(f: () -> Unit) = Thread { try { f() } catch (e: Exception) { ProbeLog.i("Error: $e") } }.start()

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var statusView: TextView? = null
    private var logView: TextView? = null
    private var sendView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        prefs = Prefs(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
    }

    override fun onResume() {
        super.onResume()
        ProbeLog.listener = { refreshLog() }
        handler.post(refresher)
    }

    override fun onPause() {
        ProbeLog.listener = null
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun build() {
        root.removeAllViews()
        statusView = null; logView = null; sendView = null
        when (prefs.role) {
            Prefs.CAR -> buildCar()
            Prefs.PHONE -> buildPhone()
            else -> buildChooser()
        }
    }

    private fun buildChooser() {
        root.label("Send to Car ${BuildConfig.VERSION_NAME}", 24f, true)
        root.label("Install this same app on the car unit and on your phone, then pick what this device is.")
        root.button("This is the CAR (head unit)") { prefs.role = Prefs.CAR; CarService.start(this); build() }
        root.button("This is my PHONE") { prefs.role = Prefs.PHONE; build() }
    }

    private fun buildCar() {
        CarService.start(this)
        if (Build.VERSION.SDK_INT in 23..32 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        root.label("Send to Car: car mode (probe ${BuildConfig.VERSION_NAME})", 22f, true)
        root.label("Pairing code: ${Prefs.pretty(prefs.carCode)}", 30f, true)
        root.label("Type this code into the app on your phone.")
        statusView = root.label("", 14f)

        root.label("Tests", 20f, true)
        root.button("1. Pop-up test: opens the card in 15 s. Switch to Waze right after tapping") {
            CarService.start(this, CarService.ACTION_TEST_POPUP)
        }
        root.button("2. Open Waze now to a test place") {
            Waze.navigate(this, Place(24.4615, 54.3172, "Emirates Palace"))
        }
        root.button("3. Read the newest file received by Bluetooth") { b ->
            b.text = "3. " + Inbox.checkBluetoothFiles(this)
        }
        root.button("4. Show report as QR codes") { showQr() }
        root.button("5. Send report to the phone (internet)") { b ->
            b.text = "5. Sending…"
            bg { val r = Report.sendToPhone(this); runOnUiThread { b.text = "5. Report: $r" } }
        }

        root.label("Settings", 20f, true)
        root.button(autoLabel()) { b -> prefs.autoGo = !prefs.autoGo; b.text = autoLabel() }
        root.button(countdownLabel()) { b ->
            val opts = listOf(0, 3, 5, 10)
            prefs.countdown = opts[(opts.indexOf(prefs.countdown) + 1) % opts.size]
            b.text = countdownLabel()
        }

        val inbox = prefs.inbox
        if (inbox.isNotEmpty()) {
            root.label("Recent places", 20f, true)
            inbox.take(6).forEach { m -> m.place?.let { p -> root.button("Navigate: ${p.label()}") { Waze.navigate(this, p) } } }
        }
        addFooter()
    }

    private fun autoLabel() = "When a place arrives: " + if (prefs.autoGo) "navigate automatically after the countdown" else "wait for me to tap Go"
    private fun countdownLabel() = "Countdown: ${prefs.countdown} s"

    private fun buildPhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
        }
        root.label("Send to Car: phone mode (probe ${BuildConfig.VERSION_NAME})", 22f, true)
        root.label("Car pairing code (shown on the car screen):")
        val codeIn = EditText(this).apply {
            setText(Prefs.pretty(prefs.phoneCode)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(codeIn)
        root.button("Save code") { prefs.phoneCode = codeIn.text.toString(); codeIn.setText(Prefs.pretty(prefs.phoneCode)); ProbeLog.i("Code saved") }
        root.label("To send a place: in Google Maps, WhatsApp or Waze tap Share → Send to car.", 14f)

        root.label("Tests", 20f, true)
        root.button("1. Ping the car on all channels") { send(Msg.ping(prefs.phoneCode)) }
        root.button("2. Send a test place (Emirates Palace)") {
            send(Msg.place(prefs.phoneCode, Place(24.4615, 54.3172, "Emirates Palace")))
        }
        sendView = root.label("", 15f)
        root.button("3. Get the car's report (internet)") { b ->
            b.text = "3. Fetching…"
            bg {
                val r = Report.fetchFromCar(prefs.phoneCode)
                runOnUiThread {
                    b.text = "3. Get the car's report (internet)"
                    if (r == null) ProbeLog.i("No car report on the relay in the last hour") else shareText("Car report", r)
                }
            }
        }
        root.button("4. Share this phone's report") { shareText("Phone report", Report.build(this)) }
        statusView = root.label("", 14f)
        addFooter()
    }

    private fun send(m: Msg) {
        if (prefs.phoneCode.length != 8) { sendView?.text = "Enter the 8-character code from the car first."; return }
        val status = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
        fun render() { sendView?.text = status.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
        render()
        Sender(this).send(m) { ch, s -> runOnUiThread { status[ch] = s; render() } }
    }

    private fun addFooter() {
        root.label("Log", 20f, true)
        logView = root.label("", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.button("Switch role (car/phone)") { prefs.role = null; build() }
        refresh()
    }

    private fun refresh() {
        statusView?.text = Report.status(this).joinToString("\n")
        refreshLog()
    }

    private fun refreshLog() {
        logView?.text = ProbeLog.snapshot().takeLast(60).reversed().joinToString("\n")
    }

    private fun showQr() {
        val pages = Report.qrPages(this)
        var i = 0
        val img = ImageView(this)
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.8).toInt()
        val d = AlertDialog.Builder(this).setView(img).setPositiveButton("Next", null).setNegativeButton("Close", null).create()
        fun page() { img.setImageBitmap(Report.qr(pages[i], size)); d.setTitle("Report page ${i + 1} of ${pages.size}") }
        d.setOnShowListener {
            page()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { i = (i + 1) % pages.size; page() }
        }
        d.show()
    }

    private fun shareText(title: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title))
    }
}

/** Phone side: Share → Send to car. Parses what was shared, sends it on every channel, shows each result. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        val prefs = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        setContentView(col)
        val what = col.label("Reading the place…", 18f, true)
        val status = col.label("", 15f)
        col.button("Close") { finish() }

        if (prefs.phoneCode.length != 8) {
            what.text = "Open Send to Car and enter the car's pairing code first."
            col.button("Open Send to Car") { startActivity(Intent(this, MainActivity::class.java)); finish() }
            return
        }
        val shared = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString
        ).joinToString("\n")
        ProbeLog.i("Shared: ${shared.take(300).replace('\n', ' ')}")
        bg {
            val place = Http.placeFrom(shared)
            runOnUiThread {
                if (place == null) { what.text = "No place found in what was shared."; status.visibility = View.GONE; return@runOnUiThread }
                what.text = "Sending: ${place.label()}"
                val st = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
                fun render() { status.text = st.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
                render()
                Sender(this).send(Msg.place(prefs.phoneCode, place)) { ch, s -> runOnUiThread { st[ch] = s; render() } }
            }
        }
    }
}
