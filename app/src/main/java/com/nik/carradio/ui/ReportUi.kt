package com.nik.carradio.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.ReportSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SendMode { MENU, QR, UPLOADING, DONE }

/** "Send report" for a unit without USB: QR pages (offline), GitHub issue, share sheet, copy. */
@Composable
fun SendReportDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SendMode.MENU) }
    var result by remember { mutableStateOf<ReportSender.Upload?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(when (mode) { SendMode.QR -> "Scan with your phone"; SendMode.DONE -> "Upload result"; else -> "Send report" }) },
        text = {
            when (mode) {
                SendMode.MENU -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose how to get the report to your phone:", color = Color(0xFF90A4AE))
                    Button(onClick = { mode = SendMode.QR }, modifier = Modifier.fillMaxWidth()) { Text("Show QR codes (works offline)") }
                    Button(onClick = {
                        mode = SendMode.UPLOADING
                        scope.launch {
                            result = ReportSender.fileGitHubIssue(ctx)
                            mode = SendMode.DONE
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Upload as GitHub issue") }
                    OutlinedButton(onClick = { shareText(ctx, buildReport(ctx)) }, modifier = Modifier.fillMaxWidth()) { Text("Share (WhatsApp, Gmail, …)") }
                    OutlinedButton(onClick = { copyText(ctx, buildReport(ctx)) }, modifier = Modifier.fillMaxWidth()) { Text("Copy to clipboard") }
                }
                SendMode.QR -> QrPager()
                SendMode.UPLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("  Uploading to GitHub…")
                }
                SendMode.DONE -> when (val r = result) {
                    is ReportSender.Upload.Ok -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Issue created. Scan to open it on your phone:")
                        QrImage(r.url, 260)
                        Text(r.url, fontSize = 12.sp, color = Color(0xFF90A4AE))
                    }
                    is ReportSender.Upload.Failed -> Column {
                        Text(r.title, color = Color(0xFFEF5350))
                        Text(r.hint)
                    }
                    null -> Text("No result")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (mode == SendMode.QR || mode == SendMode.DONE) TextButton(onClick = { mode = SendMode.MENU }) { Text("Back") } },
    )
}

@Composable
private fun QrPager() {
    val ctx = LocalContext.current
    val pages by produceState<List<String>?>(null) { value = withContext(Dispatchers.Default) { ReportSender.qrPages(ctx) } }
    var page by remember { mutableIntStateOf(0) }
    val list = pages
    if (list == null) { CircularProgressIndicator(); return }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Page ${page + 1} of ${list.size} – scan each page and paste the text into the chat.", fontSize = 13.sp)
        QrImage(list[page], 300)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = page > 0, onClick = { page-- }) { Text("◀ Prev") }
            Button(enabled = page < list.size - 1, onClick = { page++ }) { Text("Next ▶") }
        }
    }
}

@Composable
private fun QrImage(text: String, sizeDp: Int) {
    var bmp by remember(text) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(text) {
        bmp = withContext(Dispatchers.Default) {
            runCatching { ReportSender.qrBitmap(text, 600).asImageBitmap() }
                .onFailure { AppLog.e("QR encode failed", it) }.getOrNull()
        }
    }
    Box(Modifier.padding(8.dp).size(sizeDp.dp).background(Color.White), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, contentDescription = "QR code", filterQuality = FilterQuality.None, modifier = Modifier.size(sizeDp.dp)) }
            ?: CircularProgressIndicator()
    }
}
