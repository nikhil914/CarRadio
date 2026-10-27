package com.nik.carradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.Updater

/** One-line banner on the Radio screen, only while an update is available or downloading. */
@Composable
fun UpdateBanner() {
    val st by Updater.state.collectAsState()
    val ctx = LocalContext.current
    val (text, action) = when (val s = st) {
        is Updater.State.Available -> "Car Radio ${s.release.version} is available" to ("Update" to { Updater.download(ctx, s.release) })
        is Updater.State.Downloading -> "Downloading ${s.release.version}… ${s.percent}%" to null
        is Updater.State.NeedsPermission -> "Allow Car Radio to install updates, then tap Install" to ("Install" to { Updater.install(ctx, s.file) })
        else -> return
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 15.sp)
            action?.let { (label, go) -> Button(onClick = go) { Text(label) } }
        }
    }
}

@Composable
fun UpdateSettings() {
    val st by Updater.state.collectAsState()
    val ctx = LocalContext.current
    val prefs = RadioController.prefs
    var auto by remember { mutableStateOf(prefs.autoCheckUpdates) }
    Text(Updater.describe(st), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    (st as? Updater.State.Downloading)?.let { LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = st !is Updater.State.Checking && st !is Updater.State.Downloading, onClick = { Updater.check() }) { Text("Check for updates") }
        when (val s = st) {
            is Updater.State.Available -> Button(onClick = { Updater.download(ctx, s.release) }) { Text("Download and install") }
            is Updater.State.Ready -> Button(onClick = { Updater.install(ctx, s.file) }) { Text("Install") }
            is Updater.State.NeedsPermission -> Button(onClick = { Updater.install(ctx, s.file) }) { Text("Install") }
            else -> {}
        }
    }
    SwitchRow("Check once a day when the unit is online", auto) { auto = it; prefs.autoCheckUpdates = it }
    Text("Updates come from the GitHub releases of ${prefs.githubRepo}. The first time, Android asks you to allow Car Radio to install apps. " +
        "Your stations and settings are kept.", color = Grey, fontSize = 13.sp)
}
