# MASTER-ISSUES

Every finding from this audit, with a stable ID. `status` values:

| Status | Meaning |
| --- | --- |
| `CONFIRMED` | Verified by reading or by execution in this audit |
| `SUSPECTED` | Plausible, mechanism identified, but not closed without a device or a second channel |
| `NEEDS-REPRODUCTION` | Real defect indicated; the exact trigger has not been demonstrated |
| `FIXED` | Changed in this repository, with a regression test where technically possible |
| `FIXED — NOT VERIFIED` | Changed, but **never compiled or executed**. This is the status of every Kotlin fix in this audit: Google Maven is unreachable from the audit machine, so no Gradle task configures. |
| `VERIFIED` | Fixed **and** the regression demonstrated by execution |
| `DOCUMENTATION-ONLY` | Documentation is wrong; code is fine |
| `WONTFIX` | Real, but deliberately not changed — reason given |

Severity is assigned on impact, not on how interesting the bug is. A VPN app in an
adversarial network gets a stricter reading than a typical app.

Evidence citations are `path:line` against `b55320a` unless a finding says otherwise.

---

## High

### WA-001 — A refused second `nativeRun` disarms the *running* session's stop channel
- **Category:** Native / correctness
- **Severity:** High
- **Status:** `VERIFIED` — fixed in Phase 2, and the regression test was **run against
  the original code** and failed there (9 passed / 1 failed). This is the only finding in
  the register with a proven before/after.
- **Files:** `native/android-bridge/src/lib.rs:889-895`, `:947-950`
- **Evidence:**
  ```rust
  let mut sender = STOP_SENDER.lock();
  if sender.is_some() {
      return Err("engine is already running".into());   // line 890
  }
  *sender = Some(stop_tx);
  ```
  ```rust
  .unwrap_or_else(|error| {
      STOP_SENDER.lock().take();                        // line 948
      error_response(error)
  });
  ```
- **Root cause:** `STOP_SENDER` is a single process-global. The guard's own refusal returns
  from the closure *before installing a sender of its own*, so the blanket
  `unwrap_or_else` cleanup disarms the **other** session's sender.
- **Impact:** `nativeStop()` then returns `JNI_FALSE` and a live tunnel cannot be stopped
  through JNI — only by the engine ending on its own. The Kotlin-side stop path calls
  `NativeAetherBridge.stop()` and sees success.
- **Fix:** take the sender only if it is still the one this call installed.
- **Regression test:** `#[test] refusing_a_second_run_keeps_the_first_runnable` — added.
- **Upstream:** Yes. Small, isolated, obviously correct.

### WA-002 — The kill switch silently stops blocking after a failed reconnect
- **Category:** Android / VPN lifecycle
- **Severity:** High
- **Status:** `FIXED — NOT VERIFIED` (Kotlin never compiled; needs a device)
- **Files:** `service/AetherVpnService.kt` — `raiseBlackhole:2515`, `dropBlackhole:2544`,
  `establishTun:2551`, `giveUp:2975`, call sites `:444`, `:564`, `:2324`
- **Evidence:** `dropBlackhole()` runs only on `ACTION_LIFT_BLOCK`, `onDestroy`, and
  `reportConnected`. It is **not** called from `replaceSession`, and `establishTun()` is
  called at `:765`, `:1166`, `:1662` — all of which run *after* a blackhole may be up.
  The platform allows one VPN interface per owning package; a second
  `Builder.establish()` deactivates the first. `raiseBlackhole` then short-circuits on
  `if (blackhole != null) return true` and reports blocking that is not happening.
- **Impact:** The user is told "Traffic is blocked" while their traffic is flowing unblocked
  over the ordinary route — the exact leak the feature exists to prevent. The worst failure
  mode this feature has.
- **Fix:** `dropBlackhole()` immediately before `builder.establish()` in `establishTun()`,
  so the held descriptor always tracks the interface that actually owns the tunnel.
- **Verification:** static only — requires a device with VPN consent to demonstrate.

### WA-003 — The Psiphon binary is resolved from a mutable branch with no dependency verification
- **Category:** Security / supply chain
- **Severity:** High
- **Status:** `CONFIRMED` (not changed — maintainer decision)
- **Files:** `settings.gradle.kts:21-25`; no `gradle/verification-metadata.xml` exists
- **Evidence:**
  ```kotlin
  maven {
      name = "psiphon"
      url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
      content { includeGroup("ca.psiphon") }
  }
  ```
- **Impact:** `ca.psiphon:psiphontunnel:2.0.41` is a ~44 MB prebuilt Go shared library that
  becomes the Psiphon tunnel core. `master` is a mutable ref and there is no checksum pin,
  so a compromised account or force-push substitutes native code into every build and every
  device that takes an update. Group scoping correctly limits blast radius to this one
  coordinate; the serving ref is the gap.
- **Fix:** `gradle/verification-metadata.xml` via `--write-verification-metadata sha256`.
- **Not changed here:** the metadata must be generated against the maintainer's own
  resolution, and regenerating it is a supply-chain decision, not a cleanup.

### WA-004 — Every GitHub Action is pinned to a floating tag
- **Category:** Security / CI supply chain
- **Severity:** High
- **Status:** `CONFIRMED` (proposed as an upstream PR — see `UPSTREAM-PR-PLAN.md`)
- **Files:** `.github/workflows/ci.yml`, `release.yml`, `fdroid-repo.yml`,
  `.github/actions/android-toolchain/action.yml`
- **Evidence:** 11 `uses:` references (`actions/checkout@v7`, `setup-go@v7`,
  `setup-node@v7`, `setup-java@v5`, `gradle/actions/setup-gradle@v6`,
  `android-actions/setup-android@v4`, `upload-artifact@v7`, `configure-pages@v6`,
  `upload-pages-artifact@v5`, `deploy-pages@v5`); zero 40-character SHA pins. These run
  alongside `contents: write` (release) and `pages: write` + `id-token: write` (F-Droid).
