package com.nik.carradio.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "What was that song?": every new radio text line, with time and station. Radio text repeats
 * every few seconds and stations cycle slogans, so a line already seen on that station in the
 * last [REPEAT_WINDOW] entries is not added again. Kept small (150 lines) and saved at most
 * every 10 s.
 */
object SongHistory {
    data class Entry(val time: Long, val station: String, val text: String)

    private const val MAX = 150
    private const val REPEAT_WINDOW = 12
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "songs.json")
        _entries.value = runCatching { parse(file!!.readText()) }.getOrDefault(emptyList())
    }

    /** Called with every state change; cheap when the radio text did not change. */
    fun onState(old: RadioState, new: RadioState) {
        if (new.radioText == old.radioText || !UiConfig.settings.value.songHistory) return
        val station = new.stationName.ifBlank { Freq.format(new.band, new.freqKhz) + " " + Freq.unit(new.band) }
        val next = add(_entries.value, Entry(System.currentTimeMillis(), station, new.radioText)) ?: return
        _entries.value = next
        scheduleSave()
    }

    fun clear() { _entries.value = emptyList(); scheduleSave() }

    /** Pure: the new list, or null when [e] is noise or a repeat. */
    fun add(list: List<Entry>, e: Entry): List<Entry>? {
        val t = e.text.trim()
        if (t.length < 4 || t.equals(e.station.trim(), ignoreCase = true)) return null
        val key = norm(t)
        if (list.takeLast(REPEAT_WINDOW).any { it.station == e.station && norm(it.text) == key }) return null
        return (list + e.copy(text = t)).takeLast(MAX)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(10_000)
            val f = file ?: return@launch
            runCatching { f.writeText(toJson(_entries.value)) }.onFailure { AppLog.w("Song history not saved", it) }
        }
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.time).put("s", it.station).put("x", it.text)) }
    }.toString()

    fun parse(s: String): List<Entry> = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getLong("t"), it.getString("s"), it.getString("x")) } }
}
