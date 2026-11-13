# FYT radio module protocol (decoded from com.syu.ms)

Source: `190000000_com.syu.ms.apk` (FYT UIS7862 firmware 2021-11-11, from the public hvdwolf/FYTuis7862BinRepo), read with the Android SDK's `dexdump`. The service code is obfuscated. The names below are what the code does, not original names.

Clients bind `com.syu.ms.toolkit` → `IRemoteToolkit.getRemoteModule(1)` = radio. Each `IRemoteModule.cmd(code, ints, flts, strs)` goes to the dispatcher `module.k.i.b` (a 34-entry packed switch, codes 0–33), and from there to the active radio implementation `module.k.d.b` (MCU tuner = `module.k.a`).

| cmd | Needs | Radio call | Meaning |
|----:|-------|-----------|---------|
| 0 | – | `g()` | next preset channel |
| 1 | – | `h()` | previous preset channel |
| 3 / 4 | – | `c()` / `d()` | step up / down (the value is ignored) |
| 5 / 6 | – | `e()` / `f()` | seek up / down |
| 7 | 1 int | `a(i)` | select channel i |
| 8 | 1 int | `b(i)` | save channel i |
| 9 / 10 | – | `j()` / `i()` | scan / save |
| 11 | 1 int | `c(i)` | band (-1 toggle, -2 AM, -3 FM) |
| 12 | 1 int | `d(i)` | area |
| **13** | **2 ints** | **`a(mode, value)`** | **set frequency (below)** |
| 14 | 2 ints | `b(a, b)` | – |
| 15–25 | 1 int | various | sensitivity, stereo, LOC, RDS AF/TA/PTY, search (22), sort… |
| 26 | arrays | `f.a(ints, flts, strs)` | – |
| 33 | ≥1 int | `a(ints[0], rest…)` | generic |

## `setFreq(mode, value)` in the MCU tuner (`module.k.a.a(II)V`)

| mode | value | Behaviour |
|-----:|-------|-----------|
| 0 | step index 0…N | MCU packet `E1 05 idx/100 idx%100 EE EE` (dropped if idx < 0 or > N) |
| 1 | frequency | index = (v − K)·N / (L − K) → mode 0. **Silently dropped while L − K = 0** |
| 2 | 0…65535 | index = N·v / 65535 → mode 0 |
| **3** | **frequency, 10 kHz units** | **MCU packet `25 hi lo`, value clamped 0…10800: direct, no band limits needed** |

K, L and N (band min, max and step count) are filled only when the MCU sends its band-range packet. On the UIS8141E test unit that had not happened for third-party apps, so mode 1 (used by Car Radio 1.0.0 and 1.0.1) did nothing, while stepping (cmd 3/4) worked. Car Radio 1.0.2 tunes with mode 3.

The MCU reports a new frequency slowly (> 600 ms on the UIS8141E), so confirmation windows are 2–2.5 s.

## Settings commands (decoded for Car Radio 1.2)

Command numbers are the dispatcher cases; names match `com.syu.ipc.FinalRadio` from the FYT launcher. "MCU key" is the 3-byte packet `01 03 xx` (or `01 00 xx`) sent to the MCU. *Toggle* commands only act on value **2**; 0 and 1 are ignored, so set them by comparing with the reported state and sending 2.

| cmd | Name | Value | MCU packet | Reported by update |
|---:|---|---|---|---|
| 9 | C_SCAN | – | key 18 | 22 (search state) |
| 10 | C_SAVE | – | key 8 | – |
| 11 | C_BAND | -1 toggle, -2 AM, -3 FM | key 24 | 0 |
| 12 | C_AREA | 0…4 | key 33+n | 2 |
| 14 | C_SENSITY | [band 0/1, -1 or -2] | `01 00` 154–157 | 17 (AM), 18 (FM) |
| 15 | C_AUTO_SENSITY | 0 off, 1 on, 2 toggle | `01 00` 158/159 | 19 |
| 16 | C_RDS_ENABLE | 0 off, 1 on, 2 toggle | `01 00` 96/97 | 15 |
| 17 | C_STERO | 2 = toggle | key 11 | 21 |
| 18 | C_LOC | 2 = toggle | key 13 | 23 |
| 19 | C_RDS_TA_ENABLE | 2 = toggle | key 22 | 9 |
| 20 | C_RDS_AF_ENABLE | 2 = toggle | key 23 | 6 |
| 21 | C_RDS_PTY_ENABLE | 2 = toggle | key 21 | 20 |
| 22 | C_SEARCH | **2** = start/stop auto search | key 4 | 22 |
| 23 | C_SORT_TYPE | 0 / 1 | key 128 when different | 24 |

