package com.nik.carradio.backend.stream

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.nik.carradio.backend.BackendHost
import com.nik.carradio.backend.RadioBackend
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.ProbeResult
import com.nik.carradio.core.ProbeStatus
import com.nik.carradio.core.RadioException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Internet radio fallback: plays the stream URL set in Settings. No tuner needed. */
class StreamBackend : RadioBackend {
    override val id = "stream"
    override val name = "Internet radio stream"
    override val description = "Plays the stream URL from Settings with Android MediaPlayer."
    override val caps = setOf(Cap.STREAM)

    private var player: MediaPlayer? = null

    override suspend fun probe(host: BackendHost): ProbeResult =
        if (host.prefs.streamUrl.isBlank()) ProbeResult(ProbeStatus.UNAVAILABLE, "No stream URL set", "Add one in Settings to use internet radio.")
        else ProbeResult(ProbeStatus.AVAILABLE, "Stream URL set")

    override suspend fun powerOn(host: BackendHost, band: Band, khz: Int) {
        val url = host.prefs.streamUrl
        if (url.isBlank()) throw RadioException("No stream URL set", "Open Settings and paste an internet radio URL (http/https, MP3/AAC/HLS).")
        powerOff(host)
        val ready = CompletableDeferred<Result<Unit>>()
        withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setOnPreparedListener { it.start(); ready.complete(Result.success(Unit)) }
            mp.setOnErrorListener { _, what, extra ->
                val msg = "MediaPlayer error what=$what extra=$extra (${explain(extra)})"
                AppLog.e("Stream: $msg")
                if (!ready.isCompleted) ready.complete(Result.failure(RadioException("Stream could not be played", msg)))
                else host.update { it.copy(powered = false, status = "Stream stopped: ${explain(extra)}") }
                true
            }
            try { mp.setDataSource(url); mp.prepareAsync() }
            catch (e: Exception) { ready.complete(Result.failure(RadioException("Invalid stream URL", e.toString(), e))) }
        }
        val r = withTimeoutOrNull(20_000) { ready.await() }
            ?: throw RadioException("Stream did not start within 20 s", "Check the internet connection (hotspot) and the URL.")
        r.getOrThrow()
        host.update { it.copy(status = "Playing internet stream", stationName = url.substringAfter("://").substringBefore('/')) }
    }

    private fun explain(extra: Int) = when (extra) {
        MediaPlayer.MEDIA_ERROR_IO -> "network / file error"
        MediaPlayer.MEDIA_ERROR_MALFORMED -> "malformed stream"
        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "unsupported format"
        MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "timed out"
        else -> "code $extra"
    }

    override suspend fun powerOff(host: BackendHost) {
        withContext(Dispatchers.Main) { player?.let { runCatching { it.stop() }; it.release() }; player = null }
    }

    override suspend fun tune(host: BackendHost, band: Band, khz: Int) = notSupported("Tuning a frequency")
    override fun release(host: BackendHost) { player?.release(); player = null }
}
