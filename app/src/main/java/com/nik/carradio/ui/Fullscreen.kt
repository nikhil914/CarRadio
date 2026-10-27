package com.nik.carradio.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Pty
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.core.UiConfig
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Glanceable full screen: big frequency and name, logo, clock. Left/right thirds step through
 * the station group (big targets while driving); the middle closes it.
 */
@Composable
fun FullscreenRadio(onExit: () -> Unit) {
    val s by RadioController.stateFlow.collectAsState()
    val data by StationStore.data.collectAsState()
    val ui by UiConfig.settings.collectAsState()
    val saved = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    HideSystemBars()
    val noRipple = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().clickable(noRipple, null) { RadioController.stationStep(false) }, contentAlignment = Alignment.CenterStart) {
                Text("◀", fontSize = 56.sp, color = Grey.copy(alpha = .5f), modifier = Modifier.padding(start = 24.dp))
            }
            Box(Modifier.weight(2f).fillMaxHeight().clickable(noRipple, null) { onExit() })
            Box(Modifier.weight(1f).fillMaxHeight().clickable(noRipple, null) { RadioController.stationStep(true) }, contentAlignment = Alignment.CenterEnd) {
                Text("▶", fontSize = 56.sp, color = Grey.copy(alpha = .5f), modifier = Modifier.padding(end = 24.dp))
            }
        }
        if (ui.fullscreenClock) Clock(Modifier.align(Alignment.TopEnd).padding(20.dp))
        if (ui.fullscreenSpeed) Speedometer(Modifier.align(Alignment.TopStart).padding(20.dp))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (saved != null && ui.showLogos) { StationLogo(saved, 140.dp); Spacer(Modifier.height(12.dp)) }
            val name = s.stationName.ifBlank { saved?.name ?: "" }
            if (name.isNotBlank()) Text(name, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Freq.format(s.band, s.freqKhz), fontSize = 120.sp, fontWeight = FontWeight.Bold,
                    color = if (s.powered) MaterialTheme.colorScheme.primary else Grey)
                Spacer(Modifier.width(8.dp))
                Text(Freq.unit(s.band), fontSize = 28.sp, modifier = Modifier.padding(bottom = 24.dp))
            }
            val line = listOf(Pty.name(s.pty), s.radioText).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text(line, fontSize = 22.sp, color = Grey, maxLines = 2, textAlign = TextAlign.Center)
        }
        Text("tap the middle to close · sides change station", fontSize = 12.sp, color = Grey.copy(alpha = .6f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

/** Updates once a minute, on the minute: no per-second recomposition. */
@Composable
private fun Clock(modifier: Modifier) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = fmt.format(Date())
        }
    }
    Text(now, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Grey, modifier = modifier)
}

/** GPS speed in km/h (null = no fix yet) and a status text. Location updates run only while composed. */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberGpsSpeed(): Pair<Int?, String> {
    val ctx = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var kmh by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf(if (granted) "GPS…" else "No location permission") }
    if (granted) DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { kmh = if (loc.hasSpeed()) Math.round(loc.speed * 3.6f) else null; status = "" }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { status = "GPS off" }
        }
        val ok = runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
            .onFailure { status = "No GPS on this unit" }.isSuccess
        onDispose { if (ok) runCatching { lm.removeUpdates(l) } }
    }
    return kmh to status
}

@Composable
private fun Speedometer(modifier: Modifier) {
    val (kmh, status) = rememberGpsSpeed()
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(kmh?.toString() ?: "--", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Grey)
        Spacer(Modifier.width(6.dp))
        Text(if (status.isNotEmpty() && kmh == null) status else "km/h", fontSize = 16.sp, color = Grey, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** Speed for the Radio screen: big number over "km/h", same weight as the frequency. */
@Composable
internal fun SpeedPanel(modifier: Modifier = Modifier) {
    val (kmh, status) = rememberGpsSpeed()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(kmh?.toString() ?: "--", fontSize = 64.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface, lineHeight = 64.sp)
        Text(if (status.isNotEmpty() && kmh == null) status else "km/h", fontSize = 15.sp, color = Grey, maxLines = 1)
    }
}

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(Unit) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
