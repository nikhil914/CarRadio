package com.nik.carradio.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.nik.carradio.BuildConfig
import com.nik.carradio.ui.LogoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * One-file backup: stations, groups, AF lists, appearance, radio settings and logos (as
 * base64 WebP, a few KB each). The GitHub token is never written to a backup.
 */
object Backup {
    private const val FORMAT = "carradio-backup"

    suspend fun export(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val ui = UiConfig.settings.value
        val p = RadioController.prefs
        val logos = JSONObject()
        File(ctx.filesDir, "logos").listFiles()?.forEach { f ->
            f.name.removeSuffix(".webp").toLongOrNull()?.let { id -> logos.put(id.toString(), Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
        }
        val o = JSONObject()
            .put("format", FORMAT).put("version", 1).put("app", BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis())
            .put("stations", StationStore.exportObject())
            .put("ui", JSONObject().put("layout", ui.layout.name).put("cols", ui.gridColumns).put("theme", ui.themeId)
                .put("text", ui.textScale.toDouble()).put("fsAfter", ui.fullscreenAfterSec).put("fsClock", ui.fullscreenClock)
                .put("fsSpeed", ui.fullscreenSpeed).put("logos", ui.showLogos).put("night", ui.nightMode.name)
                .put("nightTheme", ui.nightThemeId).put("nightStart", ui.nightStart).put("nightEnd", ui.nightEnd)
                .put("style", ui.screenStyle.name).put("cruiseNight", ui.cruiseAtNight).put("cruiseTint", ui.cruiseTint.name)
                .put("driver", ui.driverSide.name))
            .put("prefs", JSONObject().put("fmStep", p.fmStepKhz).put("amStep", p.amStepKhz).put("autoStart", p.autoStart)
                .put("wheel", p.wheelUsesStations).put("resume", p.resumeOnStart).put("stream", p.streamUrl).put("ghRepo", p.githubRepo)
                .put("nextPrevKey", p.nextPrevKey.name).put("playKey", p.playKey.name))
            .put("logos", logos)
        val bytes = o.toString().toByteArray()
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write to that location")
        AppLog.i("Backup written: ${bytes.size / 1024} KB, ${StationStore.data.value.stations.size} stations, ${logos.length()} logos")
        "Saved ${StationStore.data.value.stations.size} stations and ${logos.length()} logos (${bytes.size / 1024} KB)"
    }

    /** Validates first, then replaces everything. Returns a one-line summary. */
    suspend fun restore(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Cannot read that file")
        val o = runCatching { JSONObject(text) }.getOrElse { throw RadioException("Not a Car Radio backup", "The file is not valid JSON.") }
        if (o.optString("format") != FORMAT) throw RadioException("Not a Car Radio backup", "Choose a file made with Settings → Back up.")
        if (o.optInt("version") > 1) throw RadioException("Backup is from a newer Car Radio", "Update the app, then restore.")
        val st = o.optJSONObject("stations") ?: throw RadioException("Backup has no station list")
        val d = StationStore.importObject(st)

        o.optJSONObject("ui")?.let { u ->
            UiConfig.update { cur -> cur.copy(
                layout = runCatching { StationLayout.valueOf(u.optString("layout")) }.getOrDefault(cur.layout),
                gridColumns = u.optInt("cols", cur.gridColumns).coerceIn(2, 6), themeId = u.optString("theme", cur.themeId),
                textScale = u.optDouble("text", cur.textScale.toDouble()).toFloat().coerceIn(0.8f, 1.4f),
                fullscreenAfterSec = u.optInt("fsAfter", cur.fullscreenAfterSec), fullscreenClock = u.optBoolean("fsClock", cur.fullscreenClock),
                fullscreenSpeed = u.optBoolean("fsSpeed", cur.fullscreenSpeed), showLogos = u.optBoolean("logos", cur.showLogos),
                nightMode = runCatching { NightMode.valueOf(u.optString("night")) }.getOrDefault(cur.nightMode),
                nightThemeId = u.optString("nightTheme", cur.nightThemeId),
                nightStart = u.optInt("nightStart", cur.nightStart), nightEnd = u.optInt("nightEnd", cur.nightEnd),
                screenStyle = UiConfig.enumOr(u.optString("style"), cur.screenStyle), cruiseAtNight = u.optBoolean("cruiseNight", cur.cruiseAtNight),
                cruiseTint = UiConfig.enumOr(u.optString("cruiseTint"), cur.cruiseTint), driverSide = UiConfig.enumOr(u.optString("driver"), cur.driverSide)) }
        }
        o.optJSONObject("prefs")?.let { j ->
            val p = RadioController.prefs
            p.fmStepKhz = j.optInt("fmStep", p.fmStepKhz); p.amStepKhz = j.optInt("amStep", p.amStepKhz)
            p.autoStart = j.optBoolean("autoStart", p.autoStart); p.wheelUsesStations = j.optBoolean("wheel", p.wheelUsesStations)
            p.resumeOnStart = j.optBoolean("resume", p.resumeOnStart); p.streamUrl = j.optString("stream", p.streamUrl)
            p.githubRepo = j.optString("ghRepo", p.githubRepo)
            p.nextPrevKey = UiConfig.enumOr(j.optString("nextPrevKey"), p.nextPrevKey); p.playKey = UiConfig.enumOr(j.optString("playKey"), p.playKey)
        }
        var logoCount = 0
        o.optJSONObject("logos")?.let { l ->
            val dir = File(ctx.filesDir, "logos").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            l.keys().forEach { k ->
                runCatching { File(dir, "$k.webp").writeBytes(Base64.decode(l.getString(k), Base64.NO_WRAP)); logoCount++ }
            }
            LogoStore.init(ctx)
        }
        AppLog.i("Backup restored: ${d.stations.size} stations, $logoCount logos (from app ${o.optString("app")})")
        "Restored ${d.stations.size} stations, ${d.groups.size} groups and $logoCount logos"
    }
}
