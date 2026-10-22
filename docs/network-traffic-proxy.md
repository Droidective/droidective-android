# Network Traffic — the debugging proxy

An HTTPS debugging proxy that runs **on the phone**: the same job Charles, Proxyman,
Fiddler and mitmproxy do, with a certificate authority the phone generates itself.
For developers watching their own app, and for anyone who needs to see what a device
is actually sending.

This document is the handover. It says what is built, what is left, and the handful
of decisions that are easy to get wrong a second time.

## Why it runs on the phone

Every comparable tool runs on a computer and makes you join the same network, find
its IP, set a Wi-Fi proxy by hand and keep the two on the same subnet. None of that
is necessary when the proxy is the phone. The phone points at `127.0.0.1`, there is
no network setup, and it keeps working on mobile data, on a train, on a device that
is not yours to configure.

## Where the code is

Everything except the Android plumbing is in `core`, which is a plain JVM module —
so the whole proxy is unit-tested against loopback sockets with no emulator. `core`'s
tests run with `java.net.preferIPv6Addresses=true`, as Android does (see the decisions).

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
| `core/net/proxy/ProxyRules.kt` | Which apps and hosts are read, and `Passing`: why one wasn't |
| `core/net/proxy/Refusals.kt` | The (app, host) pairs that refused the certificate, passed through after |
| `core/net/proxy/TrafficRules.kt` | The rules as kept: apps by package, host patterns, the step-aside switch |
| `core/net/proxy/Dial.kt` | Connecting to a host's first address that answers |
| `core/net/proxy/TlsFailure.kt` | A TLS failure in words: the alert, not BoringSSL's internals |
| `core/net/proxy/Origin.kt` | Where a request is going |
| `core/net/proxy/ProxyTls.kt` | The two TLS ends |
| `core/net/proxy/ConnectionOwners.kt` | `/proc/net/tcp` → the uid behind a connection |
| `core/net/proxy/ProxySetting.kt` | `http_proxy`: what to write, whether it points here, what to put back |
| `core/net/proxy/FlowStore.kt` | What has been seen, bounded by count and by bytes |
| `core/net/proxy/FlowFilter.kt` | The search box, and `FlowSummary` for the chips |
| `core/net/proxy/FlowLabels.kt` | How a row reads: outcome, sizes, durations, GraphQL operation, why passed |
| `core/net/proxy/BodyView.kt` | Decoding and shaping a captured body |
| `core/net/proxy/Curl.kt` | A flow written back out as a command |
| `core/net/proxy/Har.kt` | Flows as a HAR 1.2 file, written an entry at a time |
| `core/net/proxy/Repeater.kt` | Sending a captured request again, straight to its server |
| `core/net/proxy/RequestDraft.kt` | A request as typed, checked before it is sent |
| `core/net/proxy/UrlPattern.kt` | The `*` URL patterns mocks and breakpoints are named by |
| `core/net/proxy/Mocks.kt` | Mocks, their draft, and `traffic-mocks.json` |
| `core/net/proxy/Throttle.kt` | Network speed: Chrome's presets, and `Paced` streams |
| `core/net/proxy/Breakpoints.kt` | Breakpoints, what is held, the verdicts, and the `Breaker` that decides |

`core/net` carries **258 tests**, nearly all of them this work.

The Android side is in `app`:

| File | What it is |
| --- | --- |
| `proxy/TrafficProxy.kt` | The one proxy: state, store, certificate, rules, pointing the phone |
| `proxy/ProxyService.kt` | The `specialUse` foreground service, and `ProxyNotices` |
| `proxy/TrafficExport.kt` | Saving flows as HAR to Downloads, and the share sheet |
| `ui/tools/NetworkTrafficTool.kt` | The screen: proxy, certificate, rules, the list |
| `ui/tools/NetworkTrafficRules.kt` | The rules editor |
| `ui/tools/NetworkTrafficDetail.kt` | One flow: overview, headers, body, cURL, rule shortcuts |
| `ui/tools/NetworkTrafficCompose.kt` | Edit and send: a captured request changed and sent again |
| `ui/tools/NetworkTrafficMocks.kt` | The mock editor and its rows |
| `ui/tools/NetworkTrafficHolds.kt` | What is held, its editor, and the breakpoint editor |
| `proxy/Holds.kt` | The breakpoints, and the `Breaker` that waits for a person's decision |
| `surface/AppPlatformToggles.kt` | The `traffic-proxy` toggle; `TrafficProxyTile` is in `TileSlots.kt` |

