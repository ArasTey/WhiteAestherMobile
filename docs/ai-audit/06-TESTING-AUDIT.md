# 06 — Testing Audit

Counts are from the tree, not from a run. The only suite this audit could execute is
the Rust one (`01-BASELINE.md`), so "passes" below means "was executed here", and
"unknown" means "not executed here and not executed by CI".

---

## 1. Inventory

| Suite | Files | Cases | In CI? | Run here? |
| --- | --- | --- | --- | --- |
| `app/src/test` (JVM) | 32 | 275 `@Test` | Yes | **No** — Gradle blocked |
| `app/src/androidTest` | 20 + manifest | 93 `@Test` | **Compiled only** | No |
| `native/android-bridge` | 1 (`lib.rs`) | 9 → **10** | Yes | **Yes — 10/10 pass** |
| `native/android-bridge/{chain,chain_jni,tun}.rs` | 3 | **0** | No | No |
| `native/aether/aether` | 26 | 336 `#[test]` | **No** | No |
| `design/` node check | 1 script | — | Yes | No |

## 2. The 429 tests that never run

Two independent causes, both structural.

**93 instrumentation tests.** `ci.yml:90` runs `assemblePreviewDebugAndroidTest` — which
*builds* the instrumentation APK and never installs it. There is no emulator job, no
`connectedPreviewDebugAndroidTest`, no `reactor-runner`, no managed-devices step. The
step gives the impression of device coverage that does not exist.

**336 `aether` unit tests.** `cargo test --manifest-path native/android-bridge/Cargo.toml`
does not run the tests of its path dependency — Cargo does not run a dependency's unit
tests, and there is no `[workspace]`. Both need only a JVM, so this is free to fix:
`cargo test --manifest-path native/aether/aether/Cargo.toml`, or a workspace.

Plus a third: `native/android-bridge/src/tun.rs` is `#[cfg(target_os = "android")]` and
`chain.rs`/`chain_jni.rs` are `#[cfg(unix)]` and load the Go chain — so CI's host
`cargo test`/`clippy` never compiles `tun.rs`, the file containing every `dup`,
`File::from_raw_fd` and `fcntl` in the project. `native/chain/README.md:44-56` documents
this hazard for `chain.rs` and prescribes `cargo ndk -t arm64-v8a clippy`; it was never
applied to `tun.rs`. (WA-019)

## 3. What is genuinely well tested

Not padding — these would be missed by a "coverage" reading:

- **`AutoPlannerTest` (792 lines)** — lane order, framing order, budget arithmetic,
  `pauseAfterRound` backoff, plus an exhaustive `everyPlan` sweep over 2⁷ flag
  combinations. With `RouteMemoryTest`, `NetworkKeyTest` and `EngineFailureMemoryTest`
  this is the best-covered area in the project.
- **`ChainConfigTest` / `ChainProviderTest`** — mihomo YAML generation, dialer-proxy
  wiring, DNS-through-proxy, the single MATCH rule, YAML quoting of `#` in subscription
  URLs, provider-key stability (keyed on URL, not position, which fixed a real bug).
- **`TorConfigTest` / `TorBridgesTest` / `TorConfigTest`** — torrc lines, `StrictNodes 0`,
  refusing a bridge mode with no bridges, bridge-line parsing from pasted Telegram text.
  `TorBridges.parse` cannot inject a torrc directive: lines are split on `\n` first and
  `transportName` is filtered to `[A-Za-z0-9_]+`, so `ClientTransportPlugin <name> …`
  stays one token.
- **Settings model tests** — defaults, encode/decode round-trips, corrupt-JSON fallback,
  self-package filtering, the endpoint/protocol compatibility matrix.
- **`CarrierProbeTest`** — a real SOCKS5 loopback server, not a mock.
- **`AppUpdatePolicyTest`** — signer-set mismatch, version/arch/prerelease/draft refusal,
  `SHA256SUMS` parsing including the real `./`-prefixed file, download-URL allow-listing.

## 4. Tests that prove nothing

These pass, and would keep passing against a broken implementation.

| Test | Problem |
| --- | --- |
| `TunnelMtuTest` (all 6) | Asserts **only on constants declared in its own companion object** (`:22-34`). Never touches `AetherVpnService.mtuFor` (private, `:2827`) or its constants (`:3104-3141`). Every assertion passes unchanged if `mtuFor` returns `0`. The file admits this. It also models three transports; the real code has a fourth branch, `"mim" -> MASQUE_IN_MASQUE_MTU = 1162` (`:2842`), it has never heard of. |
| `ChainConfigTest.renderedConfigIsWrittenForExternalValidation` | Writes `build/chain-config/config.yaml` and asserts `assertTrue(target.length > 0)` (`:114-120`). Asserts nothing about content. |
| `AppLanguageTest` `:76-81` | `assertTrue(settings.copy(language = to).language == to)` — a Kotlin data-class `copy` asserted against itself. No code under test. |
| `AppLanguageTest` `:108-112` | `everyChoiceSurvivesBeingSavedAndReadBack` does an enum self-lookup (`AppLanguage.entries.first { it.tag == … }`). The comment claims a tag round-trips through storage; no encode/decode ever runs. |
| `AppUpdatePolicyTest.aMatchingNewerApkIsAccepted` `:101-108` | **Zero assertions.** Passes iff nothing is thrown. |
| `PsiphonConfigTest` `:27` | `assertTrue("window s outlasts the wait", window * 1_000L < wait)` — the failure message says the *opposite* of the assertion. The assertion matches the code; only the message is wrong. |

