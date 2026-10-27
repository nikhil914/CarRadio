package com.nik.carradio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.Freq
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.RadioState
import com.nik.carradio.core.Station
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore

/**
 * Radio tab (1.3). One calm screen, read at a glance from arm's length:
 *  top row   – method/status left, FM·AM, TUNER, SCAN, ENTER right
 *  centre    – logo, station name, big frequency, one scrolling line of radio text, chips
 *  controls  – seek ◀◀ · previous station · play/off · next station · seek ▶▶ (88 dp)
 *  bottom    – favourites strip: tap = play, long-press = edit, + = save what is playing
 * Sized for 1280×720 landscape (≈ 480 dp tall at 240 dpi); stacks on narrow screens.
 */
@Composable
fun RadioScreen(onOpenDiagnostics: () -> Unit, onFullscreen: () -> Unit) {
    val s by RadioController.stateFlow.collectAsState()
    val probing by RadioController.probing.collectAsState()
    var showEntry by remember { mutableStateOf(false) }
    var showTuner by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val short = maxHeight < 560.dp
        val roomy = maxWidth > 600.dp
        Column(Modifier.fillMaxSize()) {
            TopRow(s, probing, onEnter = { showEntry = true }, onTuner = { showTuner = true })
            UpdateBanner()
            s.error?.let { ErrorCard(it, onOpenDiagnostics) }
            if (s.caps.contains(Cap.EXTERNAL_UI) || s.caps.contains(Cap.STREAM)) {
                NowPlaying(s, Modifier.weight(1f), onFullscreen)
                SimpleControls(s)
            } else {
                NowPlaying(s, Modifier.weight(1f), onFullscreen)
                Controls(s, height = if (short) 76.dp else 92.dp, captions = roomy)
                Spacer(Modifier.height(10.dp))
                FavouritesStrip(s, tile = if (short) 72.dp else 92.dp)
            }
        }
    }
    if (showEntry) FrequencyDialog(s.band) { khz -> showEntry = false; if (khz != null) RadioController.tune(khz) }
    if (showTuner) TunerDialog { showTuner = false }
}

@Composable
private fun TopRow(s: RadioState, probing: Boolean, onEnter: () -> Unit, onTuner: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.busy || probing) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (probing) "Checking radio methods…" else if (s.busy) s.busyText else s.status,
                    color = Grey, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(s.backendName, color = Grey.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val tuneable = s.caps.contains(Cap.TUNE)
        if (tuneable) {
            if (s.caps.contains(Cap.BAND_AM)) {
                PillButton(if (s.band == Band.FM) "FM" else "AM", selected = true) { RadioController.setBand(if (s.band == Band.FM) Band.AM else Band.FM) }
            }
            if (RadioController.activeBackend?.tunerOptions?.isNotEmpty() == true) PillButton("Tuner") { onTuner() }
            if (s.caps.contains(Cap.SEEK)) PillButton("Scan") { RadioController.scanStations() }
            PillButton("Enter") { onEnter() }
            if (s.caps.contains(Cap.MUTE)) PillButton(if (s.muted) "Unmute" else "Mute", selected = s.muted) { RadioController.setMute(!s.muted) }
        }
    }
    val scan by RadioController.scanProgress.collectAsState()
    scan?.let { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p, color = Amber, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PillButton("Stop scan") { RadioController.cancelScan() }
        }
    }
}

/** 48 dp tall text pill: secondary actions stay quiet next to the big controls. */
@Composable
internal fun PillButton(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.padding(start = 8.dp).height(48.dp)
        .background(if (selected) c.primary.copy(alpha = .22f) else c.surfaceVariant, RoundedCornerShape(24.dp))
        .then(if (enabled) Modifier.combinedClickableCompat(onClick = onClick) else Modifier)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (!enabled) Grey else if (selected) c.primary else c.onSurface)
    }
}

