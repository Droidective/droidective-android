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
| `app/reactotron/Reactotron.kt` | The one server, its state, the apps connected, the timeline |
| `app/reactotron/ReactotronService.kt` | The foreground service that keeps it listening |
| `app/ui/tools/ReactotronTool.kt` | The screen: start and stop, the timeline, an entry whole |

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
- **The frame reader is the proxy's** (`core/net/ws`), at a 16 MB limit; a larger frame arrives
  as an `(undecodable)` command that says so.

## What is left

- **Talking back.** Reactotron's desktop can ask for state (`state.values.request`), back it up
  and restore it, run custom commands an app registers, and use the REPL. The server has
  `send()`; the screen has nothing that uses it yet.
- **Images.** An `image` command is listed, not shown.
- **The MCP server** Droidective for Mac serves Reactotron's timeline through has no phone half.

## Checking it against a real client

`reactotron-core-client` in Node, through `adb forward tcp:19090 tcp:9090`, is the app's own
client with no app: `createClient({ createSocket: (path) => new WebSocket(path), port: 19090 })`
with `ws`, then `log`, `display`, `apiResponse`, `benchmark` and `send("state.action.complete", …)`.
