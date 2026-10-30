package com.nik.carradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.BuildConfig
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.CruiseTint
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.DriverSide
import com.nik.carradio.core.NextPrevKey
import com.nik.carradio.core.PlayKey
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.ScreenStyle
import com.nik.carradio.core.UiConfig

enum class SettingsSection(val label: String) {
    APPEARANCE("Appearance"), DAY_NIGHT("Day and night"), RADIO("Radio and tuner"), KEYS("Keys and steering wheel"),
    STATIONS("Stations and backup"), EXTRAS("Floating player and history"), UPDATES("Updates"), ADVANCED("Reports and advanced"),
}

/**
 * Settings: a section list on the left (64 dp rows, easy to hit), the chosen section on the
 * right. On a narrow screen the list becomes a row of chips on top.
 */
@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.APPEARANCE) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 600.dp) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(250.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(SettingsSection.entries) { s -> NavRow(s.label, s == section) { section = s } }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Grey.copy(alpha = .2f)))
                SectionContent(section, onOpen, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SettingsSection.entries) { s -> FilterChip(selected = s == section, onClick = { section = s }, label = { Text(s.label) }) }
                }
                SectionContent(section, onOpen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(64.dp).background(if (selected) c.primary.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(14.dp))
        .combinedClickableCompat(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = 18.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) c.primary else c.onSurface)
    }
}

@Composable
private fun SectionContent(section: SettingsSection, onOpen: (SettingsPage) -> Unit, modifier: Modifier) {
    // A fresh scroll position per section.
    key(section) {
        Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(section.label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            when (section) {
                SettingsSection.APPEARANCE -> { ScreenStyleSettings(); AppearanceSettings() }
                SettingsSection.DAY_NIGHT -> DayNightSettings()
                SettingsSection.RADIO -> RadioSettings()
                SettingsSection.KEYS -> KeySettings()
                SettingsSection.STATIONS -> StationSettings()
                SettingsSection.EXTRAS -> ExtrasSettings()
                SettingsSection.UPDATES -> UpdateSettings()
                SettingsSection.ADVANCED -> AdvancedSettings(onOpen)
            }
        }
    }
}

// ============================================================================ Appearance: screen style

/** Three style cards with a small live preview in the current colours; switching is instant. */
@Composable
private fun ScreenStyleSettings() {
    val ui by UiConfig.settings.collectAsState()
    Text("Radio screen", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        ScreenStyle.entries.forEach { st ->
            val selected = ui.screenStyle == st
            val c = MaterialTheme.colorScheme
            Column(Modifier.padding(bottom = 8.dp).width(220.dp)
                .border(if (selected) 3.dp else 1.dp, if (selected) c.primary else Grey.copy(alpha = .35f), RoundedCornerShape(16.dp))
                .combinedClickableCompat(onClick = { UiConfig.update { it.copy(screenStyle = st) } }).padding(10.dp)) {
                StylePreview(st, if (st == ScreenStyle.CRUISE) cruiseScheme(ui.cruiseTint) else themeById(ui.themeId).scheme())
                Spacer(Modifier.height(8.dp))
                Text(st.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (selected) c.primary else c.onSurface)
                Text(st.hint, fontSize = 13.sp, color = Grey, minLines = 3)
            }
        }
    }
    SwitchRow("Switch to Night cruise automatically at night (uses the Day and night setting)", ui.cruiseAtNight) { v -> UiConfig.update { it.copy(cruiseAtNight = v) } }
    Text("Night cruise colour", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        CruiseTint.entries.forEach { t ->
            FilterChip(selected = ui.cruiseTint == t, onClick = { UiConfig.update { it.copy(cruiseTint = t) } },
                leadingIcon = { Box(Modifier.size(16.dp).background(cruiseScheme(t).primary, CircleShape)) }, label = { Text(t.label) })
        }
    }
    Text("Driver position", fontWeight = FontWeight.Bold)
    Text("Modern clean puts its big controls on the driver's side. The UAE drives on the right, with the wheel on the left.", color = Grey, fontSize = 13.sp)
    FlowRowCompat {
        DriverSide.entries.forEach { d -> FilterChip(selected = ui.driverSide == d, onClick = { UiConfig.update { it.copy(driverSide = d) } }, label = { Text(d.label) }) }
    }
}

