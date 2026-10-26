# Release notes

Each release has a `## X.Y.Z` section; `scripts/release-notes.sh` copies it into the GitHub
release, and a tag without one fails the release.

## 0.27.0

Widget taps now say how they went, the Wireless debugging button shows where to connect, and the
Developer Settings button opens the phone's Developer options.

- **A toast for every tap.** A widget, notification bar, tile or floating control now says what
  it did ("Wi-Fi: On", "Lock screen: done") or why it didn't work. Android hides an app's toasts
  while its notifications are off, so placing a widget now asks for notifications, and the
  Widgets screen says when they're off. Screenshots and recordings stay quiet, because the toast
  would be captured.
- **Wireless debugging's address.** Turning it on from a widget shows the address and port to
  connect to, such as 192.168.1.2:45745. On Android 17, Droidective first needs local network
  access: tap its notification to allow it. Off Wi-Fi, the toast says Android turned wireless
  debugging back off.
- **The right port in Wireless ADB.** After wireless debugging was turned off and on again,
  Wireless ADB could show the previous port. It now checks that the port it finds is live.
- **Developer Settings opens Developer options.** A Developer Settings button on a widget,
  notification bar, folder or tile now opens the phone's Developer options. The Tools list still
  opens Droidective's own Developer Settings.

## 0.26.0

Notification bars can be styled, and the Network widget shows the Wi-Fi name more often, follows
its settings straight away and looks like your other widgets.

- **Style a notification bar.** Its Style panel now has the icon size, labels and each item's
  state under the icons, Grid or List, and a rounded background of its own in any colour and
  opacity. Collapsed, the notification is one row of icons at the chosen size. Expand it to see
  the labels and states, or a row per item with List. A bar you already have looks the same until
  you change it.
- **The Wi-Fi name, more often.** With the Granted level, the Network widget now reads the Wi-Fi
  name as the app itself, before asking Shell. If it still can't, its settings say why under
  Network name.
- **Settings take effect at once.** Switching Public IP off (or anything else) changes the widget
  straight away, and leaving with Back keeps the change.
- **The public IP while Network Traffic records.** The lookup no longer goes through Droidective's
  own proxy, which made it fail with "Not found". A lookup that fails says "Couldn't look it up",
  and the settings give the reason.
- **The Network widget matches your bars.** It now draws in Droidective's dark look by default.
  Under Look, pick "Same as" one of your home-screen bars, or one of the bars' colour sets.

## 0.25.0

Network Traffic is easier to read, a new Network widget shows the IP address and Wi-Fi name, and
rooted phones stop showing the superuser toast over and over.

- **Network Traffic says why a call wasn't read.** A call your app made that Droidective didn't
  read used to be a grey "Passed" or red "Failed" row with nothing in it. Now each such row says
  why: the app isn't one of the apps being read, a host rule passes it through, or the app doesn't
  trust Droidective's certificate. A card over the list names the debug builds affected, each with
  a one-tap fix. "Show me how" gives the config file and the manifest line to copy so your debug
  build trusts the certificate, then reads it again.
- **The response comes first.** A request opens on its status, then the response body, with the
  headers folded under it. Find in the header searches the URL, the headers and the bodies. Copy
  as cURL, mocks, breakpoints and remaps are in the menu.
- **A simpler screen.** One chip in the header says whether it's recording and opens the proxy
  controls. Requests, Rules, Mocks and Certificate are tabs, and an empty list explains how to
  start. On a tablet, the request you open shows beside the list.
- **Network widget.** A new home-screen widget with the Wi-Fi name, this phone's IP address and
  the public IP. Choose what it shows and which address a tap copies. It updates when the phone
  joins a network. The Wi-Fi name needs Shell (Shizuku or root), and the public IP is looked up
  from api64.ipify.org.
- **Network slots.** Any bar, list, notification bar, folder or Quick Settings tile can show the
  IP address, the public IP or the Wi-Fi name as text, and copy it on a tap.
- **Root asks once.** With root on, every command Droidective ran started its own `su`, and Magisk
  or KernelSU showed their "granted superuser" toast each time, even with the app in the
  background. Now each app process uses one `su` session, and doesn't start it until something
  needs root.

## 0.24.2

- **Find inside a Reactotron entry.** Open an entry and tap the search button beside Copy: every
  match in its URL, headers, body, message, stack or payload is highlighted, with "1 of 7" beside
  the word, and the arrows jump from one to the next. An API call's Request and Response say how
  many matches each holds. Snapshots have the same find.
