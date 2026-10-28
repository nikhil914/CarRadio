package com.nik.carradio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.BuildConfig
import com.nik.carradio.core.FullTest
import com.nik.carradio.core.ReportSender
import com.nik.carradio.core.VendorUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostics card: one button runs every known radio command and records what works. */
@Composable
fun FullTestCard() {
    val st by FullTest.status.collectAsState()
    val lines by FullTest.lines.collectAsState()
    val vendor by VendorUpload.progress.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // The unit must not dim or sleep in the middle of the run.
    val view = LocalView.current
    DisposableEffect(st.running) { view.keepScreenOn = st.running; onDispose { view.keepScreenOn = false } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(FullTest.report().toByteArray()) } }; "Saved" }
                .getOrElse { "Save failed: ${it.message}" }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Full radio test", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Text("Tries every known way to control the tuner (FYT MCU and SoC-FM command formats, step, seek, presets, the stock radio app, " +
                "the FM chip library) and records exactly what worked. About 4 minutes. If a working tune format is found, Car Radio uses it at once.",
                color = Grey, fontSize = 14.sp)
            when {
                st.running -> {
                    Text("${st.phase}: ${st.step}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    LinearProgressIndicator(progress = { if (st.total > 0) st.index / st.total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Step ${st.index} of ${st.total} · don't touch the screen; the stock radio app opens for a moment.", color = Grey, fontSize = 13.sp)
                    OutlinedButton(onClick = { FullTest.stop() }) { Text("Stop") }
                }
                st.interruptedAt != null -> {
                    Text("The app closed during step \"${st.interruptedAt}\" last time. That step is skipped when you continue.", color = Amber, fontSize = 15.sp)
                    FlowRowCompat {
                        Button(onClick = { FullTest.start(resume = true) }) { Text("Continue the test") }
                        OutlinedButton(onClick = { confirm = true }) { Text("Start over") }
                    }
                }
                else -> FlowRowCompat {
                    Button(onClick = { confirm = true }) { Text(if (FullTest.hasResults()) "Run the full test again" else "Start full radio test", fontSize = 17.sp) }
                }
            }

            if (lines.isNotEmpty()) {
                if (!st.running) FlowRowCompat {
                    Button(enabled = !busy, onClick = {
                        busy = true; msg = "Uploading…"
                        scope.launch {
                            val r = ReportSender.fileLongIssue("Full radio test ${BuildConfig.VERSION_NAME}", FullTest.report())
                            msg = when (r) { is ReportSender.Upload.Ok -> "Uploaded: ${r.url}"; is ReportSender.Upload.Failed -> "${r.title}. ${r.hint}" }
                            busy = false
                        }
                    }) { Text("Upload report to GitHub") }
                    OutlinedButton(onClick = { shareText(ctx, FullTest.report()) }) { Text("Share") }
                    OutlinedButton(onClick = { copyText(ctx, FullTest.report()) }) { Text("Copy") }
                    OutlinedButton(onClick = { runCatching { save.launch("carradio-fulltest.txt") }.onFailure { msg = "No file picker on this unit – use Share or Upload" } }) { Text("Save to file…") }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 14.sp)
                val pass = lines.count { it.outcome == FullTest.Outcome.PASS }
                Text("$pass worked · ${lines.count { it.outcome == FullTest.Outcome.FAIL }} did not · ${lines.size} results", fontWeight = FontWeight.Medium)
                lines.takeLast(160).forEach { l ->
                    Text("${l.outcome} ${l.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = when (l.outcome) {
                        FullTest.Outcome.PASS -> Green; FullTest.Outcome.CRASH -> Red; FullTest.Outcome.INFO -> MaterialTheme.colorScheme.onSurface; else -> Grey
                    })
                }
            }

            if (!st.running) {
                Text("Optional: upload this unit's radio firmware files", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                Text("Copies com.syu.ms, the stock radio app and the FM library from this unit to a new \"device-dumps-…\" branch of your repo, so the exact " +
                    "firmware can be decoded. Needs the token to also have \"Contents: Read and write\". If your repo is public, the files are public too.",
                    color = Grey, fontSize = 13.sp)
                OutlinedButton(enabled = vendor == null || vendor!!.startsWith("Uploaded"), onClick = {
                    scope.launch {
                        val r = VendorUpload.upload(ctx)
                        msg = when (r) { is ReportSender.Upload.Ok -> "Firmware files uploaded: ${r.url}"; is ReportSender.Upload.Failed -> "${r.title}. ${r.hint}" }
                    }
                }) { Text("Upload firmware files") }
                vendor?.let { Text(it, color = Grey, fontSize = 13.sp) }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start the full radio test?") },
        text = { Text("Engine running (or ignition on), antenna connected, parked. About 4 minutes: the radio jumps between stations, " +
            "the stock radio app opens for a moment, and the controls are paused. Keep Car Radio on screen. " +
            "At the end it goes back to the station you are on now.") },
        confirmButton = { TextButton(onClick = { confirm = false; FullTest.start(resume = false) }) { Text("Start", fontSize = 17.sp) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

