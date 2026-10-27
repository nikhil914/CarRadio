package com.nik.sendtocar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone side: sends one message on Wi-Fi, Bluetooth and the internet relay at the same time.
 * The car keeps the first copy and drops the rest, so all three can safely succeed.
 */
class Sender(ctx: Context) {
    private val ctx = ctx.applicationContext
    private val prefs = Prefs(ctx)

    /** [onStatus] is called from background threads with (channel, status). */
    fun send(m: Msg, onStatus: (String, String) -> Unit) {
        val body = m.toJson()
        Thread { onStatus(WIFI, safe { wifi(body) { onStatus(WIFI, it) } }) }.start()
        Thread { onStatus(BT, safe { bt(body) { onStatus(BT, it) } }) }.start()
        Thread { onStatus(NET, safe { relay(m, body) { onStatus(NET, it) } }) }.start()
    }

    private fun safe(f: () -> String): String = try { f() } catch (e: Exception) { "failed: $e" }

    private fun since(t0: Long) = "%.1f s".format((SystemClock.elapsedRealtime() - t0) / 1000.0)

    private fun verdict(reply: String?, where: String, t0: Long): String? = when (reply) {
        "ok", "dup", "pong" -> "DELIVERED via $where in ${since(t0)}"
        "denied" -> "car at $where says the pairing code is wrong"
        null -> null
        else -> "car at $where replied \"$reply\""
    }

    // ---- Wi-Fi: last known IP first, then a sweep of every local /24 (the hotspot's subnet varies by phone).

    private fun postTo(ip: String, body: String, connectMs: Int = 1500): String? = try {
        Http.post("http://$ip:${Wire.PORT}/msg", body, connectMs, 3000).body.trim()
    } catch (e: Exception) { null }

    private fun wifi(body: String, progress: (String) -> Unit): String {
        val t0 = SystemClock.elapsedRealtime()
        prefs.lastIp?.let { ip -> verdict(postTo(ip, body), ip, t0)?.let { return it } }
        val subnets = localSubnets()
        if (subnets.isEmpty()) return "no Wi-Fi or hotspot network on this phone"
        progress("searching ${subnets.joinToString { "$it.x" }}…")
        val ping = Msg.ping(prefs.code).toJson()
        val found = AtomicReference<String?>()
        val pool = Executors.newFixedThreadPool(48)
        for (sub in subnets) for (h in 1..254) {
            val ip = "$sub.$h"
            pool.execute { if (found.get() == null && postTo(ip, ping, 400) == "pong") found.compareAndSet(null, ip) }
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)
        val ip = found.get() ?: return "car not found on ${subnets.joinToString { "$it.x" }} (is the car on this hotspot/Wi-Fi?)"
        prefs.lastIp = ip
        ProbeLog.i("Found the car at $ip")
        return verdict(postTo(ip, body), ip, t0) ?: "found the car at $ip but sending failed"
    }

    private fun localSubnets(): List<String> {
        val skip = listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "dummy", "r_rmnet", "ipsec", "clat")
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && skip.none { s -> it.name.startsWith(s) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .map { it.hostAddress!!.substringBeforeLast('.') }.distinct()
    }

    // ---- Bluetooth: RFCOMM to each paired device, the one that worked last time first.

    @SuppressLint("MissingPermission")
    private fun bt(body: String, progress: (String) -> Unit): String {
        val t0 = SystemClock.elapsedRealtime()
        @Suppress("DEPRECATION") val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "no Bluetooth on this phone"
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return "Bluetooth permission not granted (open Send to Car once)"
        if (!adapter.isEnabled) return "Bluetooth is off"
        val devices = adapter.bondedDevices.orEmpty().sortedByDescending { it.address == prefs.lastBt }
        if (devices.isEmpty()) return "no paired devices"
        adapter.cancelDiscovery()
        for (d in devices) {
            progress("trying ${d.name}…")
            try {
                d.createInsecureRfcommSocketToServiceRecord(Wire.BT_UUID).use { s ->
                    s.connect()
                    s.outputStream.write((body + "\n").toByteArray()); s.outputStream.flush()
                    val reply = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).readLine()
                    prefs.lastBt = d.address
                    return verdict(reply?.trim(), d.name ?: d.address, t0) ?: "no reply from ${d.name}"
                }
            } catch (e: Exception) { /* not the car, or the car isn't listening: try the next one */ }
        }
        return "car not reachable (tried ${devices.size} paired devices)"
    }

    // ---- Internet relay (ntfy.sh): publish, then wait for the car's acknowledgement.

    private fun relay(m: Msg, body: String, progress: (String) -> Unit): String {
        val t0 = SystemClock.elapsedRealtime()
        val code = prefs.code
        val r = try { Http.post(Wire.topicUrl(code), body) } catch (e: Exception) { return "no internet (${e.javaClass.simpleName})" }
        if (r.code !in 200..299) return "relay error ${r.code}"
        progress("on the relay, waiting for the car…")
        repeat(10) {
            Thread.sleep(2000)
            try {
                val acks = Http.get(Wire.topicUrl(code, "-ack/json?poll=1&since=10m")).body
                if (acks.contains(m.id)) return "DELIVERED via internet in ${since(t0)}"
            } catch (e: Exception) { }
        }
        return "waiting on the relay (car offline?). The car picks it up when it next connects, up to 12 h."
    }

    companion object {
        const val WIFI = "Wi-Fi"
        const val BT = "Bluetooth"
        const val NET = "Internet"
    }
}

