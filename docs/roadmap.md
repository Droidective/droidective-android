# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.15.0**.

## Shipped since 0.15.0, waiting for a release

- **Reactotron talks back** (#134): an app's custom commands run from the phone, arguments
  and all, and its state is read by path or whole.
- **Mock Location** (#135): the phone anywhere, for every app that asks where it is —
  coordinates, a Google Maps or OpenStreetMap link, a kept place or a ready city — through
  test providers for GPS, the network and fused location, as the mock location app.

## Next

- **The proxy's next steps** — the certificate in the system store with root, schemas from
  a server's reflection — are in [network-traffic-proxy.md](network-traffic-proxy.md), in the
  order people reach for them.
- **Reactotron's next steps** — state backup and restore, path subscriptions, the REPL, an
  app's commands as widget slots — are in [reactotron.md](reactotron.md).
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
