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

## Conventions

- **Quote every value a person supplies with `shellQuote()`.** Commands run through `sh -c`; the
  quote is the security boundary, not validation.
- **Split command output with `Parse.lines`**, never `"\n"`: OEM tools and adb emit CRLF.
- **Read back after every write.** `ToggleRunner.apply` reports `DidNotStick` when Android ignored it.
- **No WebView and no downloaded code anywhere in the app.** Remote v2 will run inside this app.
- **Exported components are an allowlist** (`ManifestTest`). Adding one means adding it there.
- **Two flavors, one package**: `play` and `github` share the package, key and version codes.
  Only `github` may ever compile Remote or FCM. Never put Remote in the Play build.
- Shizuku starts `ShellUserService` by class name in another process; `platform/consumer-rules.pro`
  keeps it through R8. Bump `SERVICE_VERSION` in `Shells.kt` when that class changes.
- Test fakes (`FakeShell`, `FakeSettingsStore`, `FakePlatformToggles`, `FixtureShell`) live in
  `core/src/testFixtures`. `FixtureShell` replays real emulator output from `fixtures/`
  (`scripts/sync-fixtures.sh` copies it from the main repo at a pinned commit).

## Build, test, check

```
./gradlew :core:test                   # fast, no Android
./gradlew testPlayDebugUnitTest :platform:testDebugUnitTest
./gradlew ktfmtCheck                   # ktfmtFormat to fix
./gradlew :app:lintPlayDebug :app:lintGithubDebug :platform:lintDebug
./gradlew :app:assemblePlayDebug :app:assembleGithubDebug
./gradlew :app:installGithubDebug      # onto the running emulator
```

Warnings are errors everywhere: Kotlin `allWarningsAsErrors`, Android Lint `warningsAsErrors`.
The repo is private until the first release, and the org is on GitHub's free plan (2,000 Actions
minutes a month), so CI runs format, unit tests, lint and both builds; **emulator tests run on the
Mac before merge**. Emulators: API 30, 34, 36, 37 `google_apis` arm64 images (rootable with
`adb root`).

## Git

Feature branches and PRs; pushing to `main` is blocked by a hook. In this repo I merge my own PRs
once CI is green. Commits: imperative mood, one logical change, with the Co-Authored-By trailer.
