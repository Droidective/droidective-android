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
| `core/net/proxy/Remaps.kt` | Remaps (Map Remote), and `Server`: where a request is sent, and its `Host` |
| `core/net/ws/SocketFrames.kt` | WebSocket frames read out of a copy of each direction: messages, permessage-deflate |
| `core/net/ws/SocketLog.kt` | What the sockets said, by flow, bounded like the flows |
| `core/net/h2/Http2Frames.kt` | HTTP/2 frames cut out of a copy of one direction, the preface checked |
| `core/net/h2/Hpack.kt` | HPACK: the static table, each direction's dynamic one, size updates |
| `core/net/h2/Huffman.kt` | HPACK's Huffman code, built from its 257 lengths since the code is canonical |
| `core/net/h2/Http2Reader.kt` | Both directions into one `Http2Exchange` a stream, whichever arrives first |
| `core/net/proxy/Http2Flows.kt` | A stream as a `Flow`: pseudo-headers to method, path and status; trailers kept |
| `core/net/grpc/Grpc.kt` | gRPC: a body's length-prefixed messages, `grpc-status` in words, the method's name |
| `core/zstd/Zstd.kt` | Zstandard decompression (RFC 8878): frames, blocks, literals, sequences |
| `core/zstd/ZstdTables.kt` | zstd's bitstreams read backwards, FSE tables, Huffman tables from weights |
| `core/net/grpc/ProtoWire.kt` | The protobuf wire format, nothing guessed: fields, varints, packed runs |
| `core/net/grpc/ProtoRaw.kt` | Protobuf read without its schema, printed as `protoc --decode_raw` prints it |
| `core/net/grpc/ProtoSchema.kt` | A descriptor set read: messages, enums, methods; a message printed as `protoc --decode` would, `Any` opened |

`core/net` and `core/zstd` carry **400 tests**, nearly all of them this work.

The Android side is in `app`:

