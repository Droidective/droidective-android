# Release notes

Each release has a `## X.Y.Z` section; `scripts/release-notes.sh` copies it into the GitHub
release, and a tag without one fails the release.

## 0.10.0

Network Traffic reads WebSockets, and can send an app's requests to a different server.

- **WebSockets.** A socket an app opens shows up in Network Traffic with everything it says:
  each message in and out, as it happens, with its time and size, JSON laid out when you open
  it. Compressed sockets are read too. Search with `is:socket`, and HAR exports carry the
  messages the way Chrome's DevTools writes and reads them.
- **Map remote.** Send a URL's requests to another server instead — staging, or a server on your
  computer (`http://10.0.2.2:<port>` from the emulator, or `http://127.0.0.1:<port>` on a phone
  after `adb reverse`) — with no new build. The path and query stay as the app asked, a mock
  still answers first, and Send again goes where the app's request went. Start one from Rules,
  or with "Send this server's requests elsewhere" on any request.
- **Fixed:** in Network Traffic, coming back from a request or an editor emptied the search and
  scrolled the list back to the top.
- **Fixed:** when an app closed a connection passed through the proxy, or a socket, the server
  was never told, and the proxy kept the connection open.
- **Fixed:** a request could now and then be listed without the app that made it.

## 0.9.0

Network Traffic stops being only a window onto what apps send: send a request again, change it,
answer it yourself, slow it down, or hold it on its way and decide what happens to it.

- **Send again, and edit and send.** Any captured request can be sent again as it was, or changed
  first — method, URL, headers and body — and the repeat shows in the list beside the original.
  Charles calls these *Repeat* and *Compose*.
- **Mocks.** Answer a URL here instead of the server: a status, headers and a body, matched by a
  URL pattern with `*` and, if you like, a method. "Mock this response" on any request starts one
  from the answer it got, so changing one field is one edit. Mocked requests never reach the
  server, and the list marks them.
- **Network speed.** Play a slower network through the proxy — Chrome's Fast 4G, Slow 4G and 3G
  — to see an app's loading states without leaving the desk.
- **Breakpoints.** Hold a request before it goes, or its answer before the app sees it, and
  change it, let it go or stop it. A notice pops up over the app that's waiting, with Let it go
  on it, and the held one sits at the top of Network Traffic. Make one from Rules, or with "Hold
  requests like this" on any request. A hold that nobody decides goes on as it was after five
  minutes, and stopping the proxy lets everything go. Search with `is:held` and `is:changed`.
- **Fixed:** in Network Traffic, coming back from a request or an editor folded the rules up and
  scrolled the list back to the top.

## 0.8.0

Droidective from wherever you are: over the app you're working on, from the app's icon, and from
Files and the share sheet.

- **Floating controls.** A bubble over any app that opens to Screenshot, Clean screenshot, Record
  screen, Capture views, the activity on screen (copied) and the debugging proxy, so you capture
  the app you're working on without leaving it. The controls keep out of what they capture —
  hidden for a screenshot, and for the whole of a recording — and stay where you drag them. Turn
  them on from the Toggles tab, their own Quick Settings tile, a widget or an automation.
- **Pinned and recent tools.** The Tools list leads with the tools you pinned and the ones you
  opened last. Long-press any tool to pin it or to add it to the home screen.
- **Shortcuts on the app's icon.** Long-pressing Droidective's icon offers your pinned tools, then
  your recent ones.
- **Open with, and Share.** An APK, XAPK or APKM tapped in Files, or shared from another app,
  opens in Install App, which reads it and waits for you to press Install. Text with a link in it,
  shared from any app, opens Deep Links with the link ready to try.
- **Fixed:** in Network Traffic, stopping and starting the proxy again made the new requests
  replace the earlier ones in the list, one for one.

## 0.7.0

A debugging proxy that runs on the phone itself, a tool for notifications that don't show,
and more of what an app has set up on the phone.

