# Where this is and where it goes

A status report for Droidective for Android, written so the work can be picked up on
another machine. Current release: **0.7.0**.

## Shipped since 0.7.0, waiting for a release

The four ways the app was hard to reach from outside its own screen, all done:

- **Pinned and recent tools**, and **launcher shortcuts** from them (#91): the Tools list leads
  with Pinned and Recent, a long press pins a tool or adds it to the home screen, and the app's
  icon offers the pinned tools, then the recent ones.
- **Open with, and Share** (#92): an APK, XAPK or APKM opened from Files or shared goes to Install
  App; shared text with a link in it goes to Deep Links' field.
- **Floating controls** (#93): a bubble over any app for screenshots, a recording, a views
  capture, the activity on screen and the debugging proxy; it keeps out of what it captures.
- The capture actions' own Quick Settings tiles went out in 0.7.0 (#90).

**Next release is 0.8.0**: bump `versionName`/`versionCode` in `gradle.properties`, add a
`RELEASE_NOTES.md` section, PR, merge, tag `v0.8.0`.

## Next

- **The proxy's next steps** — repeat and edit a request, breakpoints, map local and remote, and
  the certificate in the system store with root — are in
  [network-traffic-proxy.md](network-traffic-proxy.md), in the order people reach for them.
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