/** A 200×110 dp sketch of each layout, drawn from boxes (no bitmaps, no extra composition of the real screen). */
@Composable
private fun StylePreview(style: ScreenStyle, sc: ColorScheme) {
    val r = RoundedCornerShape(4.dp)
    Box(Modifier.width(200.dp).height(110.dp).background(sc.background, RoundedCornerShape(10.dp)).padding(8.dp)) {
        when (style) {
            ScreenStyle.MODERN -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(22.dp).height(10.dp).background(sc.primary.copy(alpha = .4f), r)); Spacer(Modifier.weight(1f))
                    repeat(4) { Box(Modifier.size(10.dp).background(sc.surfaceVariant, r)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(3) { i -> Box(Modifier.size(18.dp).background(if (row == 0 && i == 1) sc.primary else sc.surfaceVariant, r)) }
                        } }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(56.dp).height(6.dp).background(sc.onSurface.copy(alpha = .8f), r))
                        Box(Modifier.width(80.dp).height(16.dp).background(sc.primary, r))
                        Box(Modifier.width(70.dp).height(4.dp).background(Grey.copy(alpha = .6f), r))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { i -> Box(Modifier.width(30.dp).height(14.dp).background(if (i == 1) sc.primary.copy(alpha = .35f) else sc.surfaceVariant, r)) }
                }
            }
            ScreenStyle.CLASSIC -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(50.dp).height(5.dp).background(Grey.copy(alpha = .6f), r)); Spacer(Modifier.weight(1f))
                    repeat(5) { Box(Modifier.width(16.dp).height(8.dp).background(sc.surfaceVariant, r)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(sc.primary.copy(alpha = .5f), CircleShape))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(60.dp).height(5.dp).background(sc.onSurface.copy(alpha = .8f), r))
                        Box(Modifier.width(70.dp).height(12.dp).background(sc.primary, r))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(5) { Box(Modifier.width(10.dp).height(4.dp).background(sc.primary.copy(alpha = .4f), r)) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(5) { i -> Box(Modifier.width(34.dp).height(14.dp).background(if (i == 2) sc.primary else sc.surfaceVariant, r)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(6) { Box(Modifier.width(28.dp).height(12.dp).background(sc.surfaceVariant, r)) } }
            }
            ScreenStyle.CRUISE -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                Box(Modifier.width(50.dp).height(5.dp).background(sc.primary.copy(alpha = .8f), r))
                Box(Modifier.width(100.dp).height(22.dp).background(sc.primary, r))
                Box(Modifier.width(70.dp).height(4.dp).background(sc.onSurfaceVariant, r))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { Box(Modifier.width(30.dp).height(16.dp).border(1.dp, sc.onSurfaceVariant, r)) } }
            }
        }
    }
}

// ============================================================================ Radio and tuner

@Composable
private fun RadioSettings() {
    val prefs = RadioController.prefs
    var fmStep by remember { mutableIntStateOf(prefs.fmStepKhz) }
    var amStep by remember { mutableIntStateOf(prefs.amStepKhz) }
    var auto by remember { mutableStateOf(prefs.autoStart) }
    var resume by remember { mutableStateOf(prefs.resumeOnStart) }
    Text("FM step (the ‹ › step keys)", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        listOf(50 to "50 kHz · Europe fine", 100 to "100 kHz · Europe / UAE", 200 to "200 kHz · USA").forEach { (v, n) ->
            FilterChip(selected = fmStep == v, onClick = { fmStep = v; prefs.fmStepKhz = v }, label = { Text(n) })
        }
    }
    Text("AM step", fontWeight = FontWeight.Bold)
    FlowRowCompat {
        listOf(9 to "9 kHz · Europe / UAE", 10 to "10 kHz · USA").forEach { (v, n) ->
            FilterChip(selected = amStep == v, onClick = { amStep = v; prefs.amStepKhz = v }, label = { Text(n) })
        }
    }
    SwitchRow("Start radio automatically when the app opens", auto) { auto = it; prefs.autoStart = it }
    SwitchRow("Resume the radio when the unit starts or wakes, if it was playing", resume) { resume = it; prefs.resumeOnStart = it }
    if (com.nik.carradio.backend.fyt.StockRadio.socFm()) {
        var flash by remember { mutableStateOf(prefs.allowStockFlash) }
        SwitchRow("If needed, open the unit's own Radio app for a moment to start the FM chip", flash) { flash = it; prefs.allowStockFlash = it }
        Text("On this unit the built-in Radio app drives the FM chip, so Car Radio keeps it running in the background. " +
            "It first tries without showing it; this switch allows a short flash of its screen when that is not enough.", color = Grey, fontSize = 13.sp)
    }
    SectionHeader("Tuner: region, AF, TA, stereo, LOC/DX")
    TunerSettings()
}

