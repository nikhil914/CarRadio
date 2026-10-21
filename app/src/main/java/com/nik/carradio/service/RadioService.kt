package com.nik.carradio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nik.carradio.R
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Freq
import com.nik.carradio.core.RadioController
import com.nik.carradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the radio is on (the SoC-FM method needs our process to hold
 * /dev/fm) and shows a notification with a Stop action.
 */
class RadioService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RadioController.powerOff()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build("Radio on"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            AppLog.w("Foreground service could not start (radio keeps working while the app is open)", e)
            stopSelf(); return START_NOT_STICKY
        }
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
                s.launch {
                    RadioController.stateFlow
                        .map { "${Freq.format(it.band, it.freqKhz)} ${Freq.unit(it.band)}" + (if (it.stationName.isNotBlank()) " · ${it.stationName}" else "") }
                        .distinctUntilChanged()
                        .collect { text -> (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF_ID, build(text)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Radio playback", NotificationManager.IMPORTANCE_LOW))
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), piFlags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle("Car Radio")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() { scope?.cancel(); scope = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "radio"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.nik.carradio.STOP"

        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RadioService::class.java)) }
            .onFailure { AppLog.w("Could not start RadioService", it) }

        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, RadioService::class.java)) }
    }
}
