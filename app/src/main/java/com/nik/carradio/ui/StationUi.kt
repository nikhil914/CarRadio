package com.nik.carradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.Band
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Group
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.SortMode
import com.nik.carradio.core.Station
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.core.StationLayout
import com.nik.carradio.core.UiConfig
import com.nik.carradio.core.UiSettings
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Right-hand panel: group bar (playlists), actions, station grid.
 * Only the grid is lazy; it recomposes per station (stable keys), not on every RDS tick.
 */
@Composable
fun StationPanel(band: Band, freqKhz: Int, powered: Boolean) {
    val data by StationStore.data.collectAsState()
    val ui by UiConfig.settings.collectAsState()
    val scan by RadioController.scanProgress.collectAsState()
    var editing by remember { mutableStateOf<Station?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var deleteGroup by remember { mutableStateOf<Group?>(null) }
    val visible = remember(data) { StationLogic.visible(data) }

    // Group bar: All, Favourites, the groups, Unsorted, + New.
    val chips = remember(data.groups) { StationLogic.VIRTUAL + data.groups + Group(StationLogic.UNSORTED, "Unsorted", true) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        items(chips, key = { it.id }) { g ->
            GroupChip(g.name, selected = data.selectedGroup == g.id,
                onClick = { StationStore.update { it.copy(selectedGroup = g.id) } },
                onLong = if (g.builtin) null else ({ deleteGroup = g }))
        }
        item(key = "+new") { GroupChip("+ Group", selected = false, onClick = { newGroup = true }, onLong = null) }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        SmallButton("+ SAVE", Modifier.weight(1f), enabled = powered) { RadioController.saveCurrentStation(data.selectedGroup) }
        SmallButton(when (data.sort) { SortMode.POSITION -> "ORDER"; SortMode.FREQUENCY -> "FREQ"; SortMode.NAME -> "A–Z" }, Modifier.weight(1f)) {
            StationStore.update { it.copy(sort = SortMode.entries[(it.sort.ordinal + 1) % SortMode.entries.size]) }
        }
        SmallButton("SCAN", Modifier.weight(1f), enabled = RadioController.activeBackend?.caps?.contains(com.nik.carradio.core.Cap.SEEK) == true) { RadioController.scanStations() }
    }
    scan?.let { p ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(p, color = Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { RadioController.cancelScan() }) { Text("Stop scan") }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (visible.isEmpty()) {
        Text(if (data.selectedGroup == StationLogic.ALL) "No stations yet. Press SCAN to find them." else
            "No stations in \"${StationLogic.groupName(data, data.selectedGroup)}\". Tune one and press + SAVE, or long-press a station to add it here.",
            color = Grey, fontSize = 14.sp)
    } else {
        StationsView(visible, ui, band, freqKhz, onClick = { RadioController.playStation(it) }, onLong = { editing = it })
    }

    editing?.let { st -> EditStationDialog(st, data.groups) { editing = null } }
    if (newGroup) NewGroupDialog { newGroup = false }
    deleteGroup?.let { g ->
        AlertDialog(onDismissRequest = { deleteGroup = null }, title = { Text("Delete group \"${g.name}\"?") },
            text = { Text("Stations stay in the list; they are only removed from this group.") },
            confirmButton = { TextButton(onClick = {
                StationStore.update { d -> d.copy(groups = d.groups.filter { it.id != g.id }, selectedGroup = if (d.selectedGroup == g.id) StationLogic.ALL else d.selectedGroup,
                    stations = d.stations.map { it.copy(groups = it.groups - g.id) }) }
                deleteGroup = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("Cancel") } })
    }
}

/** Big-enough touch target (44 dp) for a moving car; long-press only on user groups. */
@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.height(44.dp).background(bg, RoundedCornerShape(22.dp)).combinedClickableCompat(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Grid, list or carousel of the visible stations. Each is lazy with stable keys. */
@Composable
fun StationsView(visible: List<Station>, ui: UiSettings, band: Band, freqKhz: Int, onClick: (Station) -> Unit, onLong: (Station) -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    fun isActive(st: Station) = st.owns(band, freqKhz)
    fun dbl(st: Station): (() -> Unit)? = if (st.afKhz.isEmpty()) null else ({ RadioController.cycleAf(st) })
    when (ui.layout) {
        StationLayout.GRID -> LazyVerticalGrid(columns = GridCells.Fixed(ui.gridColumns), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
            items(visible, key = { it.id }, contentType = { 0 }) { st ->
                StationButton(st, isActive(st), ui.showLogos, compact = ui.gridColumns >= 5, onClick = { onClick(st) }, onLong = { onLong(st) }, onDouble = dbl(st))
            }
        }
        StationLayout.LIST -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
            items(visible, key = { it.id }, contentType = { 1 }) { st -> StationRow(st, isActive(st), ui.showLogos, { onClick(st) }, { onLong(st) }, dbl(st)) }
        }
        StationLayout.CAROUSEL -> {
            val state = rememberLazyListState()
            val activeIndex = visible.indexOfFirst { isActive(it) }
            // Keep the playing station in view (wheel keys change it while you are not looking).
            LaunchedEffect(activeIndex) { if (activeIndex >= 0) state.animateScrollToItem(activeIndex) }
            LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp), flingBehavior = rememberSnapFlingBehavior(state), modifier = modifier) {
                items(visible, key = { it.id }, contentType = { 2 }) { st -> StationCard(st, isActive(st), ui.showLogos, { onClick(st) }, { onLong(st) }, dbl(st)) }
            }
        }
    }
}

@Composable
private fun StationButton(st: Station, active: Boolean, logos: Boolean, compact: Boolean, onClick: () -> Unit, onLong: () -> Unit, onDouble: (() -> Unit)?) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.height(72.dp).background(bg, RoundedCornerShape(10.dp)).combinedClickableCompat(onClick = onClick, onLongClick = onLong, onDoubleClick = onDouble).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (logos && !compact) { StationLogo(st, 44.dp); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f), horizontalAlignment = if (logos && !compact) Alignment.Start else Alignment.CenterHorizontally) {
            StationTexts(st, if (compact) 15 else 17)
        }
    }
}

