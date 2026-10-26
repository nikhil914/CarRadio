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
import kotlin.math.abs

/**
 * Right-hand panel: group bar (playlists), actions, station grid.
 * Only the grid is lazy; it recomposes per station (stable keys), not on every RDS tick.
 */
@Composable
fun StationPanel(band: Band, freqKhz: Int, powered: Boolean) {
    val data by StationStore.data.collectAsState()
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
        SmallButton("◀ STN", Modifier.weight(1f)) { RadioController.stationStep(false) }
        SmallButton("+ SAVE", Modifier.weight(1f), enabled = powered) { RadioController.saveCurrentStation(data.selectedGroup) }
        SmallButton(when (data.sort) { SortMode.POSITION -> "ORDER"; SortMode.FREQUENCY -> "FREQ"; SortMode.NAME -> "A–Z" }, Modifier.weight(1f)) {
            StationStore.update { it.copy(sort = SortMode.entries[(it.sort.ordinal + 1) % SortMode.entries.size]) }
        }
        SmallButton("STN ▶", Modifier.weight(1f)) { RadioController.stationStep(true) }
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
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(visible, key = { it.id }, contentType = { 0 }) { st ->
                StationButton(st, active = st.band == band && abs(st.freqKhz - freqKhz) < StationLogic.SAME_KHZ,
                    onClick = { RadioController.playStation(st) }, onLong = { editing = st })
            }
        }
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

@Composable
private fun StationButton(st: Station, active: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.height(68.dp).background(bg, RoundedCornerShape(10.dp)).combinedClickableCompat(onClick = onClick, onLongClick = onLong).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.label(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (st.verified) MaterialTheme.colorScheme.onSurface else Grey)
            Text((if (st.favourite) "★ " else "") + (if (st.name.isNotBlank()) Freq.format(st.band, st.freqKhz) else Freq.unit(st.band)) +
                (if (!st.verified) " · unconfirmed" else ""), fontSize = 12.sp, color = Grey, maxLines = 1)
        }
    }
}

@Composable
private fun EditStationDialog(st: Station, groups: List<Group>, onClose: () -> Unit) {
    var name by remember { mutableStateOf(st.name) }
    var freqText by remember { mutableStateOf(Freq.format(st.band, st.freqKhz)) }
    var fav by remember { mutableStateOf(st.favourite) }
    var inGroups by remember { mutableStateOf(st.groups) }
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
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                val k = parsed!!
                StationStore.update { d -> d.copy(stations = d.stations.map {
                    if (it.id == st.id) it.copy(name = name.trim(), freqKhz = k, band = Freq.bandOf(k), favourite = fav, groups = inGroups,
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
