# 24 — Endpoint & Performance: Final Verification

Every claim below is backed by a command that ran on 2026-09-30.

## Endpoint Finder

```
Before:       pressing Find endpoints on a network that blocks registration showed the
              engine's retry state in red inside the results card — a URL, an IP and a
              socket error chain — after ~20 s of silence, for over two minutes.
              The search never started: it was blocked at registration.
Root cause:   (1) the engine's engineer-facing text was rendered verbatim as the result,
                  and a live retry budget was styled as a failure;
              (2) the registration prerequisite had no route out, although the app
                  already shipped provision() + carrierEngineConfig() and used them only
                  during a connect.
After:        "Find endpoints" reports one of four classified outcomes in the user's
              language with no raw engine text; a new "Get my key, then find endpoints"
              opens a carrier, buys the identity through it, closes it, then searches.
Tests:        EndpointScanReportingTest, 6 tests, all messages captured from real engine
              output. Suite: 288 run, 0 failures, 0 errors, 0 skipped.
Real network: NOT DONE — api.cloudflareclient.com does not resolve from this emulator or
              the host, so a key actually being issued cannot be demonstrated here.
Emulator:     PASS for everything demonstrable. Button rendered; tapped; EngineLog shows
              "asking Cloudflare for an identity through psiphon, on request"; the
              :psiphon process started; progress card shown; on timeout the card read
              "The carrier did not come up in time. Try again, or pick a different one
              under Carrier."; UI-tree assertion "RAW ENGINE TEXT IN UI: False".
```

## Startup

```
Before:    ~1.2 s to first frame, ~2.5 s to an interactive Home screen (logcat).
After:     unchanged. No startup work was moved or added.
Method:    logcat timestamps after force-stop. Upper bound, not a trace.
```

## Endpoint discovery

```
Before:    registration 20.2 s to first failure, ≥131 s to a conclusive answer; the
           scan itself never began.
After:     the prerequisite has a route (carrier-backed), and a blocked network is
           reported in one sentence instead of a two-minute log dump.
Method:    engine logcat timestamps; UI-tree assertion before and after.
```

## UI

- Endpoint-scan outcomes separated into four classified states; raw engine text removed
  from the UI (asserted).
- New "Get my key, then find endpoints" button with a plain-language explanation of what
  it does and that nothing stays connected.
- Progress message during key acquisition.
- An empty result is reported as a result, not as a crash.
- A hardcoded English success string replaced with a `<plurals>` resource in both locales.
- `NativeAetherBridge.versionOrNull()` hoisted into `remember`: one JNI crossing per
  process instead of one per recomposition.
- Two resource bugs found and fixed by compiling rather than trusting: duplicate
  `msg_scan_found` definition, and two unescaped apostrophes.

## Loading

- A distinct `GETTING_KEY` operation, so a key being fetched is never shown as a search
  running badly.
- Immediate acknowledgement: the button disappears and the card states the phase.
- A bounded wait (`KEY_PREP_TIMEOUT_MS = 120 s`) with a specific message on expiry,
  instead of an indefinite retry budget rendered as an error.

## Tests

```
./gradlew testStableDebugUnitTest
    288 run, 0 failures, 0 errors, 0 skipped   (36+ classes)
    +6 EndpointScanReportingTest
    +7 DiagnosticsRedactionTest (from the previous phase)

./gradlew assembleStableDebug
    BUILD SUCCESSFUL
    aapt2 compile: 38 resource files, exit 0
```

## APK

```
Path:     app/build/outputs/apk/stable/debug/app-stable-arm64-v8a-debug.apk
Variant:  stableDebug · com.whitedns.whiteaesther · 1.10.0 (1) · minSdk 26 / target 36
ABI:      arm64-v8a
Size:     38 746 537 bytes
SHA-256:  2c32df4e7f38a907ec9bb6393ed104ce46a0c23077f529d24ace96c19e3166bb
```

Also built: `armeabi-v7a` (37 118 579 B), `x86_64` (41 610 934 B),
`universal` (87 321 018 B).

## Runtime verification

### VERIFIED

- Builds, installs, launches. No `FATAL EXCEPTION`, no ANR.
- Compose UI renders; Persian RTL renders correctly (verified in the previous phase).
- Native engine loads — no `UnsatisfiedLinkError`; `libwhiteaesther_core.so` (24.8 MB)
  present; `libquiche.so`/`libboringtun` correctly absent.
- The key-getter button renders, is reachable, and fires.
- The carrier really starts: `Start proc …:psiphon for service …PsiphonService`.
- Key preparation reports a phase, then a specific failure.
- **No raw engine text reaches the UI** — asserted over the final UI tree.
- 288 unit tests pass.

### NOT VERIFIED

- **A key actually being issued.** The registration host is unreachable here.
- **A successful endpoint scan.** Nothing can be enumerated without an identity.
- **Tunnel establishment, traffic routing, DNS, kill switch, interface handover,
  revoked-consent handling, release-build logcat suppression** — none touched by this
  work, none tested.
- **Persian rendering of the new button.** The emulator ran in English this session;
  strings exist in both locales but the RTL layout of the new button was not re-checked.
- **Physical device, real restrictive network, OEM behaviour.**
- **`CARRIER_PREPARE_TIMEOUT_MS` / `KEY_PREP_TIMEOUT_MS` are reasoned, not measured.**

## Change classification

| Change | Level |
| --- | --- |
| `EndpointScanReporting` classification | **VERIFIED** — 6 tests, real captured input |
| Raw engine text removed from UI | **VERIFIED** — UI-tree assertion |
| Key-getter button → carrier starts | **VERIFIED** on emulator |
| Key getter → key obtained → scan succeeds | **NOT VERIFIED** — host unreachable here |
| `GETTING_KEY` state, progress message, timeout | **VERIFIED** on emulator |
| `<plurals>` replacing hardcoded English | **VERIFIED** — compiles, tests pass |
| `versionOrNull()` into `remember` | **CODE-REVIEW VERIFIED** — compiles; the JNI count is a static argument |
| Update check throttled | **CODE-REVIEW VERIFIED** — compiles; the fetch/zip-scan removal is a static argument |
| Persian strings present | **CODE-REVIEW VERIFIED** — present in both locales, not rendered |

## Remaining limitations

1. The success path of the whole feature is unproven on this network. That is the single
   most important thing to check on a real device.
2. No elapsed-time counter during a long operation.
3. The key getter lives only on the endpoint screen.
4. The remaining performance findings in `22-PERFORMANCE-OPTIMIZATION.md` — main-thread
   teardown, engine-log list copies, whole-app recomposition, the animating orb — are
   diagnosed and deliberately not changed here.

## Git state

No push, no fork, no PR, no remote branch. Nothing committed.
