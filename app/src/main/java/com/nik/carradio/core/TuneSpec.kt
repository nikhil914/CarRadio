package com.nik.carradio.core

/**
 * One way of asking an FYT radio module to tune, as data, so the full radio test can save the
 * format that worked and the app uses it from then on.
 *
 * Encoded as `code|ints|floats|strings|flags`, for example `13|1,{v10}|||src`. Templates:
 *  {v10}   frequency in 10 kHz units (98.1 MHz → 9810; what FYT reports)
 *  {khz}   kHz (98100)        {v100} 100 kHz units (981)     {mhz} whole MHz (98)
 *  {idx}   step index from the band minimum using the band step
 *  {ratio} 0…65535 across the band            {fmhz} MHz as a float/string (98.1)
 * Flags: `src` = claim the radio audio source (main cmd 0 [1]) first.
 */
data class TuneSpec(val code: Int, val ints: String, val floats: String = "", val strings: String = "", val claimSource: Boolean = false) {

    fun encode() = listOf(code.toString(), ints, floats, strings, if (claimSource) "src" else "").joinToString("|")

    fun label() = "cmd($code" + (if (ints.isNotEmpty()) ",[$ints]" else "") + (if (floats.isNotEmpty()) ",f[$floats]" else "") +
        (if (strings.isNotEmpty()) ",s[$strings]" else "") + ")" + (if (claimSource) " after source claim" else "")

    data class Rendered(val ints: IntArray?, val floats: FloatArray?, val strings: Array<String>?)

    /** Fills the templates for [khz] on [band] ([min]/[max]/[step] from the tuner, kHz). */
    fun render(band: Band, khz: Int, min: Int, max: Int, step: Int): Rendered {
        fun value(t: String): String = when (t.trim()) {
            "{v10}" -> (if (band == Band.FM) khz / 10 else khz).toString()
            "{khz}" -> khz.toString()
            "{v100}" -> (khz / 100).toString()
            "{mhz}" -> (khz / 1000).toString()
            "{idx}" -> ((khz - min) / step.coerceAtLeast(1)).toString()
            "{ratio}" -> (65535L * (khz - min) / (max - min).coerceAtLeast(1)).toString()
            "{fmhz}" -> if (band == Band.FM) "%.2f".format(java.util.Locale.US, khz / 1000f).trimEnd('0').trimEnd('.') else khz.toString()
            else -> t.trim()
        }
        fun parts(s: String) = if (s.isBlank()) null else s.split(',').map { value(it) }
        return Rendered(
            parts(ints)?.map { it.toInt() }?.toIntArray(),
            parts(floats)?.map { it.toFloat() }?.toFloatArray(),
            parts(strings)?.toTypedArray(),
        )
    }

    companion object {
        fun decode(s: String?): TuneSpec? {
            if (s.isNullOrBlank()) return null
            val p = s.split('|')
            if (p.size < 5) return null
            val code = p[0].toIntOrNull() ?: return null
            return TuneSpec(code, p[1], p[2], p[3], p[4] == "src")
        }
    }
}
