# M0 spikes

Each spike answers one question that could change the design. Results go here with the device,
Android version and date, so a later change can be checked against what was measured.

| # | Question | Where | Status |
|---|---|---|---|
| 1 | Does a widget tap on a cold process reach Shizuku and flip Wi-Fi in under a second on Android 15, 16 and 17? | emulators + your phone | **API 36: yes** (below); 35 and 37 open |
| 2 | Do the Granted writes take effect at once on Samsung, Xiaomi and OnePlus builds, as AOSP 17's source says? | your phone | open (AOSP emulator: yes, below) |
| 3 | Does a six-toggle notification row fit the 48 dp collapsed height on Android 15, 16 and 17? | emulators | **API 36: yes**; 35 and 37 open |
| 4 | What does Shizuku do after a restart on your phones? | your phone | open |
| 5 | How stale do widgets get with no foreground service, per OEM? | emulators + your phone | open |
| 6 | Preferred network type, Bluetooth tethering, hotspot via `TetheringManager`, font size: which work? | emulators + your phone | open |
| 7 | Does joining a Wi-Fi network wake the app through a PendingIntent network callback, and does leaving one? | emulators | **Answered: joining once, leaving never** (below) |
| 8 | Do restart, Bluetooth and exact-alarm triggers arrive on Xiaomi and Samsung with default battery settings? | your phone | open (emulators: restart and exact alarms do, below) |

Brand-specific spikes (2, 4, 5, 8) run only on a phone you plug in, and every toggle a spike
changes is put back afterwards.

## Results

### 1. Cold widget tap → Shizuku → Wi-Fi (2026-10-18, `dv-api36`, google_apis arm64 emulator)

Process killed with `am kill` after it went to the cached state (the low-memory killer's case),
then a tap on the bar's Wi-Fi cell. Timed from the tap to WifiService's own `setWifiEnabled` log
line, both on the device clock (`cold_tap.sh` in the session scratchpad; to move into `scripts/`
when the emulator suite lands).

| round | app process before tap | tap → `setWifiEnabled` | caller |
|---|---|---|---|
| 1 | dead | 386 ms | `com.android.shell`, uid 2000 |
| 2 | dead | 504 ms | uid 2000 |
| 3 | dead | 327 ms | uid 2000 |

Warm taps: 114–199 ms from the app's Toggles screen, 66 ms from the notification bar.

What it changed:
- The Shizuku user service runs as a **daemon**, so a cold tap only rebinds instead of starting a
  new app_process JVM.
- A widget button that opened a **system screen** did nothing: Glance's `glance-action:` data URI
  stopped the implicit intent resolving (-91). Fixed with `OpenActivity`.
- Reading `reduce_bright_colors_activated` through the Settings API **crashed** the app on
  Android 12+ (hidden key, not readable). Hidden keys are now read through the shell.

Measurement traps worth knowing: `adb root` restarts adbd and kills `shizuku_server` with it; a
Google sign-in screen on google_apis images swallows taps; HOME returns the launcher to its first
page, where a pinned bar may not be.

### 2. Granted writes (AOSP emulator, API 36)

`pm grant` of WRITE_SECURE_SETTINGS and DUMP, then Animation scale from the app: all three
scales went 1.0 → 0.5 → 0 and the screen showed the state read back. OEM builds still open.

### 3. Notification row (API 36)

Six slots in a `DecoratedCustomViewStyle` custom view: each 126 px tall at 2.625 density (48 dp),
all six clickable in the shade's collapsed row. A Wi-Fi tap from it reached Shizuku in 66 ms.

### Also verified on API 36

- Shizuku 13.6.0 (signed `CN=Rikka`, cert SHA-256 `268b5590…db30`) starts over adb with
  `<apk dir>/lib/<abi>/libshizuku.so`, not the `start.sh` its docs describe. The Mac's Phone setup
  should resolve that path from `pm path moe.shizuku.privileged.api`.
- The Layout bounds tile: one click sets `debug.layout` to `true` and SystemUI shows the tile
  active; a second click sets it back to `false`.

### 7. Wi-Fi through a PendingIntent network callback (2026-10-19, `dv-api37`, API 37)

`registerNetworkCallback(NetworkRequest, PendingIntent)` for `TRANSPORT_WIFI`, registered while on
Wi-Fi: ConnectivityService sent the intent at once, then logged `RELEASE` for the request 5.0 s
later, and nothing came after. Its source says why
(`packages/modules/Connectivity`, `ConnectivityService.sendPendingIntentForRequest`): it sends only
`CALLBACK_AVAILABLE`, only once per registration (`mPendingIntentSent`), and releases the request
`CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS` (5 000 ms) after the send. Leaving a network never
sends anything, and registering again while still on Wi-Fi fires again straight away.

So a Wi-Fi trigger needs a listener that's running: Wi-Fi moved to the automation service
(`State.OnWifi.wakes = Service`). Wi-Fi conditions are unaffected; they're read when checked.

### 8, emulator half (2026-10-19)

- Restart: "When the phone restarts" with Only if "Shell isn't available" posted its notification
  after `adb reboot` on `dv-api37` (API 37). The app has to have been opened once since install,
  or Android doesn't send it `BOOT_COMPLETED`.
- Exact alarms: with Alarms & reminders allowed, a schedule fired at 01:32:00 on `dv-api36`
  (`window=0 exactAllowReason=permission`); without it the alarm gets the 10-minute window.
- A time-window While on `dv-api37` began at 01:47:00 and ended at 01:49:00, putting its toggle
  back.
