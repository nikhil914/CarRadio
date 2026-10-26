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
