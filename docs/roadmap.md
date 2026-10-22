# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.13.0**.

## Shipped since 0.13.0, waiting for a release

- **Apps that speak only HTTP/2 work while they are read** (#125). gRPC, offered only
  HTTP/1.1, couldn't connect at all; it is now relayed to its server.
- **…and what they say is read** (#126): each HTTP/2 stream is a flow of its own, its
  trailers (gRPC's `grpc-status`) kept, through `core/net/h2` — frames, HPACK, Huffman.

## Next

- **The proxy's next steps** — gRPC messages read without a `.proto`, the certificate in
  the system store with root, zstd — are in [network-traffic-proxy.md](network-traffic-proxy.md), in the
  order people reach for them.
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
