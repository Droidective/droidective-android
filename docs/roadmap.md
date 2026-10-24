# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.20.0**.

## Shipped since 0.20.0, waiting for a release

- **What an APK is made of, by size.** The APK Inspector lists every folder and file in an
  APK, biggest first, with what each takes in the APK and holds unpacked, read from the zip's
  own central directory.
- **Each dex file's classes and method references.** The APK Inspector reads every dex
  header: the classes and method references in each file and in all, and the dex format with
  the first Android that reads it.

## Next

- **The APK Inspector**, read-only and in the style of Android Studio's APK Analyzer: next, the
  manifest as XML, and whether native code is ready for 16 KB pages. Decompiling, rebuilding and signing on the phone
  are not being worked on: starting them was stopped by a safety check.
- **The proxy's last step** — the certificate in the system store with root — is not being
  worked on: starting it was stopped by a safety check. The plan is in
  [network-traffic-proxy.md](network-traffic-proxy.md).
- **Reactotron** has every part of Reactotron's desktop that talks to an app; what is left
  (images shown, the MCP server) is in [reactotron.md](reactotron.md).
- **The Mac tools not yet on the phone**: none on the list. `app-info` stays the Apps tool's
  detail screen, which already is that.
- **Real-device passes on the OnePlus Pad** need the person there: OxygenOS holds every
  `adb install` for a tap on the device (see Standing constraints).

## Standing constraints

- **Never reboot the OnePlus Pad (`9bd4e16a`)** — it is a standing test device and
  nobody is there to unlock it.
- Don't drive system pickers (DocumentsUI) or blind app-chooser presses on that device;
  test those on the emulator.
- An `adb install` on that device waits on OxygenOS's install screen
  (`com.oplus.stdsp/…InstallGuideActivity`) for a tap; don't drive it. A new build reaches it
  only when the person approves it, or updates to a release themselves.
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
