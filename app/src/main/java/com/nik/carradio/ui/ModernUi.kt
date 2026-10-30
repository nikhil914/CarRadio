package com.nik.carradio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.DriverSide
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Pty
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.RadioState
import com.nik.carradio.core.Station
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.core.UiConfig

/**
 * Modern clean: what a driver needs in a glance, nothing else.
 *  top bar  – band pill, status only while something happens, mute · tuner · stations · ⋯ · settings
 *  hero     – logo, name, huge frequency (slides up/down when it changes), one line of radio text
 *  pad      – 88 dp controls, 16 dp apart, on the driver's side (Settings → Driver position)
 *  rail     – favourites, snapping; tap = play, long-press = edit, + = save what is playing
 * Enter frequency, Scan and the band switch live behind ⋯ (stationary use).
 */
@Composable
internal fun ModernRadioScreen(onOpenDiagnostics: () -> Unit, onFullscreen: () -> Unit, onStations: () -> Unit, onSettings: () -> Unit) {
    val s by RadioController.stateFlow.collectAsState()
    val probing by RadioController.probing.collectAsState()
    val ui by UiConfig.settings.collectAsState()
    var tools by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf(false) }
    var tuner by remember { mutableStateOf(false) }
    val external = s.onlineStation == null && (s.caps.contains(Cap.EXTERNAL_UI) || s.caps.contains(Cap.STREAM))

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val wide = maxWidth > 600.dp && maxWidth > maxHeight
        val short = maxHeight < 420.dp
        Column(Modifier.fillMaxSize()) {
            ModernTopBar(s, probing, onTuner = { tuner = true }, onTools = { tools = true }, onStations = onStations, onSettings = onSettings)
            UpdateBanner()
            s.error?.let { ErrorCard(it, onOpenDiagnostics) }
            when {
                external -> {
                    Hero(s, Modifier.weight(1f), ui.showLogos, onFullscreen)
                    SimpleControls(s)
                }
                wide -> {
                    val btn = if (short) 76.dp else 88.dp
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.driverSide == DriverSide.LEFT) {
                            ControlPad(s, btn); Spacer(Modifier.width(24.dp)); Hero(s, Modifier.weight(1f), ui.showLogos, onFullscreen)
                        } else {
                            Hero(s, Modifier.weight(1f), ui.showLogos, onFullscreen); Spacer(Modifier.width(24.dp)); ControlPad(s, btn)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PresetRail(s, if (short) 64.dp else 76.dp)
                }
                else -> {
                    Hero(s, Modifier.weight(1f), ui.showLogos, onFullscreen)
                    ControlRow(s, 88.dp)
                    Spacer(Modifier.height(16.dp))
                    PresetRail(s, 76.dp)
                }
            }
        }
    }
    if (tools) ToolsDialog(s, onClose = { tools = false }, onEnter = { tools = false; entry = true }, onTuner = { tools = false; tuner = true },
        onFullscreen = { tools = false; onFullscreen() })
    if (entry) FrequencyDialog(s.band) { khz -> entry = false; if (khz != null) RadioController.tune(khz) }
    if (tuner) TunerDialog { tuner = false }
}

@Composable
private fun ModernTopBar(s: RadioState, probing: Boolean, onTuner: () -> Unit, onTools: () -> Unit, onStations: () -> Unit, onSettings: () -> Unit) {
    val tuneable = s.caps.contains(Cap.TUNE)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        when {
            s.onlineStation != null -> PillButton("Online", selected = true) { RadioController.backToFm() }
            tuneable && s.caps.contains(Cap.BAND_AM) ->
                PillButton(s.band.name, selected = true) { RadioController.setBand(if (s.band == Band.FM) Band.AM else Band.FM) }
            tuneable -> PillButton(s.band.name, selected = true, enabled = false) {}
        }
        Spacer(Modifier.width(12.dp))
        // Status only while something is going on; a calm bar otherwise.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (s.busy || probing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(if (probing) "Checking radio methods…" else s.busyText, color = Grey, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (!s.powered) Text("Radio off", color = Grey, fontSize = 16.sp)
        }
        if (s.caps.contains(Cap.MUTE)) BarIcon(if (s.muted) RadioIcons.VolumeOff else RadioIcons.VolumeUp, if (s.muted) "Unmute" else "Mute", active = s.muted) {
            RadioController.setMute(!s.muted)
        }
        if (RadioController.activeBackend?.tunerOptions?.isNotEmpty() == true) BarIcon(RadioIcons.Tune, "Tuner", onClick = onTuner)
        BarIcon(RadioIcons.Stations, "Stations", onClick = onStations)
        BarIcon(RadioIcons.More, "More", onClick = onTools)
        BarIcon(RadioIcons.Settings, "Settings", onClick = onSettings)
    }
    val scan by RadioController.scanProgress.collectAsState()
    scan?.let { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p, color = Amber, fontSize = 16.sp, modifier = Modifier.weight(1f))
            PillButton("Stop scan") { RadioController.cancelScan() }
        }
    }
}