/** Logo + name + frequency + radio text. Crossfades (150 ms) when the station changes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlaying(s: RadioState, modifier: Modifier, onFullscreen: () -> Unit) {
    val data by StationStore.data.collectAsState()
    val station = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    val accent = if (s.powered) MaterialTheme.colorScheme.primary else Grey
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val wide = maxWidth > 600.dp
        val logoSize: Dp = min(maxHeight - 24.dp, if (wide) 190.dp else 120.dp).coerceAtLeast(56.dp)
        // Name + frequency + radio text + chips must fit the height left between top row and controls.
        val freqSize = when { maxHeight < 210.dp -> 56.sp; maxHeight < 270.dp -> 68.sp; wide -> 92.sp; else -> 72.sp }
        AnimatedContent(targetState = Triple(station, s.band, s.freqKhz), label = "station", contentKey = { Triple(it.first?.id, it.second, it.third) },
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }) { (st, band, khz) ->
            val logoStation = st ?: Station(0, band, khz, name = s.stationName)
            if (!wide) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.combinedClickableCompat(onClick = onFullscreen)) { StationLogo(logoStation, 140.dp) }
                    Spacer(Modifier.height(20.dp))
                    NowPlayingText(s, st, band, khz, accent, freqSize, onFullscreen, center = true)
                }
                return@AnimatedContent
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.combinedClickableCompat(onClick = onFullscreen)) { StationLogo(logoStation, logoSize) }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) { NowPlayingText(s, st, band, khz, accent, freqSize, onFullscreen, center = false) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingText(s: RadioState, station: Station?, band: Band, khz: Int, accent: Color, freqSize: androidx.compose.ui.unit.TextUnit, onFullscreen: () -> Unit, center: Boolean) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        val name = s.stationName.ifBlank { station?.name.orEmpty() }
        Text(name.ifBlank { if (s.powered) "Tuned" else "Radio off" }, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = align,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (name.isBlank()) Grey else MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.combinedClickableCompat(onClick = {}, onDoubleClick = onFullscreen)) {
            Text(Freq.format(band, khz), fontSize = freqSize, fontWeight = FontWeight.Light, color = accent, lineHeight = freqSize)
            Text(" " + Freq.unit(band), fontSize = 22.sp, color = Grey, modifier = Modifier.padding(bottom = 14.dp))
        }
        Text(s.radioText.ifBlank { " " }, fontSize = 18.sp, color = Grey, maxLines = 1, textAlign = align,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
        RdsFlags(s)
    }
}

@Composable
private fun Controls(s: RadioState, height: Dp, captions: Boolean) {
    val tuneable = s.caps.contains(Cap.TUNE)
    val seek = s.caps.contains(Cap.SEEK)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(height)) {
        ControlButton(RadioIcons.Rewind, if (captions) "hold: step" else null, Modifier.weight(1f), enabled = seek || tuneable,
            onClick = { if (seek) RadioController.seek(false) else RadioController.step(false) }, onLong = { RadioController.step(false) })
        ControlButton(RadioIcons.Previous, if (captions) "station" else null, Modifier.weight(1.2f), enabled = tuneable, onClick = { RadioController.stationStep(false) })
        ControlButton(if (s.powered) RadioIcons.Power else RadioIcons.Play, if (captions) (if (s.powered) "radio off" else "radio on") else null,
            Modifier.weight(1.4f), primary = true, enabled = s.backendId != null, onClick = { RadioController.powerToggle(); Unit })
        ControlButton(RadioIcons.Next, if (captions) "station" else null, Modifier.weight(1.2f), enabled = tuneable, onClick = { RadioController.stationStep(true) })
        ControlButton(RadioIcons.Forward, if (captions) "hold: step" else null, Modifier.weight(1f), enabled = seek || tuneable,
            onClick = { if (seek) RadioController.seek(true) else RadioController.step(true) }, onLong = { RadioController.step(true) })
    }
}

@Composable
private fun SimpleControls(s: RadioState) {
    val label = when {
        s.caps.contains(Cap.EXTERNAL_UI) -> "Open radio"
        s.powered -> "Stop stream"
        else -> "Play stream"
    }
    ControlButton(if (s.powered) RadioIcons.Power else RadioIcons.Play, label, Modifier.fillMaxWidth().height(88.dp), primary = true, enabled = s.backendId != null,
        onClick = { if (s.caps.contains(Cap.EXTERNAL_UI)) RadioController.powerOn() else RadioController.powerToggle(); Unit })
    if (s.caps.contains(Cap.EXTERNAL_UI)) Text("This method hands over to the unit's own radio app.", color = Grey, fontSize = 13.sp)
}

@Composable
private fun ControlButton(icon: ImageVector, caption: String?, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true,
                          onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val bg = when { !enabled -> c.surfaceVariant.copy(alpha = .5f); primary -> c.primary; else -> c.surfaceVariant }
    val fg = when { !enabled -> Grey.copy(alpha = .5f); primary -> c.onPrimary; else -> c.onSurface }
    Column(modifier.fillMaxHeight().background(bg, RoundedCornerShape(18.dp))
        .then(if (enabled) Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLong) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = caption, tint = fg, modifier = Modifier.size(if (primary) 40.dp else 36.dp))
        if (caption != null) Text(caption, fontSize = 12.sp, color = fg.copy(alpha = .7f), maxLines = 1)
    }
}

/**
 * Favourites (★). Falls back to the current group while nothing is starred, so the strip is
 * never empty after a scan. Keeps the playing station in view.
 */
