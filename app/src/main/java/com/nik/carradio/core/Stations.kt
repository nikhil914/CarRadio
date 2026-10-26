package com.nik.carradio.core

import androidx.compose.runtime.Immutable
import kotlin.math.abs

@Immutable
data class Station(
    val id: Long,
    val band: Band,
    val freqKhz: Int,
    val name: String = "",
    /** RDS programme identification code, 0 = unknown. Used for logo matching later. */
    val pi: Int = 0,
    val favourite: Boolean = false,
    /** Ids of the groups (playlists) this station belongs to. Empty = "Unsorted". */
    val groups: Set<String> = emptySet(),
    /** Manual order inside lists when sort = POSITION. */
    val position: Int = 0,
    /** False for seeded entries whose frequency no scan has confirmed yet. */
    val verified: Boolean = true,
) {
    fun label(): String = name.ifBlank { Freq.format(band, freqKhz) }
}

@Immutable
data class Group(val id: String, val name: String, val builtin: Boolean = false)

enum class SortMode { POSITION, FREQUENCY, NAME }

@Immutable
data class StationData(
    val stations: List<Station> = emptyList(),
    val groups: List<Group> = StationLogic.DEFAULT_GROUPS,
    val selectedGroup: String = StationLogic.ALL,
    val sort: SortMode = SortMode.POSITION,
)

/** One station found by a scan. */
data class ScanHit(val band: Band, val freqKhz: Int, val name: String = "", val pi: Int = 0)

/** A known UAE station: names as broadcast in RDS (8-char PS) and per-city frequencies. */
data class SeedEntry(val name: String, val aliases: List<String>, val group: String, val abuDhabiKhz: Int?, val dubaiKhz: Int?)

/**
 * Pure station-list logic (no Android types) so it is unit-tested on the JVM.
 * Virtual groups: ALL, FAVOURITES and UNSORTED are computed, never stored on a station.
 */
object StationLogic {
    const val ALL = "all"
    const val FAVOURITES = "fav"
    const val UNSORTED = "unsorted"
    /** Two frequencies closer than this are the same station (covers 50 kHz-step drift). */
    const val SAME_KHZ = 60

    val VIRTUAL = listOf(Group(ALL, "All", true), Group(FAVOURITES, "Favourites", true))
    val DEFAULT_GROUPS = listOf(
        Group("hindi", "Hindi", true), Group("english", "English", true),
        Group("arabic", "Arabic", true), Group("malayalam", "Malayalam", true),
    )

    // Sources: Property Finder Abu Dhabi radio guide and Bayut Dubai radio list (Oct 2026).
    val UAE_SEED = listOf(
        SeedEntry("Abu Dhabi Classic FM", listOf("CLASSIC", "ADCLASSIC", "CLASSICFM"), "english", 91_600, null),
        SeedEntry("Radio 1", listOf("RADIO1", "RADIO 1"), "english", 100_500, null),
        SeedEntry("Radio 2", listOf("RADIO2", "RADIO 2"), "english", 106_000, null),
        SeedEntry("Dubai 92", listOf("DUBAI92", "DUBAI 92"), "english", null, 92_000),
        SeedEntry("Virgin Radio", listOf("VIRGIN"), "english", null, 104_400),
        SeedEntry("Dubai Eye", listOf("DUBAIEYE", "EYE1038"), "english", null, 103_800),
        SeedEntry("Channel 4 FM", listOf("CHANNEL4", "CH4FM"), "english", null, 104_800),
        SeedEntry("Rock Radio", listOf("ROCK", "ROCKRADIO"), "english", null, 90_700),
        SeedEntry("Kadak FM", listOf("KADAK"), "hindi", 97_300, 88_800),
        SeedEntry("City 1016", listOf("CITY", "CITY1016"), "hindi", null, 101_600),
        SeedEntry("BIG FM", listOf("BIGFM", "BIG106"), "hindi", null, 106_200),
        SeedEntry("Vibe", listOf("VIBE"), "hindi", null, 105_400),
        SeedEntry("Suno 1024", listOf("SUNO"), "hindi", null, 102_400),
    )

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Match an RDS name first (most reliable), then a known frequency in either city. */
    fun seedMatch(name: String, band: Band, khz: Int, seed: List<SeedEntry> = UAE_SEED): SeedEntry? {
        val n = norm(name)
        if (n.length >= 3) {
            // Only "RDS name contains alias": the reverse would let a bare "RADIO" match "Radio 1".
            seed.firstOrNull { e -> (e.aliases + e.name).any { a -> val an = norm(a); an.length >= 3 && n.contains(an) } }
                ?.let { return it }
        }
        if (band != Band.FM) return null
        return seed.firstOrNull { e -> listOfNotNull(e.abuDhabiKhz, e.dubaiKhz).any { abs(it - khz) < SAME_KHZ } }
    }

