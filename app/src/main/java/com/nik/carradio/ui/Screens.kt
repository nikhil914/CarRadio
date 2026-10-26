package com.nik.carradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.RadioState
import com.nik.carradio.core.UiError

private val Green = Color(0xFF66BB6A)
private val Amber = Color(0xFFFFCA28)
private val Red = Color(0xFFEF5350)
private val Grey = Color(0xFF90A4AE)

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Radio", "Diagnostics", "Log", "Settings").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 18.sp) })
                }
            }
            when (tab) {
                0 -> RadioScreen(onOpenDiagnostics = { tab = 1 })
                1 -> DiagnosticsScreen()
                2 -> LogScreen()
                else -> SettingsScreen()
            }
        }
    }
}

// ============================================================================ Radio

@Composable
fun RadioScreen(onOpenDiagnostics: () -> Unit) {
    val s by RadioController.stateFlow.collectAsState()
    val probing by RadioController.probing.collectAsState()
    var showEntry by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val wide = maxWidth > 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.4f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    MainPanel(s, probing, onOpenDiagnostics) { showEntry = true }
                }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) { Presets(s.freqKhz, s.status) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MainPanel(s, probing, onOpenDiagnostics) { showEntry = true }
                Spacer(Modifier.height(16.dp))
                Presets(s.freqKhz, s.status)
            }
        }
    }
    if (showEntry) FrequencyDialog(s.band) { khz -> showEntry = false; if (khz != null) RadioController.tune(khz) }
}

