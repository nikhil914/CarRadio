package com.nik.carradio.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.widget.RemoteViews
import com.nik.carradio.R
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Pty
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

class WideWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.renderAll(ctx) }
    override fun onReceive(ctx: Context, intent: Intent) { super.onReceive(ctx, intent); Widgets.handle(ctx, intent) }
}

class CompactWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { Widgets.renderAll(ctx) }
    override fun onReceive(ctx: Context, intent: Intent) { super.onReceive(ctx, intent); Widgets.handle(ctx, intent) }
}

/**
 * Home-screen widgets in plain RemoteViews (no Compose, nothing running for them). They are
 * redrawn only when something visible changes, at most ~3×/s, and only if one is placed.
 */
object Widgets {
    private const val ACTION_PREV = "com.nik.carradio.widget.PREV"
    private const val ACTION_NEXT = "com.nik.carradio.widget.NEXT"
    private const val ACTION_POWER = "com.nik.carradio.widget.POWER"

    private data class Key(val khz: Int, val name: String, val text: String, val powered: Boolean, val logoId: Long?)

    @OptIn(FlowPreview::class)
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            RadioController.stateFlow.combine(StationStore.data) { s, d ->
                val saved = StationLogic.find(d, s.band, s.freqKhz)
                Key(s.freqKhz, s.stationName.ifBlank { saved?.name ?: "" }, s.radioText.ifBlank { Pty.name(s.pty) }, s.powered, saved?.id)
            }.distinctUntilChanged().debounce(300).collect { if (placed(app)) renderAll(app) }
        }
    }

    private fun ids(ctx: Context): List<Pair<Class<*>, IntArray>> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return listOf(WideWidget::class.java, CompactWidget::class.java).map { it to mgr.getAppWidgetIds(ComponentName(ctx, it)) }
    }

    private fun placed(ctx: Context) = runCatching { ids(ctx).any { it.second.isNotEmpty() } }.getOrDefault(false)

    fun renderAll(ctx: Context) = runCatching {
        val mgr = AppWidgetManager.getInstance(ctx)
        val s = RadioController.state
        val saved = StationLogic.find(StationStore.data.value, s.band, s.freqKhz)
        val name = s.stationName.ifBlank { saved?.name ?: "Car Radio" }
        val freq = "${Freq.format(s.band, s.freqKhz)} ${Freq.unit(s.band)}" +
            (s.radioText.ifBlank { Pty.name(s.pty) }.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
            (if (!s.powered) " · off" else "")
        val logo = logoBitmap(ctx, saved?.id, name)
        for ((cls, ids) in ids(ctx)) {
            if (ids.isEmpty()) continue
            val layout = if (cls == WideWidget::class.java) R.layout.widget_wide else R.layout.widget_compact
            val v = RemoteViews(ctx.packageName, layout)
            v.setTextViewText(R.id.w_name, name)
            v.setTextViewText(R.id.w_freq, freq)
            v.setImageViewBitmap(R.id.w_logo, logo)
            v.setImageViewResource(R.id.w_power, if (s.powered) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            v.setOnClickPendingIntent(R.id.w_prev, action(ctx, cls, ACTION_PREV))
            v.setOnClickPendingIntent(R.id.w_next, action(ctx, cls, ACTION_NEXT))
            v.setOnClickPendingIntent(R.id.w_power, action(ctx, cls, ACTION_POWER))
            v.setOnClickPendingIntent(R.id.w_logo, openApp(ctx))
            v.setOnClickPendingIntent(R.id.w_name, openApp(ctx))
            mgr.updateAppWidget(ids, v)
        }
    }.onFailure { AppLog.w("Widget update failed", it) }

    fun handle(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a !in setOf(ACTION_PREV, ACTION_NEXT, ACTION_POWER)) return
        AppLog.i("Widget: ${a.substringAfterLast('.')}")
        // Process was started just for this tap: nothing is selected yet → open the app, which
        // picks the method and (by default) starts playing.
        if (RadioController.state.backendId == null) { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        when (a) {
            ACTION_PREV -> RadioController.stationStep(false)
            ACTION_NEXT -> RadioController.stationStep(true)
            ACTION_POWER -> RadioController.powerToggle()
        }
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun action(ctx: Context, cls: Class<*>, a: String) =
        PendingIntent.getBroadcast(ctx, a.hashCode() + cls.hashCode(), Intent(ctx, cls).setAction(a), flags())

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java), flags())

    /** Saved logo file, or drawn initials, at 96 px (RemoteViews bitmaps must stay small). */
    private fun logoBitmap(ctx: Context, stationId: Long?, name: String): Bitmap {
        stationId?.let { id -> File(ctx.filesDir, "logos/$id.webp").takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return it } } }
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val palette = intArrayOf(0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF546E7A.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[Math.floorMod(name.hashCode(), palette.size)] }
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        val text = StationLogic.initials(name)
        p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        p.textSize = if (text.length <= 2) 40f else 28f
        c.drawText(text, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

}
