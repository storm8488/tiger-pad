# Tiger Touch Android

Tiger Touch as an Android app: an Android tablet becomes a dedicated control
screen for every 3D printer of a TigerTag account. It is a bigger, more
powerful version of the BIGTREETECH Panda Touch running Tiger Touch.

It is a port of the [Tiger Touch](https://github.com/TigerTag-Project/Tiger-Touch)
firmware (C, ESP-IDF, LVGL), MIT licence, from upstream commit
`3f6bf21efd2cf592d98a6c8d2fa2469ddb0a59b9` (2026-10-17). The drivers and the
account code are rewritten in Kotlin from the C, function by function.
"TigerTag" and "Tiger Touch" are names of the TigerTag project. This port is
not affiliated with it.

## What it does

- **TigerTag account**: e-mail and password, or a QR code for accounts created
  with Google (the same pairing as the firmware). The printers come from the
  account, as in Tiger Studio Manager.
- **Every printer at once**: the ESP32 follows one printer at a time for lack
  of memory. The tablet keeps every printer connected and shows them as a grid
  of live cards: state, progress, file, time left and end time, layers,
  nozzle(s), bed and chamber, AMS / CFS / external spool, pause, resume, stop
  (hold 1.5 s) and the light.
- **A fleet at a glance**: three densities, switched from the header and
  remembered. *Wall*: every printer on one screen (15 on the tablet, 5 × 3),
  readable from three metres. *Cards*: the large cards. *List*: one row per
  printer with its buttons. *Auto* (the default) picks the cards when they all
  fit, the wall otherwise.
- **What needs someone first**: errors, pauses, finished plates and spools
  under 10 % come first, then the prints by end time. A summary under the
  header counts each state (each counter is a filter) and says which print
  ends next; a brand filter beside the sort.
- **Alerts**: a print that finishes, pauses or stops on an error, a printer
  silent for 2 min, a spool under 10 %: the printer blinks softly until it is
  touched, a short beep (Settings > Alerts), the screen lights up, and a journal
  of the last 100 events (the bell).
- **Night and screen saver** (Settings > Night): the screen dims after a few
  minutes without a touch; in the night hours (23 h – 7 h by default) a black
  page shows only the prints at work, their end and the alerts. A touch or an
  event wakes it; the page moves by a few pixels every 3 minutes.
- **Demo mode** (Settings > Demo): 1 to 20 made-up printers of every brand,
  all printing or in a mix of states, for a showroom or for measuring. No
  printer is reached while it is on.
- **Brands**: Bambu Lab (LAN and cloud), Creality (K1 / K2 / Ender-3 V4,
  WebSocket), Klipper (Moonraker), OctoPrint, Prusa (PrusaLink), Snapmaker,
  Elegoo (Centauri Carbon 1 and 2), FlashForge, Anycubic (LAN and cloud).
- **Presence**: the tablet shows up in the account under `tigertouches`, like
  a Panda Touch running Tiger Touch.
- **Dedicated screen** (kiosk), see below.

Reference device: a Galaxy Tab 3 10.1 (GT-P5210) under LineageOS 14.1
(Android 7.1, x86). Minimum: Android 7.0.

Status: runs on the reference tablet with a K1 Max and two Klipper printers.
The drivers that upstream marks as not tried against their machine stay untried
here too. Measures with 15 demo printers: [docs/perf.md](docs/perf.md).

## Build

1. Install [Android Studio](https://developer.android.com/studio).
2. *Open* this folder. Android Studio downloads Gradle and the SDK on its own.
3. On the tablet: *Settings → About tablet*, tap *Build number* 7 times, then
   *Developer options → USB debugging*.
4. Plug the tablet in, pick it in the device list, then *Run*.

Command line, for the release build the tablet runs (a debug build makes
Android show a warning about the ROM's GPU libraries at every start):

```
gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Signing reads `keystore.properties` at the root (not in git):
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Dedicated screen (kiosk)

Two levels.

**Home screen (always).** The app declares itself as a HOME screen. Press the
home button once and choose *Tiger Touch → Always*. The tablet then starts on
it, and the screen stays on while the app is in front.

**Full lock (device owner).** Android's lock task mode: no status bar, no
recent apps, no lock screen, the screen stays on while plugged in, and the app
cannot be left without the PIN of its settings page. The app must be *device
owner*, which Android only allows on a tablet with **no account added**
(Settings → Accounts: remove any Google account first):

```
adb shell dpm set-device-owner com.damou.tigertouch/.kiosk.KioskAdmin
```

Then restart the app. To give the tablet back: *Settings → Dedicated screen →
Release the tablet* (PIN), or a factory reset.

## Layout

| Kotlin | From the firmware |
|---|---|
| `cloud/Cloud.kt` | `net/cloud.c` (Firebase REST, pairing, presence) |
| `drivers/*Driver.kt` | `drivers/drv_*.c`, `k2_client.c` |
| `drivers/DriverUtil.kt` | `drivers/driver_util.h`, `net/addr.h` |
| `links/Links.kt` | `drivers/links.c`, without the load budget |
| `model/PrinterView.kt` | `printer_view_t` in `ui/ui_dashboard.h` |
| `TouchApp.kt` | the application task of `main.c` |
| `ui/` | the LVGL screens, redrawn for a tablet |
| `demo/Demo.kt` | `demo_mode.c`, 1 to 20 printers |
| `fleet/Fleet.kt` | new: what the home page draws, its order, its summary |
| `events/Events.kt` | new: the alerts and their journal |
| `ui/Night.kt`, `ui/NightPage.kt` | new: dimming, the night page |
| `kiosk/Kiosk.kt` | new |