@Composable
private fun FavouritesStrip(s: RadioState, tile: Dp) {
    val data by StationStore.data.collectAsState()
    val favs = remember(data) { StationLogic.visible(data, StationLogic.FAVOURITES) }
    val list = remember(data, favs) { favs.ifEmpty { StationLogic.visible(data).take(20) } }
    var editing by remember { mutableStateOf<Station?>(null) }
    val state = rememberLazyListState()
    val active = list.indexOfFirst { it.owns(s.band, s.freqKhz) }
    LaunchedEffect(active) { if (active >= 0) state.animateScrollToItem(maxOf(0, active - 2)) }
    Column {
        if (favs.isEmpty() && list.isNotEmpty()) Text("Long-press a station → Favourite to pin it here", color = Grey, fontSize = 12.sp)
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(tile)) {
            items(list, key = { it.id }) { st ->
                FavouriteTile(st, st.owns(s.band, s.freqKhz), tile, onClick = { RadioController.playStation(st) }, onLong = { editing = st })
            }
            item(key = "+save") {
                Box(Modifier.size(tile).border(1.dp, Grey.copy(alpha = .35f), RoundedCornerShape(16.dp))
                    .combinedClickableCompat(onClick = { if (s.powered) RadioController.saveCurrentStation(StationLogic.FAVOURITES) }),
                    contentAlignment = Alignment.Center) {
                    Text("+ Save", textAlign = TextAlign.Center, color = if (s.powered) MaterialTheme.colorScheme.primary else Grey, fontSize = 15.sp)
                }
            }
        }
    }
    editing?.let { st -> EditStationDialog(st, data.groups) { editing = null } }
}

@Composable
private fun FavouriteTile(st: Station, active: Boolean, size: Dp, onClick: () -> Unit, onLong: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.width(size * 1.45f).fillMaxHeight()
        .background(if (active) c.primary.copy(alpha = .22f) else c.surfaceVariant, RoundedCornerShape(16.dp))
        .then(if (active) Modifier.border(2.dp, c.primary, RoundedCornerShape(16.dp)) else Modifier)
        .combinedClickableCompat(onClick = onClick, onLongClick = onLong).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        StationLogo(st, size * 0.5f)
        Spacer(Modifier.height(4.dp))
        Text(st.label(), fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = if (active) c.primary else c.onSurface)
    }
}

/** Slim player on top of the Stations tab: browse the list without losing the controls. */
@Composable
fun MiniPlayer() {
    val s by RadioController.stateFlow.collectAsState()
    val data by StationStore.data.collectAsState()
    val st = remember(data, s.band, s.freqKhz) { StationLogic.find(data, s.band, s.freqKhz) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(72.dp)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp)) {
        StationLogo(st ?: Station(0, s.band, s.freqKhz, name = s.stationName), 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.stationName.ifBlank { st?.name.orEmpty() }.ifBlank { Freq.format(s.band, s.freqKhz) + " " + Freq.unit(s.band) },
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Freq.format(s.band, s.freqKhz) + " " + Freq.unit(s.band) + (if (s.radioText.isNotBlank()) " · " + s.radioText else ""),
                fontSize = 14.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val c = MaterialTheme.colorScheme
        listOf<Pair<ImageVector, () -> Unit>>(RadioIcons.Previous to { RadioController.stationStep(false) },
            (if (s.powered) RadioIcons.Power else RadioIcons.Play) to { RadioController.powerToggle(); Unit }, RadioIcons.Next to { RadioController.stationStep(true) })
            .forEachIndexed { i, (ic, go) ->
                Box(Modifier.padding(start = 8.dp).size(56.dp).background(if (i == 1) c.primary else c.surfaceVariant, RoundedCornerShape(14.dp))
                    .combinedClickableCompat(onClick = go), contentAlignment = Alignment.Center) {
                    Icon(ic, contentDescription = null, tint = if (i == 1) c.onPrimary else c.onSurface, modifier = Modifier.size(28.dp))
                }
            }
    }
}

