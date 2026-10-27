package com.nik.carradio.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nik.carradio.R
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Freq
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.UiConfig
import com.nik.carradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A small draggable bar over navigation: station, previous, power, next, close. Plain Android
 * views (no Compose) so it costs almost nothing next to the map app. Shown when Car Radio goes
 * to the background while the radio plays, hidden when Car Radio comes back or the radio stops.
 * Needs "Display over other apps".
 */
object FloatingPlayer {
    private var view: View? = null
    private var title: TextView? = null
    private var sub: TextView? = null
    private var power: ImageView? = null
    private var job: Job? = null
    /** Closed with × : stays away until Car Radio is opened again. */
    private var dismissed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun canDraw(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** MainActivity went to the background. */
    fun onAppHidden(ctx: Context) {
        if (!UiConfig.settings.value.floatingPlayer || dismissed || !RadioController.state.powered) return
        if (!canDraw(ctx)) { AppLog.w("Floating player: \"Display over other apps\" is not allowed"); return }
        show(ctx.applicationContext)
    }

    /** MainActivity is visible again. */
    fun onAppShown() { dismissed = false; hide() }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        if (view != null) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
        val accent = Color.parseColor("#42A5F5")

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F21A2027")); cornerRadius = dp(18).toFloat() }
            elevation = dp(6).toFloat()
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(200) }
        sub = TextView(ctx).apply { setTextColor(Color.parseColor("#B0BEC5")); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(200) }
        texts.addView(title); texts.addView(sub)
        bar.addView(texts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })

        fun button(icon: Int, primary: Boolean = false, onClick: () -> Unit) = ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { setColor(if (primary) accent else Color.parseColor("#2C3640")); cornerRadius = dp(14).toFloat() }
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(6) })
        }
        button(R.drawable.ic_fl_prev) { RadioController.stationStep(false) }
        power = button(R.drawable.ic_fl_power, primary = true) { RadioController.powerToggle() }
        button(R.drawable.ic_fl_next) { RadioController.stationStep(true) }
        button(R.drawable.ic_fl_close) { dismissed = true; hide() }.apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(56)).apply { marginStart = dp(6) }; background = null }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val sp = ctx.getSharedPreferences("floating", Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt("x", dp(24)); y = sp.getInt("y", dp(80))
        }

        // Drag anywhere on the bar; a short tap on the texts opens Car Radio.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(bar, lp) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) sp.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    else ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    true
                }
                else -> false
            }
        }
        texts.setOnTouchListener(drag)
        bar.setOnTouchListener(drag)

        try { wm.addView(bar, lp) } catch (e: Exception) { AppLog.w("Floating player could not be shown", e); return }
        view = bar
        AppLog.i("Floating player shown")
        job = scope.launch {
            RadioController.stateFlow.map { Triple(it.stationName to it.radioText, it.band to it.freqKhz, it.powered) }.distinctUntilChanged().collect { (names, f, on) ->
                val (name, rt) = names
                val freq = Freq.format(f.first, f.second) + " " + Freq.unit(f.first)
                title?.text = name.ifBlank { freq }
                sub?.text = if (name.isBlank()) rt else if (rt.isBlank()) freq else "$freq · $rt"
                power?.setImageResource(if (on) R.drawable.ic_fl_power else R.drawable.ic_fl_play)
            }
        }
    }

    fun hide() {
        job?.cancel(); job = null
        val v = view ?: return
        view = null
        runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        AppLog.i("Floating player hidden")
    }
}
