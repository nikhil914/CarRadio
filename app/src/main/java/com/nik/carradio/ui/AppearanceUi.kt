package com.nik.carradio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.StationLayout
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.core.UiConfig
import kotlinx.coroutines.launch

/** Layout, columns, theme, text size, logos and full screen — every change shows at once. */
@Composable
fun AppearanceSettings() {
    val ui by UiConfig.settings.collectAsState()
    val data by StationStore.data.collectAsState()
    val state by RadioController.stateFlow.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var importMsg by remember { mutableStateOf("") }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            importMsg = "Importing…"
            val (matched, total) = LogoStore.importFolder(ctx, uri)
            importMsg = if (total == 0) "No images in that folder" else "$matched of $total images matched a station"
        }
    }

    SectionHeader("Appearance")
    Text("Station layout", fontWeight = FontWeight.Bold)
    FlowRowCompat { StationLayout.entries.forEach { l -> FilterChip(selected = ui.layout == l, onClick = { UiConfig.update { it.copy(layout = l) } }, label = { Text(l.label) }) } }
    if (ui.layout == StationLayout.GRID) {
        Text("Grid columns", color = Grey, fontSize = 13.sp)
        FlowRowCompat { (2..6).forEach { c -> FilterChip(selected = ui.gridColumns == c, onClick = { UiConfig.update { it.copy(gridColumns = c) } }, label = { Text("$c") }) } }
    }
    Text("Preview", color = Grey, fontSize = 13.sp)
    val sample = remember(data) { StationLogic.visible(data, StationLogic.ALL).take(6) }
    Box(Modifier.fillMaxWidth().height(if (ui.layout == StationLayout.CAROUSEL) 210.dp else 160.dp)
        .border(1.dp, Grey.copy(alpha = .3f), RoundedCornerShape(12.dp)).padding(8.dp)) {
        StationsView(sample, ui, state.band, state.freqKhz, onClick = {}, onLong = {}, modifier = Modifier.fillMaxWidth())
    }

    Text("Theme", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        THEMES.forEach { t ->
            FilterChip(selected = ui.themeId == t.id, onClick = { UiConfig.update { it.copy(themeId = t.id) } },
                leadingIcon = { Box(Modifier.size(16.dp).background(t.accent, CircleShape)) }, label = { Text(t.name) })
        }
    }
    Text("Text size", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        listOf(0.85f to "Small", 1f to "Normal", 1.15f to "Large", 1.3f to "Extra large").forEach { (v, n) ->
            FilterChip(selected = ui.textScale == v, onClick = { UiConfig.update { it.copy(textScale = v) } }, label = { Text(n) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show station logos", modifier = Modifier.weight(1f))
        Switch(checked = ui.showLogos, onCheckedChange = { v -> UiConfig.update { it.copy(showLogos = v) } })
    }
    Text("Logos: long-press a station → Choose logo, or import a folder of images named after the stations (e.g. \"Virgin Radio.png\", \"104.4.png\"). Without a logo, the station's initials are drawn.",
        color = Grey, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { runCatching { folder.launch(null) }.onFailure { importMsg = "This unit has no folder picker" } }) { Text("Import logo folder…") }
        Spacer(Modifier.width(12.dp))
        if (importMsg.isNotBlank()) Text(importMsg, color = Grey, fontSize = 13.sp)
    }

    Text("Full screen", fontWeight = FontWeight.Bold)
    Text("Double-tap the frequency to open it. It can also open by itself after a while without a touch.", color = Grey, fontSize = 13.sp)
    FlowRowCompat {
        listOf(0 to "Only by double-tap", 15 to "After 15 s", 30 to "After 30 s", 60 to "After 60 s").forEach { (v, n) ->
            FilterChip(selected = ui.fullscreenAfterSec == v, onClick = { UiConfig.update { it.copy(fullscreenAfterSec = v) } }, label = { Text(n) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Clock in full screen", modifier = Modifier.weight(1f))
        Switch(checked = ui.fullscreenClock, onCheckedChange = { v -> UiConfig.update { it.copy(fullscreenClock = v) } })
    }
    Text("Speedometer", fontWeight = FontWeight.Bold)
    Text("GPS speed in km/h. The GPS runs only while a screen that shows it is open.", color = Grey, fontSize = 13.sp)
    // Which switch asked for the permission, so the result turns on the right one.
    var askFor by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) UiConfig.update { if (askFor == "main") it.copy(mainSpeed = true) else it.copy(fullscreenSpeed = true) }
        else importMsg = "Location permission denied – speedometer stays off"
    }
    fun speedSwitch(key: String, on: Boolean, set: (Boolean) -> Unit) {
        if (!on) set(false)
        else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) set(true)
        else { askFor = key; perm.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    SwitchRow("Show speed on the Radio screen", ui.mainSpeed) { on -> speedSwitch("main", on) { v -> UiConfig.update { it.copy(mainSpeed = v) } } }
    SwitchRow("Show speed in full screen", ui.fullscreenSpeed) { on -> speedSwitch("fs", on) { v -> UiConfig.update { it.copy(fullscreenSpeed = v) } } }
}

/** Night theme switching: off, always, by time, sunset–sunrise (Abu Dhabi), headlights (FYT). */
@Composable
fun DayNightSettings() {
    val ui by UiConfig.settings.collectAsState()
    val lights by com.nik.carradio.core.CarSignals.headlights.collectAsState()
    SectionHeader("Day and night")
    FlowRowCompat {
        com.nik.carradio.core.NightMode.entries.forEach { m ->
            FilterChip(selected = ui.nightMode == m, onClick = { UiConfig.update { it.copy(nightMode = m) } }, label = { Text(m.label) })
        }
    }
    val hint = when (ui.nightMode) {
        com.nik.carradio.core.NightMode.SUN -> {
            val c = java.util.Calendar.getInstance()
            val off = java.util.TimeZone.getDefault().getOffset(c.timeInMillis) / 60_000
            com.nik.carradio.core.DayNight.sunTimes(c.get(java.util.Calendar.DAY_OF_YEAR), com.nik.carradio.core.DayNight.DEFAULT_LAT, com.nik.carradio.core.DayNight.DEFAULT_LON, off)
                ?.let { (r, s) -> "Today in Abu Dhabi: night from ${hm(s)} to ${hm(r)}" } ?: ""
        }
        com.nik.carradio.core.NightMode.HEADLIGHTS -> when (lights) {
            null -> "This unit has not reported the headlights yet – using sunset to sunrise until it does"
            true -> "Headlights are ON"
            false -> "Headlights are OFF"
        }
        com.nik.carradio.core.NightMode.TIMED -> "Night from ${hm(ui.nightStart)} to ${hm(ui.nightEnd)}"
        else -> ""
    }
    if (hint.isNotBlank()) Text(hint, color = Grey, fontSize = 13.sp)
    if (ui.nightMode == com.nik.carradio.core.NightMode.TIMED) {
        Text("Night starts", fontSize = 13.sp, color = Grey)
        FlowRowCompat { listOf(17 * 60 + 30, 18 * 60, 18 * 60 + 30, 19 * 60, 20 * 60).forEach { v ->
            FilterChip(selected = ui.nightStart == v, onClick = { UiConfig.update { it.copy(nightStart = v) } }, label = { Text(hm(v)) }) } }
        Text("Night ends", fontSize = 13.sp, color = Grey)
        FlowRowCompat { listOf(5 * 60 + 30, 6 * 60, 6 * 60 + 30, 7 * 60).forEach { v ->
            FilterChip(selected = ui.nightEnd == v, onClick = { UiConfig.update { it.copy(nightEnd = v) } }, label = { Text(hm(v)) }) } }
    }
    if (ui.nightMode != com.nik.carradio.core.NightMode.OFF) {
        Text("Night theme", fontWeight = FontWeight.Bold)
        FlowRowCompat {
            THEMES.forEach { t ->
                FilterChip(selected = ui.nightThemeId == t.id, onClick = { UiConfig.update { it.copy(nightThemeId = t.id) } },
                    leadingIcon = { Box(Modifier.size(16.dp).background(t.accent, CircleShape)) }, label = { Text(t.name) })
            }
        }
    }
}

private fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

/** Back up / restore everything to one file, plus the plain JSON copy. */
@Composable
fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { msg = runCatching { com.nik.carradio.core.Backup.export(ctx, uri) }.getOrElse { "Backup failed: ${it.message}" } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching { com.nik.carradio.core.Backup.restore(ctx, uri) }.getOrElse { e ->
                if (e is com.nik.carradio.core.RadioException) "${e.title}. ${e.hint}" else "Restore failed: ${e.message}" }
        }
    }
    Text("Saves stations, groups, alternative frequencies, logos and settings in one file (not the GitHub token). " +
        "Keep it on an SD card or send it to your phone before a firmware update.", color = Grey, fontSize = 13.sp)
    FlowRowCompat {
        Button(onClick = { runCatching { save.launch("carradio-backup-${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}.json") }
            .onFailure { msg = "This unit has no file picker – use Copy station list instead" } }) { Text("Back up…") }
        OutlinedButton(onClick = { runCatching { open.launch(arrayOf("application/json", "text/plain", "*/*")) }
            .onFailure { msg = "This unit has no file picker" } }) { Text("Restore…") }
        OutlinedButton(onClick = { copyText(ctx, com.nik.carradio.core.StationStore.exportJson()) }) { Text("Copy station list") }
    }
    if (msg.isNotBlank()) Text(msg, fontSize = 13.sp)
}
