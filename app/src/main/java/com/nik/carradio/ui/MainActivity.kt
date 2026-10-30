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
import com.nik.carradio.core.ScreenStyle
import android.view.KeyEvent
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

    override fun onStart() { super.onStart(); com.nik.carradio.core.AppState.foreground = true; com.nik.carradio.service.FloatingPlayer.onAppShown() }
    override fun onStop() {
        super.onStop()
        com.nik.carradio.core.AppState.foreground = false
        if (com.nik.carradio.backend.fyt.StockRadio.waking) return   // the stock radio opens for a moment; not "user left"
        // Not when rotating / recreating, only when another app (navigation) comes to the front.
        if (!isChangingConfigurations) com.nik.carradio.service.FloatingPlayer.onAppHidden(this)
    }

    /**
     * Keys nothing on screen used. Media keys (wheel next/prev/play) are left alone on purpose:
     * Android hands them to the MediaSession in RadioService, which works in the background too.
     * Only channel keys, which never reach a media session, are mapped here. D-pad and rotary
     * keys move the focus (shown as a thick accent ring) and are handled by Compose.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> { RadioController.keyNextPrev(true); true }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> { RadioController.keyNextPrev(false); true }
        else -> super.onKeyDown(keyCode, event)
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
            val style = if (night && ui.cruiseAtNight) ScreenStyle.CRUISE else ui.screenStyle
            // Pure recomposition: the radio service and tuner are never touched by a theme change.
            val scheme = remember(themeId, style, ui.cruiseTint) {
                if (style == ScreenStyle.CRUISE) cruiseScheme(ui.cruiseTint) else themeById(themeId).scheme()
            }
            val density = LocalDensity.current
            // Text size setting scales fonts only, never touch targets.
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * ui.textScale), LocalScreenStyle provides style) {
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