/** 64 dp touch box around a 52 dp visible chip: the target is larger than what is drawn. */
@Composable
internal fun BarIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.padding(start = 8.dp).size(64.dp).combinedClickableCompat(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).background(if (active) c.primary.copy(alpha = .22f) else c.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = if (active) c.primary else c.onSurface, modifier = Modifier.size(28.dp))
        }
    }
}

/** Logo + name, huge frequency, one scrolling line of radio text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(s: RadioState, modifier: Modifier, showLogo: Boolean, onFullscreen: () -> Unit) {
    val data by StationStore.data.collectAsState()
    val station = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val freqSize = when { maxHeight < 220.dp -> 64.sp; maxHeight < 280.dp -> 80.sp; maxWidth < 420.dp -> 80.sp; else -> 104.sp }
        val logoSize = if (maxHeight < 260.dp) 64.dp else 88.dp
        Column(Modifier.fillMaxWidth()) {
            val name = s.stationName.ifBlank { station?.name.orEmpty() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showLogo) {
                    Box(Modifier.combinedClickableCompat(onClick = onFullscreen)) {
                        StationLogo(station ?: Station(0, s.band, s.freqKhz, name = s.stationName), logoSize)
                    }
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { if (s.powered) "Tuned" else "Radio off" }, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = if (name.isBlank()) Grey else c.onSurface)
                    QuietFlags(s)
                }
            }
            FrequencyText(s.band, s.freqKhz, freqSize, if (s.powered) c.primary else Grey, onFullscreen)
            Text(s.radioText.ifBlank { " " }, fontSize = 20.sp, color = c.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
        }
    }
}

/** Frequency that slides up when tuning up and down when tuning down. Double-tap = full screen. */
@Composable
internal fun FrequencyText(band: Band, khz: Int, size: TextUnit, color: androidx.compose.ui.graphics.Color, onDoubleTap: () -> Unit,
                           center: Boolean = false) {
    AnimatedContent(targetState = band to khz, label = "freq", modifier = Modifier.combinedClickableCompat(onClick = {}, onDoubleClick = onDoubleTap),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
        transitionSpec = {
            val up = targetState.second > initialState.second
            (slideInVertically(tween(180)) { h -> if (up) h / 3 else -h / 3 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(180)) { h -> if (up) -h / 3 else h / 3 } + fadeOut(tween(120)))
        }) { (b, k) ->
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Freq.format(b, k), fontSize = size, fontWeight = FontWeight.Light, color = color, lineHeight = size, maxLines = 1)
            Text(" " + Freq.unit(b), fontSize = 22.sp, color = Grey, modifier = Modifier.padding(bottom = 14.dp))
        }
    }
}

/** Only what helps while driving: programme type, stereo, traffic, and signal when the tuner reports it. */
@Composable
private fun QuietFlags(s: RadioState) {
    val parts = buildList {
        Pty.name(s.pty).takeIf { it.isNotBlank() }?.let { add(it) }
        if (s.stereo == true) add("Stereo")
        if (s.ta == true && s.opts[com.nik.carradio.core.TunerOpt.TA] != null) add("TRAFFIC")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), fontSize = 16.sp, color = Grey, maxLines = 1)
        s.signal?.let { Spacer(Modifier.width(12.dp)); SignalBars(it) }
    }
}

/** Four bars from the tuner's RSSI (dBµV-like scale), no numbers. */
@Composable
private fun SignalBars(rssi: Int) {
    val bars = when { rssi < 15 -> 1; rssi < 30 -> 2; rssi < 45 -> 3; else -> 4 }
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..4).forEach { i ->
            Box(Modifier.width(5.dp).height((5 + i * 4).dp).background(if (i <= bars) c.primary else Grey.copy(alpha = .3f), RoundedCornerShape(1.dp)))
        }
    }
}

/**
 * Landscape pad: station ◀ · on/off · station ▶ over seek ◀◀ · ★ · seek ▶▶. Hold a seek key to
 * step one channel instead.
 */
