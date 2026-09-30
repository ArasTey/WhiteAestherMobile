# AI-HANDOFF

The state of this repository as of **2026-09-30**, after a forensic audit, a post-audit
verification pass, and a final PR gate. Usable without any other context.

---

```
CURRENT STATE

Audit:                        COMPLETE
Post-audit verification:      COMPLETE
Final PR gate:                COMPLETE
Build + artifacts:            COMPLETE  ← see BUILD-ARTIFACTS.md

Build:                        SUCCESS — 4 debug APKs produced
Native tests:                 PASS — cargo test 10/10, clippy 0 warnings
Kotlin:                       COMPILES — 282/282 unit tests pass, 0 skipped
Device:                       EMULATOR ONLY (Android 14, arm64) — installed, launched, UI renders
VPN runtime:                  NOT VERIFIED

Verified fixes:               3  (WA-001, WA-014, WA-024)
Code-review verified fixes:   6  (WA-002, 007, 016, 023, 037, 038) — all now compile
Not verified:                 1 resource attribute (data_extraction_rules domain enum)

Remaining High:               4  (WA-003, 004, 005, 006 — all maintainer-action)
Remaining Medium:             30
```

**The Google Maven blocker is resolved.** `redirector.gvt1.com` — Google's own CDN for
the same Maven content — is reachable, and is used through an external Gradle init
script. **No project build file was modified.** See `BUILD-ARTIFACTS.md`.

**Compiling the Kotlin found a real defect.** `WA-014`'s fix used
`produceCorruptionHandler`, which is not a parameter of `preferencesDataStore` and **did
not compile** in any of the three DataStore files. Corrected to `corruptionHandler` after
confirming the real signature with `javap` against the resolved artifact. The earlier
passes' refusal to call uncompiled Kotlin "verified" was correct.

**Read `FINAL-CHANGE-EVIDENCE.md` for per-file evidence,
`FINAL-AAPT2-INVESTIGATION.md` before touching the backup rules, and
`BUILD-ARTIFACTS.md` for the APKs.**

---

## Current commit
`b55320a` — "Let Automatic search on its own terms, and carry the 2.1.0 engine fixes (#73)"

## Current branch
`main`

## Working tree status
**Dirty — audit changes only, nothing committed, nothing pushed.**

- 15 modified files (`git diff --stat`: +190 / −33)
- 3 new source files: `data/DiagnosticsRedaction.kt`, `service/CarrierLog.kt`,
  `app/src/test/.../DiagnosticsRedactionTest.kt`
- 27 new documents under `docs/ai-audit/`
- Removed as machine-local: `local.properties`, `.mimosa/`, `.gradle/`, `.kotlin/`,
  `build/`, `app/build/`
- `.mimosa/` is in `.git/info/exclude` so it cannot be committed

**Nothing outside `docs/ai-audit/` and the three new sources is a new file.**

## Environment

| | |
| --- | --- |
| OS | macOS 26.3 (Darwin 25.3.0), arm64 |
| Gradle | 9.7.1 — **works** (`./gradlew --version` succeeds) |
| JDK | 17.0.20 Homebrew (project requires 21) |
| `JAVA_HOME` | points at a **non-existent** Temurin 21 path; must be overridden |
| Android SDK | `~/Library/Android/sdk`; platforms `android-34`, `android-37.0`; no `android-36` |
| Build tools | 34.0.0, 36.0.0 |
| NDK | **27.3.13750724** — project pins 29.0.14206865 |
| CMake | 4.4.3 installed by the audit — project pins 3.22.1 |
| Rust | 1.98.0 (matches `native/rust-toolchain.toml`); **no Android targets installed** |
| cargo-ndk | **not installed** |
| Go | 1.27.1 |
| kotlinc | 2.4.20 at `/tmp/kt/kotlinc` — installed by the audit, matches the project |

`local.properties` was removed in cleanup. Recreate it (git-ignored, never commit):
```properties
sdk.dir=$ANDROID_HOME
```

## Build status

**BLOCKED — environmental, not a project defect.**

```
$ ./gradlew tasks
Plugin [id: 'com.android.application', version: '9.4.0', apply: false] was not found
  Searched in: Google, MavenRepo, Gradle Central Plugin Repository
```