    /** First-run list: the seed with Abu Dhabi frequencies preferred, marked unverified. */
    fun initial(seed: List<SeedEntry> = UAE_SEED, presetsKhz: List<Int> = emptyList()): StationData {
        var id = 1L
        val list = mutableListOf<Station>()
        seed.forEach { e ->
            val khz = e.abuDhabiKhz ?: e.dubaiKhz ?: return@forEach
            list += Station(id++, Band.FM, khz, e.name, groups = setOf(e.group), position = list.size, verified = false)
        }
        presetsKhz.filter { it > 0 }.forEach { k ->
            val band = Freq.bandOf(k)
            val existing = list.indexOfFirst { it.band == band && abs(it.freqKhz - k) < SAME_KHZ }
            if (existing >= 0) list[existing] = list[existing].copy(favourite = true)
            else list += Station(id++, band, k, favourite = true, position = list.size)
        }
        return StationData(stations = list)
    }

    fun find(data: StationData, band: Band, khz: Int): Station? =
        data.stations.firstOrNull { it.band == band && abs(it.freqKhz - khz) < SAME_KHZ }

    /** Stations of the selected group, in the chosen order. */
    fun visible(data: StationData, group: String = data.selectedGroup): List<Station> {
        val filtered = when (group) {
            ALL -> data.stations
            FAVOURITES -> data.stations.filter { it.favourite }
            UNSORTED -> data.stations.filter { it.groups.isEmpty() }
            else -> data.stations.filter { group in it.groups }
        }
        return when (data.sort) {
            SortMode.POSITION -> filtered.sortedWith(compareBy({ it.position }, { it.freqKhz }))
            SortMode.FREQUENCY -> filtered.sortedWith(compareBy({ it.band != Band.FM }, { it.freqKhz }))
            SortMode.NAME -> filtered.sortedWith(compareBy({ it.label().lowercase() }, { it.freqKhz }))
        }
    }

