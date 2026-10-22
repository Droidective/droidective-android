# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.9.0**.

## Shipped since 0.9.0, waiting for a release

Nothing yet. 0.9.0 carried the proxy's next steps: send again and edit and send (#96, #97), mocks
(#98), network speed (#99) and breakpoints (#100, #101); see `RELEASE_NOTES.md`.

## Next

- **The proxy's next steps** — map remote, the certificate in the system store with root,
  WebSocket frames, brotli, HTTP/2 — are in [network-traffic-proxy.md](network-traffic-proxy.md),
  in the order people reach for them.
- **The Mac tools not yet on the phone**, by the Mac's ids: `api-client` (an HTTP client, which
  the proxy's repeat would share), `permissions` and `app-info` as their own screens (the Apps
  tool covers most of both), and `screenshot` as a tool with markup rather than only an action.
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
