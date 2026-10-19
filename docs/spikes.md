# M0 spikes

Each spike answers one question that could change the design. Results go here with the device,
Android version and date, so a later change can be checked against what was measured.

| # | Question | Where | Status |
|---|---|---|---|
| 1 | Does a widget tap on a cold process reach Shizuku and flip Wi-Fi in under a second on Android 15, 16 and 17? | emulators + your phone | **Yes on 35, 36 and 37**, except the first shell use after a boot (below) |
| 2 | Do the Granted writes take effect at once on Samsung, Xiaomi and OnePlus builds, as AOSP 17's source says? | your phone | open (AOSP emulator: yes, below) |
| 3 | Does a six-toggle notification row fit the 48 dp collapsed height on Android 15, 16 and 17? | emulators | **Yes on all three** (below) |
| 4 | What does Shizuku do after a restart on your phones? | your phone | open |
| 5 | How stale do widgets get with no foreground service, per OEM? | emulators + your phone | open |
| 6 | Preferred network type, Bluetooth tethering, hotspot via `TetheringManager`, font size: which work? | emulators + your phone | **Network type and font size do; hotspot and Bluetooth tethering have no shell command** (below) |
| 7 | Does joining a Wi-Fi network wake the app through a PendingIntent network callback, and does leaving one? | emulators | **Answered: joining once, leaving never** (below) |
| 8 | Do restart, Bluetooth and exact-alarm triggers arrive on Xiaomi and Samsung with default battery settings? | your phone | open (emulators: restart and exact alarms do, below) |
| 9 | Can the app hear about another app's crash, and read it, without a service of its own? | emulators | **Answered: hear it only while running; read it with READ_DROPBOX_DATA and usage access** (below) |

Brand-specific spikes (2, 4, 5, 8) run only on a phone you plug in, and every toggle a spike
changes is put back afterwards.

## Still to run on real phones

Waiting for the phones to be plugged in (noted 2026-10-19). On each brand (Samsung, Xiaomi,
OnePlus if there is one), with Droidective installed and Shizuku allowed:

- **1, the phone's column**: `scripts/spikes/cold-tap.sh <serial>` with a bar holding Wi-Fi on
  the home screen. Under a second per round is the bar; the first tap after Shizuku starts is
  slower (below).
- **2**: Granted writes (animation scale, Don't keep activities, stay awake) from the app, read
  back with `adb shell settings get global <key>`, then put back.
- **4**: restart the phone and note whether Shizuku comes back on its own, and how long its
  wireless-debugging start takes.
- **5**: a bar with Wi-Fi and Bluetooth, toggled from Quick Settings instead, then how long the
  bar takes to show the change with no automation running.
- **8**: "When the phone restarts", a Bluetooth trigger and a schedule two minutes out, each with
  a Notify action, on the phone's default battery settings.


## Results

### 1. Cold widget tap → Shizuku → Wi-Fi (2026-10-18, `dv-api36`, google_apis arm64 emulator)

Process killed with `am kill` after it went to the cached state (the low-memory killer's case),
then a tap on the bar's Wi-Fi cell. Timed from the tap to WifiService's own `setWifiEnabled` log
line, both on the device clock (`scripts/spikes/cold-tap.sh`).

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

#### On API 35 and 37 (2026-10-19, `dv-api35` google_apis_playstore, `dv-api37` google_apis)

The same measurement, with the bar pinned through the app's own "Add a bar to the home screen" and
Shizuku 13.6.0 started over adb. Each emulator ran beside the API 36 one.

| | round 1 | round 2 | round 3 | round 4 |
|---|---|---|---|---|
| API 35 | 1032 ms | 693 ms | 712 ms | |
| API 37 | 1353 ms | 617 ms | 868 ms | 719 ms |

Round 1 on each was the first shell command since the emulator booted, so it also paid for
Shizuku starting the user service's JVM; it stays up as a daemon after that. Every later cold tap
was under a second. So the first Shell tap after starting Shizuku takes 1.0–1.4 s, and opening the
app once does the same, since its Toggles screen reads hidden settings through the shell.

### 2. Granted writes (AOSP emulator, API 36)

`pm grant` of WRITE_SECURE_SETTINGS and DUMP, then Animation scale from the app: all three
scales went 1.0 → 0.5 → 0 and the screen showed the state read back. OEM builds still open.

### 3. Notification row (API 35, 36 and 37)

Six slots in a `DecoratedCustomViewStyle` custom view: each 126 px tall at 2.625 density (48 dp),
all six clickable in the shade, on `dv-api36` (2026-10-18) and on `dv-api35` and `dv-api37`
(2026-10-19, same layout injected and measured from a `uiautomator` dump). A Wi-Fi tap from it
reached Shizuku in 66 ms on API 36.

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

### 9. Hearing about crashes (2026-10-19, `dv-api36`, API 36)

A receiver for `DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED`, crashes made with `am crash` (a Java
crash), `run-as <debug app> kill -SEGV` (a native crash) and the ANR that followed it.

- **A manifest receiver never runs.** ActivityManager's broadcast history shows it skipped at
  enqueue, "Background execution not allowed", while the app was dead and while it was alive;
  Google Play services' own manifest receiver is skipped the same way. A receiver registered at
  runtime got each entry 7–10 ms after DropBox filed it.
- The system sends the broadcast twice: once requiring `READ_DROPBOX_DATA`, for apps targeting
  35 and later (compat change 296060945), and once requiring `READ_LOGS` for the rest. Both are
  development permissions, so `pm grant` gives them.
- Reading the entry (`getNextEntry`) also needs usage access: without it the broadcast still
  arrives, and the read throws `SecurityException ... not allowed to perform GET_USAGE_STATS`.
- Tags: `data_app_crash`, `data_app_native_crash` and `data_app_anr` for apps,
  `system_app_*` for system apps. Each entry is a `Key: Value` header (Process, PID, Package with
  its version, Foreground), a blank line, then the stack trace, tombstone or ANR report; the
  ANR's reason is its `Subject:` line. Real entries are in `fixtures/dropbox/`.

So crash alerts are a service-woken trigger like apps opening: the automation service registers
the receiver while an automation watches for crashes, and the Granted level grows to include
`READ_DROPBOX_DATA` (`READ_LOGS` below Android 15).

### 6. The toggles still "to confirm" (2026-10-19, `dv-api36`, API 36, as the shell user)

- **Preferred network type: yes**, through the phone service's own shell command:
  `cmd phone set-allowed-network-types-for-users -s 0 <bitmask>`, read back with
  `get-allowed-network-types-for-users -s 0`. The bitmasks are its help text's
  (`NetworkTypeBitMask`, NR leftmost): 5G `11001111101111111111`, 4G `01001111101111111111`, and
  3G is 4G without the LTE bits, `00001110101111111111`. Without `-s` the command looks up the
  default voice SIM, and on the emulator it fails with "Invalid subscriptionId:-1". The toggle
  cycled 5G → 4G → 3G → 5G and read each back. Dual-SIM phones may need the data SIM's slot.
- **Font size: yes**, already: `font_scale` is a public system setting, written at App level.
- **Hotspot: no shell command.** `cmd wifi` lists no `start-softap` on Android 16, and
  `cmd tethering` has no shell implementation. It keeps its stand-in, hotspot settings.
- **Bluetooth tethering: no shell command.** `cmd bluetooth_manager` only turns Bluetooth on and
  off. It keeps its stand-in, tethering settings.
