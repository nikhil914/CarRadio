package com.nik.sendtocar

import kotlin.math.abs

/** A place to drive to: coordinates when known, otherwise a search text for Waze. */
data class Place(val lat: Double?, val lon: Double?, val name: String, val query: String? = null) {
    val hasCoords: Boolean get() = lat != null && lon != null
    fun label(): String = name.ifBlank { query ?: if (hasCoords) "%.5f, %.5f".format(lat, lon) else "?" }
}

sealed class Parsed {
    data class Found(val place: Place) : Parsed()
    /** A short link (maps.app.goo.gl …) that must be followed online before it shows coordinates. */
    data class NeedsResolve(val url: String, val name: String) : Parsed()
    object Nothing : Parsed()
}

/**
 * Turns whatever another app shared (Google Maps, WhatsApp, Waze, Apple Maps, geo: links,
 * plain coordinates or an address) into a [Place]. Pure Kotlin so it is unit-tested.
 */
object Parser {
    private val URL_RE = Regex("""https?://[^\s<>"']+""")
    private val GEO_RE = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)([^\s"'<>]*)""")
    private val PIN_RE = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val AT_RE = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val PARAM_RE = Regex("""[?&](?:q|query|ll|daddr|destination|center|sll)=(?:loc:)?(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)""")
    private val WAZE_HASH_RE = Regex("""waze\.com/ul/h([0-9b-hjkmnp-z]{5,12})""")
    private val PLACE_NAME_RE = Regex("""/maps/place/([^/@?]+)""")
    private val Q_TEXT_RE = Regex("""[?&](?:q|query|daddr|destination)=([^&#]+)""")
    private val PAIR_RE = Regex("""(?<![\d.])(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?![\d.])""")
    private val SHORT_HOSTS = listOf("maps.app.goo.gl", "goo.gl/maps", "g.co/kgs", "maps.google.com/maps?cid", "share.google")

    fun parse(raw: String): Parsed {
        val text = raw.replace("%2C", ",").replace("%2c", ",").replace("%40", "@").replace("&amp;", "&").trim()
        if (text.isBlank()) return Parsed.Nothing
        val urls = URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ')') }.toList()
        val plainLines = text.lines().map { URL_RE.replace(it, "").replace(GEO_RE, "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("<") }
        val name = plainLines.firstOrNull()?.take(80).orEmpty()

        GEO_RE.find(text)?.let { m ->
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            val q = Regex("""[?&]q=([^&]+)""").find(m.groupValues[3])?.groupValues?.get(1)?.let(::decode)
            if (valid(lat, lon)) return Parsed.Found(Place(lat, lon, name.ifBlank { labelOf(q) }))
            if (!q.isNullOrBlank()) {
                val inner = parse(q)
                if (inner is Parsed.Found) return inner
            }
        }

        for (u in urls) {
            coordsIn(u)?.let { (lat, lon) -> return Parsed.Found(Place(lat, lon, name.ifBlank { placeName(u).orEmpty() })) }
        }
        urls.firstOrNull { u -> SHORT_HOSTS.any { u.contains(it) } }?.let { return Parsed.NeedsResolve(it, name) }
        for (u in urls) {
            val search = placeName(u) ?: Q_TEXT_RE.find(u)?.groupValues?.get(1)?.let(::decode)
            if (!search.isNullOrBlank()) return Parsed.Found(Place(null, null, name.ifBlank { search }, search))
        }

        val plain = plainLines.joinToString(", ")
        PAIR_RE.find(plain)?.let { m ->
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (valid(lat, lon)) return Parsed.Found(Place(lat, lon, plain.replace(m.value, "").trim(' ', ',', '(', ')')))
        }
        if (plain.isNotBlank()) return Parsed.Found(Place(null, null, name, plain.take(200)))
        return Parsed.Nothing
    }

    /** Coordinates in one URL. A Google "!3d…!4d" pin beats the "@" map centre. */
    fun coordsIn(url: String): Pair<Double, Double>? {
        for (re in listOf(PIN_RE, PARAM_RE, AT_RE)) {
            re.find(url)?.let { m ->
                val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
                if (valid(lat, lon)) return lat to lon
            }
        }
        WAZE_HASH_RE.find(url)?.let { m -> return geohash(m.groupValues[1]) }
        return null
    }

    private fun placeName(url: String): String? =
        PLACE_NAME_RE.find(url)?.groupValues?.get(1)?.let(::decode)?.takeIf { it.isNotBlank() }

    private fun labelOf(q: String?): String = q?.substringAfter('(', "")?.substringBefore(')').orEmpty()

    private fun decode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8").trim() } catch (e: Exception) { s.replace('+', ' ').trim() }

    fun valid(lat: Double, lon: Double): Boolean =
        abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)

    /** Waze share links (waze.com/ul/h…) carry a geohash. */
    fun geohash(hash: String): Pair<Double, Double>? {
        val base = "0123456789bcdefghjkmnpqrstuvwxyz"
        val lat = doubleArrayOf(-90.0, 90.0)
        val lon = doubleArrayOf(-180.0, 180.0)
        var even = true
        for (c in hash) {
            val v = base.indexOf(c)
            if (v < 0) return null
            for (bit in 4 downTo 0) {
                val r = if (even) lon else lat
                val mid = (r[0] + r[1]) / 2
                if ((v shr bit) and 1 == 1) r[0] = mid else r[1] = mid
                even = !even
            }
        }
        return (lat[0] + lat[1]) / 2 to (lon[0] + lon[1]) / 2
    }
}
