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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.UiConfig
import com.nik.carradio.core.CarSignals
import com.nik.carradio.core.DayNight
import com.nik.carradio.core.UiSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import com.nik.carradio.core.ReportSender

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        com.nik.carradio.core.Updater.onResume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null && RadioController.probes.value.isEmpty()) {
            RadioController.startup()
        }
        setContent {
            val ui by UiConfig.settings.collectAsState()
            val lights by CarSignals.headlights.collectAsState()
            val night = rememberNight(ui, lights)
            val themeId = if (night) ui.nightThemeId else ui.themeId
            val scheme = remember(themeId) { themeById(themeId).scheme() }
            val density = LocalDensity.current
            // Text size setting scales fonts only, never touch targets.
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * ui.textScale)) {
                MaterialTheme(colorScheme = scheme) { AppRoot() }
            }
        }
    }
}

/** Re-evaluated on each minute boundary and whenever settings or the headlights change. */
@Composable
fun rememberNight(ui: UiSettings, lights: Boolean?): Boolean {
    var night by remember { mutableStateOf(false) }
    LaunchedEffect(ui.nightMode, ui.nightStart, ui.nightEnd, lights) {
        while (true) {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val off = TimeZone.getDefault().getOffset(c.timeInMillis) / 60_000
            val sun = DayNight.sunTimes(c.get(Calendar.DAY_OF_YEAR), DayNight.DEFAULT_LAT, DayNight.DEFAULT_LON, off)
            night = DayNight.isNight(ui.nightMode, now, ui.nightStart, ui.nightEnd, sun, lights)
            delay(60_000 - System.currentTimeMillis() % 60_000 + 100)
        }
    }
    return night
}

fun buildReport(ctx: Context): String = ReportSender.build(ctx, full = true)

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