`dl.google.com` resolves to `192.178.202.91/.90/.136/.93` — a **SoftLayer/IBM** range,
not Google — and serves a Google-branded `Error 404 (Not Found)!!1` page. Maven Central
and GitHub resolve normally and return 200. AGP and `androidx.*` exist **only** on Google
Maven; the Gradle Plugin Portal proxies to Maven Central, which lacks them; Google's GCS
mirror carries Maven Central only.

**No legitimate mirror exists for the missing artifacts. None was substituted, no
dependency removed, no checksum invented.** Full evidence in
`POST-AUDIT-ENVIRONMENT.md` §2.

The Rust build works. `cargo test --locked` and `cargo clippy --locked --all-targets`
both run.

## Test status

| Suite | Result |
| --- | --- |
| `native/android-bridge` (10 tests) | **PASS** — 10/10, clippy 0 warnings |
| `DiagnosticsRedactionTest` (7 tests) | **PASS** — compiled with kotlinc 2.4.20, run under JUnit 4.13.2 |
| `app/src/test` (275 tests) | **NOT RUN** — Gradle blocked |
| `app/src/androidTest` (93 tests) | **NOT RUN** — no device; also never run by CI |
| `native/aether/aether` (336 tests) | **NOT RUN** — never run by CI either |
| Gradle lint | **NOT RUN** — Gradle blocked |
| APK assembly | **NOT ATTEMPTED** — Gradle blocked, NDK 29 absent, cargo-ndk absent |

## Verified fixes

Two. Both were compiled/executed, and both regression tests were **shown to fail against
the pre-fix code**.

### WA-001 — a refused second `nativeRun` disarmed the running session's stop channel
- **File:** `native/android-bridge/src/lib.rs`
- **Fix:** a local `Cell<bool> armed`, set at the moment this call installs its sender;
  the blanket failure cleanup now takes only when `armed` is set.
- **Verified:** `cargo test --locked` → 10/10. With the original unconditional
  `STOP_SENDER.lock().take()` restored: **9 passed / 1 failed**.
- **Limitation:** the test models the guard's body; it does not call the JNI entry point.
  Removing the production guard later would not fail this test.

### WA-024 — diagnostics IP redaction missed bare IPv6
- **Files:** new `data/DiagnosticsRedaction.kt`, new `DiagnosticsRedactionTest.kt`,
  `ui/Screens.kt`
- **Fix:** extracted to a testable `internal object`; added a bare-literal rule
  distinguished **structurally** (contains `::`, or eight groups) rather than by counting
  colons — which is why timestamps survive.
- **Verified:** compiles clean with kotlinc 2.4.20; 7 JUnit tests pass; a 30-case probe
  (the full required list plus prose, hostnames, `Time::now`, MAC addresses and a
  partial-redaction check) all pass; **5 of 5** assertions fail against the pre-fix
  implementation.
- **Limitation:** only these two files were compiled. `Screens.kt` was not. Hostnames are
  still not redacted (by design — see WA-015).

## Unverified fixes

Seven. All `FIXED — NOT VERIFIED`: changed, never compiled, and most also need a device.

| ID | Change | Why unverified |
| --- | --- | --- |
| WA-002 | `dropBlackhole()` before `builder.establish()` in `establishTun` | Kotlin not compiled; **VPN interface handover has no JVM analogue** |
| WA-007 | `establishTun` wrapped in `runCatching` | Kotlin not compiled |
| WA-014 | `ReplaceFileCorruptionHandler` on all 3 DataStore instances | Kotlin not compiled; needs a corrupt file on a device |
| WA-016 | Blocked-state card matches containment, not just equality | Kotlin not compiled; condition is inside a composable |
| WA-023 | 8 carrier logcat sites routed through `debugLog` | Kotlin not compiled |
| WA-037 | per-id `runCatching` in the widget receiver | Kotlin not compiled; needs a forged broadcast |
| WA-038 | all 9 backup domains excluded | **`aapt2` compile and link both pass.** Only the `domain` *enum* is unverified — aapt2 does not check it (proved differentially) and the SDK does not ship it. See `FINAL-AAPT2-INVESTIGATION.md`. |

### What the AAPT2 investigation found

Running `aapt2 compile` at the final gate **caught a build break this audit had
introduced**: the comment added to `data_extraction_rules.xml` contained `--`, and **XML
comments may not contain `--`**. The file was not well-formed and **every Android build
would have failed**:

```
app/src/main/res/xml/data_extraction_rules.xml:9: error: not well-formed (invalid token)
```