Car Radio 1.0–1.1 sent `C_SEARCH [1]`, which this firmware ignores.

Other MCU keys: 17/16 step up/down, 6/5 seek up/down, 10/9 next/previous preset.

### Areas (update 16 = [min, max, step, count], FM in 10 kHz units, AM in kHz)

| Area | Region | FM | AM |
|---:|---|---|---|
| 0 | USA | 87.5–107.9 MHz, 200 kHz | 530–1720, 10 kHz |
| 1 | Latin America | 87.5–108 MHz, 50 kHz | 520–1620, 10 kHz |
| 2 | Europe | 87.5–108 MHz, 50 kHz | 522–1620, 9 kHz |
| 3 | Russia | FM1 65–74 MHz (OIRT, 30 kHz), FM2/3 87.5–108 MHz, 100 kHz | 522–1620, 9 kHz |
| 4 | Japan | 76–90 MHz, 100 kHz | 520–1629, 9 kHz |

## Radio implementations and the source gate (decoded for 1.5)

com.syu.ms picks one radio implementation at start-up and publishes it as `sys.fyt.radio_type`
(2021 UIS7862 build, `module.k.f.a(I)`; the 2018 build names them `CmdRadio`, `CmdRadioArm`, `CmdRadioInner`):

| radio_type | Class (2021) | Tuner | set-frequency (cmd 13) |
|---:|---|---|---|
| 1 | `module.k.a` | MCU tuner | modes 0–3; mode 3 = direct MCU packet `25 hi lo` |
| 2 | `module.k.h` | none | ignored |
| 3 | `module.k.c` | SoC FM chip, run by the stock radio app | **mode 1 only, only while `sys.app_id` = 1**; forwarded as update 28 `[13, value]` |
| 4 | `module.k.b` | SoC FM chip (variant) | same as 3 |

On types 3/4 every command is forwarded to the stock radio app on **update code 28**: step up `[3]`,
step down `[4]`, seek `[5]`/`[6]`, select preset `[7, n]`, search `[22]`, set frequency `[13, value]`.
Step and seek have no source check; set-frequency does (`module.i.e.E`, mirrored in `sys.app_id`).
That matches a unit where stepping works and direct tuning is silently ignored.

The full radio test (Diagnostics) reads both properties, registers for update 28 to see what is
forwarded, and tries every format and unit, with and without claiming the source and with the
stock radio app open.

Other references checked: FYT launcher `FinalRadio` constants; the Dudu7 (UIS7870) client in
headunit_landscape (tuner driven through FmService/libfmjni, Syu used for the source); Radio+
(edman01/radio-plus-open: Junsun/HCN/NWD/TS/SPD/Reglink stacks, explicitly not FYT); HiDefRadio
(RTL-SDR USB dongles, not head-unit tuners).

## Confirmed on the UIS8141E unit (full radio test, 1.5.0)

`sys.fyt.radio_type=4`, `ro.fyt.platform=813`. Stock radio: `/oem/app/190001002_com.syu.radio` (exported
`com.syu.broadcast.MyService`, launch `com.syu.radio/.Launch`, own `com.android.fmradio.FmService`).

- With the stock radio app not running, com.syu.ms forwarded every command on update 28 (`[3]`, `[4]`,
  `[5]`, `[13,9810]`, …) and nothing moved; it reported frequency `522` (placeholder) while the band was FM.
- With the stock app open, `cmd(13,[1,9810])` was answered with frequency 9810 **7 ms** later.
  `[1,98100]` landed on 87.5 (out of range, clamped). Commands kept working with the app in the background.
- The firmware's `libfmjni.so` refuses to load in another app (`JNI_OnLoad` fails), so the chip cannot
  be driven directly; Car Radio keeps the stock app running and tunes through com.syu.ms.

### Starting the stock radio app (1.6.1)

- The first 1.6.1 test build started the stock radio service at app start, distrusted the
  frequency com.syu.ms replays on connect, and failed radio on when the stock app did not answer.
  On the unit that was worse than 1.5.1: "Failed" on opening, and the stock radio screen kept
  opening on every station change. All three were reverted to 1.5.1 behaviour.
