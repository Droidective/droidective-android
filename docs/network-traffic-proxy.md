# Network Traffic — the debugging proxy

An HTTPS debugging proxy that runs **on the phone**: the same job Charles, Proxyman,
Fiddler and mitmproxy do, with a certificate authority the phone generates itself.
For developers watching their own app, and for anyone who needs to see what a device
is actually sending.

This document is the handover. It says what is built, what is left, in what order,
and the handful of decisions that are easy to get wrong a second time.

## Why it runs on the phone

Every comparable tool runs on a computer and makes you join the same network, find
its IP, set a Wi-Fi proxy by hand and keep the two on the same subnet. None of that
is necessary when the proxy is the phone. The phone points at `127.0.0.1`, there is
no network setup, and it keeps working on mobile data, on a train, on a device that
is not yours to configure.

## Where the code is

Everything except the Android plumbing is in `core`, which is a plain JVM module —
so the whole proxy is unit-tested against loopback sockets with no emulator.

| File | What it is |
| --- | --- |
| `core/net/tls/Der.kt` | A DER encoder, written out so no crypto library is needed |
| `core/net/tls/X509.kt` | The certificate body: names, validity, constraints, key usage, SANs |
| `core/net/tls/ProxyCertificates.kt` | The authority: generate, mint per host, export PEM, save/reopen |
| `core/net/http/Headers.kt` | Case-folding, order-keeping headers |
| `core/net/http/Messages.kt` | First lines, heads, and `BodyFraming` |
| `core/net/http/HttpReader.kt` | Reads a head and stops at the body |
| `core/net/http/HttpBody.kt` | Moves a body across, keeping a bounded copy |
| `core/net/proxy/ProxyServer.kt` | The server: forward, open a `CONNECT`, or pass it through |
| `core/net/proxy/ProxyRules.kt` | Which hosts are opened and which are left alone |
| `core/net/proxy/Origin.kt` | Where a request is going |
| `core/net/proxy/ProxyTls.kt` | The two TLS ends |
| `core/net/proxy/ConnectionOwners.kt` | `/proc/net/tcp` → the uid behind a connection |
| `core/net/proxy/FlowStore.kt` | What has been seen, bounded by count and by bytes |
| `core/net/proxy/FlowFilter.kt` | The search box, and `FlowSummary` for the chips |
| `core/net/proxy/BodyView.kt` | Decoding and shaping a captured body |
| `core/net/proxy/Curl.kt` | A flow written back out as a command |

`core/net` carries **126 tests**, nearly all of them this work.

## What is merged