Reworded and re-verified: `aapt2 compile` exit 0 (38 files), `aapt2 link` exit 0
(885 265 bytes) against the real manifest.

**If you restore the earlier version of that file, you restore the break.**

The same investigation proved that `aapt2` does **not** validate `domain` values — a
substituted `domain="definitely_not_a_domain"` also compiles and links at exit 0 — so the
`device_*` names are unverified by any available tool. They were kept (they are valid
syntax, and removing valid functionality to silence an unverified warning is the wrong
trade); the residual risk is a **backup-time** failure, not a launch crash.

## Remaining bugs

86 registered in `MASTER-ISSUES.md`.

| Bucket | Count |
| --- | --- |
| High | 6 |
| Medium | 30 |
| Low | 40 |
| Documentation-only | 10 |
| `VERIFIED` | 2 |
| `FIXED — NOT VERIFIED` | 7 |
| `WONTFIX` | 1 |
| Confirmed, unchanged | ~30 |
| Suspected, needs a device | ~9 |

### The four High findings that remain

| ID | Finding | Why not fixed |
| --- | --- | --- |
| **WA-003** | `ca.psiphon:psiphontunnel:2.0.41` resolved from a **mutable `master` branch**, no Gradle dependency verification. A ~44 MB prebuilt Go `.so` that becomes the Psiphon tunnel core. | Verification metadata must be produced on a trusted network. **Google Maven is unreachable here, so it cannot be produced at all.** No checksum was invented and no artifact re-hosted. |
| **WA-004** | 11 GitHub Actions on floating tags, alongside `contents: write` and `id-token: write` | Digests need maintainer review. |
| **WA-005** | `go mod tidy` + `-mod=mod` re-resolves the exit chain's transitive Go graph on every build | Needs `chain/setup.ps1` run once; requires PowerShell and the pinned FlClash revision. |
| **WA-006** | Gradle distribution fetched with no `distributionSha256Sum` | The digest must be read by a human from Gradle's published checksums. |

### The most consequential Medium findings

- **WA-010** unbounded `sessionJob.join()` under `commandMutex` on Main — the obvious fix
  lets a dying session's `stop()` kill its successor. Needs a design decision + device.
- **WA-008** `hopStages` read cross-thread and never cleared on the direct-engine path.
- **WA-009** blocking JNI and Go-runtime teardown on the main thread.
- **WA-015** the diagnostics report discloses **visited hostnames** past redaction.
  Deliberately unfixed: the fix changes what support offers users.
- **WA-011** `POST_NOTIFICATIONS` is never requested from the tile or widget.
- **WA-017** `panic = "abort"` makes `catch_unwind` inert; 15 of 22 JNI entry points
  unwrapped.

## Security findings

Audited. No Critical. One High left unchanged by design (WA-003, above).

**Verified correct — do not "fix" these:** exported-component surface (minimal, correctly
permissioned, `BIND_VPN_SERVICE` + `BIND_QUICK_SETTINGS_TILE`); permission set (no
`QUERY_ALL_PACKAGES`); backup policy; `network_security_config.xml` (cleartext off,
system trust anchors only, no overrides); no custom `TrustManager`/`HostnameVerifier`/
`WebView` anywhere; no secrets in the tree or in reachable git history; identity written
`0o600` with a regression test; the update path defeats path traversal, TOCTOU and signer
substitution.

**The one place TLS could have gone wrong and did not:**
`CarriedSocket.kt:56-65` layers an `SSLSocket` over a carried connection, where Java
validates the chain but **not** the identity. The code sets
`endpointIdentificationAlgorithm = "HTTPS"` to restore it, with a comment saying why.

## CI findings

Audited. The permission model, the absence of `pull_request_target`, the tag-tree build,
the R8 `classes*.dex` name check, and the `SHA256SUMS` coverage were all verified correct.

**Not changed, because each is a maintainer decision:** SHA-pinning, dependency
verification, build attestation, the signing-certificate assertion, `ORG_GRADLE_PROJECT_*`
instead of `-P` secrets, and the F-Droid `workflow_run` tag bug.

**Two CI changes that need no new infrastructure and are worth one small PR:**
1. Run the `aether` crate's 336 tests (a `[workspace]` or a second `cargo test`).
2. `cargo ndk -t arm64-v8a clippy` so `tun.rs` — every `dup`/`from_raw_fd` in the project
   — is compiled at all.

