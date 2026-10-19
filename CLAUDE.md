# CLAUDE.md — Droidective for Android

One Android app: Droidective's developer toggles and tools on the phone itself, MicroToggles-style
widgets, Quick Settings tiles, shortcuts, automations, and (later, gated) Remote v2's phone side.
Kotlin 2.4, Jetpack Compose + Material 3, Glance for widgets. minSdk 30, targetSdk 37.

The Mac app is a separate repo, `Droidective/Droidective` (Swift, ADBKit). Nothing here depends on
it at build time. The plan, with every decision and its reasoning:
https://claude.ai/code/artifact/7f001a8e-334b-49d9-9619-5939c84b2c22

## Modules (the load-bearing rule)

- **`core/`**: plain Kotlin on the JVM. It **cannot import `android.*`**, and that's the point:
  the toggle registry, the routing in `ToggleRunner`, every parser, `shellQuote`, `Grants`, and the
  automation model live here and test in seconds without an emulator. The compiler enforces the
  boundary the way `PortabilityGuardTests` does for ADBKit.
- **`platform/`**: Android library. Implements core's interfaces: `AndroidSettingsStore`,
  `AndroidPlatformToggles`, the shells (`ShizukuShell` via a Shizuku user service, `RootShell` via
  `su -c`, `AppShell`), and `AccessMonitor`.
- **`app/`**: Compose UI, Glance widgets, tiles, the notification bar, shortcuts. UI only: if a
  screen reaches for a command, a setting key or a parser, move it down.

## Access levels

Every toggle declares the lowest level that runs it in place. The level is **derived from how it
writes** (`Write.level`), so it can't disagree with the implementation.

| Level | Means | Survives restart |
|---|---|---|
| App | normal permissions + special access (Modify system settings, DND access) | yes |
| Granted | WRITE_SECURE_SETTINGS + DUMP, from `pm grant` (Mac Phone setup, adb, or self-grant while Shell is up) | yes |
| Shell | uid 2000 through Shizuku, or root | no for Shizuku |
| Root | su (Magisk/KernelSU) | yes |

Facts that decided levels (AOSP 17 source, not tested on every OEM):
- `show_touches` and `pointer_location` are **private** System settings: WRITE_SECURE_SETTINGS does
  *not* unlock them. They're `SettingScope.SystemPrivate` → Shell.
- `cmd wifi start-softap` is root-only; the hotspot needs `TetheringManager` through Shizuku.
- A READ_LOGS grant still shows a consent prompt per read on Android 13+ and is refused in the
  background; logcat goes through the shell, which is exempt.
- Ongoing notifications are dismissible on Android 14+, foreground service or not. Play accepts a
  foreground service only while it's needed, so the notification bar has no service behind it.

**A missing level changes what a tap does; it never fails silently.** Shell toggles name a
`Fallback` (a system screen) or are on the short allow-list in `ToggleRegistryTest`.

## Adding a toggle

