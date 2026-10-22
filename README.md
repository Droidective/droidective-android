# Droidective for Android

Droidective's developer toggles and tools on the phone itself: home-screen widgets, Quick
Settings tiles, a notification bar, shortcuts and automations, with Shizuku or root for the
toggles Android keeps from ordinary apps. The macOS app lives in
[Droidective/Droidective](https://github.com/Droidective/Droidective).

## What it does

- **Widgets everywhere.** Home-screen bars, buttons and lists, folders, a notification bar, Quick
  Settings tiles and shortcuts, all built from the same items: 60 toggles (network, sound,
  display, device and developer), single actions (a toggle set to one state, an app force-stopped,
  a permission granted), apps, other apps' shortcuts, your own shell commands, deep links,
  automations and tools. Every widget can be styled: grid or list, sizes, chips, labels, states,
  colours, and each slot's own label, icon and colour.
- **Network Traffic.** An HTTPS debugging proxy on the phone itself, with no computer or network
  to set up: every request the phone's apps make, attributed to its app, decoded and searchable,
  copied as cURL or shared as HAR. Read only the apps you choose and the rest pass through
  untouched; stopping always puts the phone's proxy setting back.
- **Floating controls.** A bubble over any app for a screenshot, a clean screenshot, a screen
  recording, a capture of the views, the activity on screen and the debugging proxy, without
  leaving the app you are working on. It keeps out of what it captures.
- **Developer tools.** Performance Monitor (CPU, memory, temperature and an app's frames, with
  an overlay that shows them over any app), Logcat, Crash Catcher, Terminal, Settings Editor
  (settings, properties and feature flags, with undo), Sandbox Browser (an app's files,
  preferences and databases), File Explorer, APK Inspector, Screenshot (taken and marked up:
  arrows, boxes, text, black-out and pixelation), Screen Record, Bug Report, Background
  Work (Doze, standby buckets, jobs and alarms), UI Inspector, Intent Builder, Network Check, App
  Snapshots, Notification Inspector, Monkey Test, React Native, Language, Install App (APK,
  APKS, XAPK and APKM, with only this phone's splits), Developer Settings, Memory Usage, Current
  Activity, Simulate, Demo Mode, HTTP Proxy, Private DNS, Wireless ADB, Wi-Fi, Network Speed,
  Apps, Permissions (which apps hold the camera, the microphone, location and the rest, to
  grant or revoke), API Testing (requests kept by name, with variables and cURL pasted in),
  Device Info and more.
- **Automations.** When something happens, run actions once; while something holds, keep
  toggles set and put each one back when it ends. Schedules, restarts, Bluetooth, Wi-Fi, apps
  opening, charging, crashes, a run from the Mac, and more.

`RELEASE_NOTES.md` has the full list for each release.

## Install

Droidective is released as a signed APK on this repo's
[Releases](https://github.com/Droidective/droidective-android/releases) page, not on Google Play.
Download the APK on the phone and open it, or install it from a computer with
`adb install droidective-android-<version>.apk`. It needs Android 11 or later, and checks for new
releases once a day (Setup ▸ Updates turns that off).

## Access

Android keeps many settings from ordinary apps, so every toggle names the level it needs, and
does the closest thing it can without it, usually opening the right system screen:

| Level | What it is | How to get it |
|---|---|---|
| App | Ordinary permissions and special access, such as Modify system settings | Android's own prompts |
| Granted | `WRITE_SECURE_SETTINGS` and `DUMP`, plus the crash-report permission | Phone setup in Droidective for Mac, or the `adb shell pm grant` lines Setup shows |
| Shell | The shell user's access, through [Shizuku](https://shizuku.rikka.app/) | Start Shizuku from its app or from the Mac |
| Root | `su`, through Magisk or KernelSU | Turn on Use root in Setup |

The Setup tab shows where the phone stands and the next step for each level.

## Building

Kotlin 2.4 and Jetpack Compose, three modules: `core` (plain Kotlin, every rule and parser),
`platform` (the Android side) and `app` (screens, widgets, tiles). With JDK 17 or later and the
Android SDK:

```sh
./gradlew :core:test :platform:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`CLAUDE.md` describes the architecture and conventions; `docs/releasing.md` how a release is made.

## License

MIT, see `LICENSE`.