- **Fix:** pin each to a full SHA with the version as a trailing comment. Dependabot's
  `github-actions` ecosystem already tracks them.
- **Not changed here:** a mass SHA rewrite is opaque to a reviewer and needs the
  maintainer's own approval of the digests.

### WA-005 — The exit-chain Go module graph is re-resolved on every build
- **Category:** Build / reproducibility
- **Severity:** High
- **Status:** `CONFIRMED` (not changed)
- **Files:** `native/chain/setup.ps1:85-94`, `native/tor/build.ps1:57`, `:105`
- **Evidence:** `$env:GOFLAGS = '-mod=mod'; go mod tidy` — the committed `go.sum` is
  deliberately discarded by the script's own comment.
- **Impact:** `chain/setup.ps1` pins the two source repositories by full commit SHA, but
  the several hundred transitive modules are resolved from `proxy.golang.org` at setup
  time. Two runs a week apart can produce different binaries from identical pins.
- **Fix:** commit the tidied `go.sum`, build with `-mod=readonly`, and keep `tidy` as a
  separate, deliberate maintenance step.
- **Why not changed:** removing `tidy` from a script that has never been run with a
  committed lockfile would break the documented one-command setup. This needs the
  maintainer to run the setup once and commit the result.

### WA-006 — The Gradle distribution is fetched with no integrity check
- **Category:** Build / supply chain
- **Severity:** High
- **Status:** `CONFIRMED` (not changed)
- **Files:** `gradle/wrapper/gradle-wrapper.properties`
- **Evidence:** 8 lines, no `distributionSha256Sum`. `validateDistributionUrl=true` only
  checks the URL is well formed.
- **Fix:** one line — the official SHA-256 for `gradle-9.7.1-bin.zip`.
- **Why not changed:** the digest must be fetched from Gradle's published checksums by the
  maintainer and verified by eye; inserting an unverified value would be worse than the
  current state.

---

## Medium

### WA-007 — `establishTun()` is not exception-safe; an unguarded throw kills the process
- **Category:** Android / lifecycle
- **Severity:** Medium · **Status:** `FIXED — NOT VERIFIED` (Kotlin never compiled)
- **Files:** `service/AetherVpnService.kt:2650` (`builder.establish()`),
  `:2668-2673` (`addAddress`, `require(...)`)
- **Evidence:** every other failure in the method is wrapped (`addDnsServer:2663`, the
  allow/deny calls `:2445-2496`); the sibling `raiseBlackhole` uses
  `runCatching { builder.establish() }`. `serviceScope` has no `CoroutineExceptionHandler`,
  so a throw reaches `Thread.defaultUncaughtExceptionHandler`.
- **Impact:** a crash, not a reported error — `Builder.establish()` throws if consent is
  revoked between `prepare()` and the call.
- **Fix:** wrap the whole body; return `null`, which every call site already handles.

### WA-008 — `hopStages` is a plain `LinkedHashMap` read from the engine's callback thread
- **Category:** Android / concurrency
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `service/AetherVpnService.kt:208`, `:1096-1097`, `:1099-1101`, `:2336`
- **Evidence:** `reportConnected` runs on the Go engine's thread and calls `pathStatus()`,
  which iterates `hopStages`, while the main thread mutates it in `markHop`.
  `autoStages` is `@Volatile` and replaced rather than mutated for exactly this reason
  (`:254-259`); `hopStages` was missed.
- **Second defect:** it is cleared only on the two carrier paths, so a direct-engine session
  in the same service instance can publish the *previous* hop list alongside `CONNECTED`.
- **Not changed:** needs a device to demonstrate; the fix interacts with the generation
  mechanism.

### WA-009 — Blocking JNI and Go-runtime shutdown run on the main thread in teardown
- **Category:** Android / performance
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `service/AetherVpnService.kt:566-569` (`onDestroy`), `:599-601`
  (`replaceSession`), `:2691-2693` (`stopFromUser`)
- **Evidence:** `runCatching { chain.stop() }` drains the event stream, stops the log, stops
  the tun and shuts down a Go runtime. All three run on `Dispatchers.Main.immediate`.
  The connect path is careful — `runChainSession:2244` wraps even a log-only `chain.nodes()`
  in `Dispatchers.IO` and documents at `:2239-2243` that doing otherwise "is the freeze
  people saw on connect".
- **Not changed:** moving teardown off-main touches the service's shutdown ordering; it
  needs a device profile to confirm the fix.

### WA-010 — An unbounded `sessionJob.join()` holds `commandMutex` on the main dispatcher
- **Category:** Android / liveness
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `service/AetherVpnService.kt:606-607`, `:96`, `:2705-2707`, `:3260`
- **Evidence:**
  ```kotlin
  if (sessionCancellable) sessionJob?.cancel()
  sessionJob?.join()
  ```
  `sessionCancellable` is set `true` only at `:1291` and `:1649`; `runSession` sets it
  `false` at `:617`. So on the direct-engine path the join is neither cancellable nor
  timed out, while `stopFromUser` bounds the identical wait with
  `withTimeoutOrNull(STOP_GRACE_MS)` (4s).
- **Impact:** if a session is wedged in a native read, `commandMutex` is held forever on
  Main and every later `replaceSession`/`networkMayHaveChanged` queues behind it — the
  "connect button that does nothing" failure the file itself warns about at `:1416-1421`.
- **Not changed:** timing out the join and starting a new session would let a dying
  session's `NativeAetherBridge.stop()` kill its successor. Fixing this correctly needs
  a design decision about session ownership, plus device testing.

### WA-011 — Notification permission is only requested from the activity
- **Category:** Android / UX
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `MainActivity.kt:398-405`; `service/AetherTileService.kt:68-90`;
  `WidgetActionActivity.kt:45-79`; `service/AetherNotification.kt:57`
