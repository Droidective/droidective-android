# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.19.0**.

## Shipped since 0.19.0, waiting for a release

- **Reactotron's REPL.** The values an app shares with `Reactotron.repl`, listed, and code run in
  the app with them as `this`, its answer in the timeline.
- **Copy as cURL that runs.** A gRPC call copies as `curl --http2` with its bytes piped in, and
  a binary or half-kept body no longer copies a command the shell splits in two.

0.19.0 carried gRPC calls sent again over HTTP/2 (#147) and schemas from a
server's reflection (#148); see `RELEASE_NOTES.md`.

## Next

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
