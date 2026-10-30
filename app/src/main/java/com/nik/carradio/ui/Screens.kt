package com.nik.carradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.BuildConfig
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.DeviceInfo
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Prefs
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.Pty
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.RadioState
import com.nik.carradio.core.TunerOpt
import com.nik.carradio.core.UiError

internal val Green = Color(0xFF66BB6A)
internal val Amber = Color(0xFFFFCA28)
internal val Red = Color(0xFFEF5350)
internal val Grey = Color(0xFF90A4AE)

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Plain holder, not state: touches must not trigger recomposition.
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    val ui by com.nik.carradio.core.UiConfig.settings.collectAsState()
    val powered by remember { RadioController.stateFlow.map { it.powered }.distinctUntilChanged() }.collectAsState(false)
    // Idle timer: full screen after N s without a touch, only on the Radio tab while playing.
    LaunchedEffect(ui.fullscreenAfterSec, tab, powered, fullscreen) {
        if (ui.fullscreenAfterSec <= 0 || tab != 0 || !powered || fullscreen) return@LaunchedEffect
        lastTouch[0] = System.currentTimeMillis()
        while (true) {
            delay(1000)
            if (System.currentTimeMillis() - lastTouch[0] >= ui.fullscreenAfterSec * 1000L) { fullscreen = true; break }
        }
    }
    if (fullscreen) { FullscreenRadio { fullscreen = false; lastTouch[0] = System.currentTimeMillis() }; return }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) {
        // Watches touches without consuming them, to reset the idle timer.
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch[0] = System.currentTimeMillis() } }
    }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Modern clean and Night cruise carry their own Stations / Settings entries on the Radio screen.
            val ownBar = tab == 0 && LocalScreenStyle.current != com.nik.carradio.core.ScreenStyle.CLASSIC
            if (!ownBar) TabBar(tab) { tab = it; if (it == 2) page = SettingsPage.MAIN }
            when (tab) {
                0 -> RadioScreen(onOpenDiagnostics = { tab = 2; page = SettingsPage.DIAGNOSTICS }, onFullscreen = { fullscreen = true },
                    onStations = { tab = 1 }, onSettings = { tab = 2; page = SettingsPage.MAIN })
                1 -> StationsScreen()
                else -> when (page) {
                    SettingsPage.MAIN -> SettingsScreen(onOpen = { page = it })
                    SettingsPage.DIAGNOSTICS -> SubPage("Diagnostics", { page = SettingsPage.MAIN }) { DiagnosticsScreen() }
                    SettingsPage.LOG -> SubPage("Log", { page = SettingsPage.MAIN }) { LogScreen() }
                }
            }
        }
    }
}

enum class SettingsPage { MAIN, DIAGNOSTICS, LOG }