- **Evidence:** the tile and widget connect paths call
  `ContextCompat.startForegroundService` directly. With `POST_NOTIFICATIONS` denied on
  API 33+, `startForeground` still succeeds but nothing is shown, and the notification is
  `setOngoing(true)` — no stop action anywhere.
- **Impact:** a user who connects from the tile and denied notifications has a VPN they
  can only stop by force-stopping the app.
- **Not changed:** the correct remedy is a notification shown by the service itself, which
  is an OS-policy question rather than a code change. Needs a device.

### WA-012 — `TorCarrierService` publishes state from a raw `Thread` unsynchronised
- **Category:** Android / concurrency
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `service/TorCarrierService.kt:46-47`, `:288-291`; read at `:70-112`,
  `:229-234`, `:307-321`, `:330-334`
- **Evidence:** `state` and `socksPort` are plain fields written from
  `awaitBootstrap`'s `Thread` and read from the main looper. No `@Volatile`, no lock.
- **Impact:** a main-thread reader can miss the `CONNECTED` transition and keep reporting
  `CONNECTING`.

### WA-013 — `TorCarrierService.start()` stays wedged after an early failure
- **Category:** Android / lifecycle
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `service/TorCarrierService.kt:172-173` vs `PsiphonService.kt:101`
- **Evidence:**
  ```kotlin
  if (started) return
  started = true
  ```
  `tunnel` (the Psiphon equivalent) is cleared on failure, so a failed Psiphon start can
  be retried; `started` is not, so every early `return` at `:186`, `:193`, `:204`, `:219`,
  `:250` leaves the service deaf to `ACTION_START`. Reachable on any build without the
  pluggable-transport binaries (`transportBinary` returns null at `:266`).

### WA-014 — No DataStore corruption handler; a truncated preferences file crashes on launch
- **Category:** Android / reliability
- **Severity:** Medium · **Status:** `VERIFIED` — but only after a build break was found and fixed
- **Files:** `data/SettingsRepository.kt:17-20` and the other two stores;
  no `CorruptionHandler` exists anywhere in `app/src`
- **Evidence:** `grep -rn CorruptionHandler app/src` → none. `MainViewModel.kt:127`
  collects `repository.settings` in `viewModelScope` and `MainActivity.kt:298` collects it,
  so a `CorruptionException` from a truncated `.preferences_pb` is not contained.
- **Impact:** availability. A phone that loses the atomic rename (disk full mid-write)
  cannot start the app at all.
- **Fix:** `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }` on all
  three stores.
- **Correction (2026-09-30, build phase):** the fix was first written with
  `produceCorruptionHandler`, which **does not compile**. `preferencesDataStore` takes the
  handler directly as `corruptionHandler`; `produceCorruptionHandler` is the *builder*
  name used by `PreferenceDataStoreFactory.create`. Confirmed with `javap` against the
  resolved `datastore-preferences-android-1.2.0` artifact. Corrected, and the project now
  compiles with 282/282 unit tests passing.
- **Verification:** `compileStableDebugKotlin` succeeds; the app installs and launches on
  an Android 14 emulator with no DataStore error at startup. **Recovery from an actually
  corrupt file is still NOT VERIFIED** — that needs a deliberately truncated
  `.preferences_pb` on a device.

### WA-015 — The diagnostics report carries visited hostnames despite redaction being on
- **Category:** Privacy
- **Severity:** Medium · **Status:** `CONFIRMED` (analysis); fix **proposed, not applied** — see note
- **Files:** `core/ChainConfig.kt:68`, `core/ChainController.kt:262`,
  `ui/Screens.kt:2610` (`includeEvents` defaults `true`), `:2805-2806` (`redactAddresses`)
- **Evidence:** mihomo runs at `log-level: info`, whose connection lines carry the matched
  destination. `collectEvents` filters out entries whose `type == "log"` — a filter whose
  own comment says it exists because the alternative "would name every host the user
  visited in a report they might send us". `redactAddresses` replaces **IP literals only**.
- **Impact:** the default report, with the exit chain enabled, discloses browsing history.
- **Why not applied:** the correct fix is to stop collecting chain logs at `info` (or to
  default `includeEvents` to `false`), and both change what the maintainer deliberately
  offers users for support. Needs the maintainer's intent, not an audit's.
- **Also:** `ChainConfig.kt:68` itself — the DNS listener binds `0.0.0.0:1053` while the
  external controller is deliberately kept off all interfaces (`:63-66`). On a shared
  network that is an open resolver. Low.

### WA-016 — The "Lift the block" recovery card is unreachable in the failure path it exists for
- **Category:** UX / correctness
- **Severity:** Medium · **Status:** `FIXED — NOT VERIFIED` (Kotlin never compiled)
- **Files:** `ui/Screens.kt:503`; `service/AetherVpnService.kt:2993-2994`
- **Evidence:**
  ```kotlin
  if (status.message == stringResource(R.string.traffic_is_blocked)) {
  ```
  `giveUp` builds its message as
  `listOfNotNull(said, …, sayNow(R.string.traffic_is_blocked)).joinToString(" ")`, so the
  message *contains* but does not *equal* the string.
- **Impact:** when retries are exhausted under the kill switch — the one moment the card
  exists for — the only in-app control that undoes the block is not rendered, while the
  notification says "Tap to open and lift".
- **Fix:** match on containment as well as equality.

### WA-017 — `catch_unwind` is inert in release, and 15 of 22 JNI entry points lack it
- **Category:** Native / robustness
- **Severity:** Medium · **Status:** `CONFIRMED` (not changed — needs a maintainer decision)
- **Files:** `native/android-bridge/Cargo.toml:44-49` (`panic = "abort"`),
  `native/android-bridge/src/lib.rs` (7 `catch_unwind`), `src/chain_jni.rs` (7 unwrapped)
