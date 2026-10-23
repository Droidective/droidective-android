# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.18.0**.

## Shipped since 0.18.0, waiting for a release

- **gRPC calls sent again.** A captured gRPC call goes back to its server from the phone, on
  HTTP/2 through a client of Droidective's own, its status and trailers read as the app's were.
- **Schemas from the server.** A gRPC call's server is asked for its schema by reflection, and
  the call reads with field names with no `.proto` file pushed.

0.18.0 carried Reactotron watches (#142) and dispatch, from the screen and from a
slot (#143, #144); see `RELEASE_NOTES.md`.

## Next

- **The proxy's next step** — the certificate in the system store with root, waiting on the
  person's go-ahead — is in [network-traffic-proxy.md](network-traffic-proxy.md).
- **Reactotron's next step** — the REPL — is
  in [reactotron.md](reactotron.md).
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
