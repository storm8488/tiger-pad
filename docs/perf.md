# Performance on the reference tablet

Galaxy Tab 3 10.1 (GT-P5210), LineageOS 14.1 (Android 7.1), Atom Z2560 2 × 1.6 GHz,
PowerVR SGX544, 1 GB RAM, 1280×800 at density 1.0. Release build, installed
with `adb install -r`.

## How it is measured

Always with **15 demo printers, all printing** (Settings → Demo, *Every printer
prints* on), no camera open, the app in front for at least 30 s.

1. `dumpsys gfxinfo com.damou.tigertouch reset`, then 30 s without touching:
   the frames the once-a-second refresh costs, and the app's share of the CPU
   (from `/proc/<pid>/stat` and `/proc/stat`, both cores together).
2. `gfxinfo reset`, then 10 swipes down and up (`input swipe`, 250 ms each):
   scrolling (Cards and List; the Wall does not scroll).
3. `gfxinfo reset`, then 12 taps on the density switch (Wall, Cards, List, four
   times): changing view.
4. `dumpsys meminfo com.damou.tigertouch`: TOTAL PSS.
5. For the Wall, the figure that counts: `gfxinfo` over **3 minutes** of the
   wall, untouched (30 s hold too few frames for a stable percentage).

Targets: under 10 % janky frames, under 120 MB of PSS.

"Janky" is Android's own count (`Janky frames` of gfxinfo): a frame over
16.7 ms or one that missed its vsync.

### Two states of the code

Android 7 installs an app **interpreted** (`compilation_filter=interpret-only`)
and compiles it only in its background job, when the tablet is idle on its
charger, with the profile the app has gathered. A kiosk tablet is always on its
charger: it is compiled after its first night. The measures are taken in both
states:

- **fresh**: right after `adb install -r`;
- **compiled**: as after that first night, forced with
  `cmd package compile -m speed-profile -f com.damou.tigertouch` after the app
  has run for a minute (its profile written).

### Why it was slow, and what changed

Traces (`atrace -a com.damou.tigertouch gfx view`) and `gfxinfo framestats`
showed where a frame went:

- **Every card recomposed every second.** The driver views were rebuilt as new
  mutable objects at each refresh, so Compose could not tell that nothing had
  changed. Now `PrinterUi` is immutable and rounded to what is shown (whole
  degrees with a degree of hysteresis, whole minutes), made off the main
  thread, and each printer has its own state (`FleetView`): a refresh redraws
  the printers that changed and recomposes nothing around them.
- **Compose's text is expensive on this Atom** (several ms per card at each
  change, in layout and in `TextMeasurer`). What a printer says is now drawn
  with the platform's `drawText` in one node per tile, card or row; only the
  buttons stay composables.
- **The GPU redraws the whole window at every frame**: the driver has no
  `EGL_EXT_buffer_age`, so no partial update. Each tile, card and row is now
  a texture of its own (an offscreen layer): a frame redraws the one that
  changed and composites the others. Rounded clips (one layer each) and
  `Modifier.alpha` (an offscreen buffer each) are gone, and the window's own
  background is the page's, one full-screen fill less.
- **The smart order and the summary** are computed with the fleet, off the main
  thread, and reach the screen only when they change. While a finger scrolls,
  a list keeps its order (it reorders when the scroll stops).
- **R8** is on: 11.7 MB → 1.6 MB of APK, and about 10 MB less of code in
  memory.

## Baseline (before the fleet work)

The grid of large cards (three per row, five rows to scroll for 15 printers),
commit `1a8a041`.

| | Frames | Janky | 90th pct | 99th pct | CPU (app) |
|---|---|---|---|---|---|
| Fresh, idle 30 s | 32 | 32 (100 %) | 69 ms | 101 ms | 2.5 % |
| Fresh, scroll ×10 | 876 | 750 (85.6 %) | 73 ms | 150 ms | |
| Compiled, idle 30 s | 32 | 32 (100 %) | 73 ms | 97 ms | 2.0 % |
| Compiled, scroll ×10 | 762 | 742 (97.4 %) | 77 ms | 150 ms | |

Memory: **67.3 MB** PSS fresh, 67.0 MB compiled.

Every refresh was a janky frame of about 70 ms, and scrolling was unusable.

## After priority 1 (Wall, Cards, List)

Commit `8c624ca`, compiled. Before the drawn tiles, the same three views built
from composables gave 74 % (wall, idle), 91 % (cards, scroll), 64 % (list,
scroll): the work listed above took them to:

| | Frames | Janky | 90th pct |
|---|---|---|---|
| Wall, idle 30 s | 16 to 17 | 1 to 3 (6 to 18 %) | 16 to 19 ms |
| Cards, scroll ×10 | 1259 | 382 (30.3 %) | 24 ms |
| List, scroll ×10 | 1148 | 203 (17.7 %) | 23 ms |
| Switching view ×12 | 603 | 69 (11.4 %) | 21 ms |

Memory: 66 to 80 MB PSS (the textures of the tiles).

## After priority 2 (smart order, summary, filters)

Commit `43ef3f2`, compiled.

| | Frames | Janky | 90th pct |
|---|---|---|---|
| Wall, idle 30 s | 18 | 2 (11.1 %) | 19 ms |
| Cards, scroll ×10 | 1345 | 458 (34.1 %) | 23 ms |
| List, idle 30 s | 16 | 4 (25 %) | 22 ms |
| List, scroll ×10 | 1319 | 386 (29.3 %) | 22 ms |
| Switching view ×12 | 409 | 28 (6.9 %) | 11 ms |

Memory: 69 to 82 MB PSS. The list scrolls worse than after priority 1: the smart
order moves rows while the demo's (accelerated) end times shift; fixed after
priority 3 (below).

## After priority 3 (alerts, journal)

Commit `b830572`, compiled. Prints finish during the measure: the tiles that
blink add cheap frames (a border, two steps a second).

| | Frames | Janky | 90th pct |
|---|---|---|---|
| Wall, idle 30 s | 59 | 3 (5.1 %) | 14 ms |
| Cards, idle 30 s | 98 | 4 (4.1 %) | 12 ms |
| Cards, scroll ×10 | 1251 | 454 (36.3 %) | 24 ms |
| List, scroll ×10 | 1309 | 396 (30.3 %) | 24 ms |
| Switching view ×12 | 444 | 25 (5.6 %) | 11 ms |

Memory: 59 to 77 MB PSS.

## Final (R8, order held while scrolling, baseline profile)

Commits `02bbbc1` to `d74a45e`.

**The Wall, 3 minutes untouched** (the figure of the success criterion):

| | Frames | Janky | 50th pct | 90th pct | 99th pct | PSS |
|---|---|---|---|---|---|---|
| Compiled | 309 | 21 (**6.8 %**) | 7 ms | 14 ms | 53 ms | **47.7 MB** |
| Fresh | 319 | 34 (10.7 %) | 8 ms | 16 ms | 77 ms | 45.9 MB |

The other views, compiled (30 s idle, 10 swipes, 12 switches):

| | Frames | Janky | 90th pct |
|---|---|---|---|
| Cards, scroll ×10 | 1258 | 450 (35.8 %) | 24 ms |
| List, scroll ×10, smart order held | 1180 | 146 (12.4 %) | 19 ms |
| List, scroll ×10, by name | 1206 | 155 (12.9 %) | 19 ms |
| Switching view ×12 | 436 | 25 (5.7 %) | 11 ms |

Memory: 43 MB (wall) to 59 MB (list) PSS. CPU: under 1 % of the two cores at
rest. A demo event is detected 0.28 s after it happens (logcat), well within the
1 s refresh.

## Before / after, in short

| | Before (compiled) | After (compiled) |
|---|---|---|
| 15 printers on one screen | no (5 rows to scroll) | yes (Wall, 5 × 3) |
| Idle frames | 100 % janky, ~70 ms | 6.8 % janky, median 7 ms |
| Scrolling the list | 97 % janky | 12 % janky |
| Changing view | — | 6 % janky |
| PSS | 67 MB | 43 to 59 MB |
| APK | 11.7 MB | 1.6 MB |

## Priority 4 (night, screen saver)

Commits `4522c86` to `b15dcd7`, compiled, measured at night (23:15 to 23:40,
in the night hours 23 h – 7 h).

What it adds, and what it costs:

- **Dimming and night page** come from a tick every 5 s that reads the clock
  and the idle time: it writes a state only when the mode changes, so it draws
  nothing. The brightness is the window's (`screenBrightness`), set only on a
  change of mode.
- **The night page** is one node drawn with the platform's text, in place of
  the printers page (nothing is drawn under it). Redrawn at each change of a
  printer, it was 182 frames in 3 min at 20 ms each (69 % janky: a full-screen
  redraw on this GPU); it now redraws when the minute turns or an alert comes
  or goes, and reads the printers without watching them.
- **The few pixels' move** every 3 min is a layer's translation: the tiles keep
  their textures, one frame recomposites them.