- **The timeline's search is back to the rows.** It matches what each row shows (app, status,
  message, URL), as it did before 0.24.1, so it finds the row you're after instead of every entry
  that mentions a word somewhere inside.

## 0.24.1

- **Reactotron's search reads inside what each entry carried.** It used to match only the line
  each row shows. Now it also finds text in a response's or a request's body and headers, an
  action, a state answer or a log's whole message, so a word that only appears in a response
  body finds that call. It searches a moment after you stop typing, without slowing the screen.

## 0.24.0

Reactotron is easier to find your way around: the timeline is the screen, and everything else is
a tab away instead of stacked above it. Nothing it did before is gone.

- **One chip for the server.** The header says Off, Waiting, the connected app's name or how many
  apps are connected. Tap it for Start and Stop, the address, each connected app and how to
  connect one. Until an app is connected or anything is kept, the screen is one card with the
  three steps and the Start button.
- **Tabs.** Timeline, State, Snapshots and Console, each keeping its fields and scroll. The
  timeline keeps search and the kind filters above the list, and new entries stay in view while
  you read from the top.
- **Answers where you ask.** A state read, a dispatched action and a REPL run show what came back
  right under the button, as well as in the timeline, and say what the app is missing when
  nothing does. Each watched path shows its values under it.
- **Entries.** An API call shows its URL and status first, then its request or its response,
  each with headers and body. A log shows its message and stack. Copy payload is in the header,
  and the payload text can be selected.
- **Console.** The app's commands are grouped by app, with Run on the row, beside the REPL.
- **Snapshots.** Restore comes first; Rename and Delete are in the menu, and Delete asks first.
- **Reading the whole store.** Read value with an empty path shows the store as a value. It was
  labelled KEYS, because reactotron-redux answers that request as keys.
- **Tablets.** The entry or snapshot you open shows beside the list instead of in its place.

## 0.23.0

Widgets are simpler to make and change, and the app uses the room a tablet has.

- **The Widgets tab.** One New button says what each thing is: a home-screen widget that starts
  as a bar, a button or a list, a notification bar, or a folder. Your widgets are cards with a
  live preview, and Delete asks first. Quick Settings shows only the tiles in use and Add a tile;
  picking what a new tile does goes straight on to Android's prompt to add it.
- **The editor.** The preview comes first, with Grid or List right under it. Items are tiles:
  tap one to change its look, move it or remove it. The style has a panel of its own, with the
  colour sets, size and labels first and the rest under More options. Notification bars and
  folders show only the two colours they use.
- **Saved deep links in widgets.** Links saved in Deep Links are listed when you add to a widget,
  folder, notification bar or tile, and a link typed there is saved to Deep Links too.
- **Tablets.** Toggles, Tools, Automations and Setup lay out in columns, the widget editor shows
  its style beside the preview, and Add opens as a panel. Phones look as before.

## 0.22.0

