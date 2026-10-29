package com.nik.carradio.core

/** Process-wide UI facts the radio logic needs. */
object AppState {
    /** Car Radio's screen is in front (between onStart and onStop). */
    @Volatile var foreground = false
}
