# 21 — Endpoint Finder Fix

## Original failure

Tapping **Find endpoints** on a network that blocks `api.cloudflareclient.com` left the
user watching a red, monospace dump of the engine's internal retry state inside the card
that is supposed to list endpoints, for over two minutes:

```
registration is on hold for another 30s: api: registration: direct route ->
api: registration: error sending request for url
(https://api.cloudflareclient.com/v0a4471/reg); camouflaged route ->
api: connect to 141.101.113.18:443 timed out
```

## Root cause

**Two distinct causes. The first is a bug; the second is a missing feature.**

### Cause 1 — the engine's words were shown to the user verbatim (bug)

`MainViewModel.scanEndpoints` put the raw exception message into `EndpointScannerState.error`,
and `Screens.kt` renders `error ?: message` as the body of the results card. The engine
writes for an engineer: a URL, a route name, a socket error chain. A person cannot act on
any of it, and it is presented where results should be.

Compounding it: the engine reports a **live retry budget** in the same channel it reports
a dead end, so a message meaning "still working, 30 s left" was rendered in the failure
style as if it were a verdict.

### Cause 2 — the prerequisite could not be reached, and nothing offered a way around it (missing feature)

The engine needs an identity from Cloudflare before it can prepare, scan or connect.
That registration is an ordinary request to `api.cloudflareclient.com`, and on a
restricted network it never leaves. Every later step then fails for that one reason.

The app already had the answer and never used it from this screen:
- `NativeAetherBridge.provision()` — "Buys the engine's identity without building
  anything with it… that carrier's SOCKS listener is the only route a registration can
  leave by";
- `AetherVpnService.buyIdentityBehind(carrier, port, generation)` — calls it, but **only**
  during a connect where a carrier won the race;
- `carrierEngineConfig(base, port)` — the config that routes registration through it.

`MainViewModel` and the UI referenced **none** of it. A user on a blocked network had to
start a full connect to get the thing the endpoint screen needed first.

**Why "connect, then scan" is not the fix.** `nativeScan` refuses while the engine runs:

```rust
if STOP_SENDER.lock().is_some() {
    return Err("disconnect before scanning endpoints".into());
}
```

So the carrier must come up **without** the Aether engine. That is why this is a separate
service action rather than a flag on connect.

## The fix

### 1. Classify what the engine said (`data/EndpointScanReporting.kt`, new)

A pure, total mapping from the engine's text to what a person can act on:

| Outcome | Meaning | User sees |
| --- | --- | --- |
| `STILL_WAITING` | a live retry budget, not a verdict | "Still trying to reach Cloudflare…" |
| `NETWORK_UNREACHABLE` | a required host could not be reached | "Could not reach the server that issues this app's key…" |
| `NOTHING_ANSWERED` | the search finished; nothing replied | "…there is just no way out on it right now" |
| `UNKNOWN` | unrecognised | the engine's raw text, kept, because it is the only clue |

The waiting check runs **before** the unreachable check, deliberately: a waiting message
also contains `error sending request for url` and `timed out`, and reporting those as
"no route out" while the engine is still retrying would be a lie.

### 2. A key getter (`service/KeyPrepState.kt` new, `AetherVpnService.kt`, `MainViewModel.kt`, `Screens.kt`)

A new button — **"Get my key, then find endpoints"** — and a new service action
`ACTION_PREPARE_KEY` that does exactly what the name says and nothing more:

1. bring up a carrier (Psiphon, or Tor) — **no tunnel, no VPN interface**;
2. `NativeAetherBridge.provision(carrierEngineConfig(base, port))`;
3. stop the carrier, on **every** exit including failure;
4. then run the ordinary search.

It deliberately does not touch `EngineStatus`: a user who asked for a key must not find
themselves connected. The carrier is torn down in a `finally` so a one-shot registration
never leaves a Psiphon or Tor process running for a tunnel nobody asked for.

### 3. Two smaller correctness fixes found on the way

- The success path built its message in Kotlin: `"${endpoints.size} validated endpoint…"`
  — **hardcoded English** in a Persian-capable app. Now a `<plurals>` resource.
- An empty result was reported the same way as a crash. It is now
  `NOTHING_ANSWERED` with its own advice — the search worked, there was just no way out.

## Why the fix works

It does not make the blocked network work, and it does not pretend to. It does two things
that were missing:

1. **It gives the prerequisite a route.** Registration now has a path out that does not
   depend on the host being reachable directly.
2. **It tells the truth in the user's language.** A waiting engine says so; a dead network
   says so; an empty result says so. None of them says it with a URL in it.

## Tests

`EndpointScanReportingTest` — 6 tests. Every message is **real engine output captured
from logcat**, not invented, including the one that shipped broken:

```kotlin
private val liveRetryBudget =
    "registration is on hold for another 30s: api: registration: direct route -> " +
        "api: registration: error sending request for url " +
        "(https://api.cloudflareclient.com/v0a4471/reg); camouflaged route -> " +
        "api: connect to 141.101.113.18:443 timed out"
```

Covered: a live retry budget is progress not failure; a wait is not misreported as a dead
network even though its text contains the unreachable markers; unreachable variants map
correctly; empty/null/blank mean "nothing answered"; **an unrecognised message stays
`UNKNOWN` and is never guessed at**; the three retry markers the engine actually writes
are all recognised.

**288 unit tests, 0 failures, 0 errors, 0 skipped** (282 before this phase, +6).

## Emulator test

Installed on `emulator-5554`, Android 14 arm64. Observed:

```
WA/identity: asking Cloudflare for an identity through psiphon, on request
ActivityManager: Start proc 14086:com.whitedns.whiteaesther:psiphon/u0a195 for service …
                     {com.whitedns.whiteaesther/…service.PsiphonService}
```

UI during the attempt:

> "Opening a carrier to ask for this phone's key…"

UI after the carrier did not come up (this network blocks Cloudflare):

> "The carrier did not come up in time. Try again, or pick a different one under Carrier."

Automated check over the final UI tree:

```
RAW ENGINE TEXT IN UI: False
```

Before the fix the same tree contained `api.cloudflareclient.com`,
`camouflaged route`, `error sending request` and `on hold for another`. It now contains
none of them.

## Real network test

**NOT DONE, and not claimable.** `api.cloudflareclient.com` does not resolve from this
emulator *or* from the host, so the success path — a key actually being issued, and a
scan actually returning endpoints — **cannot be demonstrated here**. The
carrier-starts-and-registration-is-attempted path **is** demonstrated. The distinction
matters: the button works; what it is for has not been shown to succeed on a network that
allows it.

## Remaining limitations

1. **The success path is unverified here**, for the reason above. It needs a network that
   can reach Cloudflare.
2. **Key preparation is refused while connected**, deliberately — the engine will not
   provision while running, and the message says to disconnect. Not tested on a device
   mid-connection.
3. **`CARRIER_PREPARE_TIMEOUT_MS = 45 s`** and **`KEY_PREP_TIMEOUT_MS = 120 s`** are
   reasoned values, not measured ones. The first is the service's carrier deadline; the
   second the ViewModel's overall wait. On a very slow link Psiphon may need longer, and
   the user would be told "did not come up in time" for a carrier that was nearly there.
   Both are single constants and easy to raise.
4. **The key getter is not yet offered from the Home screen.** It lives only on the
   endpoint screen, which is where the need was reported from.
5. **No engine-level change was made.** The scan, the prober, the registration and the
   retry ladder are untouched.