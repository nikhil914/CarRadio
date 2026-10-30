package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UiConfigTest {
    @Test fun enumOrFallsBackOnMissingOrUnknown() {
        assertEquals(ScreenStyle.CRUISE, UiConfig.enumOr("CRUISE", ScreenStyle.MODERN))
        assertEquals(ScreenStyle.MODERN, UiConfig.enumOr(null, ScreenStyle.MODERN))
        assertEquals(ScreenStyle.MODERN, UiConfig.enumOr("", ScreenStyle.MODERN))
        assertEquals(NextPrevKey.STATION, UiConfig.enumOr("BOGUS", NextPrevKey.STATION))
    }

}
