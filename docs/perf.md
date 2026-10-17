# Performance on the reference tablet

Galaxy Tab 3 10.1 (GT-P5210), LineageOS 14.1 (Android 7.1), Atom Z2560 2 × 1.6 GHz,
1 GB RAM, 1280×800 at density 1.0. Release build, installed with `adb install -r`.

## How it is measured

Always with **15 demo printers, all printing** (Settings → Demo, *Every printer
prints* on), no camera open, the app in front for at least a minute.

1. `dumpsys gfxinfo com.damou.tigertouch reset`, then 30 s without touching:
   the frames the once-a-second refresh costs, and the app's share of the CPU
   (from `/proc/<pid>/stat` and `/proc/stat`, both cores together).
2. `gfxinfo reset`, then 10 swipes down and up (`input swipe`, 250 ms each):
   scrolling.
3. `dumpsys meminfo com.damou.tigertouch`: TOTAL PSS.

Targets: under 10 % janky frames, under 120 MB of PSS.

"Janky" is Android's own count (`Janky frames` of gfxinfo): a frame over 16 ms
or one that missed its deadline.

## Baseline (before the fleet work)

The grid of large cards (three per row, five rows to scroll for 15 printers).
Every card recomposes every second: the views are rebuilt as new mutable
objects at each refresh, so Compose cannot tell that nothing changed.

| | Frames | Janky | 90th pct | 99th pct | CPU (app) |
|---|---|---|---|---|---|
| Idle 30 s | 32 | 32 (100 %) | 69 ms | 101 ms | 2.5 % |
| Scroll ×10 | 876 | 750 (85.6 %) | 73 ms | 150 ms | |

Memory: **67.3 MB** PSS (Java heap 13.4 MB, native heap 9.2 MB, code 19.6 MB).

Every refresh is a janky frame (about 70 ms on the UI thread), and scrolling is
unusable. The memory is well inside the budget.
