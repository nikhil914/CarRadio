package com.nik.carradio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.nik.carradio.core.AppLog
import com.nik.carradio.core.Station
import com.nik.carradio.core.StationLogic
import com.nik.carradio.core.StationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Station logos as 96×96 WebP files in app storage (a few KB each), decoded once into an LRU
 * cache. Logos are imported by the user: real station logos are their owners' trademarks, so
 * the app ships none and draws initials instead.
 */
object LogoStore {
    const val SIZE = 96
    private lateinit var dir: File
    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    /** Station ids that have a logo file; also the cache-busting signal for the UI. */
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()
    private val cache = LruCache<Long, ImageBitmap>(64)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logos").apply { mkdirs() }
        _ids.value = dir.listFiles()?.mapNotNull { it.name.removeSuffix(".webp").toLongOrNull() }?.toSet() ?: emptySet()
    }

    private fun file(id: Long) = File(dir, "$id.webp")

    fun cached(id: Long): ImageBitmap? = cache.get(id)

    suspend fun load(id: Long): ImageBitmap? = cache.get(id) ?: withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file(id).path)?.asImageBitmap() }.getOrNull()?.also { cache.put(id, it) }
    }

    /** Decode with subsampling (never a full-size bitmap in RAM), fit into 96×96, save as WebP. */
    suspend fun importFor(ctx: Context, stationId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            require(largest > 0) { "not an image" }
            var sample = 1
            while (largest / (sample * 2) >= SIZE * 2) sample *= 2
            val src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("decode failed")
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val scale = SIZE.toFloat() / maxOf(src.width, src.height)
            val w = src.width * scale; val h = src.height * scale
            Canvas(out).drawBitmap(src, null, RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            file(stationId).outputStream().use { out.compress(fmt, 90, it) }
            cache.put(stationId, out.asImageBitmap())
            _ids.value = _ids.value + stationId
            true
        }.onFailure { AppLog.w("Logo import failed for station $stationId", it) }.getOrDefault(false)
    }

    /** Imports every image in a folder whose file name matches a station. Returns matched / images. */
    suspend fun importFolder(ctx: Context, tree: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext 0 to 0
        val images = root.listFiles().filter { f -> f.isFile && (f.type?.startsWith("image/") == true ||
            f.name?.substringAfterLast('.')?.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp")) }
        val stations = StationStore.data.value.stations
        var matched = 0
        for (img in images) {
            val name = img.name ?: continue
            val st = StationLogic.matchLogoFile(name, stations) ?: continue
            if (importFor(ctx, st.id, img.uri)) { matched++; AppLog.i("Logo: $name -> ${st.label()}") }
        }
        AppLog.i("Logo folder import: $matched of ${images.size} images matched a station")
        matched to images.size
    }

    fun remove(id: Long) {
        file(id).delete(); cache.remove(id); _ids.value = _ids.value - id
    }
}

private val MONO_COLORS = listOf(0xFF1E88E5, 0xFF43A047, 0xFFF4511E, 0xFF8E24AA, 0xFF00897B, 0xFFC0CA33, 0xFF6D4C41, 0xFF3949AB, 0xFFD81B60, 0xFF546E7A)

/** The station's logo, or its initials on a colour derived from its name (stable per station). */
@Composable
fun StationLogo(st: Station, size: Dp, show: Boolean = true) {
    val ids by LogoStore.ids.collectAsState()
    val hasLogo = show && st.id in ids
    var bmp by remember(st.id, hasLogo) { mutableStateOf(if (hasLogo) LogoStore.cached(st.id) else null) }
    if (hasLogo) LaunchedEffect(st.id, ids) { bmp = LogoStore.load(st.id) }
    val img = bmp
    if (img != null) {
        Image(img, contentDescription = st.label(), modifier = Modifier.size(size).clip(RoundedCornerShape(size / 6)))
    } else {
        val label = st.name.ifBlank { "" }
        val color = Color(MONO_COLORS[Math.floorMod(label.ifBlank { st.freqKhz.toString() }.hashCode(), MONO_COLORS.size)])
        Box(Modifier.size(size).background(color.copy(alpha = .85f), CircleShape), contentAlignment = Alignment.Center) {
            val text = if (label.isNotBlank()) StationLogic.initials(label) else com.nik.carradio.core.Freq.format(st.band, st.freqKhz)
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
                fontSize = (size.value * if (text.length <= 2) 0.40f else 0.27f).sp)
        }
    }
}