## What is merged

| PR | What it added |
| --- | --- |
| [#69](https://github.com/Droidective/droidective-android/pull/69)–[#74](https://github.com/Droidective/droidective-android/pull/74), [#76](https://github.com/Droidective/droidective-android/pull/76) | The engine: certificates, HTTP, the proxy, attribution, the store, bodies, cURL |
| [#78](https://github.com/Droidective/droidective-android/pull/78) | `ProxyService` and `TrafficProxy`; pointing read back from the setting; the certificate saved to Downloads |
| [#79](https://github.com/Droidective/droidective-android/pull/79) | Listening on `127.0.0.1` — Android's loopback address is `::1` |
| [#80](https://github.com/Droidective/droidective-android/pull/80) | The Network Traffic tool |
| [#81](https://github.com/Droidective/droidective-android/pull/81) | Attribution in the background, and for every connection of a burst |
| [#82](https://github.com/Droidective/droidective-android/pull/82) | A phone left pointed at a stopped proxy gets its setting back |
| [#83](https://github.com/Droidective/droidective-android/pull/83) | Every address of a host tried; TLS failures in words; requests on record before the dial |
| [#84](https://github.com/Droidective/droidective-android/pull/84) | Reading only chosen apps; stepping aside for apps that refuse |
| [#85](https://github.com/Droidective/droidective-android/pull/85) | The rules editor, kept in `traffic-rules.json` |
| [#86](https://github.com/Droidective/droidective-android/pull/86) | HAR export |
| [#87](https://github.com/Droidective/droidective-android/pull/87) | The `traffic-proxy` toggle and its Quick Settings tile |
| [#95](https://github.com/Droidective/droidective-android/pull/95) | Flow ids unique across runs, so a restart can't overwrite what is on screen |
| [#96](https://github.com/Droidective/droidective-android/pull/96), [#97](https://github.com/Droidective/droidective-android/pull/97) | Send again, and edit and send — Charles's *Repeat* and *Compose* |
| [#98](https://github.com/Droidective/droidective-android/pull/98) | Mocks: an answer given here instead of the server's, kept in `traffic-mocks.json` |
| [#99](https://github.com/Droidective/droidective-android/pull/99) | Network speed: Chrome's throttling presets, played through the proxy |
| [#100](https://github.com/Droidective/droidective-android/pull/100) | Breakpoints in the engine: a request or its answer held whole and handed over |
| [#101](https://github.com/Droidective/droidective-android/pull/101) | Breakpoints on the phone: the editor, what is held, and a notice that reaches you in the app being debugged |

## What is left, worth doing

Roughly in the order people reach for them after the list itself:

- **Map remote.** Send a URL to another host — a staging server, a laptop on the same
  network. A mock already answers from text; answering from a file is the other half of
  Charles's *Map Local*.
- **The system store, with root.** A user certificate is trusted only by apps that opt in.
  With root it can go in the system store, and every app that doesn't pin trusts it. On
  Android 14 and later the store lives in the Conscrypt APEX, so it means a tmpfs mounted
  over `/apex/com.android.conscrypt/cacerts` in every app's mount namespace (HTTP Toolkit's
  approach), redone after each reboot. `CertificateTrust.System` already reads the result.
- **WebSocket frames.** An upgrade is carried as bytes (`tunnelled`); reading the frames
  would show what a socket.io or GraphQL subscription says.
- **brotli and zstd.** Browsers ask for them, and a body in either is shown and exported as
  it arrived: it needs a decoder the app doesn't carry.
- **HTTP/2.** Only HTTP/1.1 is offered in ALPN, so clients fall back; gRPC can't.
- **An app that hangs up part-way.** A flow whose app goes away while its answer is being
  relayed stays pending in the list (`serve` treats a vanished client as ordinary). A held
  answer is recorded as ended, since an app giving up while it waits is the usual case there;
  the rest would need the exchange in progress known where the connection's failure is caught.

## What stops this working, and what to tell people

**Android 7 and later do not trust a user-installed certificate by default.** The screen
says so, and offers the fix for your own app:

- **Your own debug build** — a network security config, which the certificate card copies:
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
- **Chrome** trusts user certificates, which makes it the quickest check that it all works.
- **Any app, on a rooted device** — the certificate goes in the system store (left to do).
- **An app that pins its certificates** will not work either way. That is the app working
  correctly. Pass its host through, or leave step-aside on. **Defeating pinning is
  deliberately out of scope** — Charles and Proxyman do not do it either.

**Android 11 and later refuse a CA certificate an app hands over**
(`KeyChain.createInstallIntent`). The certificate is saved to Downloads and installed from
Settings (search "CA certificate"); Android asks for a screen lock first.

**Some apps go around a proxy they can't trust.** Android's `HttpURLConnection` (OkHttp
inside) always tries a direct connection after the proxy route fails, so such an app is
recorded refusing once and then works without the proxy. Droidective's own update check
does exactly this.

## Decisions worth not relitigating

- **No BouncyCastle.** `Der.kt` and `X509.kt` write the certificate by hand; the JDK
  generates keys and signs. Saves a ~5 MB dependency and keeps it all testable in `core`.
- **One leaf key for every host.** Standing in for a new host costs a signature, not an
  RSA key generation — milliseconds instead of seconds on a phone.
- **The real server's certificate is checked**, hostname and all. An app that trusts the
  proxy has handed it that decision.
- **Only HTTP/1.1 is offered in ALPN**, so clients fall back from HTTP/2 to something this
  can read.
- **It listens on `127.0.0.1`, by name.** A proxy on every interface is an open proxy for
  everyone else on that Wi-Fi. And not `InetAddress.getLoopbackAddress()`: on Android that
  is `::1`, and a socket there never hears a connection to `127.0.0.1` (#79). `core`'s tests
  prefer IPv6 for that reason, so the desktop JVM can't hide it again.
- **Bodies are forwarded whole** however little is kept. The app must behave exactly as it
  would with nothing in the way.
- **Stopping puts the setting back the way it was**: the proxy somebody had before, or `:0`
  (an empty value does not clear it), and nothing at all when somebody changed it since.
  Whether it points here is read back from the setting, never remembered, and the app's
  next start puts back a setting left pointing at a proxy that isn't running (#82).
- **Attribution never makes a request wait**, except when the answer decides what happens
  to the connection: some apps chosen, or a host somebody refused (#81, #84). It is asked
  the moment a connection is accepted, while the app is still on the other end.
- **Only the chosen apps are read; the rest pass through untouched.** An app that can't be
  told apart, while apps are chosen, passes through: reading it could break an app nobody
  asked about. Chosen apps of which none is installed read nothing (`NOBODY`).
- **Step aside is on by default**, keyed by app and host, kept for one run, and cleared when
  the certificate's trust changes.
- **HAR is written decoded**, an entry at a time, and leaves out connections never read.
- **A held exchange waits five minutes for a person**, then goes on as it was, so a phone left
  alone never ends up with an app waiting forever. Stopping the proxy lets everything go.
- **A request held for a person goes out on a new connection to the server.** The one it
  was sharing may have been closed while it waited, and the edit would fail as "the server
  answered nothing".
- **An answer that will be held is asked for in gzip or deflate**, never brotli or zstd, so
  it can be read and changed. A changed answer is written out plain, its length restated.
- **A held request keeps its host.** The app is talking to that host, over TLS made for it;
  sending its request elsewhere is map remote's job. Edit and send, from the list, goes
  anywhere.

## Resuming

```bash
git fetch origin
git worktree add -b <branch> ../droidective-android-<branch> origin/main
```

The check CI runs is:

```bash
./gradlew ktfmtFormat
./gradlew ktfmtCheck :core:test :platform:testDebugUnitTest :app:testDebugUnitTest \
  :core:lint :platform:lintDebug :app:lintDebug :app:assembleDebug :app:assembleRelease
```
