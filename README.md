# Car Radio

A generic FM/AM radio app for Android car head units. It doesn't assume one hardware path. At start it probes every known way to control a tuner, picks the best working one, falls back automatically, and explains every failure in plain language.

Built first for an **FYT UIS8141E (SC7731E)** unit (reports "Android 13", really Android 8.1, build `OPM2.171019.012`), but it works on any unit that matches one of the methods below.

## Radio methods (tried in this order)

| # | Method | Platforms | Needs system permission? | Status |
|---|--------|-----------|--------------------------|--------|
| 1 | **FYT / Syu MCU tuner**: binds `com.syu.ms` (`com.syu.ms.toolkit`), radio module 1, main-module cmd 0 switches the MCU audio source | FYT: UIS8141E, UIS7862/7870, SC9853i, PX5-FYT, Joying, many "Tesla style" units | No | Full: tune, seek, step, FM/AM, auto-scan, RDS |
| 2 | **TopWay TWUtil**: firmware class `android.tw.john.TWUtil` (registers 1025/1026) | TopWay TS7/TS10/TS18, some Dudu | No | Full |
| 3 | **SoC FM chip**: firmware `libfmjni.so` via `com.android.fmradio.FmNative` JNI | FYT/Dudu SoC-FM units (UIS7870), MTK/Unisoc-derived FM | Sometimes (`/dev/fm` access) | FM: tune, seek, step, RDS, mute |
| 4 | **Microntek CarManager**: `av_channel_enter=fm` | MTCB/MTCD/MTCE | No | Source switch + opens stock radio |
| 5 | **Android BroadcastRadio HAL**: `RadioManager` | AAOS / AOSP units | **Yes** (`ACCESS_BROADCAST_RADIO`, signature\|privileged) | Detect and explain only |
| 6 | **Stock radio app**: launches `com.syu.radio` etc. | any | No | Always works if the unit has a radio app |
| 7 | **Internet stream**: MediaPlayer with a URL from Settings | any with internet | No | Play/stop |

### Why the "system permission" problem usually doesn't apply

`ACCESS_BROADCAST_RADIO` only guards Android's standard radio HAL (method 5). Aftermarket units wire the tuner to their MCU and expose it through a vendor service (methods 1, 2, 4), which any installed app can call. That's how NavRadio+ works as a normal APK.

## Robustness features

- **Crash guard**: a marker is written synchronously before every backend start. If a vendor native library kills the app, the next launch disables that method for auto mode and says so. You can re-enable it in Settings.
- **Auto fallback**: if the chosen method fails to start, the next working one is tried, and every reason is listed.
- **Self-learning tuning (FYT)**: the app tries both known direct-tune payloads, checks each against what the tuner reports back, remembers which one works, and falls back to single steps if neither does.
- **Persistent log**: every step is written to a file. The Log tab and "Share report" show the full trail, including the step that was running if the app was killed.
- **Diagnostics tab**: per-method status (WORKS / MAYBE / BLOCKED / NO / CRASHED), with technical details, "Use this", "Test start", device properties, vendor packages, `libfmjni` and `/dev` nodes.

## Sending the diagnostics report (no USB needed)

Diagnostics → **Send report** offers:

- **QR codes (offline):** the report is split into numbered ASCII pages of 1,000 characters, one QR code each. Scan every page with your phone camera and paste the text.
- **GitHub issue:** one tap, once a fine-grained token with *Issues: Read and write* on this repo is saved under Settings → GitHub report upload. The token lives only on the unit and is never in the APK, because this repo is public. Issues are public too: the report contains device and firmware details, not personal data.
- **Share sheet / clipboard:** WhatsApp, Gmail, Telegram, or any app on the unit.

## Performance (0.2.0)

- R8 shrinking: the release APK dropped from 6.6 MB to 1.2 MB.
- `profileinstaller` installs the baseline profiles that ship with Compose on first run. On Android 7–8.1, ART compiles them during idle dexopt.
- Fast start: only the method that worked last time is probed at launch. The full probe of all 7 methods runs on first launch, after a failure, or when Diagnostics opens.
- Buffered log: info lines are flushed every 2 s. Risky-step markers, warnings and errors are flushed immediately, so crash breadcrumbs survive.
- Device-info queries run off the main thread. Presets no longer recompose on every RDS update.

## First run in the car

1. Install `CarRadio-<version>.apk` (sideload, allow unknown sources).
2. Open it with the engine or ACC on. It probes and, if a tuner method works, starts the radio at the last frequency.
3. If anything is wrong, open **Diagnostics → Share report** (or Copy report) and send the text. It contains everything needed to add support.

## Build

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest lintDebug
```

The `keystore/sideload.jks` key (password `carradio`) is committed on purpose. Every build, local or CI, is signed with the same key, so updates install over the previous version. It is not a Play Store key. Keep this repo private.

## Planned enhancements

- MTC direct tuning parameters (needs a report from an MTC unit)
- BroadcastRadio HAL tuning when installed as a priv-app
- MediaSession for steering-wheel keys, home-screen widget, station list from scan, RDS PTY

## Credits and protocol references

- FYT IPC interfaces: vasyl91/FYT-Launcher-Mod (`com.syu.ipc.*`, `FinalRadio`, `FinalMain`)
- FYT SoC-FM / `FmNative` JNI contract: andreassamitsch/headunit_landscape (Dudu7 / NavRadio+ reference)
- TopWay `TWUtil` register map: dipcore/Radio-MST768