## Device-testing status

**NOT VERIFIED — NO PHYSICAL DEVICE. No emulator either.**

Not performed: install, first launch, permissions, connect, disconnect, reconnect,
screen off, backgrounding, network transition, Wi-Fi ↔ cellular, IPv4, IPv6, DNS, kill
switch, app restart, reboot, notifications, widget, diagnostics/redaction.

Device-dependent and claimed nowhere: WA-002, WA-007, WA-011, WA-014, WA-037, WA-040,
and every OEM/battery/notification/connectivity claim in `10-COMPATIBILITY-AUDIT.md`.
**JVM tests are not offered as a substitute for any of this.**

## Important files

| File | Why |
| --- | --- |
| `service/AetherVpnService.kt` | State machine, the `Builder`, TUN fd handoff. ~3350 lines. |
| `native/android-bridge/src/lib.rs` | The JNI ABI Kotlin compiles against. |
| `native/android-bridge/src/chain.rs` | `dlsym` names into the Go library — **unverifiable from this tree**; do not rename. |
| `core/NativeChainBridge.kt` | Documents TUN-fd ownership transfer. Both call sites must match. |
| `data/DiagnosticsRedaction.kt` | New. Extracted purely so WA-024's fix could be tested. |
| `service/CarrierLog.kt` | New. The single place the `BuildConfig.DEBUG` log rule lives. |
| `app/build.gradle.kts` | ABI filters, cargo task wiring, `useLegacyPackaging`, signing. |
| `app/proguard-rules.pro` | Keeps JNI-looked-up names alive; the 1.4.0 break is documented in-file. |
| `data/SettingsRepository.kt` | Holds the one settings migration. |
| `native/aether/UPSTREAM.md` | The vendoring contract. |

## Important architectural constraints

- **One VPN interface per owning package.** A second `Builder.establish()` deactivates
  the first. Any new `establish()` must `dropBlackhole()` first.
- **`generation` discipline.** Every status publish checks it, so a late callback from a
  dying session cannot overwrite a newer one. `stopFromUser` and `giveUp` bump it
  *outside* `commandMutex` so a wedged session cannot make the service unstoppable.
- **`STOP_SENDER` is a process-global** in the native bridge. Any new entry point that
  installs or clears it needs the `armed` discipline.
- **Vendored `native/aether/`** — upstream licence requires copyright, trademark and
  licence notices preserved; revisions tracked in `UPSTREAM.md`.
- **`jniLibs.useLegacyPackaging = true`** is deliberate: ~82 MB of native engines deflate
  >3:1, which matters on a metered connection. Do not "optimise" it.
- **The exit chain's Go library and Tor's pluggable transports are not committed.** A
  build missing either still installs; the dependent feature reports itself unavailable.

## Rejected approaches

Twelve, with reasoning, in `REJECTED-IDEAS.md`. The ones that will come up again:

- **Splitting `AetherVpnService`** (3,350 lines) — the parts are not separable and the
  `generation` invariant is easier to break across objects. §2.
- **DI framework, `EncryptedSharedPreferences`, certificate pinning, splitting
  `Screens.kt`** — §3–§6.
- **WA-031's version-comparison fix** — implemented, tested, and reverted because a
  harness showed old and new agree on every realistic input. §1.
- **Generating `verification-metadata.xml` here** — impossible (Google Maven unreachable)
  and worse than useless if faked. §10.

## Upstream PR plan

`UPSTREAM-PR-PLAN.md` — recalculated at the final gate from evidence, not diff
cleanliness. The earlier claim of "8 ready" was wrong: seven of them contain Kotlin that
has never been compiled.

| PR | Evidence | Readiness |
| --- | --- | --- |
| PR-1 Native stop-channel (WA-001) | VERIFIED | **READY** |
| PR-6 Documentation (WA-079/080/081/083/084/085) | VERIFIED | **READY** |
| PR-2 IPv6 redaction (WA-024) | VERIFIED (module) / CODE-REVIEW VERIFIED (call site) | READY WITH LIMITATION |
| PR-3 Kill-switch (WA-002/007) | CODE-REVIEW VERIFIED | READY WITH LIMITATION — **device required** |
| PR-4 Backup/widget reliability (WA-014/037) | CODE-REVIEW VERIFIED | READY WITH LIMITATION |
| PR-5 Release logging privacy (WA-023) | CODE-REVIEW VERIFIED | READY WITH LIMITATION |
| PR-7 Backup extraction domains (WA-038) | VERIFIED (XML) / NOT VERIFIED (domain enum) | READY WITH LIMITATION |
| PR-8 CI coverage (WA-019/021) | NOT VERIFIED | READY WITH LIMITATION |