@Composable
private fun ControlPad(s: RadioState, btn: Dp) {
    val tuneable = s.caps.contains(Cap.TUNE) || s.onlineStation != null
    val tune = s.caps.contains(Cap.TUNE)
    val seek = s.caps.contains(Cap.SEEK)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(btn)) {
            ControlButton(RadioIcons.Previous, null, Modifier.width(btn), enabled = tuneable, onClick = { RadioController.stationStep(false) })
            ControlButton(if (s.powered) RadioIcons.Power else RadioIcons.Play, null, Modifier.width(btn), primary = true, enabled = s.backendId != null,
                onClick = { RadioController.powerToggle(); Unit })
            ControlButton(RadioIcons.Next, null, Modifier.width(btn), enabled = tuneable, onClick = { RadioController.stationStep(true) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(btn)) {
            ControlButton(RadioIcons.Rewind, null, Modifier.width(btn), enabled = tune,
                onClick = { if (seek) RadioController.seek(false) else RadioController.step(false) }, onLong = { RadioController.step(false) })
            FavouriteButton(s, Modifier.width(btn))
            ControlButton(RadioIcons.Forward, null, Modifier.width(btn), enabled = tune,
                onClick = { if (seek) RadioController.seek(true) else RadioController.step(true) }, onLong = { RadioController.step(true) })
        }
    }
}

/** Portrait / narrow: one row of five. */
@Composable
private fun ControlRow(s: RadioState, height: Dp) {
    val tuneable = s.caps.contains(Cap.TUNE) || s.onlineStation != null
    val seek = s.caps.contains(Cap.SEEK)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().height(height)) {
        ControlButton(RadioIcons.Rewind, null, Modifier.weight(1f), enabled = s.caps.contains(Cap.TUNE),
            onClick = { if (seek) RadioController.seek(false) else RadioController.step(false) }, onLong = { RadioController.step(false) })
        ControlButton(RadioIcons.Previous, null, Modifier.weight(1f), enabled = tuneable, onClick = { RadioController.stationStep(false) })
        ControlButton(if (s.powered) RadioIcons.Power else RadioIcons.Play, null, Modifier.weight(1.2f), primary = true, enabled = s.backendId != null,
            onClick = { RadioController.powerToggle(); Unit })
        ControlButton(RadioIcons.Next, null, Modifier.weight(1f), enabled = tuneable, onClick = { RadioController.stationStep(true) })
        ControlButton(RadioIcons.Forward, null, Modifier.weight(1f), enabled = s.caps.contains(Cap.TUNE),
            onClick = { if (seek) RadioController.seek(true) else RadioController.step(true) }, onLong = { RadioController.step(true) })
    }
}

/** ★ filled when the playing station is a favourite; tap toggles (saving the station first if needed). */
@Composable
private fun FavouriteButton(s: RadioState, modifier: Modifier) {
    val data by StationStore.data.collectAsState()
    val st = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    val fav = st?.favourite == true
    ControlButton(if (fav) RadioIcons.Star else RadioIcons.StarOutline, null, modifier, enabled = s.powered || st != null,
        onClick = { toggleFavourite(st) })
}

internal fun toggleFavourite(st: Station?) {
    if (st == null) { RadioController.saveCurrentStation(StationLogic.FAVOURITES); return }
    StationStore.update { d -> d.copy(stations = d.stations.map { if (it.id == st.id) it.copy(favourite = !it.favourite) else it }) }
}