| File | What it is |
| --- | --- |
| `proxy/TrafficProxy.kt` | The one proxy: state, store, certificate, rules, pointing the phone |
| `proxy/ProxyService.kt` | The `specialUse` foreground service, and `ProxyNotices` |
| `proxy/TrafficExport.kt` | Saving flows as HAR to Downloads, and the share sheet |
| `proxy/ProtoFolder.kt` | The `protos` folder: descriptor sets pushed there, read as one schema |
| `ui/tools/NetworkTrafficTool.kt` | The screen: proxy, certificate, rules, the list |
| `ui/tools/NetworkTrafficRules.kt` | The rules editor |
| `ui/tools/NetworkTrafficDetail.kt` | One flow: overview, headers, body, cURL, rule shortcuts |
| `ui/tools/NetworkTrafficCompose.kt` | Edit and send: a captured request changed and sent again |
| `ui/tools/NetworkTrafficMocks.kt` | The mock editor and its rows |
| `ui/tools/NetworkTrafficHolds.kt` | What is held, its editor, and the breakpoint editor |
| `ui/tools/NetworkTrafficRemaps.kt` | The remap editor and its rows |
| `ui/tools/NetworkTrafficSockets.kt` | A socket's messages, live, in its detail |
| `proxy/Holds.kt` | The breakpoints, and the `Breaker` that waits for a person's decision |
| `proxy/MockFolder.kt` | The mocks folder, where a mock that answers from a file reads it |
| `proxy/KeptList.kt` | The mocks, remaps and breakpoints as kept: read once, written on each change |
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
| [#103](https://github.com/Droidective/droidective-android/pull/103) | `KeptList`: the mocks and breakpoints kept one way |
| [#104](https://github.com/Droidective/droidective-android/pull/104) | Map remote: a URL's requests sent to another server, repeats included; the proxy never dials itself |

## What is left, worth doing

Roughly in the order people reach for them after the list itself:

- **The system store, with root.** A user certificate is trusted only by apps that opt in.
  With root it can go in the system store, and every app that doesn't pin trusts it. On
  Android 14 and later the store lives in the Conscrypt APEX, so it means a tmpfs mounted
  over `/apex/com.android.conscrypt/cacerts` in every app's mount namespace (HTTP Toolkit's
  approach), redone after each reboot. `CertificateTrust.System` already reads the result.
- **Schemas from the server.** A gRPC server with reflection on would give its schema to
  whoever asks, so no file would need pushing; asking means an HTTP/2 client of its own.
- **Sending a gRPC call again.** A repeat goes out as HTTP/1.1, so gRPC is refused it;
  repeating one means an HTTP/2 client of its own.

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
- **HTTP/1.1 comes first in ALPN, and the server's order is the one TLS honours**, so a
  client that can speak either falls back to something this reads. HTTP/2 is offered
  second, for the client that speaks nothing else: offered only HTTP/1.1, gRPC fails the
  handshake on the JVM and, on Android, finishes it with no protocol agreed and hangs up,
  with nothing on record either way. Such a connection is relayed to a server asked for
  HTTP/2 alone and **read from the side**, as a WebSocket is: a copy of each direction is
  cut into frames, each direction's header table is followed block by block, and each
  stream becomes a flow. Nothing here speaks HTTP/2 to either end, so the two settle flow
  control and settings between themselves, and mocks, remaps and breakpoints don't reach
  these flows. If the frames stop making sense, the rest of the connection is carried
  unread and says so in one row; one missed header block would misread every block after
  it, so there is no skipping ahead. The answer can be read before its request, since
  each direction is read on its own thread after its bytes have gone on.
- **It listens on `127.0.0.1`, by name.** A proxy on every interface is an open proxy for
  everyone else on that Wi-Fi. And not `InetAddress.getLoopbackAddress()`: on Android that
  is `::1`, and a socket there never hears a connection to `127.0.0.1` (#79). `core`'s tests
  prefer IPv6 for that reason, so the desktop JVM can't hide it again.
- **Bodies are forwarded whole** however little is kept. The app must behave exactly as it
  would with nothing in the way.
- **An answer is passed on as it arrives**: flushed after every read, and the head before the
  body. Server-sent events and long polls wait on it; a buffer left to fill held them back.
- **An answer cut short is still on record**: with what the server sent and why it ended —
  the app hanging up, or the server stopping part-way — never left pending.
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
- **A body is decoded up to 32 MB** (`BodyView.MAX_DECODED`). A few kept megabytes of gzip,
  brotli or zstd can unpack to gigabytes; past the bound the rest is left undone.
- **zstd is decoded by code of its own** (`core/zstd`), not a library: the pure-Java decoders
  lean on `sun.misc.Unsafe`, which Android only partly has, and the native one needs a library
  per ABI. It is held to the reference — every fixture was written by libzstd, and
  `fixtures.py` writes them again — and a frame that needs a dictionary is refused by name.
- **A held exchange waits five minutes for a person**, then goes on as it was, so a phone left
  alone never ends up with an app waiting forever. Stopping the proxy lets everything go.
- **A request held for a person goes out on a new connection to the server.** The one it
  was sharing may have been closed while it waited, and the edit would fail as "the server
  answered nothing".
- **An answer that will be held is asked for only in codings this can undo** — gzip,
  deflate, brotli, zstd — so it can be read and changed. A changed answer is written out plain, its length restated.
- **A held request keeps its host.** The app is talking to that host, over TLS made for it;
  sending its request elsewhere is map remote's job. Edit and send, from the list, goes
  anywhere.
- **A remap changes the server, never the path.** The request is recorded as the app asked it,
  with `sentTo` saying where it went; only what goes on the wire has the new server's `Host`.
  A mock answers before a remap sends anywhere, and Send again follows remaps, so a repeat
  goes where the app's request went.
- **A socket is read from a copy.** The bytes of an upgraded connection cross as they always
  did; a `FrameReader` per direction reads what crossed, after it has gone on, and one that
  can't make sense of it stops reading rather than touch the connection.
- **A hang-up at either end is a hang-up at the other.** A tunnel or a socket has no timeout,
  so a server never told the app had gone held a thread and two connections for ever.
- **A mock's file lives in the app's own folder on shared storage**
  (`Android/data/com.droidective.android/files/mocks`): the app reads it with no permission,
  `adb push` writes it, and it is read again for every answer, so pushing a fixture again
  changes the answer. A name that would leave the folder — `..`, a leading `/`, a link out of
  it — is refused, and a file over 8 MB answers a 502 that says so.
- **A gRPC schema is a descriptor set in a folder beside it** (`files/protos`), compiled
  as grpcurl's `-protoset` is: `protoc --include_imports --descriptor_set_out=app.protoset
  *.proto`, then `adb push app.protoset` there. A descriptor set rather than `.proto` text,
  because protoc has already resolved the imports and the names, and reading one is reading
  protobuf. Every file there is one schema together, read again only when the folder
  changes; a file that isn't one is passed over, and a type it lacks is read without.
- **The proxy never dials itself.** An app asking for `127.0.0.1:8888`, or a remap there,
  would have it ask itself again for ever; it fails as a 502 instead.

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
