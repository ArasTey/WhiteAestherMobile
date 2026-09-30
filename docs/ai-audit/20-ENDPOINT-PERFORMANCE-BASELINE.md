# 20 — Endpoint & Performance Baseline

Measured 2026-09-30 on `emulator-5554` (Android 14 / API 34, arm64-v8a), against the
APK built from `b55320a` + the audit's fixes.

**Measurement caveat:** there is no physical device and no restrictive network. Startup
and screen timings come from logcat timestamps; everything about hostile-network
behaviour is reasoned from the engine's own log output, not measured. No number here is
invented — each is traceable to a command.

---

## 1. Endpoint Finder — the chain, traced end to end

```
Screens.kt  EndpointScreen
  └─ onScanEndpoints(settings)                              MainActivity:326
      └─ MainViewModel.scanEndpoints(settings)             MainViewModel:830
          ├─ endpointScannerState ← SCANNING               (immediate, button → "Stop")
          ├─ scanFirstFraming()                            MainViewModel:847
          └─ withContext(Dispatchers.IO) {
                NativeAetherBridge.scan(json)              NativeAetherBridge:116
                   └─ nativeScan(configJson)               lib.rs:737
                      └─ aether::scan_embedded(...)        lib.rs:765
                          └─ identity/registration first    aether/src/identity.rs
```

**Two blocking conditions, in the order they bite:**

| Stage | Guard | Message |
| --- | --- | --- |
| `nativeScan` | `STOP_SENDER.is_some()` | `disconnect before scanning endpoints` |
| `nativeScan` | `PREPARE_RUNNING` | `route preparation is already running` |
| `nativeScan` | `SCAN_RUNNING.swap(true)` | `endpoint scan is already running` |

This ordering is load-bearing for the fix: **you cannot scan while connected.** So
"connect first, then scan" is not a possible design — see `21-ENDPOINT-FINDER-FIX.md`.

## 2. What actually happens, measured

Fresh install, no identity. Tap **Find endpoints**:

| Time | Event | Source |
| --- | --- | --- |
| 0 ms | UI sets `SCANNING`, button reads "Stop" | ViewModel |
| **+305 ms** | `aether::identity: migrated 0 device(s) into the identity store` | engine logcat |
| +306 ms | `no usable masque identity for the masque slot; provisioning a dedicated masque account` | engine logcat |
| **+20 223 ms** | `registration: the direct route did not complete a request (error sending request for url (https://api.cloudflareclient.com/v0a4471/reg))` | engine logcat |
| +20 223 ms | `registration failed over the direct route` | engine logcat |
| +20 224 ms | `registration retrying over a camouflaged route: random cloudflare edge address, no dns lookup, split client hello, alternate tls fingerprints` | engine logcat |
| **+131 110 ms** | `not registering: 30s of the wait from the last attempt is still to run (… direct route -> … error sending request …; camouflaged route -> api: connect to 141.101.113.18:443 timed out)` | engine logcat |

**Findings:**

- **20.2 s of silence.** No user-visible progress between the tap and the first failure.
- **≥131 s to a conclusive answer**, and the state during it is a *retry budget*, not a
  result.
- The search itself **never started.** It never reached candidate enumeration.

## 3. Root cause of the failure — and it is not the app

The registration host is unreachable **from this environment entirely**:

```
host:      curl https://api.cloudflareclient.com/     → code=000, timeout after 30 s
emulator:  ping api.cloudflareclient.com            → ping: unknown host
emulator:  ping www.google.com                       → resolves, 216.239.38.120
emulator:  HTTPS https://www.google.com/generate_204 → 204 in ~900 ms
```

So the emulator has working general internet but **cannot resolve
`api.cloudflareclient.com`**. The engine cannot register; without a registration it
cannot probe; without probing there are no endpoints. The scan is blocked at its
prerequisite, not in its own logic.

**That is environmental, and it is stated as such. It is also the normal condition on a
restricted network — which is the case the product exists for, and which is why the fix
below is about the *prerequisite*, not about the scan.**

## 4. What the user was shown (before the fix)

Screenshot of the endpoint screen while the above was running:

```
ENDPOINTS THAT WORKED
registration is on hold for another 30s: api: registration: direct route ->
api: registration: error sending request for url
(https://api.cloudflareclient.com/v0a4471/reg); camouflaged route ->
api: connect to 141.101.113.18:443 timed out
```

Rendered in red, in the monospace `Data` style, **inside the card that is supposed to
list endpoints**. A URL, an IP, a route name and a reqwest error chain, presented as the
result of a search.

## 5. Startup and screen timing