@Composable
private fun ColumnScope.MainPanel(s: RadioState, probing: Boolean, onOpenDiagnostics: () -> Unit, onEnter: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Method: ", color = Grey)
        Text(s.backendName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.busy || probing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (probing) "Checking radio methods…" else s.busyText, color = Grey, fontSize = 13.sp)
        }
    }
    Text(s.status, color = Grey, fontSize = 13.sp, maxLines = 2)
    s.error?.let { ErrorCard(it, onOpenDiagnostics) }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Text(Freq.format(s.band, s.freqKhz), fontSize = 96.sp, fontWeight = FontWeight.Bold,
            color = if (s.powered) MaterialTheme.colorScheme.primary else Grey,
            modifier = Modifier.combinedClickableCompat(onClick = onEnter))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.padding(bottom = 18.dp)) {
            Text(Freq.unit(s.band), fontSize = 22.sp)
            Text(s.band.name + (s.stereo?.let { if (it) " · ST" else " · MONO" } ?: "") + (s.signal?.let { " · sig $it" } ?: ""), color = Grey)
        }
    }
    if (s.stationName.isNotBlank()) Text(s.stationName, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    if (s.radioText.isNotBlank()) Text(s.radioText, color = Grey, maxLines = 2)
    if (s.scanning) Text("Scanning…", color = Amber)
    Spacer(Modifier.height(12.dp))

    val tuneable = s.caps.contains(Cap.TUNE)
    if (s.caps.contains(Cap.EXTERNAL_UI)) {
        BigButton("OPEN RADIO", Modifier.fillMaxWidth()) { RadioController.powerOn() }
        Text("This method hands over to the unit's own radio app.", color = Grey, fontSize = 13.sp)
    } else if (s.caps.contains(Cap.STREAM)) {
        BigButton(if (s.powered) "STOP STREAM" else "PLAY STREAM", Modifier.fillMaxWidth()) { RadioController.powerToggle() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton("◀◀\nSEEK", Modifier.weight(1f), enabled = s.caps.contains(Cap.SEEK)) { RadioController.seek(false) }
            BigButton("◀\nSTEP", Modifier.weight(1f), enabled = tuneable) { RadioController.step(false) }
            BigButton(if (s.powered) "⏻\nOFF" else "⏻\nON", Modifier.weight(1.2f), primary = true, enabled = s.backendId != null) { RadioController.powerToggle() }
            BigButton("▶\nSTEP", Modifier.weight(1f), enabled = tuneable) { RadioController.step(true) }
            BigButton("▶▶\nSEEK", Modifier.weight(1f), enabled = s.caps.contains(Cap.SEEK)) { RadioController.seek(true) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SmallButton("FM", Modifier.weight(1f), selected = s.band == Band.FM, enabled = tuneable) { RadioController.setBand(Band.FM) }
            SmallButton("AM", Modifier.weight(1f), selected = s.band == Band.AM, enabled = s.caps.contains(Cap.BAND_AM)) { RadioController.setBand(Band.AM) }
            SmallButton("SCAN", Modifier.weight(1f), enabled = s.caps.contains(Cap.SCAN)) { RadioController.scan() }
            SmallButton("ENTER", Modifier.weight(1f), enabled = tuneable) { onEnter() }
            SmallButton(if (s.muted) "UNMUTE" else "MUTE", Modifier.weight(1f), enabled = s.caps.contains(Cap.MUTE)) { RadioController.setMute(!s.muted) }
        }
    }
}

@Composable
private fun ErrorCard(e: UiError, onOpenDiagnostics: () -> Unit) {
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
private fun Presets(freqKhz: Int, status: String) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    Text("Presets  (tap = play, long-press = save current)", color = Grey, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    val values = remember(tick, status) { prefs.presets() }
    values.chunked(3).forEachIndexed { row, chunk ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            chunk.forEachIndexed { col, khz ->
                val slot = row * 3 + col
                val active = khz > 0 && khz == freqKhz
                Box(
                    Modifier.weight(1f).height(64.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .combinedClickableCompat(onClick = { RadioController.recallPreset(slot) }, onLongClick = { RadioController.savePreset(slot); tick++ }),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${slot + 1}", color = Grey, fontSize = 11.sp)
                        Text(if (khz > 0) Freq.format(Freq.bandOf(khz), khz) else "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencyDialog(band: Band, onDone: (Int?) -> Unit) {
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
                    FlowRowCompat {
                        TextButton(onClick = { RadioController.select(b.id) }) { Text("Use this") }
                        TextButton(onClick = { RadioController.testBackend(b.id) }) { Text("Test start") }
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

@Composable
fun SettingsScreen() {
    val prefs = RadioController.prefs
    var fmStep by remember { mutableIntStateOf(prefs.fmStepKhz) }
    var amStep by remember { mutableIntStateOf(prefs.amStepKhz) }
    var auto by remember { mutableStateOf(prefs.autoStart) }
    var url by remember { mutableStateOf(prefs.streamUrl) }
    var repo by remember { mutableStateOf(prefs.githubRepo) }
    var token by remember { mutableStateOf(prefs.githubToken) }
    val crashed = remember { mutableStateOf(prefs.crashedBackends()) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FM step", fontWeight = FontWeight.Bold)
        FlowRowCompat { listOf(50, 100, 200).forEach { v -> FilterChip(selected = fmStep == v, onClick = { fmStep = v; prefs.fmStepKhz = v }, label = { Text("$v kHz") }) } }
        Text("AM step", fontWeight = FontWeight.Bold)
        FlowRowCompat { listOf(9, 10).forEach { v -> FilterChip(selected = amStep == v, onClick = { amStep = v; prefs.amStepKhz = v }, label = { Text("$v kHz") }) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start radio automatically when the app opens", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; prefs.autoStart = it })
        }
        Text("Internet radio stream URL (fallback method)", fontWeight = FontWeight.Bold)
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("http(s)://…") })
        Button(onClick = { prefs.streamUrl = url; AppLog.i("Stream URL saved"); RadioController.probeAll() }) { Text("Save URL") }
        Text("GitHub report upload", fontWeight = FontWeight.Bold)
        Text("Paste a fine-grained token with \"Issues: Read and write\" on the repo below. It stays on this unit only.", color = Grey, fontSize = 13.sp)
        OutlinedTextField(repo, { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Repository (owner/name)") })
        OutlinedTextField(token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Token") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(onClick = { prefs.githubRepo = repo; prefs.githubToken = token; AppLog.i("GitHub upload settings saved (token ${if (token.isBlank()) "cleared" else "set"})") }) { Text("Save GitHub settings") }
        Text("Methods disabled after a crash: " + crashed.value.ifEmpty { setOf("none") }.joinToString(), color = Grey)
        OutlinedButton(enabled = crashed.value.isNotEmpty(), onClick = { RadioController.reenableCrashed(); crashed.value = emptySet() }) { Text("Re-enable crashed methods") }
        Text("Version ${BuildConfig.VERSION_NAME} · Android ${DeviceInfo.realAndroid()} (reported: ${android.os.Build.VERSION.RELEASE})", color = Grey, fontSize = 12.sp)
    }
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
private fun SmallButton(text: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else MaterialTheme.colorScheme.surfaceVariant, contentColor = Color.White)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