/** Three tabs, 56 dp tall, text only; the selected one carries the accent underline. */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).background(c.background).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Radio", "Stations", "Settings").forEachIndexed { i, t ->
            Column(Modifier.weight(1f).fillMaxHeight().combinedClickableCompat(onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(t, fontSize = 19.sp, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected == i) c.onSurface else Grey)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(36.dp).height(3.dp).background(if (selected == i) c.primary else Color.Transparent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Stations tab: mini player on top, then groups, actions and the full list/grid/carousel. */
@Composable
fun StationsScreen() {
    val s by RadioController.stateFlow.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MiniPlayer()
        Spacer(Modifier.height(10.dp))
        StationPanel(s.band, s.freqKhz, s.powered)
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text("‹ Settings", fontSize = 17.sp) }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

// ============================================================================ Radio (see RadioUi.kt)

@Composable
internal fun ErrorCard(e: UiError, onOpenDiagnostics: () -> Unit) {
    var expanded by remember(e) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1F22)), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(e.title, color = Red, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (e.hint.isNotBlank()) Text(e.hint, fontSize = 14.sp)
            if (expanded && e.technical.isNotBlank()) Text(e.technical, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Grey)
            FlowRowCompat {
                TextButton(onClick = { RadioController.tryNextMethod() }) { Text("Try next method") }
                TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                if (e.technical.isNotBlank()) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
                TextButton(onClick = { RadioController.dismissError() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
internal fun RdsFlags(s: RadioState) {
    val items = buildList {
        s.stereo?.let { add(if (it) "ST" to true else "MONO" to false) }
        Pty.name(s.pty).takeIf { it.isNotBlank() }?.let { add(it to true) }
        s.tp?.let { add("TP" to it) }
        // TA lit = traffic announcements wanted; "TRAFFIC" = one is on air now.
        (s.opts[TunerOpt.TA] ?: s.ta)?.let { add("TA" to it) }
        if (s.ta == true && s.opts[TunerOpt.TA] != null) add("TRAFFIC" to true)
        s.af?.let { add("AF" to it) }
        s.loc?.let { add(if (it) "LOC" to true else "DX" to true) }
        if (s.opts[TunerOpt.RDS] == false) add("RDS OFF" to false)
    }
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        items.forEach { (label, on) ->
            Box(Modifier.background((if (on) MaterialTheme.colorScheme.primary else Grey).copy(alpha = .18f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.primary else Grey)
            }
        }
    }
}

@Composable
internal fun FrequencyDialog(band: Band, onDone: (Int?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = Freq.parseUser(text)
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Enter frequency") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(if (band == Band.FM) "e.g. 104.1" else "e.g. 1152") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text(parsed?.let { "→ ${Freq.format(Freq.bandOf(it), it)} ${Freq.unit(Freq.bandOf(it))}" } ?: "FM 87.5–108 MHz or AM 522–1710 kHz", color = Grey)
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onDone(parsed) }) { Text("Tune") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

// ============================================================================ Diagnostics

@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val probes by RadioController.probes.collectAsState()
    val probing by RadioController.probing.collectAsState()
    val s by RadioController.stateFlow.collectAsState()
    // Device facts need PackageManager queries: compute once, off the main thread.
    val device by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { DeviceInfo.report(ctx) } }
    var sending by remember { mutableStateOf(false) }
    // The fast start probes only the last working method; complete the picture on first visit.
    LaunchedEffect(Unit) { if (RadioController.needsFullProbe()) RadioController.probeAll() }
    if (sending) SendReportDialog { sending = false }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FullTestCard() }
        item {
            FlowRowCompat {
                Button(onClick = { sending = true }) { Text("Send report") }
                OutlinedButton(onClick = { RadioController.probeAll() }, enabled = !probing) { Text(if (probing) "Checking…" else "Run checks again") }
                OutlinedButton(onClick = { RadioController.useAutomatic() }) { Text("Use automatic choice") }
            }
            Text("Active: ${s.backendName}" + (RadioController.prefs.preferredBackend?.let { " (pinned)" } ?: " (automatic)"), color = Grey)
        }
        items(RadioController.backends, key = { it.id }) { b ->
            val r = probes[b.id]
            var open by remember { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(r?.status)
                        Spacer(Modifier.width(8.dp))
                        Text(b.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (s.backendId == b.id) Text("ACTIVE", color = Green, fontSize = 12.sp)
                    }
                    Text(b.description, color = Grey, fontSize = 12.sp)
                    Text(r?.summary ?: if (probing) "checking…" else "not checked", fontSize = 15.sp)
                    if (!r?.hint.isNullOrBlank()) Text(r!!.hint, color = Amber, fontSize = 13.sp)
                    if (open) r?.details?.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Grey) }
                    if (b.id == "fyt") {
                        val learn by RadioController.learnResult.collectAsState()
                        learn?.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if ("MOVED" in it || "WORKS" in it) Green else Grey) }
                    }
                    FlowRowCompat {
                        TextButton(onClick = { RadioController.select(b.id) }) { Text("Use this") }
                        TextButton(onClick = { RadioController.testBackend(b.id) }) { Text("Test start") }
                        if (b.id == "fyt" && r?.status == ProbeStatus.AVAILABLE) TextButton(onClick = { RadioController.learnFytCommands() }) { Text("Learn tuner commands") }
                        if (!r?.details.isNullOrEmpty()) TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
                    }
                }
            }
        }
        item {
            Text("Device", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            device.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Grey) }
        }
    }
}

@Composable
private fun StatusChip(st: ProbeStatus?) {
    val (label, color) = when (st) {
        ProbeStatus.AVAILABLE -> "WORKS" to Green
        ProbeStatus.UNKNOWN -> "MAYBE" to Amber
        ProbeStatus.BLOCKED -> "BLOCKED" to Amber
        ProbeStatus.CRASHED_BEFORE -> "CRASHED" to Red
        ProbeStatus.UNAVAILABLE -> "NO" to Red
        null -> "…" to Grey
    }
    Box(Modifier.background(color.copy(alpha = .2f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ============================================================================ Log

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        FlowRowCompat {
            OutlinedButton(onClick = { copyText(ctx, AppLog.fullText()) }) { Text("Copy log") }
            OutlinedButton(onClick = { shareText(ctx, buildReport(ctx)) }) { Text("Share report") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(lines) { l ->
                Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = when { " E " in l -> Red; " W " in l -> Amber; " > " in l -> MaterialTheme.colorScheme.secondary; else -> Color(0xFFCFD8DC) })
            }
        }
    }
}

// ============================================================================ Settings

// SettingsScreen and its sections: see SettingsUi.kt

@Composable
internal fun SectionHeader(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
    Box(Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = .25f)))
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Whether the Radio-key proxy can replace com.syu.radio on this firmware, and how. */
@Composable
internal fun RadioKeySection() {
    val ctx = LocalContext.current
    val verdict by produceState("Checking…") { value = withContext(Dispatchers.IO) { DeviceInfo.proxyVerdict(ctx) } }
    Text(verdict, fontSize = 14.sp)
    Text("The proxy is a tiny separate app (CarRadio-proxy-*.apk on the GitHub release page) that installs as an update to the unit's own radio app, " +
        "so the Radio icon and the hardware Radio key open Car Radio. Android only accepts it if the key matches – the line above says which file to use. " +
        "To undo: Settings → Apps → Radio → Uninstall updates.", color = Grey, fontSize = 13.sp)
}

// ============================================================================ small widgets

@Composable
private fun BigButton(text: String, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(84.dp), shape = RoundedCornerShape(14.dp),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = Color.White)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun SmallButton(text: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else MaterialTheme.colorScheme.surfaceVariant, contentColor = Color.White)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * Clickable with a thick accent ring while it has key focus (D-pad, rotary knob). Compose gives
 * clickables focus only outside touch mode, so the ring never shows for finger taps.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onDoubleClick: (() -> Unit)? = null) =
    this.composed {
        var focused by remember { mutableStateOf(false) }
        val ring = MaterialTheme.colorScheme.primary
        Modifier.onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, ring, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
    }