@Composable
private fun StationRow(st: Station, active: Boolean, logos: Boolean, onClick: () -> Unit, onLong: () -> Unit, onDouble: (() -> Unit)?) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().height(64.dp).background(bg, RoundedCornerShape(10.dp)).combinedClickableCompat(onClick = onClick, onLongClick = onLong, onDoubleClick = onDouble).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (logos) { StationLogo(st, 44.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) { StationTexts(st, 18) }
        if (active) Text("▶", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
    }
}

@Composable
private fun StationCard(st: Station, active: Boolean, logos: Boolean, onClick: () -> Unit, onLong: () -> Unit, onDouble: (() -> Unit)?) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.width(168.dp).height(196.dp).background(bg, RoundedCornerShape(16.dp)).combinedClickableCompat(onClick = onClick, onLongClick = onLong, onDoubleClick = onDouble).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (logos) { StationLogo(st, 96.dp); Spacer(Modifier.height(10.dp)) }
        StationTexts(st, 18, center = true)
    }
}

@Composable
private fun StationTexts(st: Station, size: Int, center: Boolean = false) {
    Text(st.label(), fontSize = size.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (st.verified) MaterialTheme.colorScheme.onSurface else Grey,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null)
    Text((if (st.favourite) "★ " else "") + (if (st.name.isNotBlank()) Freq.format(st.band, st.freqKhz) else Freq.unit(st.band)) +
        (if (st.afKhz.isNotEmpty()) " · AF ${st.afKhz.size}" else "") +
        (if (!st.verified) " · unconfirmed" else ""), fontSize = (size - 5).sp, color = Grey, maxLines = 1)
}