- **Evidence:** under `panic = "abort"` the 7 wrappers can never fire. The unwrapped entry
  points include `nativeStop`, `nativeMigrate`, `nativeDrainLog`, `nativeCancelScan`,
  `nativeCancelPrepare`, `nativeExportIdentity`, `nativeImportIdentity`,
  `nativeSetSocketProtector` and all of `chain_jni.rs`.
- **Second point:** debug builds are built **without** `--release` (`app/build.gradle.kts:299-333`),
  so debug uses the `dev` profile (`panic = "unwind"`) and the same source has different
  panic behaviour in the two shipped variants.
- **Why not changed:** removing `panic = "abort"` measurably grows the `.so` for a hot-path
  tunnel, and wiring `catch_unwind` everywhere is a real change to a safety-critical
  boundary. Both are the maintainer's call; the inconsistency must be recorded either way.

### WA-018 — A failed carrier start leaks the TUN file descriptor
- **Category:** Native / resource
- **Severity:** Medium · **Status:** `SUSPECTED`
- **Files:** `core/NativeChainBridge.kt:49-50` (ownership contract),
  `native/android-bridge/src/chain.rs:267-293`, `src/chain_jni.rs:76-81`;
  call sites `AetherVpnService.kt:1252`, `:1708-1709`, `:1744`
- **Evidence:** `start_tun` only hands the fd to Go after all three `CString::new`
  conversions succeed, and `chain_jni.rs` returns without closing `fd` when any
  `env.get_string` fails. Kotlin has already called `tun.detachFd()`. One call site
  (`:1709`) tracks `handedOff` and closes on failure; the other (`:1252-1268`) does not.

### WA-019 — `tun.rs` is never compiled or linted by CI
- **Category:** Native / test coverage
- **Severity:** Medium · **Status:** `CONFIRMED`
- **Files:** `native/android-bridge/src/tun.rs`,
  `.github/workflows/ci.yml:48-49`
- **Evidence:** `tun.rs` is `#[cfg(target_os = "android")]`; CI runs host `cargo test` and
  `cargo clippy` only. The file containing every `dup`, `File::from_raw_fd` and `fcntl`
  in the project is compiled exclusively by the Gradle cargo-ndk task.
- **Fix:** `cargo ndk -t arm64-v8a clippy` / `check` in CI — `native/chain/README.md:44-56`
  already documents this pattern for `chain.rs`.

### WA-020 — Identity material crosses the FFI boundary as an immutable Java `String`
- **Category:** Security / key handling
- **Severity:** Medium · **Status:** `CONFIRMED` (accepted)
- **Files:** `native/android-bridge/src/lib.rs:459-477`, `native/aether/aether/src/lib.rs:714-748`,
  `native/aether/aether/src/account.rs:138-155`, `core/NativeAetherBridge.kt:50`