- Kept: the stock app is nudged up to three times (service) / twice (screen) before giving up,
  and its screen is opened at most once per radio on.

### Steering-wheel next/prev on SoC-FM units (1.6.1)

Reported on the UIS8141E: wheel next kept landing on 88.0 (the stock radio app's first preset).
One press can reach both Car Radio (media key) and com.syu.ms, which forwards it to the stock
radio app; the app's jump came while Car Radio was still in its own "expect" window and was
ignored, so 88.0 stuck. Now:

- Car Radio registers for update 28 (`U_FORWARD`). Forwarded `[0]`/`[1]` (next/previous preset,
  never sent by Car Radio) are wheel keys; forwarded step/seek count only outside Car Radio's own
  command window. A fresh wheel key is acted on even while a tune is still settling.
- After Car Radio reaches a station it defends it for 4 s: a jump with no key in that time is
  undone (at most twice).
- A media key and a vendor jump within 1.5 s in the same direction are one press, handled once.
- No key reported at all: the tuner landing on the same frequency again from another station
  (within 2 min) is treated as wheel NEXT.

### Who carries out commands on radio types 3/4 (1.6.3)

From the DABplay+ FM project's notes on the stock FYT apps (github.com/creafy/dabplayplusfm, `docs/fyt-radio.md`,
EKIY T900, radio_type 4):

- Only the stock radio app's **screen** (`Act_Radio` → page `onNotify`) carries out the commands com.syu.ms forwards
  on update 28. Its keep-alive service only starts the process, which powers the chip up at the app's **own last
  station** when the source becomes radio. So a frequency report is no proof that commands work: that is how 1.5–1.6.2
  could think the stock app was ready while tuning and the wheel landed on its station (88.0).
- The screen opens tuned: action `com.syu.radio`, component `com.syu.radio/com.syu.radio.act.ActRadio`, data with a
  query parameter `freq` (10 kHz units). It keeps working behind other apps; its Back key gives up the radio source.
- With `persist.lsec.radio.ms_handler` set, com.syu.ms drives the chip itself and the stock app is not needed.
- The stock app re-asks for the radio source every second until main update 0 says 1.
- An app that plays audio or holds audio focus becomes source 10 and the stock app powers the chip off (Car Radio
  requests no focus for FYT).

Car Radio 1.6.3: a command counts as carried out only when the tuner reaches the frequency Car Radio asked for. Until
then, a tune (while Car Radio is in front) opens the stock radio screen tuned to the target, checks it, and brings
Car Radio back; at most every 20 s. Opening Car Radio while the radio plays but does not obey does the same once.
The radio check at start logs ms_handler and which stock screen was found.
- Car Radio's Radio-key proxy (package `com.syu.radio`) must not be installed on radio types 3/4: it replaces the
  stock radio app that drives the chip. NavRadio+'s RadioProxy does the same, and NavRadio+ lists only MCU-tuner FYT
  platforms (UIS7862/7870/8581, SC9853i, SC9863A), not UIS8141. Radio on now stops with a clear message when the
  proxy is installed on such a unit, and Settings → Radio key warns.

### Full radio test on the UIS8141E (radio type 4, 2026-11-13, Car Radio 1.6.3)
- Tunes: `cmd(13,[1,v10])` (98.1 MHz → 9810), with or without a source claim, with or without the stock screen open.
- Moves: step `cmd(3/4,[0])`, seek `cmd(5/6,[0])`, preset next/prev `cmd(0/1,[0])`; all forwarded as update 28.
- `cmd(13,[1,kHz])` (98100) sends the tuner to 87.5 MHz. Mode 3, mode 0/2, code 33 and code 2 are not forwarded at all.
- Starting `com.syu.broadcast.MyService` from Car Radio is refused by Android 8 while the stock app is cached
  (`IllegalStateException: Not allowed to start service … app is in background`). The screen wake still works.
- `libfmjni.so` cannot be loaded by another app (`JNI_OnLoad` returns JNI_ERR), so the SoC FM backend stays unusable.

Car Radio 1.6.4: on radio types 3/4 the direct-tune fallback only uses `cmd(13,[1,v10])`, so a slow first answer no
longer falls through to the kHz form that jumps to 87.5 MHz.