    /** Steering-wheel next/prev: the neighbour of the current frequency in the visible list, wrapping. */
    fun neighbour(data: StationData, band: Band, khz: Int, up: Boolean): Station? {
        val list = visible(data).ifEmpty { visible(data, ALL) }
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.band == band && abs(it.freqKhz - khz) < SAME_KHZ }
        if (idx >= 0) return list[Math.floorMod(idx + if (up) 1 else -1, list.size)]
        // Not on a saved station: go to the nearest one in that direction (by frequency).
        val sameBand = list.filter { it.band == band }.sortedBy { it.freqKhz }
        return (if (up) sameBand.firstOrNull { it.freqKhz > khz } ?: sameBand.firstOrNull()
                else sameBand.lastOrNull { it.freqKhz < khz } ?: sameBand.lastOrNull()) ?: list.first()
    }

    /**
     * Merge scan results: known stations get verified and pick up their RDS name; new ones are
     * added, filed into a group when the seed recognises them, otherwise left Unsorted.
     * Seeded stations the scan did not hear stay, still unverified.
     */
    fun mergeScan(data: StationData, hits: List<ScanHit>, seed: List<SeedEntry> = UAE_SEED): Pair<StationData, Int> {
        val list = data.stations.toMutableList()
        var nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        var added = 0
        for (h in hits) {
            val i = list.indexOfFirst { it.band == h.band && abs(it.freqKhz - h.freqKhz) < SAME_KHZ }
            val match = seedMatch(h.name, h.band, h.freqKhz, seed)
            if (i >= 0) {
                val s = list[i]
                list[i] = s.copy(freqKhz = h.freqKhz, verified = true,
                    name = s.name.ifBlank { match?.name ?: h.name.trim() },
                    pi = if (h.pi != 0) h.pi else s.pi,
                    groups = if (s.groups.isEmpty() && match != null) setOf(match.group) else s.groups)
            } else {
                list += Station(nextId++, h.band, h.freqKhz, name = match?.name ?: h.name.trim(), pi = h.pi,
                    groups = match?.let { setOf(it.group) } ?: emptySet(), position = list.size, verified = true)
                added++
            }
        }
        return data.copy(stations = list) to added
    }

    /**
     * Which station a logo file belongs to, from its file name (NavRadio-style packs are named
     * after the station): exact name, then a UAE seed alias, then a frequency in the name
     * ("1041", "104.1", "104_1"), then a name contained in the file name.
     */
    fun matchLogoFile(fileName: String, stations: List<Station>, seed: List<SeedEntry> = UAE_SEED): Station? {
        val base = fileName.substringBeforeLast('.')
        val n = norm(base)
        if (n.isEmpty()) return null
        stations.firstOrNull { it.name.isNotBlank() && norm(it.name) == n }?.let { return it }
        seedMatch(base, Band.FM, -1, seed)?.let { e -> stations.firstOrNull { norm(it.name) == norm(e.name) }?.let { return it } }
        Regex("(?<!\\d)(8[7-9]|9\\d|10[0-8])[._,]?(\\d)(?!\\d)").find(base)?.let { m ->
            val khz = m.groupValues[1].toInt() * 1000 + m.groupValues[2].toInt() * 100
            stations.firstOrNull { it.band == Band.FM && abs(it.freqKhz - khz) < SAME_KHZ }?.let { return it }
        }
        return stations.filter { norm(it.name).length >= 4 && n.contains(norm(it.name)) }.maxByOrNull { it.name.length }
    }

    /** Up to two letters for a drawn logo: "Virgin Radio" → "VR", "Kadak FM" → "K". */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '_').map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() && !it.equals("FM", ignoreCase = true) }
        if (words.isEmpty()) return name.take(2).uppercase()
        val first = words[0]
        val second = words.getOrNull(1)
        return when {
            second == null -> first.take(1).uppercase()
            second.all { it.isDigit() } -> if (second.length <= 3) first.take(1).uppercase() + second else first.take(1).uppercase()
            else -> (first.take(1) + second.take(1)).uppercase()
        }
    }

    fun groupName(data: StationData, id: String): String =
        (VIRTUAL + data.groups + Group(UNSORTED, "Unsorted", true)).firstOrNull { it.id == id }?.name ?: id

    fun newGroupId(name: String, existing: List<Group>): String {
        val base = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "group" }
        var id = base; var n = 2
        while (existing.any { it.id == id } || id in listOf(ALL, FAVOURITES, UNSORTED)) id = base + n++
        return id
    }

    /** Swap a station with its neighbour in the current visible order (manual reordering). */
    fun move(data: StationData, stationId: Long, delta: Int): StationData {
        val order = visible(data.copy(sort = SortMode.POSITION))
        val i = order.indexOfFirst { it.id == stationId }
        val j = i + delta
        if (i < 0 || j !in order.indices) return data
        val a = order[i]; val b = order[j]
        val pa = a.position; val pb = b.position.let { if (it == pa) pa + delta else it }
        return data.copy(sort = SortMode.POSITION, stations = data.stations.map {
            when (it.id) { a.id -> it.copy(position = pb); b.id -> it.copy(position = pa); else -> it }
        })
    }
}
