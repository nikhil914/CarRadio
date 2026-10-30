package com.nik.carradio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.nik.carradio.R
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Freq
import com.nik.carradio.core.Pty
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.RadioState
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import com.nik.carradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Lives while the radio is on. Owns the MediaSession, which is how steering-wheel next/prev,
 * the play/pause key and car launchers (Agama, FCC, CarLauncher…) reach the radio, and keeps
 * the process alive for the SoC-FM method.
 */
class RadioService : Service() {
    private var scope: CoroutineScope? = null
    private var session: MediaSessionCompat? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val s = MediaSessionCompat(this, "CarRadio", ComponentName(this, MediaButtonReceiver::class.java), null)
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { AppLog.i("Media key: play"); RadioController.keyPlayPause(true) }
            override fun onPause() { AppLog.i("Media key: pause"); RadioController.keyPlayPause(false) }
            override fun onStop() { AppLog.i("Media key: stop"); RadioController.powerOff() }
            override fun onSkipToNext() { AppLog.i("Media key: next"); RadioController.keyNextPrev(true) }
            override fun onSkipToPrevious() { AppLog.i("Media key: previous"); RadioController.keyNextPrev(false) }
            override fun onFastForward() { AppLog.i("Media key: fast-forward -> seek up"); RadioController.seek(true) }
            override fun onRewind() { AppLog.i("Media key: rewind -> seek down"); RadioController.seek(false) }
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN) AppLog.i("Media button: ${KeyEvent.keyCodeToString(ev.keyCode)}")
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
        s.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags()))
        session = s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RadioController.powerOff(); stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(RadioController.state),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            AppLog.w("Foreground service could not start (radio keeps working while the app is open)", e)
            stopSelf(); return START_NOT_STICKY
        }
        // A wheel/headset key that arrived while we were not running.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) session?.let { MediaButtonReceiver.handleIntent(it, intent) }
        session?.isActive = true
        // Only methods whose sound goes through Android need focus. For MCU tuners (FYT,
        // TopWay) a focus request can make the vendor switch its source away from radio.
        if (RadioController.state.backendId in setOf("fmnative", "stream")) requestFocus()
        else AppLog.i("Audio focus not requested: ${RadioController.state.backendId} plays through the MCU")
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { sc ->
                sc.launch {
                    RadioController.stateFlow.combine(StationStore.data) { st, d -> st to d }
                        .distinctUntilChanged { a, b ->
                            a.first.freqKhz == b.first.freqKhz && a.first.stationName == b.first.stationName &&
                                a.first.radioText == b.first.radioText && a.first.powered == b.first.powered &&
                                a.first.pty == b.first.pty && a.second === b.second
                        }
                        .collect { (st, _) -> publish(st) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(st: RadioState) {
        val s = session ?: return
        val saved = StationLogic.find(StationStore.data.value, st.band, st.freqKhz)
        val freq = "${Freq.format(st.band, st.freqKhz)} ${Freq.unit(st.band)}"
        val title = st.stationName.ifBlank { saved?.name?.takeIf { it.isNotBlank() } ?: freq }
        val subtitle = st.radioText.ifBlank { Pty.name(st.pty).ifBlank { freq } }
        val group = StationLogic.groupName(StationStore.data.value, StationStore.data.value.selectedGroup)
        s.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, freq)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Car Radio · $group")
            .build())
        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND)
            .setState(if (st.powered) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF_ID, build(st, title, subtitle))
    }

    /** Audio focus tells other players (music, YouTube) to pause while the radio plays. */
    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { change -> AppLog.i("Audio focus change: $change") }
        val r = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(listener).build()
            focus = req; am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION") am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        AppLog.i("Audio focus request: ${if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "granted" else "result $r"}")
    }

    private fun piFlags() = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun build(st: RadioState, title: String = "${Freq.format(st.band, st.freqKhz)} ${Freq.unit(st.band)}", text: String = "Radio on"): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Radio playback", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags())
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP), piFlags())
        val prev = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val next = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "Previous", prev)
            .addAction(0, "Stop", stop)
            .addAction(0, "Next", next)
        session?.let { b.setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) }
        return b.build()
    }

    override fun onDestroy() {
        scope?.cancel(); scope = null
        session?.run { isActive = false; release() }; session = null
        if (Build.VERSION.SDK_INT >= 26) focus?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "radio"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.nik.carradio.STOP"

        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RadioService::class.java)) }
            .onFailure { AppLog.w("Could not start RadioService", it) }

        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, RadioService::class.java)) }
    }
}