- **Crash reports.** When Droidective crashes or freezes, its stack trace now goes to the
  developer through Sentry, with the phone's model and Android version, so a crash gets fixed
  without anyone copying a report. Package names, file paths, IP addresses, email addresses, host
  names and URLs are taken out on the phone first, and nothing on screen is captured: no
  screenshots, no view hierarchy, no session replay. Setup ▸ Crash reports turns it off, and
  [the privacy policy](https://droidective.com/privacy.html) has the details. The full report
  Setup offers after a crash still stays on the phone.

## 0.21.1

- **The app picker no longer crashes on devices with two Chrome channels.** Every Chrome channel
  (Stable, Beta, Dev, Canary) names its launcher activity `com.google.android.apps.chrome.Main`,
  and the picker keyed its rows by activity name alone — opening it with two Chromes installed
  took the app down. Rows are now keyed by package and activity.

## 0.21.0

The APK Inspector reads an APK the way Android Studio's APK Analyzer does.

- **What takes the room.** Every folder and file in an APK, biggest first, with what each takes
  in the APK and holds unpacked; tap a folder to open it. An app installed as splits picks which
  of its APKs.
- **Dex files.** The classes and method references in each dex file and in all of them, and the
  dex format with the first Android that reads it.
- **The manifest as XML.** The compiled AndroidManifest.xml turned back into XML to read or copy,
  with numbers such as `singleTask` and `signature|privileged` named again, and each reference
  named from the app's own resources.
- **16 KB pages.** Whether each 64-bit native library is ready for phones with 16 KB memory
  pages, in its segments and where it sits in the APK, and why when it isn't.

## 0.20.0

Reactotron's REPL, and a copied cURL command that does what the app did.

- **Reactotron's REPL.** List what your app shares with `Reactotron.repl(name, value)`, tap a
  name to put it in the code, and run it in the app: `this.store.cart.items.length`, or a
  function you shared. What it comes to shows in the timeline as REPL.
- **Copy as cURL that runs.** A gRPC call copies as `curl --http2` with its bytes piped in, and
  any binary body up to 64 KB comes along the same way. A body kept only in part used to copy a
  command the shell split in two; what is left out is now said ahead of the command instead.

## 0.19.0

gRPC calls go again from the phone, and a server can name their fields itself.

- **Send a gRPC call again.** Send again works on gRPC calls now, which need HTTP/2: the call
  goes back to its server on a connection of Droidective's own, and its answer, status and
  trailers are read as the app's were.
- **Ask the server for its schema.** Under a gRPC call's messages, a server with reflection on
  gives its own schema: the call reads with field names, enum names and message types, with no
  `.proto` file compiled or pushed. What it gives is kept with the pushed schemas, and a server
  without reflection says so.

## 0.18.0

Reactotron watches your app's state as it changes, and dispatches actions into it.

- **Watch.** Name a path in the app's store — `cart.items`, or `user.*` for each key under
  `user` — and its value shows on the Reactotron screen, changing as the app's state does. The
  paths are kept, and every app is asked for them as it connects.
- **Dispatch.** Type an action and the app's store dispatches it: a type alone, such as
  `logout`, or a whole action as JSON, such as `{"type":"cart/add","item":"tea"}`.
- **Dispatch from a slot.** The same action on a widget, tile or notification bar, or in an
  automation: `logout` in one tap, in the app you chose.

## 0.17.0

Mock Location and Reactotron without opening Droidective, and an app's state kept to put back.

- **Places as slots and actions.** A widget, tile, notification bar, folder or automation can
  put the phone at a place you kept or a ready one, or give it its own location back, in one
  tap. Android lets an app start this from the background only from a widget, tile,
  notification or schedule; an automation on another trigger, such as joining a Wi-Fi network,
  manages it with Shizuku or root.
- **Reactotron commands as slots and actions.** A command your React Native app registers with
  `Reactotron.onCustomCommand`, arguments filled in, runs from a slot or an automation while
  the app is connected; if it isn't, the slot says so.
- **Reactotron snapshots.** Take a snapshot of the app's whole state through reactotron-redux
  or reactotron-mst, keep it on the phone, and restore it into the app: a filled cart, a login,
  a step deep in a flow, without going through the app to reach it again. Snapshots can be
  renamed, read and deleted; twenty are kept.
- **Restore from a slot.** A snapshot goes on a widget, tile or notification bar, or into an
  automation, and puts the app back in that state in one tap.

## 0.16.0

Put the phone anywhere, or set it moving; and Reactotron answers back.

- **Mock Location.** Every app that asks where the phone is gets the place you choose: typed
  coordinates, a Google Maps or OpenStreetMap link pasted in, a `geo:` link, a place you kept,
  or one of ten cities ready for testing what changes by country. Droidective becomes the mock
  location app in one tap with Shizuku or root, or from Developer options where the shell can't
  set it, and Stop gives the phone its own location back.
- **Moving.** Give it waypoints, one a line, or a GPX track, and a pace — walk, run, cycle,
  drive, highway — and the phone travels them with the bearing and speed a delivery app's
  tracking screen or a run tracker reads, once or round and round.
- **Reactotron talks back.** Commands your app registers with `Reactotron.onCustomCommand`
  show as buttons, with a field for each argument, and run in the app from the phone. Its state
  can be read too, at a path or whole, with reactotron-redux or reactotron-mst.

## 0.15.0

Reactotron comes to the phone, and Network Traffic reads two more things it couldn't.

- **Reactotron.** A React Native app's Reactotron client connects to `localhost:9090`, which on a
  phone with no `adb reverse` from a computer is the phone itself. Start Reactotron in Droidective
  and the debug build you are running reports to it: its logs, API calls, Redux or MobX
  actions, displays and benchmarks, newest first, filtered and searched. An API call opens to
  its request and answer, bodies laid out as JSON; a log opens to its stack. No host to set, no
  cable, no computer. If a computer's `adb reverse` holds the port, it says so and gives the
  command that frees it.
- **gRPC with your schema.** Compile your `.proto` files with `protoc --include_imports
  --descriptor_set_out=app.protoset` and `adb push` the file to Droidective's `protos` folder:
  gRPC calls then show field names, enum names and typed values, as `protoc --decode` would,
  and gRPC's error details open up whole.
- **zstd.** Chrome and its WebView ask for zstd, and more sites now answer in it. Those bodies
  are now shown and exported decoded, where before they were compressed bytes.

## 0.14.0

Network Traffic reads apps that speak only HTTP/2, gRPC among them, where before it broke them.

- **HTTP/2.** An app that speaks nothing else, as gRPC and Firestore do, couldn't connect while
  it was being read: the proxy offered only HTTP/1.1. It is now carried to its server, and each
  stream on the connection is a flow of its own, with its headers, body and trailers. Apps that
  can speak either still use HTTP/1.1, where mocks, breakpoints and remaps reach them; those
  don't reach an HTTP/2 connection, and its flows say so.
- **gRPC.** A call is listed by its service and method, `Firestore/GetDocument`, and judged by
  its `grpc-status` rather than its HTTP 200, so a call that failed shows as failed, with its
  status in words. Its messages are read field by field without the `.proto`, the way
  `protoc --decode_raw` prints them, and so are binary trailers such as
  `grpc-status-details-bin`, where gRPC puts its error details.

## 0.13.0

Two of the Mac's tools come to the phone: one for permissions, one for sending requests by hand.

- **Permissions.** Each kind of runtime permission — camera, microphone, location, notifications,
  photos, contacts and the rest — with every app that asks for it and whether it holds it, and a
  switch to grant or revoke it. The quickest way to see who can use the microphone, or to take a
  permission away and see how an app copes. Switching needs Shell; looking doesn't.
- **API Testing.** Write a request, keep it by name, and send it from the phone, over its network
  and its VPN. `{{host}}` and `{{token}}` take values kept once, so one request works against
  staging and production. Paste what a browser's "Copy as cURL" gives and it becomes a request to
  edit. The answer shows its status, time, headers and body, and the request shows in Network
  Traffic too.
- **WebSockets** that can't be read — an extension Droidective doesn't know, or frames that stop
  making sense — now say so in their Messages, rather than going quiet.

## 0.12.0

Screenshots you can mark up before anyone sees them, taken from wherever you are.

- **Screenshot.** Take a screenshot and mark it up: a pen, a highlighter, arrows, boxes and
  text in six colours, and black-out or pixelation for what nobody should see. Undo and redo go
  back and forth through every change, clearing included. Save and share writes it to
  Pictures/Droidective at its full size and opens the share sheet; leaving with marks unsaved
  asks first. Pixelation averages every pixel in each block, so what's under it can't be read
  back out.
- **From anywhere.** A *Screenshot to mark up* tile in Quick Settings, a button in the floating
  controls, a widget slot or an automation takes it in two seconds and opens the editor — by
  itself when Droidective may draw over other apps, or from its notice.
- **Fixed:** capturing views in the UI Inspector while the debugging proxy ran took the proxy's
  notice, and its Stop button, away; an update notice and a monkey run's took turns the same way.

## 0.11.0

Network Traffic reads what Chrome is usually sent, answers mocks from files on the phone, and
passes on what is streamed as it streams.

- **brotli.** Bodies compressed with brotli — what Chrome gets from Cloudflare and most other
  CDNs — are now shown, mocked and exported as they read, instead of as compressed bytes.
- **Mocks from a file.** A mock can answer with a file instead of text typed on the phone:
  `adb push` a fixture into the mocks folder, and every request it answers reads the file as it
  is then, so pushing it again changes the answer. The editor shows the folder, a ready `adb push`
  line, and whether the file is there. JSON, HTML, images and more are typed by their extension.
- **Fixed:** an answer the server sent bit by bit — server-sent events, a long poll — reached the
  app in 8 KB lumps or only at the end, so a live stream looked stalled through the proxy.
- **Fixed:** a request whose app hung up, or whose server stopped part-way, stayed "waiting" in
  the list for good. It now says what happened.
- **Fixed:** a very compressible answer could unpack to gigabytes on the phone; decoding now
  stops at 32 MB.

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