## 5. Coverage gaps, prioritised

1. **Connection state machine — entirely untested on the JVM.** 14
   `publish(EngineStatus(...))` transitions (`:695, 785, 1150, 1195, 1237, 1444, 1519,
   1558, 1706, 2079, 2330, 2684, 2753, 2795`) have no invariant test. Nothing asserts
   ERROR is terminal, that STOPPING cannot be followed by CONNECTED, or that a stale
   session cannot overwrite a newer one.
2. **Retry/backoff bounds.** `reconnectDelayMs`, `MAX_RECONNECT_ATTEMPTS` and `giveUp`
   are private and untested. The only backoff tested is `AutoPlanner.pauseAfterRound` —
   a different pure function in a different file.
3. **Cancellation.** No test that a stop makes an in-flight session inert, nor that a late
   native reply cannot publish status. `CancellationException` handling in
   `AppUpdateManager.copyIn`/`verified` (`:175, 205, 222`) is untested.
4. **Update checksum/signature verification.** The *rules* are covered; the *verification*
   is not — SHA-256 over the downloaded file, size cap (`:179`), `copyIn` (`:163-190`),
   `getPackageArchiveInfo`, the signer-set computation (`:193-219`), and the
   DownloadManager status mapping (`:140-149`) have **zero tests of any tier**. The test
   fixture signer is `setOf("aa")`; nothing covers the real `SHA-256(cert bytes)`.
5. **VpnService lifecycle.** `onStartCommand` (`:439`), `onRevoke` (`:547`), `onDestroy`,
   the `START_STICKY` branch (`:514-542`), `mtuFor`, the IPv6 floor gate (`:2628`), the
   chain 9000-MTU branch (`:2584`), `applySplitTunnel`. `SplitTunnelSessionTest` covers
   `Builder` acceptance **on a device only, behind `split=true`**.
6. **Network-change handoff.** `Roaming.actionFor` is tested as a pure function; nothing
   tests that the `NetworkCallback` is registered (`:360-368`), that
   `networkMayHaveChanged()` is reached, or what `Replan`/`RecordOnly` do to a live
   session. `NetworkIdentity.current()` (94 lines, dual-SIM + non-VPN-underneath) has
   **no test of any tier**.
7. **Identity/key persistence.** `IdentityPortabilityTest` and `SharedIdentityTest` are
   instrumentation-only and 5 of their methods are `assume`-gated on `identity=true` or
   on an identity already existing. `NativeAetherBridge.export/importIdentity` has no JVM
   test. (The Rust side has 21 `identity.rs` tests — which never run; see §2.)
8. **Settings migration — mostly fine, persistence is not.** The one real migration
   (`AutomaticCarrierMigration`) is well covered. `SettingsRepository.save()` has no
   test, and `AppSettings.toNativeJson()` — the JSON actually handed to the native engine
   — has no JVM test at all.
9. **Error propagation from the native bridge.** `NativeChainBridgeTest` covers
   `isLoaded`/`isAvailable` and one `wa.probe` round trip. No test that a native
   exception surfaces as a Kotlin error, no timeout/cancellation test, no error-branch
   test for `drainLog()`.

### Production classes no test file references at all
`MainViewModel` (1008 lines), `TorCarrierService`, `PsiphonService`, `PsiphonClient`,
`TorClient`, `CarrierClient`, `AetherCarrierClient`, `PluggableTransport`,
`TrafficMeter`, `AppUpdateManager`, `NetworkIdentity`, `AetherTileService`,
`AetherWidgetProvider`, `WidgetActionActivity`, `AetherNotification`, `LocalAddress`,
and all of `Screens.kt` (2830), `ConnectOrb.kt`, `ChainScreen.kt`,
`SplitTunnelScreen.kt`, `AetherComponents.kt`, `TvSupport.kt`, `Summaries.kt`.

## 6. What this audit changed

Two things, both because a test was impossible without them:

- **`DiagnosticsRedactionTest`** (7 tests) for WA-024. The redaction rules were
  `private` file-level declarations in `Screens.kt`, so no test could reach them; they
  were extracted to `data/DiagnosticsRedaction.kt` as an `internal object`. That
  extraction is justified by the fix, not by tidiness — a redaction rule that stops
  matching fails nothing, because the report still builds, previews and sends.
- **`refusing_a_second_run_leaves_the_first_runnable`** for WA-001, in Rust, executed and
  **proven to fail against the pre-fix code** (9 passed / 1 failed with the original
  unconditional cleanup restored).

**One proposed test was written and then deleted:** a version-comparison regression test
for WA-031. A Java mirror showed the old and new implementations agree on every
realistic input, so the test would have passed before and after — that is, it would have
proven nothing. The fix was reverted. See `REJECTED-IDEAS.md`.

## 7. Recommended next steps, in order

1. Add a workspace or a second `cargo test` invocation so the 336 `aether` tests run.
   **Free — they need only a JVM.**
2. Add `cargo ndk -t arm64-v8a clippy` so `tun.rs` is compiled at all.
3. Either add an emulator job or drop `assemblePreviewDebugAndroidTest`; today it
   advertises coverage it does not provide.
4. Make `AetherVpnService.mtuFor` non-private and rewrite `TunnelMtuTest` against it, so
   the file tests the service instead of itself.
5. Extract the pure transition table from `AetherVpnService` and test the state machine
   invariants on the JVM. This is the single highest-value test change available, and
   `05-ARCHITECTURE-AUDIT.md` §4 explains why extracting the *pure* part is the right seam
   and extracting a service class is not.
6. Fix the four tautological tests in §4.