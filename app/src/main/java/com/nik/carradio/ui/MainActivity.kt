package com.nik.carradio.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.RadioController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null && RadioController.probes.value.isEmpty()) {
            RadioController.probeAll(thenAutoStart = true)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF42A5F5), secondary = Color(0xFF26C6DA),
                background = Color(0xFF101418), surface = Color(0xFF1A2027),
                surfaceVariant = Color(0xFF232B33), error = Color(0xFFEF5350),
            )) { AppRoot() }
        }
    }
}

fun buildReport(ctx: Context): String = buildString {
    appendLine("=== Car Radio diagnostics ===")
    DeviceInfo.report(ctx).forEach { appendLine(it) }
    appendLine()
    appendLine("=== Probe results ===")
    RadioController.probes.value.forEach { (id, r) ->
        appendLine("[$id] ${r.status}: ${r.summary}")
        if (r.hint.isNotBlank()) appendLine("   hint: ${r.hint}")
        r.details.forEach { appendLine("   $it") }
    }
    val s = RadioController.state
    appendLine()
    appendLine("=== State ===")
    appendLine("backend=${s.backendId} powered=${s.powered} band=${s.band} freq=${s.freqKhz} status=${s.status}")
    s.error?.let { appendLine("error=${it.title} | ${it.hint} | ${it.technical.take(600)}") }
    appendLine()
    appendLine("=== Log (last 300 lines) ===")
    AppLog.lines.value.takeLast(300).forEach { appendLine(it) }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Car Radio report", text))
    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Car Radio report")
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { copyText(ctx, text) }
}