- **The touch that wakes** is taken in `dispatchTouchEvent` (and in the
  full-page dialogs' window callback) before anything sees it.

| | Frames | Janky | 50th pct | 90th pct | CPU (app) | PSS |
|---|---|---|---|---|---|---|
| Wall, awake, 3 min | 382 | 18 (**4.7 %**) | 9 ms | 13 ms | 1.0 % | 50.0 MB |
| Night page, 3 min | **4** | 3 | 23 ms | 23 ms | **0.4 %** | 51.9 MB |
| Waking (night → wall) | 10 | 3 | 10 ms | 81 ms | | |

The Wall stays under the target (6.8 % before; the difference is the noise of
two runs). Waking costs three frames of 69, 84 and 37 ms: the wall is composed
again from nothing, while the backlight comes up. Keeping it composed under
the night page would make the wake instant but redraw it all night.

Waking on an event, logcat, with the demo's *In 2 min* incident while the night
page was on: incident at 23:11:28.249, events seen at .885, full brightness at
.897. A touch at night brings the wall back and opens nothing.

## What is still over the target

- **Scrolling the cards: about 35 % janky.** A card is a large texture
  (380 × 420); a frame of scrolling redraws the window and composites six to
  nine of them, about 23 ms on this GPU. Without the textures it is far worse
  (85 %). With 15 printers, Auto shows the Wall, not the cards: the cards are
  the view for three printers, where nothing scrolls.
- **The list, idle, in the smart order**: when the order changes (the demo's
  end times move six times faster than real ones), a frame lays out every row
  and takes 35 to 65 ms. Real end times barely move.
- **Fresh install**: until the tablet's first night on its charger, the code
  is interpreted and the wall is at 10.7 %.

## Cameras in the cards

Settings → Display → *Cameras in the cards*: *Live* (the default), *Preview*,
*Off*. A thumbnail sits above the card of each printer **at work** (printing or
paused) that has a camera; an idle printer shows none.

How the pace is chosen (`cam/CameraHub.kt`):

- a thumbnail asks for one picture at a time (`?action=snapshot`), never the
  continuous MJPEG stream, which would send every frame of every camera over
  the tablet's Wi-Fi;
- a moving average of what one decode costs on this tablet sets a budget of
  500 ms of decoding per second (half of one core), shared between the
  thumbnails open: only the cards on screen hold one. Each camera gets between
  0.2 and 8 pictures per second;
- everything waits while a finger scrolls, while a camera is full screen, at
  night and when the app is not in front; a dimmed screen falls back to one
  picture every 3 s;
- pictures are decoded at the smallest power-of-two scale that keeps three
  quarters of the thumbnail's width, in RGB_565, into three bitmaps used in turn.

Measured with 15 demo printers, 14 at work, Cards view, six thumbnails on
screen (demo cameras: 1280 × 720 JPEGs with grain, decoded for real):

| | Value |
|---|---|
| One decode | 37 to 41 ms |
| Pace per camera | 2.2 pictures/s (one every ~450 ms) |
| App CPU, 40 s untouched | 46 % of one core |
| Janky frames, 40 s untouched | 19 / 477 (4.0 %), 90th pct 14 ms |
| Janky frames, scroll ×10 | 111 / 760 (14.6 %), 90th pct 21 ms (cards alone: 35.8 %) |
| Memory | 67 MB PSS |

With fewer cameras on screen each one gets more: from the measured decode
cost, two cameras should get about 6 pictures per second each (computed, not
measured). With the real K1 Max camera (one thumbnail, 110 KB snapshots), the pace
was about one picture a second: the network, not the tablet, sets it there.
Each snapshot took 0.4 to 2.9 s to arrive over the tablet's Wi-Fi (-64 dBm,
5 GHz). The hub waits no longer than the pace asks, so a better signal gives
more pictures on its own.

### The wall

The same cameras on the wall: a printer at work shows its picture whole and
untinted in the middle of its tile, between the name and the times, framed in
the state's colour; the number is drawn over it with a shadow. The picture is
a layer of its own between the tile's ground and its number; the tile
recomposes only when the print starts or ends.

Measured with 15 demo printers, 14 at work, all 14 cameras on one screen:

| | Value |
|---|---|
| Pace per camera | about 0.9 pictures/s (14 feeds sharing the 500 ms budget, computed from the 40 ms decode) |
| App CPU, 40 s untouched | 36 % of one core |
| Janky frames, 40 s untouched | 11 / 531 (2.1 %), 90th pct 13 ms, 99th 21 ms |
| Memory | 80 MB PSS (67 MB with six cameras in the cards) |

