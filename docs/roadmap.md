# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.6.0**.

## Shipped since 0.6.0, waiting for a release

All merged to `main`, all unreleased:

- **Record screen, Performance overlay and Capture views** as toggles, so they can sit
  in a Quick Settings slot, a widget, the notification bar or an automation (#64).
- **App Links** in the Deep Links tool (#65).
- **Alarms** in Background Work (#66).
- **App ops** in the Apps tool (#67) — the special accesses beyond runtime permissions,
  each set in a tap.
- **Notification Inspector** (#68) — why a notification shows or doesn't: what an app has
  showing now, each channel's importance, whether notifications are off as a whole, and
  Do Not Disturb.
- **Network Traffic** (#69–#74, #76, #78–#87) — an HTTPS debugging proxy on the phone
  itself: every request the phone's apps make, decoded, searchable, attributed to its app;
  reading only the apps chosen while the rest pass through; HAR export; a Quick Settings
  tile and a toggle. See [network-traffic-proxy.md](network-traffic-proxy.md), which also
  lists what is worth doing next for it.

**Next release is 0.7.0**: bump `versionName`/`versionCode` in `gradle.properties`, add a
`RELEASE_NOTES.md` section, PR, merge, tag `v0.7.0`.

## Then: make the app reach further than its own screen

A review of the newer features found the same gap in all of them — they work well once
you are in the app, and are hard to reach from anywhere else. A phone tool that needs you
to open an app first has already lost to the thing you were doing instead.

1. **Quick Settings tiles for the capture actions.** Seven tools have a tile of their own
   (Layout bounds, Show taps, Animation scale, Demo mode, Network speed, Wireless
   debugging, Current activity). Screen record, Screenshot, Performance overlay and
   Capture views do not — they can only be reached by first assigning them to one of the
   twelve generic slots in Widgets. The system recorder has a tile; so should ours.
2. **Launcher shortcuts.** Long-pressing the app icon offers nothing. The most-used tools
   belong there, plus "add this tool to the home screen" from the tool itself.
3. **Open with, and Share.** Tapping an APK in Files, or sharing a link to try as a deep
   link, never reaches Droidective.
4. **A floating control over other apps.** Only the performance overlay floats today. To
   capture or inspect a screen you have to leave the app you are inspecting, which is the
   one moment you cannot.
5. **Pinned and recent tools.** Thirty-seven tools sit in one list behind a search box.

## Standing constraints

- **Never reboot the OnePlus Pad (`9bd4e16a`)** — it is a standing test device and
  nobody is there to unlock it.
- Don't drive system pickers (DocumentsUI) or blind app-chooser presses on that device;
  test those on the emulator.
- Don't touch the user's own apps' data.
- Fixtures scrub identifiers. Record them from the emulator where possible.
- Feature ids must match the Mac app's, or be listed in `ToolsTest.phoneOnly`.
- Every new parser gets a test in the same change; anything taking user input that
  reaches `adb shell` gets a test asserting the `shellQuote`d form.

## The check CI runs

```bash
./gradlew ktfmtFormat    # first, separately
./gradlew ktfmtCheck :core:test :platform:testDebugUnitTest :app:testDebugUnitTest \
  :core:lint :platform:lintDebug :app:lintDebug :app:assembleDebug :app:assembleRelease
```

## Branches in flight

None.