- **Network Traffic.** An HTTPS debugging proxy on the phone — the job Charles, Proxyman and
  mitmproxy do from a computer, with no network to set up. Start points the phone at it; Stop
  puts the phone's proxy setting back the way it was, even after a reboot or an update cut it
  short. Every request the phone's apps make shows with the app that made it, its status, time
  and size, and its body decoded: JSON laid out, gzip undone, forms, images. GraphQL requests
  are named by their operation. Search by app, host, status or failure, copy one as cURL, or
  share what the search shows as a HAR file that Chrome, Charles and Proxyman open.
  - HTTPS needs Droidective's certificate installed, which the screen walks through, and an
    app that trusts it: Chrome does, and so does your own debug build with the network security
    config the screen copies for you.
  - **Read only the apps you choose**, and every other app's traffic passes through untouched,
    so the rest of the phone keeps working. An app that refuses the certificate is stepped
    aside for after its first refusal. Hosts can be passed through, or read alone.
  - A **Debugging proxy** tile and toggle start it from Quick Settings, a widget or an
    automation.
  - Without Granted or Shell it still runs: point Wi-Fi's proxy at 127.0.0.1:8888 yourself.
    Naming the app behind each request needs Shell.
- **Notification Inspector.** Why an app's notification shows or doesn't: notifications off for
  the app, every channel off, or Do Not Disturb; what it has showing now, with each one's
  channel, actions and whether it belongs to a foreground service; and every channel's
  importance.
- **App ops** in the Apps tool: the special accesses beyond runtime permissions — running in the
  background, display over other apps, usage access, exact alarms and the rest — each with what
  its modes mean, when it was last used, and a tap to change it.
- **App Links** in Deep Links: each of an app's domains, whether links to it open the app, and
  why not, with Verify again and Approve for testing.
- **Alarms** in Background Work: the alarms an app has set, when each fires, and whether it
  wakes the phone or is exact.
- **Record screen, Performance overlay and Capture views** as toggles, so they can sit in a
  widget, the notification bar or an automation, and **Quick Settings tiles of their own** for
  Record screen, Screenshot, Clean screenshot, Performance overlay and Capture views.

## 0.6.0

Four new tools, none of them on the Mac yet, and a root fix.

- **UI Inspector.** Any app's views from `uiautomator`, with a screenshot: tap the picture to pick
  the view under your finger, or search by text, description or id. Each view shows its id, text,
  bounds in pixels and dp, and the lines UI Automator, Espresso, Compose and Maestro tests would
  find it by. It counts clickable views TalkBack can't name and touch targets under 48 dp.
- **Intent Builder.** Any activity, broadcast or service intent: action, data, type, a component
  picked from the app's own list, categories, flags and typed extras. A launch it waits for is
  timed, and Cold start stops the app first, which is how Android Studio measures startup. Save
  the ones you reuse, or copy one as an adb command.
- **Network Check.** A host or URL checked from the phone: DNS, the connection, the TLS
  handshake with every certificate's expiry and SPKI pin, and the first HTTP answer, each timed.
  Pins copy as an OkHttp `CertificatePinner` or a network security config.
- **App Snapshots.** Save an app's private data and put it back later, to test again from a
  signed-in or onboarded state. Debug builds through run-as; any app with root.
- **Fixed:** the Sandbox Browser's root mode found nothing for other apps on phones where su runs
  in the app's own mount namespace; it now reads in init's namespace, where every app's data is.

## 0.5.0

Two more of the Mac's tools, and a crash fixed.

- **APK Inspector.** What an installed app or an APK file is made of, with no shell needed: its
  signature schemes and each certificate's SHA-256 (with a warning for Android Studio's debug
  key), the manifest flags reviewers ask about, native code per ABI and the dex count across every
  split, each permission's protection level, and every component with whether any app can reach
  it. Copy report puts it all in one block of text.
- **Screen Record.** The screen as a video through Android's own screenrecord: full or half size,
  a choice of quality and time limit, a countdown to open what you want to show, and while it
  records shown taps, demo mode's clean status bar, or burned-in timestamps for bug videos. It
  keeps recording when you leave; the notification stops it, and taps and the status bar go back
  exactly as they were.
- **Fixed:** Bug Report's Share closed the app. The report now saves to Downloads as a file
  Droidective owns, which the share sheet accepts.

## 0.4.0

Four more tools, the numbers over any app, and two fixes underneath.

- **Performance Monitor.** CPU per core, memory and the hottest temperatures, live, and for one
  app the frames it drew since you last reset: how many were janky and how long the slow ones
  took, 50th to 99th percentile. **Show over other apps** puts the numbers in a small window you
  can drag over any app, following whichever app is in front.
