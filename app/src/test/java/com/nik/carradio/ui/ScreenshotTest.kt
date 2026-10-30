package com.nik.carradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.nik.carradio.core.Band
import com.nik.carradio.core.Cap
import com.nik.carradio.core.RadioController
import com.nik.carradio.core.Station
import com.nik.carradio.core.StationStore
import com.nik.carradio.core.TunerOpt
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens as PNGs in app/build/screenshots. Opt-in, because Robolectric is slow:
 *   ./gradlew :app:testDebugUnitTest --tests com.nik.carradio.ui.ScreenshotTest -Pscreenshots=1
 * Sizes: the FYT unit (1280×720 px at 240 dpi = 853×480 dp) and a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        assumeTrue("set -Pscreenshots=1", System.getProperty("screenshots").orEmpty().isNotEmpty())
        RadioController.update {
            it.copy(backendId = "fyt", backendName = "FYT / Syu MCU tuner", powered = true, band = Band.FM, freqKhz = 104_800,
                caps = setOf(Cap.TUNE, Cap.SEEK, Cap.STEP, Cap.BAND_AM, Cap.SCAN, Cap.RDS), stationName = "Radio Mirchi",
                radioText = "Now playing: Kesariya – Arijit Singh · Brahmastra", stereo = true, tp = true, af = true, loc = false,
                opts = mapOf(TunerOpt.TA to true, TunerOpt.AF to true, TunerOpt.LOCAL to false, TunerOpt.STEREO to true),
                status = "Playing via MCU tuner", error = null)
        }
        val names = listOf("Radio Mirchi" to 104_800, "City 101.6" to 101_600, "Virgin Radio" to 104_400, "Dubai 92" to 92_000,
            "Hum FM" to 106_500, "Radio Spice" to 105_400, "Emirates Radio" to 100_500, "Big 106.2" to 106_200)
        StationStore.update { d ->
            d.copy(stations = names.mapIndexed { i, (n, f) -> Station(i + 1L, Band.FM, f, name = n, favourite = i < 6, position = i, verified = true,
                streamUrl = if (i == 0) "https://example.invalid/mirchi.aac" else "") })
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme(colorScheme = themeById("ocean").scheme()) { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun radioHeadUnit() = shot("radio_headunit") { AppRoot() }

    @Config(qualifiers = "w1280dp-h720dp-land-mdpi")
    @Test fun radioLarge() = shot("radio_1280dp") { AppRoot() }

    @Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
    @Test fun radioPhone() = shot("radio_phone") { AppRoot() }

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun stationsHeadUnit() = shot("stations_headunit") { androidx.compose.material3.Surface(color = themeById("ocean").scheme().background) { StationsScreen() } }


    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun radioWithSpeed() {
        com.nik.carradio.core.UiConfig.update { it.copy(mainSpeed = true) }
        try { shot("radio_headunit_speed") { AppRoot() } } finally { com.nik.carradio.core.UiConfig.update { it.copy(mainSpeed = false) } }
    }

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun songHistory() {
        // Through the controller, the way the tuner reports radio text.
        listOf("Kesariya – Arijit Singh", "Apna Bana Le – Arijit Singh", "Traffic: slow on Sheikh Zayed Rd").forEach { t ->
            RadioController.update { it.copy(radioText = t) }
        }
        shot("song_history") { SongHistoryDialog {} }
    }

    private fun styled(name: String, style: com.nik.carradio.core.ScreenStyle) {
        val scheme = if (style == com.nik.carradio.core.ScreenStyle.CRUISE) cruiseScheme(com.nik.carradio.core.CruiseTint.AMBER) else themeById("ocean").scheme()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalScreenStyle provides style) { MaterialTheme(colorScheme = scheme) { AppRoot() } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun modernHeadUnit() = styled("modern_headunit", com.nik.carradio.core.ScreenStyle.MODERN)

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun modernRightHandDrive() {
        com.nik.carradio.core.UiConfig.update { it.copy(driverSide = com.nik.carradio.core.DriverSide.RIGHT) }
        try { styled("modern_headunit_rhd", com.nik.carradio.core.ScreenStyle.MODERN) }
        finally { com.nik.carradio.core.UiConfig.update { it.copy(driverSide = com.nik.carradio.core.DriverSide.LEFT) } }
    }

    @Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
    @Test fun modernPhone() = styled("modern_phone", com.nik.carradio.core.ScreenStyle.MODERN)

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun cruiseHeadUnit() = styled("cruise_headunit", com.nik.carradio.core.ScreenStyle.CRUISE)

    @Config(qualifiers = "w853dp-h480dp-land-hdpi")
    @Test fun settingsHeadUnit() = shot("settings_headunit") {
        androidx.compose.material3.Surface(color = themeById("ocean").scheme().background) { SettingsScreen() }
    }
}
