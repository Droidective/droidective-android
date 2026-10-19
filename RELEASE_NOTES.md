# Release notes

Each release has a `## X.Y.Z` section; `scripts/release-notes.sh` copies it into the GitHub
release, and a tag without one fails the release.

## 0.1.0

The first release: Droidective's developer toggles and tools on the phone itself.

- **Widgets.** Home-screen bars with as many toggles as you like, folders that open more, a
  notification bar one pull down from every screen, twelve Quick Settings tile slots, and
  home-screen shortcuts. Every surface holds the same items: 52 toggles, apps, other apps'
  shortcuts, custom commands, deep links and automations.
- **Developer tools.** Developer Settings, Logcat, Simulate, Demo Mode, System Restrictions,
  HTTP Proxy, Private DNS, Wireless ADB, Wi-Fi, Network Speed, IP addresses, Apps (force stop,
  clear data, permissions, app info), Install App, Current Activity (with a Quick Settings tile),
  Memory Usage, Crash Catcher, Custom Commands, Deep Links, Device Info and Root Status.
- **Install App.** APK, APKS, XAPK and APKM files, installing only the splits this phone needs
  (its processor, screen density and languages) through Android's installer.
- **Crash Catcher.** Recent crashes, native crashes and ANRs on the phone, each with its full
  report to copy or share, and a notification when an app you watch crashes.
- **Automations.** When an event happens, run actions once; while a state holds, keep toggles set
  and put each one back when it ends. Triggers include schedules, restarts, Bluetooth devices,
  Wi-Fi networks, apps opening, charging, the screen, battery level, headphones, a toggle
  changing, and a run from the Mac.
- **Access.** Each toggle names the level it needs: ordinary app permissions; WRITE_SECURE_SETTINGS,
  which Droidective's Phone setup on the Mac grants in one click; Shizuku; or root.
- **Updates.** The app checks GitHub once a day and tells you when a new release is out. You can
  turn that off in Setup. If Droidective itself crashes, Setup keeps the report for you to copy
  into a bug report; nothing is sent anywhere.

Needs Android 11 or later.