- **Evidence:** `Identity` holds `wg_private_key: [u8;32]`, `access_token: String`,
  `key_pem: Vec<u8>` (PKCS#8 private). The export serialises them to TOML and crosses as a
  Java `String`. Java `String` is immutable and not zeroable; the Rust `String` is dropped
  unzeroized. `zeroize` is present only as a transitive dep of boring/rustls and is used
  nowhere in either crate's source.
- **In the app's favour:** the round trip is entirely text, there is no raw key buffer at
  the boundary, nothing is logged, `config.rs:225/237` write files `0o600` with a
  regression test, backups are fully excluded, and the export only happens through a
  user-driven `CreateDocument`.
- **Why accepted:** removing the `Debug` derive and zeroizing would be a defence-in-depth
  improvement against memory forensics on a rooted device — a threat this app cannot
  meaningfully defend against anyway, since the key must be readable by the engine process.

### WA-021 — 429 tests never execute on any push
- **Category:** Testing
- **Severity:** Medium · **Status:** `CONFIRMED`
- **Files:** `.github/workflows/ci.yml:90`, `native/android-bridge/Cargo.toml`
- **Evidence:** two independent causes.
  1. `assemblePreviewDebugAndroidTest` **builds** the instrumentation APK and never installs
     it; there is no emulator job and no `connectedPreviewDebugAndroidTest`. 93 tests.
  2. `cargo test --manifest-path native/android-bridge/Cargo.toml` does not run the 336
     `#[test]`s inside its path dependency `aether` — Cargo does not run a dependency's
     unit tests, and there is no `[workspace]`.

### WA-022 — Instrumentation tests are gated behind opt-in flags
- **Category:** Testing
- **Severity:** Medium · **Status:** `CONFIRMED`
- **Files:** `app/src/androidTest/**` — 34 `assumeTrue` calls across 11 files
- **Evidence:** gates include `whole=true`, `switch=true`, `split=true`, `identity=true`,
  `psiphon=1`, `tor=1`, `wireguard=true`, `chainSub=<url>`, plus TV-image gating. Roughly a
  third of the suite skips even on a device, and `SplitTunnelSessionTest` — the only thing
  that exercises `Builder` on-device — is behind `split=true`.
- **Assessment:** deliberate and documented, and correct given no CI device exists. Recorded
  so the coverage number is not read as 93 running tests.

### WA-023 — Release builds write carrier diagnostics to logcat
- **Category:** Privacy
- **Severity:** Medium · **Status:** `FIXED — NOT VERIFIED` (Kotlin never compiled)
- **Files:** `service/PsiphonService.kt:175`, `service/TorCarrierService.kt:207`,
  `:290`, `:300`, `:326`, `service/PluggableTransport.kt:67`, `:76`, `service/PsiphonService.kt:282`
- **Evidence:** `EngineLog.kt:58` gates its logcat mirror on `if (BuildConfig.DEBUG)` and
  documents "A release build keeps its log in memory only." These seven sites ignore that.
  `PsiphonService.kt:175` logs the **raw, unfiltered** tunnel-core notice before the
  `consider()` filter at `:214-245` selects a safe subset; Psiphon's notices carry server
  host/port and `ClientRegion` carries the country it believes the phone is in.
- **Impact:** logcat is UID-scoped, so this is exposure to `adb logcat`, OEM bug-report
  collectors and rooted devices — for a tool whose threat model is a hostile local
  environment, that is fingerprinting metadata written where the rest of the app
  deliberately keeps nothing.

### WA-024 — Diagnostics IP redaction misses bare (unbracketed) IPv6
- **Category:** Privacy
- **Severity:** Medium · **Status:** `VERIFIED` — see below
- **Files:** `ui/Screens.kt:2802-2806`, `data/DnsServers.kt:50-70`,
  `service/AetherVpnService.kt:2663-2664`
- **Evidence:**
  ```kotlin
  private val IPV6 = Regex("""\[[0-9a-fA-F:]+](:\d+)?""")
  ```
  Square brackets are required. `DnsServers.isIpv6` accepts bare literals, and
  `AetherVpnService` logs exactly those when the platform rejects a resolver — so the
  address reaches the report verbatim even with **Hide IP addresses** on.
- **Fix:** add a bare-IPv6 alternative. Covered by a new unit test.
- **Verification (2026-09-30, post-audit pass):**
  - `DiagnosticsRedaction.kt` and `DiagnosticsRedactionTest.kt` compiled with
    **kotlinc 2.4.20** — the project's exact Kotlin version — against the Kotlin
    standard library and JUnit 4.13.2 only. **Compiles clean, no warnings.**
  - `DiagnosticsRedactionTest` executed under JUnit: **OK (7 tests)**.
  - A 30-case probe covering the full required list — `::1`, bare `::`,
    `2001:db8::1`, `2001:db8:0:0:0:0:0:1`, `2606:4700::1111`, `fe80::1`, IPv4,
    bracketed, URLs, timestamps, prose, hostnames, MAC addresses — **all pass**,
    including a partial-redaction check confirming no address is ever half-replaced.
  - **Regression proven:** the pre-fix implementation, transcribed from `b55320a`,
    was compiled alongside the fix and the same assertions run against both.
    **5 of 5 fail on the pre-fix code and pass on the fix.**
- **What is still not verified:** the `Screens.kt` call site, and the Android build
  these files are part of. Only the two files above were compiled.

### WA-025 — `apksigner verify` proves consistency, not identity
- **Category:** Release integrity
- **Severity:** Medium · **Status:** `CONFIRMED` (proposed as an upstream PR)
- **Files:** `.github/workflows/release.yml:174`, `:195`
- **Evidence:** `apksigner verify --verbose` succeeds for *any* self-consistent signature.
  If the keystore secret were repointed at a different `.jks`, every check in the job still
  passes and a different-key APK ships. The script already has the digest available via
  `--print-certs`.
- **Fix:** assert a hardcoded SHA-256 certificate digest.

### WA-026 — Keystore passwords are passed as Gradle command-line arguments
- **Category:** CI hygiene
- **Severity:** Medium · **Status:** `CONFIRMED` (proposed as an upstream PR)
- **Files:** `.github/workflows/release.yml:151-158`
- **Evidence:** `-PWHITEAESTHER_KEYSTORE_PASSWORD=…` puts the secrets in the runner's
  process table for the length of a ~90-minute job. Gradle supports `ORG_GRADLE_PROJECT_*`
  environment variables, which is the correct channel.
- **Related:** `release.yml:74-78` promotes both passwords to `$GITHUB_ENV`, exposing them
  to every later step in the job.

### WA-027 — The F-Droid workflow cannot fire from its `workflow_run` trigger
- **Category:** CI functional
- **Severity:** Medium · **Status:** `CONFIRMED`
- **Files:** `.github/workflows/fdroid-repo.yml:96`, `:19-22`
- **Evidence:**
  ```bash
  tag="${{ inputs.tag || github.event.workflow_run.head_branch }}"
  ```
  `head_branch` is empty for the tag-triggered `Release` run this workflow subscribes to,
  so `gh release download ""` fails. Only the `workflow_dispatch` path with an explicit
  `tag` works.
- **Impact:** a distribution channel may silently never publish once F-Droid secrets are
  configured.

### WA-028 — `quiche` is shipped but absent from `THIRD_PARTY_NOTICES.md`
- **Category:** Licensing / attribution
- **Severity:** Medium · **Status:** `CONFIRMED` (not changed — maintainer/legal call)
- **Files:** `native/aether/quiche/` (vendored, compiled into the `.so`),
  `THIRD_PARTY_NOTICES.md`
- **Evidence:** `native/aether/aether/Cargo.toml:21` and `:41` reference it as a path
  dependency. Its `COPYING` (BSD-2-Clause) travels with the source. It has **no** entry in
  `THIRD_PARTY_NOTICES.md` and **no revision recorded anywhere** — unlike every other
  vendored component, which `UPSTREAM.md` handles explicitly.
- **Observation, not a legal conclusion:** the notices' closing sentence — "the Gradle,
  Cargo, and Go lockfiles identify the exact resolved versions" — is not true of this one,
  because a path dependency has no registry version to read from the lockfile.
  BSD-2-Clause retains-notice and no-endorsement obligations apply.

### WA-029 — No vulnerability scanning, SAST, or build attestation
- **Category:** CI
- **Severity:** Medium · **Status:** `CONFIRMED` (proposed as an upstream PR)
- **Files:** `.github/`
- **Evidence:** no CodeQL, no `dependency-review-action`, no
  `actions/attest-build-provenance`, no SBOM.
- **Assessment:** attestation is the one that matters for this project — it ships signed,
  self-updating binaries to users in adversarial networks, where "is this really your APK"
  is the whole product question. Dependency review and SAST are nice-to-have.

### WA-030 — A partially-failed release cannot restore its prerelease flag or notes
- **Category:** Release integrity
- **Severity:** Medium · **Status:** `CONFIRMED` (proposed as an upstream PR)
- **Files:** `.github/workflows/release.yml:247-256`
- **Evidence:** `--prerelease` and `--generate-notes` are applied only on the
  `gh release create` branch. If creation succeeds and an asset upload fails, a rerun takes
  the `gh release upload --clobber` branch — assets are fixed, but the prerelease flag,
  title and notes never get applied. A build intended to be quiet ships publicly.
- **Related:** `release.yml:111` derives `versionCode` from `GITHUB_RUN_NUMBER`, so a
  retry produces a different version code than the first attempt.

---

## Low

| ID | Finding | Files | Status |
| --- | --- | --- | --- |
| WA-031 | `AppUpdatePolicy.parts()` uses `mapNotNull`, so a component that overflows `Long` is dropped rather than failing the version. | `data/AppUpdate.kt:256-259` | `WONTFIX` — **reverted after verification.** A Java mirror of both implementations was run: the old and new code agree on every realistic input. The only case that distinguishes them needs a 20-digit component on *both* sides, and the "correct" answer there is debatable. Contained by `validate()`'s independent versionCode check. See `REJECTED-IDEAS.md`. |
| WA-032 | Two independent update systems: two comparators (`UpdateChecker.isNewer`, `AppUpdatePolicy.isNewer`), two skip stores (raw tag vs normalised version), one endpoint, no shared throttle — dismissing in one screen leaves the other asking. | `data/UpdateChecker.kt:26,68,80`, `data/AppUpdateManager.kt:62-64,304` | `CONFIRMED`, not changed |
| WA-033 | `UpdateChecker.fetchLatestTag` reads the response unbounded (`readText()`); `AppUpdateManager.fetch` bounds to 1 MiB. | `data/UpdateChecker.kt:110` | `CONFIRMED`, not changed |
| WA-034 | `tor-android` 0.4.9.11 is two patch releases behind (0.4.9.13 exists). `jtorctl` 0.4.5.7 is current but was last published 2021-05-04 — five years without a release. | `app/build.gradle.kts:245-246` | `CONFIRMED` |
| WA-035 | Tor pluggable transports are pinned by mutable tag (`lyrebird-0.8.1`, `v2.14.1`) where `chain/setup.ps1` pins full SHAs and says why a tag is not enough. | `native/tor/setup.ps1:22,28` | `CONFIRMED` |
| WA-036 | `verify/main.go` never checks `scanner.Err()`; a read error silently truncates the loop, and `verified == total` then passes for a partially-checked list. | `native/psiphon/verify/main.go:37-59` | `CONFIRMED` |
| WA-037 | `AetherWidgetProvider.onUpdate` calls `updateAppWidget` in a loop with no `runCatching`; any local app can broadcast `APPWIDGET_UPDATE` with forged ids and crash the process via `SecurityException`. No privilege gain. | `service/AetherWidgetProvider.kt:23-30` | `FIXED — NOT VERIFIED` (Kotlin never compiled) |
| WA-038 | `data_extraction_rules.xml` excludes only `root`/`file`/`database`/`sharedpref`; `external` and the four `device_*` domains are omitted. No real exposure today. | `res/xml/data_extraction_rules.xml:3-14` | `FIXED — NOT VERIFIED` (Kotlin never compiled) |
| WA-039 | The verified APK is shared through an implicit `ACTION_VIEW`, so the URI grant goes to whichever activity resolves it. The artefact is a public release file, so impact is negligible. | `MainActivity.kt:191-198` | `CONFIRMED`, not changed |
| WA-040 | `AetherTileService.connect` starts a foreground service from a `TileService` with no visible activity; the widget path deliberately routes through an invisible activity for exactly this reason. May raise `ForegroundServiceStartNotAllowedException` inside a scope with no handler. | `service/AetherTileService.kt:76-89` | `SUSPECTED` |
| WA-041 | `onStartListening` re-adds a collector on a never-completing `StateFlow` if `scope` survives; `connect()` returns silently when the tile is not listening. | `service/AetherTileService.kt:35-48`, `:73` | `CONFIRMED` |
| WA-042 | `TorCarrierService` never stops its bootstrap watcher; `PsiphonService` never dedups its `Messenger` client list, so a dropped client leaks an entry for the process lifetime. | `service/TorCarrierService.kt:282-304`, `PsiphonService.kt:59-65` | `CONFIRMED` |
| WA-043 | `chain/build.ps1` and `tor/build.ps1` pick the NDK with `Sort-Object Name -Descending` on strings — lexicographic, so `9.x` sorts above `29.0.14206865`. | `native/chain/build.ps1:69`, `native/tor/build.ps1:76` | `CONFIRMED` |
| WA-044 | `chain/setup.ps1` reads `$?` after an assignment, so "already applied" detection reflects the assignment rather than `git apply`. | `native/chain/setup.ps1:69` | `CONFIRMED` |
| WA-045 | `MainViewModel.sampleTrafficWhileConnected` polls once a second for the life of the ViewModel even when nothing is connected. | `MainViewModel.kt:487-496` | `CONFIRMED` |
| WA-046 | A malformed identity paste renders key material on screen: the `toml` `de::Error` Display includes the offending source line, which becomes the user-visible error text. The user's own key, on their own screen — a screenshot and "copy the error to a forum" hazard. | `native/aether/aether/src/lib.rs:752` → `MainViewModel.kt:560` | `CONFIRMED`, not changed |
| WA-047 | `AppLocale`'s SharedPreferences mirror is written with an async `apply()` before the DataStore write, so a process death between them leaves `wrap()` (which cannot suspend) reading a stale tag. | `data/SettingsRepository.kt:117-118`, `core/AppLocale.kt:57` | `CONFIRMED` |
| WA-048 | `NetworkKey.cellular` collapses to the bare key `"cell"` whenever MCC/MNC is absent or non-conforming, so a route proven on one operator is recalled on another and `Roaming.actionFor` never replans. | `data/AutoRoute.kt:732-733` | `CONFIRMED` |
| WA-049 | `longestPassMs` sums `budgetMs` per route, but `windowMs` returns `maxOf(budgetMs, remainingMs)` for Psiphon — so the bound is wrong by up to 330s and other lanes can be granted a pass sized against an impossible budget. | `data/AutoRoute.kt:297-298`, `:337-340` | `CONFIRMED` |
| WA-050 | The carrier path dials DoH to the `1.1.1.1` literal through the carrier, which `AddressReporter.kt:112-115` states refuses a port forward to 1.1.1.1. | `core/ChainConfig.kt:279-280` | `SUSPECTED` |
| WA-051 | `Identity`/`Device` derive `Debug` while holding secrets. Nothing formats them today (verified: the only `{:?}` uses are in `#[cfg(test)]`), so this is latent. | `native/aether/aether/src/account.rs:136-137`, `identity.rs:88-89` | `CONFIRMED`, accepted |
| WA-052 | `AppUpdateManager`'s comment claims the checksum comes "from a different asset than the same answer that named the APK"; `release.checksums` is in fact from the same API response. The signer check is the real control. | `data/AppUpdateManager.kt:96-102` | `DOCUMENTATION-ONLY`, not changed |
| WA-053 | `fdroid-repo.yml` declares `repo_url: …/fdroid/repo` but uploads `path: fdroid`, publishing the contents at `…/repo/`. | `.github/workflows/fdroid-repo.yml:132`, `:156`; `docs/FDROID.md:54-56` | `CONFIRMED`, proposed upstream |
| WA-054 | No `values-night/styles.xml`, so the light theme draws light status-bar icons on `#F6FAF8`; the navigation bar stays `#0B0D0D` in light mode. | `res/values/styles.xml:2-8` | `CONFIRMED`, proposed upstream |
| WA-055 | Measured contrast failures: `text3` on `ink2` is 3.75:1 (dark) / 3.93:1 (light); `brand`/`signalLive` on `ink2` is 4.07:1 in light; `line` on `ink2` is 1.99:1 / 1.38:1 against a 3:1 target for control borders. | `ui/theme/Theme.kt:49-91` | `CONFIRMED`, proposed upstream |
| WA-056 | No selection or state semantics anywhere: tabs and option rows use `Modifier.clickable`, so TalkBack hears four unlabelled-current buttons and `ConnectOrb` has no `stateDescription`. | `ui/WhiteAestherApp.kt:437,515`, `ui/AetherComponents.kt:532,573,634`, `ui/ConnectOrb.kt:130` | `CONFIRMED`, proposed upstream |
| WA-057 | Touch targets below 48dp: CrumbBar back 38dp, `SegGroup` 38dp tall, attention-card action chips ≈30dp, subscription "Remove" ≈28dp. | `ui/AetherComponents.kt:256,517,764`, `ui/ChainScreen.kt:272` | `CONFIRMED`, proposed upstream |
| WA-058 | Hardcoded English user-visible strings: `Screens.kt:832-833` (per-app rule note), `:1432` ("Automatic"), `:2800` (device line), and `SplitTunnel.summary()` — whose own comment says it is English "for the diagnostics log" — which is rendered on screen at `SplitTunnelScreen.kt:166` and `Screens.kt:1647`. The correct `split_*` resources exist in **both** locales and are referenced nowhere. | `ui/Screens.kt`, `data/SplitTunnel.kt:68-75` | `CONFIRMED`, proposed upstream |
| WA-059 | `Back`/`Chevron` `ImageVector`s are built without `autoMirror`, so they do not mirror under Persian RTL; the text arrow at `Screens.kt:219` is handled correctly, which makes the inconsistency visible. | `ui/AetherComponents.kt:63-81,124-125` | `CONFIRMED`, proposed upstream |
| WA-060 | Mono styles with no Persian glyphs carry localisable text (`Data` on `R.string.shown_size_events`, `tor_bridges_have`, bridge counts), falling back to a system-substituted face. | `ui/theme/Type.kt:224-238`, `ui/Screens.kt:2642`, `:1199-1204` | `CONFIRMED`, proposed upstream |
| WA-061 | `maxLines = 1` with the default `TextOverflow.Clip` inside a fixed 50dp height cuts button labels mid-glyph at `fontScale` ≥ 1.3 — no ellipsis, unlike every other truncating `Text` in the app. | `ui/AetherComponents.kt:799`, `:829` | `CONFIRMED`, proposed upstream |
| WA-062 | Two one-second clocks are read inside the Home `ScreenColumn` content lambda, so the entire Home subtree re-executes every second while connected. | `ui/Screens.kt:289-315`, `:437`, `:482-490` | `CONFIRMED`, proposed upstream |
| WA-063 | `InstalledApps.icon()` has no cache and `SplitTunnelScreen` launches `produceState` per row, so icons are re-rasterised on every scroll-on. | `ui/SplitTunnelScreen.kt:239-248`, `data/InstalledApps.kt:65-66` | `CONFIRMED`, proposed upstream |
| WA-064 | The LAN address is `remember`ed on a boolean, so joining a different Wi-Fi shows the previous network's address with no way to refresh. | `ui/Screens.kt:1777-1779` | `CONFIRMED`, proposed upstream |
| WA-065 | On TV the remote's BACK key and the gamepad's B button disagree at top-level tabs: `BackHandler` is disabled when `parentOf` is null while `tvControllerBack` returns Home. | `ui/WhiteAestherApp.kt:199` vs `:177-181` | `CONFIRMED`, proposed upstream |
| WA-066 | There are no `AlertDialog`s anywhere in the app, so identity restore (which overwrites the device private key), clear log, forget endpoint and subscription removal are all single taps. | `ui/Screens.kt:2521-2528,1537-1547,2738`, `ui/ChainScreen.kt:261-275` | `CONFIRMED`, proposed upstream |
| WA-067 | `TunnelMtuTest` asserts only on constants declared in its own companion object; it never touches `AetherVpnService.mtuFor` and every assertion would pass if that returned `0`. It also models three transports where the real code has a fourth (`"mim" -> 1162`). | `app/src/test/.../TunnelMtuTest.kt:22-34` | `CONFIRMED` |
| WA-068 | `AppLanguageTest` asserts a Kotlin data-class `copy` against itself, and its round-trip test does an enum self-lookup — no encode/decode runs. | `app/src/test/.../AppLanguageTest.kt:76-81`, `:108-112` | `CONFIRMED` |
| WA-069 | `ChainConfigTest.renderedConfigIsWrittenForExternalValidation` asserts only `target.length > 0`. `AppUpdatePolicyTest.aMatchingNewerApkIsAccepted` has zero assertions. | `app/src/test/.../ChainConfigTest.kt:114-120`, `AppUpdatePolicyTest.kt:101-108` | `CONFIRMED` |
| WA-070 | `native/aether/aether/src/mac_test.rs` is never compiled — no `mod mac_test;` exists and its function has no callers. | `native/aether/aether/src/mac_test.rs` | `CONFIRMED` |
| WA-071 | `thiserror = "2.0.16"` is a direct dependency of `android-bridge` with zero references in `src/`. | `native/android-bridge/Cargo.toml:42` | `CONFIRMED`, proposed upstream |
| WA-072 | `androidx.test.ext:junit:1.3.0` is declared for androidTest but no test imports it. | `app/build.gradle.kts:262` | `CONFIRMED`, proposed upstream |
| WA-073 | Bundled fonts (`ui_*.ttf`, `fa_*.ttf`, `plex_mono_*.ttf`) have no `THIRD_PARTY_NOTICES.md` entry and no OFL text in `licenses/`. The notices also claim the Vazirmatn cut ships in `res/font-fa/`, but the files are unqualified in `res/font/` — and `PersianTypefaceTest` exists specifically to assert no `font-*` directory exists. | `app/src/main/res/font/`, `THIRD_PARTY_NOTICES.md:113` | `CONFIRMED`, not changed |
| WA-074 | BSD-3-Clause texts (tor, lyrebird, snowflake) and the MIT text (boring-sys) are absent from `licenses/`; only `GPL-3.0.txt` and `OFL-1.1-Vazirmatn.txt` are present. | `licenses/` | `CONFIRMED`, not changed |
| WA-075 | BoringSSL itself has no `THIRD_PARTY_NOTICES.md` entry and no recorded version/submodule commit. | `THIRD_PARTY_NOTICES.md:133-139` | `CONFIRMED`, not changed |
| WA-076 | Two Rust lockfiles resolve the same crates to different versions (serde 1.0.229 vs 1.0.228, tokio 1.53.1 vs 1.52.3, rustls 0.23.43 vs 0.23.41, libc 0.2.189 vs 0.2.186). `android-bridge`'s is authoritative; `aether`'s is a drifting second view. | `native/android-bridge/Cargo.lock`, `native/aether/aether/Cargo.lock` | `CONFIRMED` |
| WA-077 | Instrumentation dependencies are compiled but never installed, and there is no emulator job — the CI step gives the impression of device coverage it does not provide. | `.github/workflows/ci.yml:90` | `CONFIRMED`, proposed upstream |
| WA-078 | CI installs `platforms;android-36` (unused; `compileSdk` is 37) and hardcodes `build-tools;36.0.0` in two places. | `.github/actions/android-toolchain/action.yml:43-44`, `.github/workflows/ci.yml:27` | `CONFIRMED`, proposed upstream |
| WA-079 | `dependabot.yml`'s comment says the Rust toolchain is pinned "through `RUST_VERSION`"; no such variable exists — the control is the tag in `action.yml:59` and `native/rust-toolchain.toml`. | `.github/dependabot.yml:66` | **Corrected** — the comment now names the real control |

---

## Documentation-only

| ID | Claim | Actual | Correction |
| --- | --- | --- | --- |
| WA-080 | `README.md:128` "Rust 1.88.0" | `native/rust-toolchain.toml` pins `channel = "1.98.0"`; CI uses `dtolnay/rust-toolchain@1.98.0` | **Corrected** — README now says 1.98.0 |
| WA-081 | `README.md:128` "Android SDK 36" | `app/build.gradle.kts:55` sets `compileSdk = 37` (needed by tor-android); `targetSdk` is 36 | **Corrected** — README now says SDK 37 and explains the compile/target split |
| WA-082 | `README.md:132-133` `./gradlew :app:compileStableDebugKotlin` skips the native build | true for compilation, but the task still resolves `ca.psiphon` from a network Maven repo, so it is not offline | left as-is; the qualification is in `01-BASELINE.md` |
| WA-083 | `docs/RELEASE.md:21-23` "Continuous main releases are prereleases and are replaced after each successful main build" | `ci.yml:4-7` says that behaviour was removed; `release.yml:6-8` triggers on tags only | **Corrected** — replaced with what CI actually does |
| WA-084 | `docs/RELEASE.md:13` points at a signing-secrets table "documented in `README.md`" | README has no such table; the four names exist only in `release.yml:49-52` | **Corrected** — the four names are now listed in `RELEASE.md` |
| WA-085 | `design/PORT-STATUS.md` names `inter_*.ttf` in `res/font/` | the directory holds `ui_*.ttf`, `fa_*.ttf` and `plex_mono_*.ttf` | **Corrected** — all three faces named |
| WA-086 | `design/PORT-STATUS.md` lists obfuscation "Off" as a known gap needing "a warning, not just a label" | still true: `strings.xml:161` carries only a descriptive line, in the same un-gated `AdvancedSection` as the kill switch | left; needs a design decision |
