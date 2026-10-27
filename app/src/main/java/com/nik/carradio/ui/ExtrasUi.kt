package com.nik.carradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nik.carradio.core.SongHistory
import com.nik.carradio.core.StationLookup
import com.nik.carradio.core.UiConfig
import com.nik.carradio.service.FloatingPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Stations: fill in logos and internet streams from radio-browser.info. */
@Composable
fun LookupSection() {
    val ctx = LocalContext.current
    val running by StationLookup.running.collectAsState()
    val progress by StationLookup.progress.collectAsState()
    Text("Logos and internet streams", fontWeight = FontWeight.Bold)
    Text("Looks up each named station in radio-browser.info, a free community station directory, and adds its logo and internet stream " +
        "(for listening online when FM is weak). Needs internet. Logos you added yourself are kept.", color = Grey, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !running, onClick = { StationLookup.run(ctx) }) { Text("Find logos and streams online") }
        if (running) OutlinedButton(onClick = { StationLookup.cancel() }) { Text("Stop") }
    }
    progress?.let { Text(it, fontSize = 14.sp) }
}

/** Settings: floating player over navigation, song history. */
@Composable
fun ExtrasSettings() {
    val ctx = LocalContext.current
    val ui by UiConfig.settings.collectAsState()
    var allowed by remember { mutableStateOf(FloatingPlayer.canDraw(ctx)) }
    // Coming back from the "Display over other apps" page: re-check and finish switching on.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                allowed = FloatingPlayer.canDraw(ctx)
                if (allowed && pendingFloating) { pendingFloating = false; UiConfig.update { it.copy(floatingPlayer = true) } }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    SwitchRow("Floating mini player over other apps (navigation) while the radio plays", ui.floatingPlayer && allowed) { on ->
        if (!on) UiConfig.update { it.copy(floatingPlayer = false) }
        else if (FloatingPlayer.canDraw(ctx)) UiConfig.update { it.copy(floatingPlayer = true) }
        else { pendingFloating = true; runCatching { ctx.startActivity(FloatingPlayer.permissionIntent(ctx)) } }
    }
    Text(if (!allowed && ui.floatingPlayer) "Allow \"Display over other apps\" for Car Radio to use it."
        else "Drag it anywhere; tap the name to come back; × hides it until you open Car Radio again.", color = Grey, fontSize = 13.sp)
    SwitchRow("Keep a history of song titles (radio text)", ui.songHistory) { v -> UiConfig.update { it.copy(songHistory = v) } }
}

private var pendingFloating = false

/** Tap the radio text on the Radio screen: what played, newest first. */
@Composable
fun SongHistoryDialog(onClose: () -> Unit) {
    val list by SongHistory.entries.collectAsState()
    val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Heard recently") },
        text = {
            if (list.isEmpty()) Text("Nothing yet. Song titles appear here when a station sends them as radio text (RDS).", color = Grey)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(list.asReversed(), key = { it.time.toString() + it.text }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.width(96.dp)) {
                            Text(fmt.format(Date(e.time)), color = Grey, fontSize = 13.sp)
                            Text(e.station, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(e.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", fontSize = 17.sp) } },
        dismissButton = { if (list.isNotEmpty()) TextButton(onClick = { SongHistory.clear() }) { Text("Clear") } },
    )
}