- **Settings Editor.** Android's Global, Secure and System settings, system properties and feature
  flags, searched and changed. Every change remembers what it replaced, so Undo puts it back, even
  after the app restarts. As the shell user, Android 15 and later change only the flags on their
  allowlist; root changes any.
- **Background Work.** Doze on demand, and Wake up to hand it back to Android; an app's standby
  bucket; and the jobs it has scheduled, WorkManager's included, each runnable now.
- **File Explorer.** The phone's folders as the shell or root sees them, from Downloads to /proc,
  with the Sandbox Browser's viewers for preferences, databases and text.
- Long output can't overflow Shizuku any more: a command's result comes back through a pipe rather
  than a reply that tops out near 1 MB. The flag list alone made a 714 KB reply on an emulator.
- Every switch row is one toggle: TalkBack reads its label with its state, and a tap anywhere on
  the row flips it.

## 0.3.0

Six more of the Mac's tools, on the phone.

- **Sandbox Browser.** An app's private files: SharedPreferences as a table of typed values,
  databases as tables you can query (read from a copy, so nothing writes to the app), text, and a
  copy saved anywhere. Debug builds through Shell; any app with root.
- **React Native.** For your app's debug build: reload JS, open the dev menu, restart it, simulate
  process death, and point it at a Metro dev server. Reload JS and Dev menu are toggles too, so a
  Quick Settings tile or the notification bar acts on whatever app is open under the shade.
- **Language.** One app in another language (Android 13+), pseudo-locales included: en-XA for
  long, accented strings and ar-XB for a mirrored, right-to-left layout.
- **Monkey Test.** Random taps in one app to hunt for crashes, with the seed shown so a crash can
  be repeated. It taps only, unless you allow swipes, so it can't wander into Quick Settings, and
  its notification stops it, since Monkey keeps every other app from opening while it runs.
- **Terminal.** A shell prompt as root, the shell user or the app, where `cd` sticks, with history
  and a Stop that ends the command for real.
- **Bug Report.** The zipped report Android Studio and the Play Console ask for, saved to
  Downloads with a Share button. It keeps going if you leave the screen.

## 0.2.0

- **Any action, one tap from the home screen.** A widget slot can now run one of your
  automations, run a single action on its own (set a toggle to one exact state, force stop, clear
  data or uninstall an app, grant or revoke a permission), run a saved command as root or Shell,
  or open any of Droidective's tools. Saved commands can finally be put on a widget, too.
- **Style widgets your own way.** Grid or list, a fixed number of columns, icon size, corners,
  padding, a circle or rounded chip behind each icon that fills in while it's on, labels, each
  slot's state under its label, text size and colour, and your wallpaper's colours on Android 12+.
  Presets set the colours in one tap. Each slot can have its own label, icon and colour, from 181
  icons.
- **Two more widgets**: a one-cell button and a list that shows every state. Place any of the
  three from the Widgets tab or the launcher.
- **Keep widgets live.** On OnePlus, OPPO and realme phones, Android freezes apps in the
  background and held a widget tap for up to half a minute. Droidective now keeps a quiet
  notification up while any widget, tile or notification bar is placed, so every tap lands at once
  and widgets follow changes made anywhere else. It's on by default on those phones, and the
  Widgets tab has the switch.
- Fixes from testing on a OnePlus Pad: Stay awake, Grayscale, SELinux and Strict mode flash now
  show their real state; toggles a phone lacks the hardware for (NFC, mobile data) are hidden;
  font size, brightness and timeout no longer ask for Modify system settings when Droidective has
  Shell; rows follow changes made outside the app; Crash Catcher asks for notifications when you
  watch an app; Usage access opens Droidective's own page and is allowed automatically with Shell;
  Device Info shows the bootloader's lock; preinstalled apps no longer say they were installed in
  1970; a Quick Settings slot can be cleared.

## 0.1.2

- **Widgets work again.** In 0.1.0 and 0.1.1 a tap on a home-screen bar or a folder did nothing:
  the release build had stripped what Android needs to run it. Update to use your bars.
- **The app icon is Droidective's logo**, the same one as the Mac app, with a themed version for
  launchers that tint icons.

## 0.1.1

- The Tools, Widgets and Setup tabs no longer draw under the status bar. The Tools search field
  sat under the clock, and the Widgets and Setup lists scrolled beneath it.

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
