package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Phase4Test {
    private fun near(expected: Int, actual: Int, tol: Int = 10) =
        assertTrue("expected ~$expected min, got $actual", abs(expected - actual) <= tol)

    @Test fun abuDhabiSunTimes() {
        // 26 Oct (day 299): sunrise ≈ 06:17, sunset ≈ 17:41 (UTC+4)
        val (rise, set) = DayNight.sunTimes(299, DayNight.DEFAULT_LAT, DayNight.DEFAULT_LON, 240)!!
        near(6 * 60 + 17, rise); near(17 * 60 + 41, set)
        // 21 Jun (day 172): sunrise ≈ 05:31, sunset ≈ 19:12
        val (r2, s2) = DayNight.sunTimes(172, DayNight.DEFAULT_LAT, DayNight.DEFAULT_LON, 240)!!
        near(5 * 60 + 31, r2); near(19 * 60 + 12, s2)
        assertNull(DayNight.sunTimes(172, 80.0, 0.0, 0))     // polar day
    }

    @Test fun nightWindowsWrapMidnight() {
        assertTrue(DayNight.inWindow(23 * 60, 19 * 60, 6 * 60 + 30))
        assertTrue(DayNight.inWindow(3 * 60, 19 * 60, 6 * 60 + 30))
        assertFalse(DayNight.inWindow(12 * 60, 19 * 60, 6 * 60 + 30))
        assertTrue(DayNight.inWindow(10 * 60, 9 * 60, 17 * 60))
    }

    @Test fun nightModes() {
        val sun = 6 * 60 + 17 to 17 * 60 + 41
        val noon = 12 * 60; val evening = 20 * 60
        assertFalse(DayNight.isNight(NightMode.OFF, evening, 0, 0, sun, true))
        assertTrue(DayNight.isNight(NightMode.ON, noon, 0, 0, sun, false))
        assertTrue(DayNight.isNight(NightMode.SUN, evening, 0, 0, sun, null))
        assertFalse(DayNight.isNight(NightMode.SUN, noon, 0, 0, sun, null))
        assertTrue(DayNight.isNight(NightMode.HEADLIGHTS, noon, 0, 0, sun, true))      // tunnel at noon
        assertFalse(DayNight.isNight(NightMode.HEADLIGHTS, evening, 0, 0, sun, false))
        assertTrue(DayNight.isNight(NightMode.HEADLIGHTS, evening, 0, 0, sun, null))   // not reported → sun
    }

    @Test fun afListsParseAndCycle() {
        assertEquals(listOf(99_300, 101_100, 104_800), StationLogic.parseAfList("99.3, 101.1 104,8"))
        assertEquals(listOf(99_300), StationLogic.parseAfList("99.3; 1152; x"))       // AM and junk dropped
        val st = Station(1, Band.FM, 104_400, "Virgin Radio", afKhz = listOf(99_300, 101_100))
        assertEquals(99_300, StationLogic.nextAf(st, 104_400))
        assertEquals(101_100, StationLogic.nextAf(st, 99_300))
        assertEquals(104_400, StationLogic.nextAf(st, 101_100))
        assertNull(StationLogic.nextAf(st.copy(afKhz = emptyList()), 104_400))
    }

    @Test fun stationIsFoundOnItsAlternativeFrequency() {
        val d = StationData(stations = listOf(Station(1, Band.FM, 104_400, "Virgin Radio", afKhz = listOf(99_300)), Station(2, Band.FM, 92_000, "Dubai 92")))
        assertEquals(1L, StationLogic.find(d, Band.FM, 99_300)?.id)
        // On Virgin's AF 99.3, "next" (by frequency: Dubai 92, Virgin) wraps from Virgin to Dubai 92.
        assertEquals(2L, StationLogic.neighbour(d.copy(sort = SortMode.FREQUENCY), Band.FM, 99_300, up = true)?.id)
    }
}