// ============================================================================ Keys

@Composable
private fun KeySettings() {
    val prefs = RadioController.prefs
    var nextPrev by remember { mutableStateOf(prefs.nextPrevKey) }
    var play by remember { mutableStateOf(prefs.playKey) }
    var wheel by remember { mutableStateOf(prefs.wheelUsesStations) }
    Text("Next / previous keys", fontWeight = FontWeight.Bold)
    Text("Steering-wheel ⏭ ⏮, a launcher's next/previous and the channel keys.", color = Grey, fontSize = 13.sp)
    FlowRowCompat { NextPrevKey.entries.forEach { k -> FilterChip(selected = nextPrev == k, onClick = { nextPrev = k; prefs.nextPrevKey = k }, label = { Text(k.label) }) } }
    Text("Play / pause key", fontWeight = FontWeight.Bold)
    Text("Mute works only with methods that can mute; otherwise the key turns the radio on and off.", color = Grey, fontSize = 13.sp)
    FlowRowCompat { PlayKey.entries.forEach { k -> FilterChip(selected = play == k, onClick = { play = k; prefs.playKey = k }, label = { Text(k.label) }) } }
    SwitchRow("When the unit's own next/prev moves the tuner (FYT), follow my station group instead", wheel) { wheel = it; prefs.wheelUsesStations = it }
    Text("Rotary knob and D-pad: turn or press arrows to move between controls; the focused one gets a thick ring in the theme colour. " +
        "Press OK / the knob to use it. Mute and volume keys are handled by the unit itself.", color = Grey, fontSize = 13.sp)
    SectionHeader("Radio key")
    RadioKeySection()
}

// ============================================================================ Stations and backup

@Composable
private fun StationSettings() {
    val prefs = RadioController.prefs
    var url by remember { mutableStateOf(prefs.streamUrl) }
    LookupSection()
    SectionHeader("Backup")
    BackupSection()
    SectionHeader("Internet radio (fallback)")
    OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Stream URL http(s)://…") })
    Button(onClick = { prefs.streamUrl = url; AppLog.i("Stream URL saved"); RadioController.probeAll() }) { Text("Save URL") }
}

// ============================================================================ Reports and advanced

@Composable
private fun AdvancedSettings(onOpen: (SettingsPage) -> Unit) {
    val prefs = RadioController.prefs
    var repo by remember { mutableStateOf(prefs.githubRepo) }
    var token by remember { mutableStateOf(prefs.githubToken) }
    val crashed = remember { mutableStateOf(prefs.crashedBackends()) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = { onOpen(SettingsPage.DIAGNOSTICS) }, modifier = Modifier.height(64.dp)) { Text("Diagnostics and reports", fontSize = 16.sp) }
        OutlinedButton(onClick = { onOpen(SettingsPage.LOG) }, modifier = Modifier.height(64.dp)) { Text("Log", fontSize = 16.sp) }
    }
    SectionHeader("Reports")
    Text("GitHub upload: paste a fine-grained token with \"Issues: Read and write\" on the repo below (add \"Contents: Read and write\" only if you want to upload firmware files from Diagnostics). It stays on this unit only and is never put in backups.", color = Grey, fontSize = 13.sp)
    OutlinedTextField(repo, { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Repository (owner/name)") })
    OutlinedTextField(token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Token") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
    Button(onClick = { prefs.githubRepo = repo; prefs.githubToken = token; AppLog.i("GitHub upload settings saved (token ${if (token.isBlank()) "cleared" else "set"})") }) { Text("Save GitHub settings") }
    SectionHeader("Radio methods")
    Text("Methods disabled after a crash: " + crashed.value.ifEmpty { setOf("none") }.joinToString(), color = Grey)
    OutlinedButton(enabled = crashed.value.isNotEmpty(), onClick = { RadioController.reenableCrashed(); crashed.value = emptySet() }) { Text("Re-enable crashed methods") }
    Text("Version ${BuildConfig.VERSION_NAME} · Android ${DeviceInfo.realAndroid()} (reported: ${android.os.Build.VERSION.RELEASE})", color = Grey, fontSize = 12.sp)
}