| Measurement | Value | Method |
| --- | --- | --- |
| Process start → first frame | ~1.2 s | logcat `ActivityManager: Start proc` → first frame |
| Home screen interactive | ~2.5 s | logcat timestamps, cold start after force-stop |
| Routes → Endpoint navigation | < 400 ms | uiautomator dump + tap timestamps |
| Endpoint screen first dump | 1.1 s after tap | `uiautomator dump` round trip (upper bound, not the render cost) |

## 6. Main-thread work found (static + confirmed by audit)

| Finding | Site | Cost |
| --- | --- | --- |
| JNI call in the root composable body | `MainActivity.kt:321` `NativeAetherBridge.versionOrNull()` | 1 JNI crossing + string alloc **per emission**, and the root re-executes on every log line and every 1 Hz traffic tick |
| Engine log copies a 400-element list per line | `EngineLog.kt:53` `(it + entry).takeLast(CAPACITY)` | 2 list copies **per log line**; lines are drained every 2 s |
| Whole app recomposes on every emission | `MainActivity.kt:297-375`, 16 `collectAsStateWithLifecycle` in one scope | every tab re-executes for a signal aimed at one screen |
| Update check on every connect | `MainViewModel.kt` on `EngineStage.CONNECTED` | 1 HTTPS GET of ≤1 MiB **plus** opening the installed ~40 MB APK as a `ZipFile` and regex-walking every entry — per connect *and* per reconnect |
| `chain.stop()` / `NativeAetherBridge.stop()` on the main thread | `AetherVpnService.kt` 7 sites | blocking JNI into a Go runtime on `Dispatchers.Main.immediate` at every teardown |
| Diagnostics report rebuilt per log line | `Screens.kt:2622` | `buildReport` over 120 entries, each running 3 regexes including the lookbehind/lookahead one |
| `ConnectOrb` animates forever | `ConnectOrb.kt:139` | Canvas invalidated at 60 fps even at `IDLE`, ~1200 short-lived objects/s in `WORKING` |

## 7. Network behaviour

| Call | Timeout | Bound? |
| --- | --- | --- |
| GitHub latest release (`AppUpdateManager`) | 15 s connect / 15 s read | yes |
| Cloudflare trace (`AddressReporter`) | 6 s / 6 s | yes |
| Tor bridge service (`MoatClient`) | 30 s / 30 s | yes |
| Carried-socket read (`CarriedSocket.kt:107`) | `soTimeout` per read, **no overall deadline** | **no** |
| Reconnects | `MAX_RECONNECT_ATTEMPTS = 8`, backoff capped 60 s | yes |
| Automatic search | 2 passes under a 15 min ceiling | yes |

No unbounded retry loop exists. Retries are bounded throughout.

## 8. Disk / DataStore

| Access | Cost |
| --- | --- |
| Settings read | `stateIn(WhileSubscribed(5s))` — one read per subscription, not per recomposition |
| Psiphon regions | re-read on every stage change **and** on every `FileObserver` event, with no in-flight guard |
| Identity file | written `0o600`, native side, never through the UI thread |

## 9. Composition hotspots

| Finding | Site | Cost |
| --- | --- | --- |
| `traffic` is a `HomeScreen` parameter | `WhiteAestherApp.kt:220` | whole Home body re-executes every second while connected |
| Non-lazy `Column` over the whole Psiphon region list | `Screens.kt:1144` | every region composed, each building a `java.util.Locale` |
| `EndpointAddress.normalize` 2–4× per recomposition | `Screens.kt:1409`, `:786` | `split`/`map`/`joinToString`, and `InetAddress.getByName` on the IPv6 branch |
| `Modifier.focusRequester` rebuilt per root execution | `WhiteAestherApp.kt` 8 sites | fresh element objects each time |

## 10. Already correct — recorded so it is not "optimised"

- **The two clock loops are correctly placed.** `Screens.kt:290-316` sit at the top of
  `HomeScreen`, above `ScreenColumn`, keyed on the connection timestamps. They restart
  only when a session starts, and the *state write* is read inside a narrow card, not the
  whole screen. The earlier audit's suspicion that they force the whole Home to recompose
  is **wrong** — confirmed by tracing the read sites (`:438` inside the column opened at
  `:358`; `:489` inside one `AetherCard`).
- `ChainScreen` and `SplitTunnelScreen` both use stable `key =` on their lists.
- `collectAsStateWithLifecycle` throughout, never `collectAsState` — collection stops
  off-screen. The *scope* is the problem, not the lifetime.
- `remember`ed `MutableInteractionSource` on every control; `AdvancedSection` never
  composes while collapsed; the tab indicator animates via `Modifier.offset` without
  recomposing the bar.
- Every blocking JNI call on the **connect** path is already in `Dispatchers.IO`.