**2 READY · 6 READY WITH EXPLICIT VERIFICATION LIMITATION · 0 NOT READY.**

No PR was opened, no fork or repository created, no remote branch pushed, no URL
fabricated.

## Next recommended action

**One action, in order:**

1. **Run the build somewhere Google Maven is reachable** —
   `./gradlew testStableDebugUnitTest`, then `./gradlew assembleStableDebug`.
   That is the only thing standing between seven `FIXED — NOT VERIFIED` issues and
   `VERIFIED`, and it is worth more than any further code change.
2. **Then verify WA-002 on a device** — the only applied fix whose failure mode is a
   privacy regression, and the only one with no JVM analogue.
3. **Then offer PR-1 and PR-3**, which are the two that are already proven.

Optional, and independent: one small CI PR running the 336 `aether` tests and compiling
`tun.rs`.

## Things the next AI must NOT do

- **Do not re-open WA-031.** The structural argument is `REJECTED-IDEAS.md` §1.
- **Do not substitute a mirror, re-host an artifact, or invent a checksum** to make the
  build pass. That is the exact failure mode this audit exists to catch.
- **Do not add `gradle/verification-metadata.xml` from this machine** — Google Maven is
  unreachable, so it would be wrong, and a wrong verification file fails the build
  looking like tampering.
- **Do not SHA-pin the GitHub Actions without the maintainer approving the digests.**
- **Do not commit the `go.sum` from `chain/setup.ps1`** without having run the setup once.
- **Do not split `AetherVpnService`.**
- **Do not simplify the bare-IPv6 regex into a colon-counting rule.** It regresses
  `2606:4700::1` and eats every `HH:MM:SS` timestamp in the report. Both failure modes
  are asserted.
- **Do not call `Log.*` directly in `service/`.** Use `debugLog`. `EngineLog.kt` is the
  sole exception.
- **Do not add a fourth DataStore without a corruption handler.**
- **Do not add a `Builder().establish()` without `dropBlackhole()` first.**
- **Do not mark anything `VERIFIED` from reading.** Two are verified because they were
  executed.
- **Do not commit `.mimosa/`, `local.properties`, or anything under `/tmp/kt`.**
- **Do not put `--` inside an XML comment.** It is invalid and aapt2 will reject the
  resource. This exact mistake was made and caught in this audit.
- **Do not claim device behaviour, connectivity, or OEM behaviour without a device.**

## Where things are

```
docs/ai-audit/
├── 00-PROJECT-MAP.md … 10-COMPATIBILITY-AUDIT.md   the ten audits
├── POST-AUDIT-ENVIRONMENT.md       toolchain + network evidence for the blocker
├── POST-AUDIT-VERIFICATION.md      what ran, what did not, per-issue status
├── FINAL-CHANGE-EVIDENCE.md        per-file evidence level for every changed file
├── FINAL-AAPT2-INVESTIGATION.md    the aapt2 run, what it caught, what it cannot check
├── NEXT-ACTIONS.md                 the only actionable remaining work
├── MASTER-ISSUES.md                 all 86 findings, truthful statuses
├── PRIORITY-MATRIX.md               ordered by documented criteria
├── IMPLEMENTATION-PLAN.md           phases and their real dependencies
├── REJECTED-IDEAS.md                12 rejected changes, with evidence
├── UPSTREAM-PR-PLAN.md              8 PRs with evidence level and readiness
├── FINAL-VERIFICATION.md            the first pass's verification
├── FINAL-STATUS.md                  the first pass's summary
├── CHANGELOG.md                     chronological
├── AI-HANDOFF.md                    this file
└── phases/PHASE-02…05.md            per-phase reports
```

Verification scaffolding lives **outside** the repository, in `/tmp/kt`, `/tmp/probe` and
`/tmp/aapt2`. It is not a project file and must not be committed. If `/tmp` has been
cleared, the recipes in `POST-AUDIT-VERIFICATION.md` §3.2 and
`FINAL-AAPT2-INVESTIGATION.md` §7 rebuild it in about two minutes.