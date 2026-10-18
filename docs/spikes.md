# M0 spikes

Each spike answers one question that could change the design. Results go here with the device,
Android version and date, so a later change can be checked against what was measured.

| # | Question | Where | Status |
|---|---|---|---|
| 1 | Does a widget tap on a cold process reach Shizuku and flip Wi-Fi in under a second on Android 15, 16 and 17? | emulators + your phone | open |
| 2 | Do the Granted writes take effect at once on Samsung, Xiaomi and OnePlus builds, as AOSP 17's source says? | your phone | open |
| 3 | Does a six-toggle notification row fit the 48 dp collapsed height on Android 15, 16 and 17? | emulators | open |
| 4 | What does Shizuku do after a restart on your phones? | your phone | open |
| 5 | How stale do widgets get with no foreground service, per OEM? | emulators + your phone | open |
| 6 | Preferred network type, Bluetooth tethering, hotspot via `TetheringManager`, font size: which work? | emulators + your phone | open |
| 7 | Does joining a Wi-Fi network wake the app through a PendingIntent network callback, and does leaving one? | emulators | open |
| 8 | Do restart, Bluetooth and exact-alarm triggers arrive on Xiaomi and Samsung with default battery settings? | your phone | open |

Brand-specific spikes (2, 4, 5, 8) run only on a phone you plug in, and every toggle a spike
changes is put back afterwards.