1. Add it to `Toggles` in `core/` (id in kebab-case; keep the Mac's id where the idea is the same).
2. Add its icon to `app/symbols.txt`, run `python3 -I scripts/import-symbols.py`, then
   `./gradlew ktfmtFormat` (the script writes `Symbols.kt`).
3. If it's `Write.Api`, implement the `PlatformApi` case in `AndroidPlatformToggles`.
4. Add an arg-vector test in `ToggleRunnerTest` for any command it runs, asserting the exact line
   and the quoted form of any value a person typed.

The registry tests fail on: duplicate ids, a write that doesn't cover every state, a Shell toggle
with no stand-in, an unexpected Pending toggle, an icon with no drawable.

## Automations

The model and every rule are pure, in `core/…/automations/`; the app only delivers signals and
runs actions.

- **A `State` is both a trigger and a condition.** `Trigger.Starts`/`Ends`/`While` take a state,
  `Trigger.On` an `Event`; Only if is a `Condition(state, negate)`. A state says what wakes the app
  (`wakes`, null for condition-only ones like `ShellAvailable`) and how long a change has to hold
  (`settleMillis`). Adding a kind means adding it to the exhaustive lists in
  `AutomationRulesTest`.
- **`AutomationRules.problems` is the one validator.** The editor and import both use it: a While
  only sets toggles it can read back, nothing triggers or runs itself.
- **`Holds` is how a While puts things back.** A stack per toggle: the newest holder is in force,
  the state from before the first comes back when the last ends, and a hand change is never
  overwritten. It's persisted, because a While can outlive the process.
- **`StateWatch` and `Matcher` decide what fires.** A first reading starts a While that already
  holds but is never a Starts or an Ends. `Cause` stops a chain after five and never repeats one.
- **Imports arrive switched off**, with clashing ids renamed and references rewritten, and
  `Review` spells out every shell line an automation can run before someone turns it on.
- **Every firing is WorkManager work** (`Automations.enqueue` → `AutomationWorker` →
  `AutomationRunner`), so it outlives the receiver that noticed it, and a Wait re-enqueues the rest
  with a delay. A run redraws surfaces once at the end (`ToggleTaps.set(refresh = false)`), and
  every run, skipped ones included, lands in History.
- **Opening something from the background**: a direct start when the app is on screen or has
  Display over other apps, else `ShellStarts` (`am start`) with Shell, else a tap-to-open
  notification. Another app's shortcut never goes through the shell.
- **`RunAutomation` is the only way in from outside**: exported, but guarded by
  WRITE_SECURE_SETTINGS and `FLAG_RECEIVER_FROM_SHELL`, which Android strips from anyone but the
  shell and root (Remote v2's enrollment guard). Alarms reschedule on save, on every app start
  (a force stop clears them), at boot, after an update and when the clock changes.
- **`StateSignals` covers the states Android wakes the app for**: Bluetooth (ACL broadcasts, then
  the audio profiles say who's connected, re-read 8 s later because the profile lags the link)
  and time windows (an alarm at the next edge). Each signal re-reads every watched state and
  keeps the readings in `automation-watch.json`. Wi-Fi triggers need the automation service: a
  PendingIntent network callback fires once on joining and never on leaving (spike 7).
- **`AutomationService` runs only while an enabled automation needs it** (`Matcher.needsService`),
  as a `specialUse` foreground service whose notification names what it watches. It feeds the
  same `StateSignals.check`: power, screen, unlock, battery, headset and install broadcasts, a
  live Wi-Fi callback, settings observers for toggles (a 30 s poll for the rest), and a 2 s poll of
  the app in front while the screen is on (usage access, else the shell). Android 12+ refuses to
  start it from the background; boot, an update and opening the app all start it.
- **`RecentWrites` carries a run's chain into the changes it causes**, so two automations that
  set each other's triggers stop after five instead of looping.
- **Emulators are shared with other agents.** `adb tcpip`/`adb root` restart adbd and kill
  Shizuku's server; a run that says "needs Shell" may just be that.

## Conventions

- **Quote every value a person supplies with `shellQuote()`.** Commands run through `sh -c`; the
  quote is the security boundary, not validation.
- **Split command output with `Parse.lines`**, never `"\n"`: OEM tools and adb emit CRLF.
- **Android's regex engine is ICU, not the JVM's.** Escape every brace that isn't a quantifier and
  every `]` outside a set; JVM tests accept a bare `}` that crashes on a phone.
  `RegexPortabilityTest` holds every `Regex` literal to that rule.
- **Read back after every write.** `ToggleRunner.apply` reports `DidNotStick` when Android ignored it.
- **No WebView and no downloaded code anywhere in the app.** Remote v2 will run inside this app.
- **Exported components are an allowlist** (`ManifestTest`). Adding one means adding it there.
- **One build, distributed outside Play** (GitHub releases, and the Mac's adb install). There's no
  Play listing for now. If one comes: add a `play` flavor that leaves out Remote and FCM, keep
  the same package, signing key and version codes so either build updates the other, and never
  submit Remote to Play (its Malware policy names backdoors; a strike can cost the account).
  That flavor also drops `QUERY_ALL_PACKAGES` (Play allows it only for a few app types): its
  Apps tool lists packages through the shell, and the picker, `RootProbe` and the Shizuku check
  go back to `<queries>` entries.
- Shizuku starts `ShellUserService` by class name in another process; `platform/consumer-rules.pro`
  keeps it through R8. Bump `SERVICE_VERSION` in `Shells.kt` when that class changes.
- Glance adds a `glance-action:` data URI to every activity action, which stops an implicit
  intent (a Settings panel) resolving. Widget buttons that open implicit intents go through
  `OpenActivity`; explicit ones go direct. RemoteViews and tiles don't have the problem.
- **What the app can't run as itself**, even holding DUMP: `dumpsys meminfo` answers "Can't find
  service: meminfo" (SELinux) and `screencap` fails, so Memory Usage and screenshots need Shell;
  `dumpsys activity` needs usage access on top.
- Android 12+ won't let an app *read* a hidden settings key that isn't marked readable, even one
  it may write: `SettingsStore.get` returns `Unreadable` and `ToggleRunner` reads it as the shell.
- The Shizuku user service is a daemon, started once per boot; a cold widget tap only rebinds.
- **`Shell.stream` runs one long command, `exec`'d** (`streamArgv`): without `exec`, `sh -c`
  forks it and stopping `sh` orphans it. Through Shizuku the service hands back a pipe; the app
  closing its end stops the command, and a `poll()` watch (POLLERR on a pipe with no reader)
  catches it for a quiet command that never writes again.
- Test fakes (`FakeShell`, `FakeSettingsStore`, `FakePlatformToggles`, `FixtureShell`) live in
  `core/src/testFixtures`. `FixtureShell` replays real emulator output from `fixtures/`
  (`scripts/sync-fixtures.sh` copies it from the main repo at a pinned commit).

## Build, test, check

```
./gradlew :core:test                   # fast, no Android
./gradlew :app:testDebugUnitTest :platform:testDebugUnitTest
./gradlew ktfmtCheck                   # ktfmtFormat to fix
./gradlew :core:lint :platform:lintDebug :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:installDebug            # onto the running emulator
```

Warnings are errors everywhere: Kotlin `allWarningsAsErrors`, Android Lint `warningsAsErrors`.
The repo is private until the first release, and the org is on GitHub's free plan (2,000 Actions
minutes a month), so CI runs format, unit tests, lint and both builds; **emulator tests run on the
Mac before merge**. Emulators: API 30, 34, 36, 37 `google_apis` arm64 images (rootable with
`adb root`).

## Crash Catcher

Crash reports come from Android's DropBox (`platform/…/CrashLog.kt`, parsed by
`core/…/crashes/CrashReport.kt` against `fixtures/dropbox/`). Spike 9 settled the shape:
`DROPBOX_ENTRY_ADDED` reaches only a receiver registered at runtime, so alerts for watched apps
(`app/…/crashes/CrashAlerts.kt`) ride the automation service, which runs while any app is
watched. Reading needs `READ_DROPBOX_DATA` (`READ_LOGS` below Android 15), granted with the
Granted level by `Grants.commands`, plus usage access.

## Releases

Signed APKs on GitHub, from a `vX.Y.Z` tag: `docs/releasing.md` has the key setup and the steps.
The version lives in `gradle.properties` (`droidective.versionName`/`versionCode`); the release
key arrives only through `DROIDECTIVE_KEYSTORE*` variables, and `scripts/build-release.sh`
refuses an APK not signed by the certificate in `release-cert.sha256`. The app's update check
(`core/…/update/`, `app/…/update/Updates.kt`) reads `releases/latest` once a day and only ever
opens pages inside this repo's releases: nothing is downloaded or installed by the app.

## Git

Feature branches and PRs; pushing to `main` is blocked by a hook. In this repo I merge my own PRs
once CI is green. Commits: imperative mood, one logical change, with the Co-Authored-By trailer.
