package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationLogicTest {

    @Test fun seedMatchesByRdsNameThenFrequency() {
        assertEquals("Virgin Radio", StationLogic.seedMatch("VIRGIN", Band.FM, 99_000)?.name)
        assertEquals("Kadak FM", StationLogic.seedMatch(" KADAK ", Band.FM, 90_000)?.name)
        assertEquals("Radio 1", StationLogic.seedMatch("", Band.FM, 100_500)?.name)       // Abu Dhabi freq
        assertEquals("Kadak FM", StationLogic.seedMatch("", Band.FM, 88_800)?.name)       // Dubai freq
        assertNull(StationLogic.seedMatch("RADIO", Band.FM, 95_000))                      // too generic
        assertNull(StationLogic.seedMatch("", Band.AM, 100_500))
    }

    @Test fun initialListIsSeededUnverifiedAndKeepsOldPresets() {
        val d = StationLogic.initial(presetsKhz = listOf(97_300, 104_100, 0))
        val kadak = d.stations.first { it.name == "Kadak FM" }
        assertEquals(97_300, kadak.freqKhz)            // Abu Dhabi frequency preferred
        assertTrue(kadak.favourite)                     // old preset 97.3 marked it favourite
        assertFalse(kadak.verified)
        assertTrue(d.stations.any { it.freqKhz == 104_100 && it.favourite && it.groups.isEmpty() })
        assertEquals(StationLogic.UAE_SEED.size + 1, d.stations.size)
    }

    @Test fun groupsFilterAndUnsorted() {
        val d = StationLogic.initial(presetsKhz = listOf(104_100))
        assertTrue(StationLogic.visible(d, "hindi").all { "hindi" in it.groups })
        assertEquals(5, StationLogic.visible(d, "hindi").size)
        assertEquals(listOf(104_100), StationLogic.visible(d, StationLogic.UNSORTED).map { it.freqKhz })
        assertEquals(1, StationLogic.visible(d, StationLogic.FAVOURITES).size)
    }

    @Test fun neighbourWrapsInsideTheSelectedGroup() {
        val d = StationLogic.initial().copy(selectedGroup = "english", sort = SortMode.FREQUENCY)
        val english = StationLogic.visible(d)
        val last = english.last()
        assertEquals(english.first().freqKhz, StationLogic.neighbour(d, Band.FM, last.freqKhz, up = true)!!.freqKhz)
        assertEquals(last.freqKhz, StationLogic.neighbour(d, Band.FM, english.first().freqKhz, up = false)!!.freqKhz)
        // Between stations: next one above.
        val above = StationLogic.neighbour(d, Band.FM, english.first().freqKhz + 100, up = true)!!
        assertTrue(above.freqKhz > english.first().freqKhz)
    }

    @Test fun scanVerifiesKnownAndFilesNewStations() {
        val d = StationLogic.initial()
        val (merged, added) = StationLogic.mergeScan(d, listOf(
            ScanHit(Band.FM, 97_300, "KADAK"),          // seeded → verified
            ScanHit(Band.FM, 104_400, "VIRGIN"),        // seeded at its Dubai freq → verified, not added
            ScanHit(Band.FM, 95_500, "XYZ FM"),          // unknown → new, Unsorted
            ScanHit(Band.FM, 93_900, "BIG FM"),          // new freq, recognised by name → Hindi
        ))
        assertEquals(2, added)
        assertEquals(setOf("hindi"), StationLogic.find(merged, Band.FM, 93_900)!!.groups)
        assertTrue(StationLogic.find(merged, Band.FM, 104_400)!!.verified)
        assertTrue(merged.stations.first { it.name == "Kadak FM" }.verified)
        assertEquals(setOf("english"), StationLogic.find(merged, Band.FM, 104_400)!!.groups)
        assertNotNull(StationLogic.find(merged, Band.FM, 95_500))
        assertTrue(StationLogic.find(merged, Band.FM, 95_500)!!.groups.isEmpty())
        // Virgin seeded at its Dubai frequency 104.4 too: no duplicate created.
        assertEquals(1, merged.stations.count { it.name == "Virgin Radio" })
    }

    @Test fun moveSwapsNeighbours() {
        val d = StationLogic.initial()
        val order = StationLogic.visible(d)
        val moved = StationLogic.move(d, order[1].id, -1)
        assertEquals(order[1].id, StationLogic.visible(moved)[0].id)
        assertEquals(order[0].id, StationLogic.visible(moved)[1].id)
        assertEquals(d, StationLogic.move(d, order[0].id, -1).copy(sort = d.sort))   // already first: unchanged
    }

    @Test fun ptyNames() {
        assertEquals("News", Pty.name(1))
        assertEquals("Pop music", Pty.name(10))
        assertEquals("", Pty.name(0))
        assertEquals("", Pty.name(99))
    }
}
