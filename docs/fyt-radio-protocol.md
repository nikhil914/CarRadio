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
