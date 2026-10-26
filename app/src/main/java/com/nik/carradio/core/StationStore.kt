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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stations and groups in one small JSON file (a few KB): loads in milliseconds on a slow head
 * unit with no database engine to start. Writes are debounced and atomic (temp file + rename),
 * so a power cut when the ignition goes off cannot corrupt the list.
 */
object StationStore {
    private const val FILE = "stations.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _data = MutableStateFlow(StationData())
    val data: StateFlow<StationData> = _data.asStateFlow()
    private var file: File? = null
    private var saveJob: Job? = null
    @Volatile var loaded = false; private set

    fun load(ctx: Context, prefs: Prefs) {
        val f = File(ctx.filesDir, FILE)
        file = f
        val parsed = if (f.exists()) runCatching { fromJson(JSONObject(f.readText())) }
            .onFailure { AppLog.e("Station list unreadable, keeping a backup and starting fresh", it); f.renameTo(File(ctx.filesDir, "$FILE.bad")) }
            .getOrNull() else null
        _data.value = parsed ?: StationLogic.initial(presetsKhz = prefs.presets()).also {
            AppLog.i("Station list created: ${it.stations.size} UAE seed stations + old presets")
            writeNow(it)
        }
        loaded = true
        AppLog.i("Stations loaded: ${_data.value.stations.size} stations, ${_data.value.groups.size} groups")
    }

    fun update(transform: (StationData) -> StationData) {
        _data.update(transform)
        saveJob?.cancel()
        saveJob = scope.launch { delay(400); writeNow(_data.value) }
    }

    private fun writeNow(d: StationData) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(toJson(d).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { AppLog.e("Saving stations failed", it) }
    }

    fun exportJson(): String = toJson(_data.value).toString(2)
    fun exportObject(): JSONObject = toJson(_data.value)

    /** Replace everything with a backup (validated by the caller). */
    fun importObject(o: JSONObject): StationData {
        val d = fromJson(o)
        _data.value = d
        writeNow(d)
        return d
    }

    private fun toJson(d: StationData) = JSONObject().apply {
        put("version", 1)
        put("selectedGroup", d.selectedGroup)
        put("sort", d.sort.name)
        put("groups", JSONArray().apply { d.groups.forEach { g -> put(JSONObject().put("id", g.id).put("name", g.name).put("builtin", g.builtin)) } })
        put("stations", JSONArray().apply {
            d.stations.forEach { s ->
                put(JSONObject().put("id", s.id).put("band", s.band.name).put("khz", s.freqKhz).put("name", s.name)
                    .put("pi", s.pi).put("fav", s.favourite).put("pos", s.position).put("verified", s.verified)
                    .put("groups", JSONArray(s.groups.toList())).put("af", JSONArray(s.afKhz)))
            }
        })
    }

    private fun fromJson(o: JSONObject): StationData {
        val groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Group(it.getString("id"), it.getString("name"), it.optBoolean("builtin")) } }
            ?: StationLogic.DEFAULT_GROUPS
        val stations = o.optJSONArray("stations")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                Station(j.getLong("id"), runCatching { Band.valueOf(j.getString("band")) }.getOrDefault(Band.FM), j.getInt("khz"),
                    j.optString("name"), j.optInt("pi"), j.optBoolean("fav"),
                    j.optJSONArray("groups")?.let { g -> (0 until g.length()).map { g.getString(it) }.toSet() } ?: emptySet(),
                    j.optInt("pos"), j.optBoolean("verified", true),
                    j.optJSONArray("af")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList())
            }
        } ?: emptyList()
        return StationData(stations, groups, o.optString("selectedGroup", StationLogic.ALL),
            runCatching { SortMode.valueOf(o.optString("sort")) }.getOrDefault(SortMode.POSITION))
    }
}
