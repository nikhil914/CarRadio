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

    Text("Appearance", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Grey.copy(alpha = .25f)))
}