| PR | What it added |
| --- | --- |
| [#69](https://github.com/Droidective/droidective-android/pull/69) | The certificate authority |
| [#70](https://github.com/Droidective/droidective-android/pull/70) | The HTTP wire layer |
| [#71](https://github.com/Droidective/droidective-android/pull/71) | The proxy itself |
| [#72](https://github.com/Droidective/droidective-android/pull/72) | Per-app attribution |
| [#73](https://github.com/Droidective/droidective-android/pull/73) | The store and the filter |
| [#74](https://github.com/Droidective/droidective-android/pull/74) | Body decoding and cURL export |
| [#76](https://github.com/Droidective/droidective-android/pull/76) | Listening on loopback rather than every interface |

Nothing is wired into the app yet. There is no feature id in the registry, no screen,
and no way to start it — `core` is complete and inert.

## What is left, in order

### 1. `ProxyService`

A foreground service, `specialUse`, declared in the manifest beside
`ScreenRecordService` (copy that declaration and its `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
wording). It owns the proxy's lifetime: `onStartCommand` starts it, `onDestroy` calls
`TrafficProxy.shutDown`. Notification: "Watching this phone's traffic", "Listening on
127.0.0.1:8888", with a Stop action and a tap that opens the tool. Channel
`traffic-proxy`, id 7430, icon `sym_travel_explore`.

This is not decoration. Without a foreground service OxygenOS freezes the app, and a
frozen proxy is a phone whose network has silently stopped.

> Writing this file was refused by the Claude Code auto-mode permission classifier
> ("Expose Local Services"). Whoever picks this up either writes it by hand or grants
> the permission first.

### 2. `TrafficProxy` — on the `proxy-android` branch, unmerged

Already written, pushed, **does not compile on its own** because it refers to
`ProxyService`. It holds the state flow, the store, the certificate keystore
(`filesDir/proxy-ca.p12`), the `FlowSink` that stamps each flow with the app name,
the `FlowOwners` that reads the socket table through the shell, the `KeyChain` install
intent, and the proxy-setting plumbing.

### 3. Feature registration

- `Features.kt`: id **`network-traffic`**, title "Network Traffic", category `Network`,
  level `Shell`, icon `travel_explore`. Keywords worth having: proxy, http, https,
  charles, proxyman, mitm, capture, intercept, api, request, response.
- `ToolsScreen.kt`: add to `toolScreens`.
- `ToolsTest.kt`: add to `phoneOnly` — the Mac has no counterpart. (The Mac's
  `http-proxy` is a different feature: it only *sets* the device's proxy.)

### 4. The screen

Model the layout on `LogcatTool`: a `Scaffold` with a `LazyColumn`, not `ToolScaffold`,
which scrolls and cannot hold a long list.

- **Setup**, shown until it is running: install the certificate, point the phone here,
  and the explanation in "What stops this working" below.
- **Running**: start/stop, the address, how many flows and how much is held.
- **The list**: a row per flow — method, status tinted by class, host and path, the
  app, duration, size. Search box over `FlowFilter.parse`, chips from `FlowSummary`.
- **The detail**: request and response headers, the body through `BodyView`
  (JSON tree, text, form fields, image), and copy as cURL.

Pace the list off `TrafficProxy.changes` with `sample(250)` rather than recomposing per
flow — the same lesson the Mac app learned about feeds.

### 5. Then

Pass-through rules editor (`ProxyRules`), HAR export, a Quick Settings tile, and
editing a request before it goes out.

## What stops this working, and what to tell people

**Android 7 and later do not trust a user-installed certificate by default.** This is
the single thing that confuses everyone, and the screen should say it plainly rather
than let someone discover it:

- **Your own debug build** — add a network security config and it works:
  ```xml
  <network-security-config>
    <debug-overrides>
      <trust-anchors>
        <certificates src="system" />
        <certificates src="user" />
      </trust-anchors>
    </debug-overrides>
  </network-security-config>
  ```
  Offer this as a copy button.
- **Any app, on a rooted device** — the certificate goes in the system store.
- **An app that pins its certificates** will not work either way. That is the app
  working correctly. Put the host in `ProxyRules.except` so it keeps working while
  the app you are debugging stays open. **Defeating pinning is deliberately out of
  scope** — Charles and Proxyman do not do it either.

## Decisions worth not relitigating

- **No BouncyCastle.** `Der.kt` and `X509.kt` write the certificate by hand; the JDK
  generates keys and signs. Saves a ~5 MB dependency and keeps it all testable in `core`.
- **One leaf key for every host.** Standing in for a new host costs a signature, not an
  RSA key generation — milliseconds instead of seconds on a phone.
- **The real server's certificate is checked**, hostname and all. An app that trusts the
  proxy has handed it that decision; skipping the check would quietly make every
  connection through it weaker than it was.
- **Only HTTP/1.1 is offered in ALPN**, so clients fall back from HTTP/2 to something
  this can read.
- **It listens on loopback only.** A proxy on every interface is an open proxy for
  everyone else on that Wi-Fi. The phone points at `127.0.0.1`, which is all the
  on-device design ever needed.
- **Bodies are forwarded whole** however little is kept. The app must behave exactly as
  it would with nothing in the way.
- **Stopping clears the phone's proxy setting.** A phone left pointing at a proxy that
  has gone has no working network and nothing on screen says why. `:0` is how Android
  clears it; an empty value does not.
- **Attribution is asked once per connection.** The port stops meaning anything the
  moment the connection closes.

## Resuming

```bash
git fetch origin
git worktree add -b proxy-ui ../droidective-android-proxy-ui origin/main
git checkout origin/proxy-android -- app/src/main/kotlin/com/droidective/android/proxy/
```

Then work through the list above from step 1. The check CI runs is:

```bash
./gradlew ktfmtFormat
./gradlew ktfmtCheck :core:test :platform:testDebugUnitTest :app:testDebugUnitTest \
  :core:lint :platform:lintDebug :app:lintDebug :app:assembleDebug :app:assembleRelease
```
