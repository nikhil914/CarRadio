package com.nik.carradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.Band
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.TunerOpt

/** The Radio screen's TUNER button: the same panel as Settings → Tuner, in a dialog. */
@Composable
fun TunerDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done", fontSize = 18.sp) } },
        title = { Text("Tuner") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { TunerSettings() } },
    )
}

/**
 * Settings the tuner itself keeps (stereo, LOC/DX, AF, TA, RDS, sensitivity, region).
 * Each row shows what the hardware reports; a setting the method cannot change is not shown.
 */
@Composable
fun TunerSettings() {
    val s by RadioController.stateFlow.collectAsState()
    val b = RadioController.activeBackend
    if (b == null || (b.tunerOptions.isEmpty() && b.regions.isEmpty() && !b.hasSensitivity)) {
        Text("The current radio method (${s.backendName}) has no tuner settings. They are available with the FYT method.", color = Grey, fontSize = 14.sp)
        return
    }
    if (!s.powered) Text("Turn the radio on to read the tuner's current settings.", color = Amber, fontSize = 14.sp)

    for (opt in TunerOpt.entries) {
        if (opt !in b.tunerOptions) continue
        val on = s.opts[opt]
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(opt.label + if (on == null) "  (not reported)" else "", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(opt.hint, color = Grey, fontSize = 13.sp)
            }
            Switch(checked = on == true, enabled = !s.busy, onCheckedChange = { RadioController.setOption(opt, it) })
        }
    }

    if (b.hasSensitivity) {
        val auto = s.opts[TunerOpt.AUTO_SENS] == true
        val level = if (s.band == Band.FM) s.sensFm else s.sensAm
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Seek sensitivity (${s.band.name}): ${level ?: "not reported"}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(if (auto) "Turn automatic sensitivity off to change it." else "Higher finds weaker stations, but also noise.", color = Grey, fontSize = 13.sp)
            }
            OutlinedButton(enabled = !auto && !s.busy, onClick = { RadioController.sensitivity(false) }) { Text("−", fontSize = 22.sp) }
            OutlinedButton(enabled = !auto && !s.busy, onClick = { RadioController.sensitivity(true) }) { Text("+", fontSize = 22.sp) }
        }
    }

    if (b.regions.isNotEmpty()) {
        var confirm by remember { mutableStateOf<Int?>(null) }
        Text("Region" + (s.region?.let { ": ${b.regions.getOrElse(it) { "unknown ($it)" }}" } ?: " (not reported)"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        s.bandInfo?.let { Text(it.describe(), color = Grey, fontSize = 13.sp) }
        FlowRowCompat {
            b.regions.forEachIndexed { i, name ->
                FilterChip(selected = s.region == i, enabled = !s.busy, onClick = { if (s.region != i) confirm = i }, label = { Text(name) })
            }
        }
        Text("The region sets the band limits and step size. The UAE uses Europe's (FM 50 kHz steps, AM 9 kHz).", color = Grey, fontSize = 13.sp)
        confirm?.let { i ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Change region to ${b.regions[i]}?") },
                text = { Text("The tuner changes its band limits and steps, and it may clear the stock radio's presets. Car Radio's own station list is kept.") },
                confirmButton = { TextButton(onClick = { confirm = null; RadioController.setRegion(i) }) { Text("Change") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
        }
    }
}
