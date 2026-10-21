# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.7.0**.

## Shipped since 0.7.0, waiting for a release

Nothing yet. 0.7.0 carried Network Traffic (#69–#88), the Notification Inspector (#68), app ops
(#67), alarms (#66), App Links (#65) and the capture actions as toggles and tiles (#64, #90); see
`RELEASE_NOTES.md`. For what is worth doing next in the proxy, see
[network-traffic-proxy.md](network-traffic-proxy.md).

## Then: make the app reach further than its own screen

A review of the newer features found the same gap in all of them — they work well once
you are in the app, and are hard to reach from anywhere else. A phone tool that needs you
to open an app first has already lost to the thing you were doing instead.

1. **Launcher shortcuts.** Long-pressing the app icon offers nothing. The most-used tools
   belong there, plus "add this tool to the home screen" from the tool itself.
2. **Open with, and Share.** Tapping an APK in Files, or sharing a link to try as a deep
   link, never reaches Droidective.
3. **A floating control over other apps.** Only the performance overlay floats today. To
   capture or inspect a screen you have to leave the app you are inspecting, which is the
   one moment you cannot.
4. **Pinned and recent tools.** Thirty-eight tools sit in one list behind a search box.

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