/** Favourites rail: snaps to whole tiles; falls back to the current group while nothing is starred. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRail(s: RadioState, height: Dp) {
    val data by StationStore.data.collectAsState()
    val favs = remember(data) { StationLogic.visible(data, StationLogic.FAVOURITES) }
    val list = remember(data, favs) { favs.ifEmpty { StationLogic.visible(data).take(20) } }
    var editing by remember { mutableStateOf<Station?>(null) }
    val state = rememberLazyListState()
    val active = list.indexOfFirst { it.owns(s.band, s.freqKhz) }
    LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem(maxOf(0, active - 2)) }
    val c = MaterialTheme.colorScheme
    LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().height(height)) {
        items(list, key = { it.id }) { st ->
            val on = st.owns(s.band, s.freqKhz)
            Column(Modifier.width(136.dp).fillMaxHeight()
                .background(if (on) c.primary.copy(alpha = .22f) else c.surfaceVariant, RoundedCornerShape(16.dp))
                .then(if (on) Modifier.border(3.dp, c.primary, RoundedCornerShape(16.dp)) else Modifier)
                .combinedClickableCompat(onClick = { RadioController.playStation(st) }, onLongClick = { editing = st })
                .padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                Text(st.label(), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) c.primary else c.onSurface)
                Text(Freq.format(st.band, st.freqKhz) + " " + Freq.unit(st.band), fontSize = 14.sp, color = Grey, maxLines = 1)
            }
        }
        item(key = "+save") {
            Box(Modifier.width(96.dp).fillMaxHeight().border(1.dp, Grey.copy(alpha = .35f), RoundedCornerShape(16.dp))
                .combinedClickableCompat(onClick = { if (s.powered) RadioController.saveCurrentStation(StationLogic.FAVOURITES) }),
                contentAlignment = Alignment.Center) {
                Text("+ Save", textAlign = TextAlign.Center, color = if (s.powered) c.primary else Grey, fontSize = 16.sp)
            }
        }
    }
    editing?.let { st -> EditStationDialog(st, data.groups) { editing = null } }
}

/** ⋯ : everything a driver should only use while parked. */
@Composable
private fun ToolsDialog(s: RadioState, onClose: () -> Unit, onEnter: () -> Unit, onTuner: () -> Unit, onFullscreen: () -> Unit) {
    val data by StationStore.data.collectAsState()
    val current = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    val tuneable = s.caps.contains(Cap.TUNE)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("More") },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tuneable) ToolButton("Enter a frequency", onEnter)
                if (tuneable && s.caps.contains(Cap.SEEK)) ToolButton("Scan for stations") { onClose(); RadioController.scanStations() }
                if (tuneable && s.caps.contains(Cap.BAND_AM)) ToolButton(if (s.band == Band.FM) "Switch to AM" else "Switch to FM") {
                    onClose(); RadioController.setBand(if (s.band == Band.FM) Band.AM else Band.FM)
                }
                if (RadioController.activeBackend?.tunerOptions?.isNotEmpty() == true) ToolButton("Tuner settings (stereo, LOC/DX, AF, TA)", onTuner)
                if (s.onlineStation != null) ToolButton("Back to FM") { onClose(); RadioController.backToFm() }
                else if (tuneable && current?.streamUrl?.isNotBlank() == true) ToolButton("Listen online") { onClose(); RadioController.listenOnline(current) }
                ToolButton("Full screen", onFullscreen)
            }
        },
    )
}

@Composable
private fun ToolButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

// ============================================================================ Night cruise

/**
 * Night cruise: pure black, one tint, no fills (nothing bright in a dark cabin). Name,
 * frequency, one dim line of radio text and three outlined keys; Stations and Settings sit dim
 * in the top corners.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CruiseRadioScreen(onFullscreen: () -> Unit, onStations: () -> Unit, onSettings: () -> Unit) {
    val s by RadioController.stateFlow.collectAsState()
    val data by StationStore.data.collectAsState()
    val station = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    val c = MaterialTheme.colorScheme
    val tuneable = s.caps.contains(Cap.TUNE) || s.onlineStation != null
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(16.dp)) {
        val freqSize = when { maxHeight < 380.dp -> 88.sp; maxHeight < 520.dp -> 112.sp; else -> 136.sp }
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            CruiseIcon(RadioIcons.Stations, "Stations", onStations)
            Spacer(Modifier.weight(1f))
            val scan by RadioController.scanProgress.collectAsState()
            Text(scan ?: if (s.busy) s.busyText else "", color = c.onSurfaceVariant, fontSize = 16.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            CruiseIcon(RadioIcons.Settings, "Settings", onSettings)
        }
        Column(Modifier.fillMaxSize().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val name = s.stationName.ifBlank { station?.name.orEmpty() }
            Text(name.ifBlank { if (s.powered) " " else "Radio off" }, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = c.primary.copy(alpha = .85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FrequencyText(s.band, s.freqKhz, freqSize, if (s.powered) c.primary else c.onSurfaceVariant, onFullscreen, center = true)
            Text(s.radioText.ifBlank { " " }, fontSize = 20.sp, color = c.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(.8f).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val seek = s.caps.contains(Cap.SEEK)
                CruiseKey(RadioIcons.Previous, tuneable, onClick = { RadioController.stationStep(false) },
                    onLong = { if (seek) RadioController.seek(false) else RadioController.step(false) })
                CruiseKey(if (s.powered) RadioIcons.Power else RadioIcons.Play, s.backendId != null, onClick = { RadioController.powerToggle(); Unit })
                CruiseKey(RadioIcons.Next, tuneable, onClick = { RadioController.stationStep(true) },
                    onLong = { if (seek) RadioController.seek(true) else RadioController.step(true) })
            }
        }
    }
}

/** Outlined, never filled: a lit block of colour glares at night. Hold = seek. */
@Composable
private fun CruiseKey(icon: ImageVector, enabled: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val tint = if (enabled) c.primary else c.onSurfaceVariant.copy(alpha = .5f)
    Box(Modifier.width(132.dp).height(88.dp).border(2.dp, c.onSurfaceVariant, RoundedCornerShape(20.dp))
        .then(if (enabled) Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLong) else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun CruiseIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(64.dp).combinedClickableCompat(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
    }
}