@Composable
internal fun EditStationDialog(st: Station, groups: List<Group>, onClose: () -> Unit) {
    var name by remember { mutableStateOf(st.name) }
    var freqText by remember { mutableStateOf(Freq.format(st.band, st.freqKhz)) }
    var fav by remember { mutableStateOf(st.favourite) }
    var inGroups by remember { mutableStateOf(st.groups) }
    var afText by remember { mutableStateOf(st.afKhz.joinToString(", ") { Freq.format(Band.FM, it) }) }
    var stream by remember { mutableStateOf(st.streamUrl) }
    val lookupRunning by com.nik.carradio.core.StationLookup.running.collectAsState()
    val liveSt = StationStore.data.collectAsState().value.stations.firstOrNull { it.id == st.id }
    // A lookup for this station fills the field when it finishes.
    LaunchedEffect(liveSt?.streamUrl) { if (liveSt != null && liveSt.streamUrl.isNotBlank() && stream.isBlank()) stream = liveSt.streamUrl }
    val parsed = Freq.parseUser(freqText)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(freqText, { freqText = it }, singleLine = true, label = { Text("Frequency") }, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(fav, { fav = it }); Text("Favourite") }
                OutlinedTextField(afText, { afText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Alternative frequencies (e.g. 99.3, 101.1)") },
                    supportingText = { Text("Same station on other transmitters. Double-tap the station to switch between them.") })
                OutlinedTextField(stream, { stream = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Internet stream (http/https)") },
                    isError = stream.isNotBlank() && !stream.startsWith("http"),
                    supportingText = { Text("Lets you listen online when FM is weak: the \"Online\" button on the Radio screen.") })
                val ctx0 = LocalContext.current
                TextButton(enabled = !lookupRunning && name.isNotBlank(), onClick = {
                    StationStore.update { d -> d.copy(stations = d.stations.map { if (it.id == st.id) it.copy(name = name.trim(), streamUrl = "") else it }) }
                    stream = ""
                    com.nik.carradio.core.StationLookup.run(ctx0, only = st.id)
                }) { Text(if (lookupRunning) "Looking up…" else "Find logo and stream online") }
                Text("Groups", color = Grey, fontSize = 13.sp)
                FlowRowCompat {
                    groups.forEach { g ->
                        FilterChip(selected = g.id in inGroups, onClick = { inGroups = if (g.id in inGroups) inGroups - g.id else inGroups + g.id }, label = { Text(g.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { StationStore.update { StationLogic.move(it, st.id, -1) } }) { Text("◀ Move earlier") }
                    TextButton(onClick = { StationStore.update { StationLogic.move(it, st.id, +1) } }) { Text("Move later ▶") }
                }
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var logoMsg by remember { mutableStateOf("") }
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) scope.launch { logoMsg = if (LogoStore.importFor(ctx, st.id, uri)) "Logo saved" else "That file is not a readable image" }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationLogo(st, 44.dp)
                    TextButton(onClick = {
                        runCatching { pick.launch("image/*") }.onFailure { logoMsg = "No file picker on this unit – use Settings → Import logo folder" }
                    }) { Text("Choose logo…") }
                    TextButton(onClick = { LogoStore.remove(st.id); logoMsg = "Logo removed" }) { Text("Remove logo") }
                }
                if (logoMsg.isNotBlank()) Text(logoMsg, color = Grey, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                val k = parsed!!
                StationStore.update { d -> d.copy(stations = d.stations.map {
                    if (it.id == st.id) it.copy(name = name.trim(), freqKhz = k, band = Freq.bandOf(k), favourite = fav, groups = inGroups,
                        afKhz = StationLogic.parseAfList(afText).filter { a -> kotlin.math.abs(a - k) >= StationLogic.SAME_KHZ },
                        streamUrl = stream.takeIf { u -> u.startsWith("http") } ?: "",
                        verified = it.verified || k != st.freqKhz) else it }) }
                onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { StationStore.update { d -> d.copy(stations = d.stations.filter { it.id != st.id }) }; onClose() }) { Text("Delete", color = Red) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun NewGroupDialog(onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New group") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("e.g. Tamil, News, Kids") }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                StationStore.update { d ->
                    val id = StationLogic.newGroupId(name, d.groups)
                    d.copy(groups = d.groups + Group(id, name.trim()), selectedGroup = id)
                }
                onClose()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