/** Diagnostics report: shown on screen, as QR pages, or sent to the phone over the relay. */
object Report {
    fun build(ctx: Context): String = buildString {
        val prefs = Prefs(ctx)
        appendLine("=== Send to Car ${BuildConfig.VERSION_NAME} (${prefs.role}) ===")
        status(ctx).forEach { appendLine(it) }
        appendLine("auto-go=${prefs.autoGo} countdown=${prefs.countdown}")
        appendLine("=== Inbox ===")
        prefs.inbox.take(5).forEach { appendLine("${it.place?.label()} sent ${java.util.Date(it.sentAt)}") }
        appendLine("=== Log ===")
        ProbeLog.snapshot().takeLast(120).forEach { appendLine(it) }
    }

    @SuppressLint("MissingPermission")
    fun status(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        out += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.DISPLAY}"
        out += "Waze: ${Waze.installed(ctx)}"
        val ips = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { i ->
                i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${i.name}=${it.hostAddress}" }
            }
        } catch (e: Exception) { emptyList() }
        out += "IPv4: ${ips.joinToString().ifBlank { "none" }}"
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION") val g = wm.dhcpInfo?.gateway ?: 0
            out += "Wi-Fi ${if (wm.isWifiEnabled) "on" else "off"}, gateway ${if (g == 0) "none" else "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"}"
        } catch (e: Exception) { out += "Wi-Fi: $e" }
        try {
            @Suppress("DEPRECATION") val a = BluetoothAdapter.getDefaultAdapter()
            out += if (a == null) "Bluetooth: no adapter" else
                "Bluetooth: ${if (a.isEnabled) "on" else "off"}, name \"${a.name}\", paired: ${a.bondedDevices?.joinToString { it.name ?: it.address } ?: "?"}"
        } catch (e: Exception) { out += "Bluetooth: $e" }
        if (Prefs(ctx).role == Prefs.CAR) {
            out += "Service: ${if (CarService.running) "running since ${java.util.Date(CarService.startedAt)}" else "NOT running"}"
            out += "Wi-Fi server: ${CarService.wifiState}"
            out += "Bluetooth server: ${CarService.btState}"
            out += "Internet relay: ${CarService.relayState}"
            out += "Pop-up: ${Inbox.lastShown}"
        }
        return out
    }

    /** ~900 chars a page keeps each QR easy to scan off a 720p screen. */
    fun qrPages(ctx: Context): List<String> {
        val chunks = build(ctx).filter { it.code in 9..126 || it == '\n' }.chunked(900)
        return chunks.mapIndexed { i, c -> "SendToCar ${i + 1}/${chunks.size}\n$c" }
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    /** Car → phone over the relay, split to stay under ntfy's 4 KB message limit. */
    fun sendToPhone(ctx: Context): String {
        val code = Prefs(ctx).carCode
        val stamp = System.currentTimeMillis()
        val parts = build(ctx).chunked(3500)
        parts.forEachIndexed { i, p ->
            val r = Http.post(Wire.topicUrl(code, "-report"), "R$stamp ${i + 1}/${parts.size}\n$p")
            if (r.code !in 200..299) return "relay error ${r.code}"
        }
        return "sent ${parts.size} part(s). On the phone: Get car report."
    }

    fun fetchFromCar(code: String): String? {
        val lines = Http.get(Wire.topicUrl(code, "-report/json?poll=1&since=1h")).body.lines().filter { it.isNotBlank() }
        val parts = lines.mapNotNull { l ->
            try { JSONObject(l).takeIf { it.optString("event") == "message" }?.optString("message") } catch (e: Exception) { null }
        }.mapNotNull { msg ->
            val head = msg.substringBefore('\n')
            val m = Regex("""R(\d+) (\d+)/(\d+)""").find(head) ?: return@mapNotNull null
            Triple(m.groupValues[1].toLong(), m.groupValues[2].toInt(), msg.substringAfter('\n'))
        }
        val latest = parts.maxOfOrNull { it.first } ?: return null
        return parts.filter { it.first == latest }.sortedBy { it.second }.joinToString("") { it.third }
    }
}
