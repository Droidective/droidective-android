# Reactotron — on the phone

[Reactotron](https://github.com/infinitered/reactotron) is how many React Native developers see
what their app is doing: its logs, network calls, Redux or MobX actions and benchmarks, in a
desktop app the app reports to over WebSocket. Droidective is that desktop app, on the phone the
app runs on.

## Why it works with no setup

A React Native app's Reactotron client dials `localhost:9090` unless told otherwise. On a phone,
`localhost` is the phone: a computer only hears it because `adb reverse tcp:9090 tcp:9090` sends
the port there. With nothing reversed, Droidective listening on the phone's own port 9090 is
what the app reaches — no host to configure, no cable, no computer. A debug build allows
cleartext to localhost, which is all a `ws://` connection there needs.

## Where the code is

| File | What it is |
| --- | --- |
| `core/reactotron/ReactotronServer.kt` | The WebSocket server: upgrade, frames, the client handshake, pings |
| `core/reactotron/ReactotronCommand.kt` | A frame's envelope, and the client's `~~~ … ~~~` markers undone |
| `core/reactotron/ReactotronEvent.kt` | Each command read for what it says, as Droidective for Mac reads it |
| `core/reactotron/ReactotronTimeline.kt` | What the apps said, bounded by count and by bytes |
| `core/reactotron/ReactotronLabels.kt` | A row's tag and summary, the filter's kinds, the search |
| `core/reactotron/ReactotronSearch.kt` | One search of the timeline, keeping each entry's answer so new entries are all it reads |
| `core/reactotron/ReactotronCommands.kt` | The custom commands apps register, running one, asking for state, backing it up and restoring it |
| `core/reactotron/ReactotronSnapshots.kt` | The states kept to put back: an index, a file each, bounded |
| `core/reactotron/ReactotronWatch.kt` | Watched paths: what each app last said they hold, and which answer which |
| `app/reactotron/Reactotron.kt` | The one server, its state, the apps connected, the timeline |
| `app/reactotron/ReactotronService.kt` | The foreground service that keeps it listening |
| `app/ui/tools/ReactotronTool.kt` | The screen: the header, the tabs (Timeline, State, Snapshots, Console), the timeline; on a tablet, what's open beside the list |
| `app/ui/tools/ReactotronServerUi.kt` | The status chip, the server sheet, and the card that says how to connect |
| `app/ui/tools/ReactotronEntryUi.kt` | An entry whole: overview, request or response, stack, payload; the frame every detail shares |
| `app/ui/tools/ReactotronStateUi.kt` | Reading the apps' state and dispatching an action, with what comes back shown in place |
| `app/ui/tools/ReactotronWatchUi.kt` | Watch: each path with what it holds now, as it changes |
| `app/ui/tools/ReactotronSnapshotsUi.kt` | Snapshots: taking one, the ones kept, and one whole to restore, rename or delete (Delete asks first) |
| `app/ui/tools/ReactotronConsoleUi.kt` | The commands the apps registered, by app, to run |
| `app/ui/tools/ReactotronReplUi.kt` | The REPL: what the app shares, and code run in it |
| `app/automation/ToolActions.kt` | A command run from a widget slot or an automation |

## Decisions worth not relitigating

- **The handshake is reactotron-core-server's**, so an app can't tell the two apart: a client
  with no id is given one (`setClientId`), the empty subscription list
  (`state.values.subscribe`) is what tells it it is connected, and a reload — which connects
  before its old socket dies — takes the earlier connection's place without a "gone" line.
- **It listens on `127.0.0.1` only**, as the proxy does, and **refuses an upgrade whose `Origin`
  isn't this phone**, so a web page open in Chrome can't write into the timeline. React Native's
  WebSocket sends `Origin: http://localhost:9090`; the JDK's and Node's `ws` send none.
- **A port someone else holds says who**: almost always a computer's `adb reverse tcp:9090`,
  which the screen offers to free with `adb reverse --remove tcp:9090`.
- **The timeline is bounded by bytes as they crossed** (32 MB, 5,000 entries), counted by the
  server rather than by writing payloads back out: a state tree or an answer can be megabytes.
- **What the server sends goes from its own thread.** A screen calls `send()` from the main
  thread, which Android won't let touch a socket; the first Run button on the emulator found
  that out the hard way.
- **Commands are buttons, not news.** An app registers its commands again on every connect
  and reload; they are kept per connection, gone with it, and left out of the timeline, as
  Reactotron's desktop keeps them in a tab of their own.
- **The frame reader is the proxy's** (`core/net/ws`), at a 16 MB limit; a larger frame arrives
  as an `(undecodable)` command that says so.
- **A slot or an automation finds its command by the app's name and the command's**, never by
  the connection, which is new on every reload. A reloading app briefly has two connections;
  the newer one is the live one. What the slot gives the arguments is kept with it.
- **Snapshots are kept in the app's files**, where the Mac keeps them for the session: Android
  ends a process in the background, and a snapshot is something to come back to — a filled cart,
  a login, a step deep in a flow. An index, and each state in a file of its own, read only to
  restore or show it; twenty, or 32 MB, the oldest going first. A backup answer is a snapshot,
  not news, so it stays out of the timeline. A restore leaves no line there either:
  reactotron-redux keeps its own `REACTOTRON_RESTORE_STATE` action out, so the screen says it
  was sent, and the app's state is the proof.
- **Watched paths are the server's to send**, as reactotron-core-server does: the list goes out
  in place of the empty one at every connect and again whenever it changes, and is kept in the
  app's files. A store plugin sends every watched value on each change, so an app's latest
  `state.values.change` is the whole picture; those messages update the Watch panel and stay out
  of the timeline. With its last path gone an app sends nothing at all (the client skips an
  empty change), so what is shown is filtered to the paths still watched.
- **An action is a type alone or a whole object.** Typing braces and quotes on a phone is a
  chore and many actions are only a type, so `logout` dispatches `{"type":"logout"}`; anything
  starting with `{` must be a JSON object with a string `type`. It goes to every connected app,
  as a state read does, and shows in the timeline as the ACTION the app reports back. A slot or
  an automation dispatches in one app, found by its name, so `logout` on a widget logs that app
  out and no other.
- **The REPL runs code in the app, as Reactotron's does.** `repl.ls` lists what the app shares
  with `Reactotron.repl(name, value)`, kept as chips that put `this.<name>` in the field, not as
  lines in the timeline; `repl.execute` sends the code, which the client runs with those values
  as `this`, and what it comes to is a REPL line in the timeline. The client doesn't catch what
  the code throws, so a throw brings nothing back, and the screen says so.
- **An answer shows where it was asked for, and in the timeline too.** A state read, a dispatch
  and a REPL run each list what came back after the button was pressed: the newest few entries of
  that kind received since then (a dispatch, the ACTION of the type sent), each opening the entry
  whole. With nothing after four seconds, the screen says what the app is missing. Watched values
  sit under the path that asked for them (`ReactotronWatch.readings`), a `.*` path with a value
  per key.
- **Search reads everything an entry carried**, not only its row: a response's body and headers,
  a request's, an action, a state answer. Keys and values are searched as text, so a body sent as
  text is found as it reads (`"status":"placed"`), and nulls aren't text. That's up to 32 MB, so
  the screen searches once typing pauses, off the main thread, and a `ReactotronSearch` keeps each
  entry's answer: a timeline growing under a search reads only what's new.
- **A slot or an automation restores a snapshot by its id**, so renaming it changes nothing. A
  snapshot deleted, or dropped past the bounds, makes the slot say it is gone rather than guess
  at another.

## What is left

- **Images.** An `image` command is listed, not shown.
- **The MCP server** Droidective for Mac serves Reactotron's timeline through has no phone half.

## Checking it against a real client

`reactotron-core-client` in Node, through `adb forward tcp:19090 tcp:9090`, is the app's own
client with no app: `createClient({ createSocket: (path) => new WebSocket(path), port: 19090 })`
with `ws`, then `log`, `display`, `apiResponse`, `benchmark` and `send("state.action.complete", …)`.
