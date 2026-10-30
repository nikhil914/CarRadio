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

## Screen styles (1.6.0)

- **Three Radio-screen styles**, switched instantly in Settings → Appearance (cards with a small preview). Switching only recomposes the UI: the tuner, the MediaSession service and the audio route are never touched.
  - **Modern clean** (default): band pill and a quiet top bar (mute, tuner, stations, ⋯, settings), a large frequency that slides up or down as it changes, one line of radio text, a 3×2 pad of 88 dp keys 16 dp apart on the driver's side (station ◀ · on/off · station ▶, seek ◀◀ · ★ · seek ▶▶), and a snapping favourites rail. Enter frequency, Scan, FM/AM, Tuner, Listen online and Full screen sit behind ⋯.
  - **Classic dense**: the 1.3–1.5 screen with every control exposed.
  - **Night cruise**: pure black, one tint (amber, cyan or red), name, frequency, one dim line of radio text and three outlined keys (hold = seek). By default it takes over at night (the Day and night setting decides when).
- **Driver position:** left- or right-hand drive puts Modern clean's pad on the driver's side.
- **Settings** is a section list (Appearance, Day and night, Radio and tuner, Keys and steering wheel, Stations and backup, Floating player and history, Updates, Reports and advanced) with the chosen section beside it.
- **Keys:** next/previous keys can step through saved stations (default), seek, or step one channel; play/pause can turn the radio on/off (default) or mute. Channel up/down keys follow the same choice. Media keys still go through the MediaSession, so they work with the app in the background. D-pad and rotary focus is shown as a thick ring in the theme colour.
- FM/AM only: the FYT tuner has no DAB, so there is no DAB band.

## Polish (1.0.0)

- **Day and night:** always day, always night, by time, sunset to sunrise (computed for Abu Dhabi on the unit, no GPS or internet), or with headlights (FYT main-module update 4, the ILL wire; it falls back to sunset/sunrise until the unit reports it). Day and night themes are chosen separately; the night theme defaults to Night red. It is re-evaluated each minute and immediately when the headlights change.
- **Speedometer in full screen:** GPS speed in km/h. Location updates run once per second, and only while full screen is visible. It asks for location permission when you switch it on.
- **Backup and restore:** one JSON file with stations, groups, AF lists, logos (base64 WebP), appearance and radio settings, saved and opened with the system file picker. The GitHub token is never included.
- **Alternative frequencies:** each station can list other transmitters of the same programme. Double-tap the station to cycle through them, and the app still recognises the station when tuned to one of its AFs. FYT's own AF is unreliable (NavRadio says so too), so this is manual by design.
- **Settings in sections:** Appearance, Day and night, Radio, Stations and backup, Radio key, Internet radio, Reports, Advanced.

## System hooks (0.5.0)

- **Home-screen widgets:** wide (4×1) and square (2×2), each with the logo or drawn initials, the station, frequency and RDS line, plus previous, on/off and next. They are plain RemoteViews: nothing runs for them, and they redraw only on a visible change (debounced), and only when a widget is placed.
- **Resume:** if the radio was on when the unit went down, it comes back on cold boot (`BOOT_COMPLETED` / quick boot) and on ACC on (FYT main-module update 50). Only an explicit "off" clears that state. It never starts the media foreground service from boot on Android 15+. Switch it off in Settings.
- **Radio-key proxy (`radioproxy/`):** a 9 KB app with package `com.syu.radio` and the same activity names as the FYT radio, which opens Car Radio. It installs as an update to the firmware's radio app only when signed with the same key, so it is built with both public AOSP keys (`keystore/aosp/testkey.jks`, `platform.jks`, from AOSP `build/target/product/security`, password `android`). Car Radio → Settings → Radio key compares the firmware app's signing certificate with these keys and tells you which file will install, or that none will. Undo: Settings → Apps → Radio → Uninstall updates. Car Radio never launches its own proxy as "the stock radio".

## Look and logos (0.4.0)

- **Three layouts**, switchable in Settings → Appearance with a live preview: grid (2–6 columns), list, and carousel (snaps, and follows the playing station when the wheel keys change it).
- **8 flat dark themes:** Ocean, Emerald, Amber, Crimson, Violet, Teal, Graphite, and Night red for night driving. Four text sizes: only fonts scale, touch targets stay the same size.
- **Full screen:** double-tap the frequency, or let it open after 15, 30 or 60 s without a touch while playing. It shows a big frequency, the name, the logo, the programme type or radio text, and a clock. Tap the left or right third to change station, the middle to close. The system bars are hidden.
- **Logos:** long-press a station → Choose logo, or Settings → Import logo folder (files named after the station, e.g. `Virgin Radio.png`, `virgin.png`, `104.4.png`). Each logo is fitted to 96×96 and stored as WebP, decoded with subsampling, and cached in an LRU. No logos ship with the app, because they are the stations' trademarks. Without one, the station's initials are drawn on a colour derived from its name.

## Stations and wheel keys (0.3.0)

- **Station groups (playlists):** Hindi, English, Arabic, Malayalam, plus your own (`+ Group`; long-press a group of your own to delete it). Also All, Favourites and Unsorted. One station can sit in several groups.
- **Seeded UAE list:** 13 stations with Abu Dhabi frequencies where known, shown as "unconfirmed" until a scan hears them.
- **SCAN:** seeks up through the band and stays 2.5 s on each station to read its RDS name. Known stations become confirmed; new ones are filed into a group by RDS name or frequency, otherwise they go to Unsorted.
- **Station grid:** tap to play, long-press to edit the name, frequency, groups and favourite, move, or delete. `+ SAVE` stores the current station in the selected group. The sort button cycles through manual order, frequency and A–Z.
- **Steering-wheel next/prev:** on FYT, `com.syu.ms` handles the wheel key itself and jumps to its own preset (seen on Dudu7 captures). Car Radio watches the FYT steer module (keys 87/88), sees the jump it didn't ask for, and redirects it to the next or previous station in your current group. A MediaSession also handles standard media keys and gives launchers (Agama, FCC, CarLauncher) the station name and radio text. The redirect can be switched off in Settings.
- **RDS:** programme type (News, Pop music…) plus TP, TA, AF and LOC/DX indicators when the tuner reports them.
- Stations live in `stations.json`, which is written atomically. Settings has a button that copies it as a JSON backup.

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
