package com.nik.carradio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Transport icons as vectors (paths from Material Icons, Apache-2.0). Unicode media symbols
 * (⏮ ⏭ ⏻) are missing from or drawn as colour emoji by Android 8 fonts, so they are not used.
 */
object RadioIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()

    val Previous = icon("prev", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Next = icon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Rewind = icon("rew", "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z")
    val Forward = icon("ffw", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
    val Play = icon("play", "M8,5v14l11,-7z")
    val Power = icon("power", "M13,3h-2v10h2L13,3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z")
}
