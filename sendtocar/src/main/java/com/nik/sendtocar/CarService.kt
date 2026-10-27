package com.nik.sendtocar

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Car side. Keeps three listeners alive (Wi-Fi HTTP, Bluetooth RFCOMM, ntfy relay) and hands
 * every message to [Inbox]. Android 8.1 still lets a background service open an activity, so
 * the takeover card needs no notification (the unit's shade doesn't show them anyway).
 */
class CarService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        startForeground(1, notification())
        running = true
        startedAt = System.currentTimeMillis()
        val prefs = Prefs(this)
        val gap = if (prefs.lastBeat == 0L) "first run" else "last alive ${(System.currentTimeMillis() - prefs.lastBeat) / 60000} min ago"
        ProbeLog.i("Service start: uptime since boot ${SystemClock.elapsedRealtime() / 60000} min, $gap")
        thread("beat") { while (!stopped) { prefs.lastBeat = System.currentTimeMillis(); Thread.sleep(60_000) } }
        thread("wifi") { wifiLoop() }
        thread("bt") { btLoop() }
        thread("relay") { relayLoop() }
        Watchdog.arm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_POPUP) {
            ProbeLog.i("Pop-up test armed: the card opens in 15 s. Switch to Waze now.")
            main.postDelayed({
                Inbox.show(this, Place(24.4615, 54.3172, "Emirates Palace (pop-up test)"), "background test")
            }, 15_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        ProbeLog.i("Service destroyed")
        try { btServer?.close() } catch (e: Exception) { }
        try { httpServer?.close() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("car", "Send to Car receiver", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "car")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_send_car).setContentTitle("Send to Car is listening").setOngoing(true).build()
    }

    private fun thread(name: String, body: () -> Unit) = Thread({
        try { body() } catch (e: InterruptedException) { } catch (e: Throwable) { ProbeLog.i("[$name] crashed: $e") }
    }, "stc-$name").apply { isDaemon = true; start() }

    // ---- Wi-Fi: a tiny HTTP server. POST /msg with the message JSON, reply is the Inbox answer.

    private var httpServer: ServerSocket? = null

    private fun wifiLoop() {
        while (!stopped) {
            try {
                ServerSocket(Wire.PORT).use { server ->
                    httpServer = server
                    wifiState = "listening on port ${Wire.PORT}"
                    ProbeLog.i("[wifi] $wifiState")
                    while (!stopped) {
                        val s = server.accept()
                        Thread { try { serveHttp(s) } catch (e: Exception) { ProbeLog.i("[wifi] client error: $e") } }.start()
                    }
                }
            } catch (e: Exception) {
                if (stopped) return
                wifiState = "error: $e"
                ProbeLog.i("[wifi] $wifiState")
                Thread.sleep(10_000)
            }
        }
    }

    private fun serveHttp(s: Socket) {
        s.use { serveHttpOn(it) }
    }

    private fun serveHttpOn(it: Socket) {
        it.soTimeout = 5000
        val r = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val requestLine = r.readLine() ?: return
        var length = 0
        while (true) {
            val h = r.readLine() ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val buf = CharArray(length.coerceAtMost(16_000))
        var read = 0
        while (read < buf.size) { val n = r.read(buf, read, buf.size - read); if (n < 0) break; read += n }
        val reply = if (requestLine.startsWith("POST /msg")) {
            Inbox.handle(this, String(buf, 0, read), "Wi-Fi from ${it.inetAddress.hostAddress}")
        } else "Send to Car"
        val bytes = reply.toByteArray()
        it.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
        it.getOutputStream().flush()
    }

    // ---- Bluetooth: an insecure RFCOMM server (no PIN prompt). One JSON line in, one line out.

    private var btServer: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    private fun btLoop() {
        @Suppress("DEPRECATION") val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            btState = "no Android Bluetooth adapter on this unit"
            ProbeLog.i("[bt] $btState")
            return
        }
        var lastLogged = ""
        while (!stopped) {
            try {
                if (!adapter.isEnabled) {
                    btState = "adapter present but OFF"
                    if (lastLogged != btState) { ProbeLog.i("[bt] $btState"); lastLogged = btState }
                    Thread.sleep(15_000); continue
                }
                val server = adapter.listenUsingInsecureRfcommWithServiceRecord("SendToCar", Wire.BT_UUID)
                btServer = server
                btState = "listening as \"${adapter.name}\", ${adapter.bondedDevices?.size ?: 0} paired"
                if (lastLogged != btState) { ProbeLog.i("[bt] $btState"); lastLogged = btState }
                while (!stopped) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val line = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).readLine().orEmpty()
                                val reply = Inbox.handle(this, line, "Bluetooth from ${s.remoteDevice?.name ?: "?"}")
                                s.outputStream.write((reply + "\n").toByteArray()); s.outputStream.flush()
                                Thread.sleep(300) // let the reply leave before the socket closes
                            }
                        } catch (e: Exception) { ProbeLog.i("[bt] client error: $e") }
                    }.start()
                }
            } catch (e: Exception) {
                if (stopped) return
                btState = "error: $e"
                if (lastLogged != btState) { ProbeLog.i("[bt] $btState"); lastLogged = btState }
                Thread.sleep(10_000)
            }
        }
    }

    // ---- Internet relay: streams the ntfy topic. Places sent while the car was off arrive on next start.

    private fun relayLoop() {
        val prefs = Prefs(this)
        var backoff = 5_000L
        var lastLogged = ""
        while (!stopped) {
            val code = prefs.carCode
            try {
                val c = URL(Wire.topicUrl(code, "/json?since=${prefs.relaySince}")).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 120_000 // ntfy sends a keepalive every ~45 s
                try {
                    val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                    relayState = "connected"
                    if (lastLogged != relayState) { ProbeLog.i("[relay] $relayState"); lastLogged = relayState }
                    backoff = 5_000L
                    while (!stopped) {
                        val line = r.readLine() ?: break
                        val o = JSONObject(line)
                        if (o.optString("event") != "message") continue
                        prefs.relaySince = o.getString("id")
                        val body = o.optString("message")
                        val reply = Inbox.handle(this, body, "internet relay")
                        Msg.fromJson(body)?.let { m ->
                            if (reply == "ok" || reply == "pong" || reply == "dup") {
                                try { Http.post(Wire.topicUrl(code, "-ack"), m.id) } catch (e: Exception) { ProbeLog.i("[relay] ack failed: $e") }
                            }
                        }
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) {
                if (stopped) return
                relayState = "offline ($e)"
                if (lastLogged != relayState) { ProbeLog.i("[relay] $relayState"); lastLogged = relayState }
            }
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    companion object {
        const val ACTION_TEST_POPUP = "com.nik.sendtocar.TEST_POPUP"
        @Volatile var running = false
        @Volatile var startedAt = 0L
        @Volatile var wifiState = "not started"
        @Volatile var btState = "not started"
        @Volatile var relayState = "not started"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, CarService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { ProbeLog.i("Service start refused: $e") }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ProbeLog.init(ctx)
        ProbeLog.i("Broadcast: ${intent.action}")
        if (Prefs(ctx).role == Prefs.CAR) CarService.start(ctx)
    }
}

/** Every 15 min: restart the service if the unit's task killer stopped it. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ProbeLog.init(ctx)
        if (Prefs(ctx).role != Prefs.CAR) return
        if (!CarService.running) { ProbeLog.i("Watchdog: service was not running, restarting"); CarService.start(ctx) }
    }
}

object Watchdog {
    fun arm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WatchdogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi)
    }
}
