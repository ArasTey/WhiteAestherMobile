# UPSTREAM-PR-PLAN — recalculated readiness

**Recalculated 2026-09-30 at the final PR gate.** The earlier statement "Upstream PRs
ready: 8" has been challenged and **is wrong**: seven of those eight contain Kotlin that
has never been compiled. Readiness is recalculated below from evidence, not from diff
cleanliness.

## Evidence levels

| Level | Meaning |
| --- | --- |
| **VERIFIED** | Compiled and/or executed; tests pass; regression demonstrated |
| **CODE-REVIEW VERIFIED** | Inspected in full; static reasoning supports correctness; required build/runtime verification unavailable |
| **NOT VERIFIED** | Insufficient evidence |

## Readiness

| Readiness | Meaning |
| --- | --- |
| **READY** | Compiles, tests pass, regression verified, no known build blocker, changes minimal |
| **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION** | Sound and minimal, but the reader must know what was not compiled |
| **NOT READY** | A known blocker, or the change is speculative |

**Nothing reaches READY except PR-1.** Everything else that touches Kotlin either has a
compilation blocker or needs a device.

---

## The PRs

### PR-1 — Native stop-channel correctness
- **Scope:** `STOP_SENDER` is only cleared by the call that installed it.
- **Files:** `native/android-bridge/src/lib.rs` (1 file, +70/−2)
- **Issue IDs:** WA-001
- **Build status:** PASS — `cargo test --locked` compiles the crate
- **Test status:** PASS — 10 passed, 0 failed
- **Runtime status:** PASS — the guard body and `nativeStop`'s send-then-drop are executed
- **Device status:** n/a — no Android API surface
- **Evidence level:** **VERIFIED** — and the regression test **fails on the pre-fix code**
  (`9 passed / 1 failed`)
- **Readiness:** **READY**
- **Blocking reason:** none

### PR-2 — Diagnostics IPv6 redaction
- **Scope:** bare IPv6 literals are redacted; rules extracted so they can be tested.
- **Files:** new `data/DiagnosticsRedaction.kt`, new `DiagnosticsRedactionTest.kt`,
  `ui/Screens.kt` (1 line + import + comment)
- **Issue IDs:** WA-024
- **Build status:** **BLOCKED** — Gradle cannot resolve AGP. The two new files compile
  under standalone `kotlinc 2.4.20`; `Screens.kt` does not.
- **Test status:** **PASS for the two new files** — 7 JUnit tests, `OK (7 tests)`; 30-case
  probe passes; 5/5 assertions fail on the pre-fix code. **Not run under Gradle.**
- **Runtime status:** PASS (JVM) for the redaction object
- **Device status:** n/a for the object; the call site is Compose and unverified
- **Evidence level:** **VERIFIED** for the module and test; **CODE-REVIEW VERIFIED** for
  the `Screens.kt` call site
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** `Screens.kt` has never been compiled. One Gradle run closes it.

### PR-3 — Kill-switch correctness
- **Scope:** `dropBlackhole()` before `builder.establish()`, and `establishTun` made
  exception-safe.
- **Files:** `service/AetherVpnService.kt` (1 file, +17/−3)
- **Issue IDs:** WA-002, WA-007
- **Build status:** **BLOCKED**
- **Test status:** none exist; none possible on the JVM — `VpnService.Builder` cannot be
  constructed off-device
- **Runtime status:** not verified
- **Device status:** **not verified — no device**
- **Evidence level:** **CODE-REVIEW VERIFIED**
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** needs a Gradle run **and** a device. The invariant it restores
  ("traffic stays blocked during interface handover") is OS behaviour and **cannot** be
  demonstrated without hardware. **Do not merge sight-unseen.**

### PR-4 — Backup and widget reliability
- **Scope:** DataStore corruption handlers; widget receiver cannot be crashed.
- **Files:** `data/SettingsRepository.kt`, `data/UpdateChecker.kt`, `data/AddressReporter.kt`,
  `service/AetherWidgetProvider.kt`
- **Issue IDs:** WA-014, WA-037
- **Build status:** **BLOCKED**
- **Test status:** none
- **Runtime status:** not verified
- **Device status:** not verified
- **Evidence level:** **CODE-REVIEW VERIFIED**
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** Kotlin never compiled. Grouped because both are one-line
  robustness fixes with no shared root cause — if a maintainer prefers, they split cleanly
  into two one-PRs.

### PR-5 — Release logging privacy
- **Scope:** carrier diagnostics no longer reach logcat in release builds.
- **Files:** new `service/CarrierLog.kt`, `PsiphonService.kt`, `TorCarrierService.kt`,
  `PluggableTransport.kt`
- **Issue IDs:** WA-023
- **Build status:** **BLOCKED**
- **Test status:** none
- **Runtime status:** not verified — `BuildConfig.DEBUG` is false only in a release build,
  and no release build has been produced here
- **Device status:** not verified
- **Evidence level:** **CODE-REVIEW VERIFIED**
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** Kotlin never compiled. Note the fix *restores* an existing,
  already-documented rule rather than introducing a policy, which lowers the risk of
  review disagreement.

### PR-6 — Documentation and toolchain corrections
- **Scope:** README, RELEASE, PORT-STATUS, dependabot comment.
- **Files:** `README.md`, `docs/RELEASE.md`, `design/PORT-STATUS.md`, `.github/dependabot.yml`
- **Issue IDs:** WA-079, WA-080, WA-081, WA-083, WA-084, WA-085
- **Build status:** n/a
- **Test status:** n/a — each claim was checked against the file it describes
  (`rust-toolchain.toml`, `app/build.gradle.kts:55,61`, `ci.yml`, `release.yml:49-52`,
  `ls app/src/main/res/font/`, `grep -rn RUST_VERSION`)
- **Runtime status:** n/a
- **Device status:** n/a
- **Evidence level:** **VERIFIED** (against sources), **CODE-REVIEW VERIFIED** for the
  `docs/RELEASE.md` prose
- **Readiness:** **READY**
- **Blocking reason:** none. This is the one non-code PR that is genuinely ready.

### PR-7 — Backup extraction domains
- **Scope:** complete the backup exclusions.
- **Files:** `app/src/main/res/xml/data_extraction_rules.xml`
- **Issue IDs:** WA-038
- **Build status:** **PASS** — `aapt2 compile` exit 0 (38 files) and `aapt2 link` exit 0
  (885 265 bytes), against the real manifest
- **Test status:** n/a — declarative
- **Runtime status:** the framework parses the domains at backup time; not exercised
- **Device status:** not verified
- **Evidence level:** **VERIFIED** for XML validity and resource linking; **NOT VERIFIED**
  for the `domain` enum values
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** `aapt2` provably does not validate domain names — a nonsense domain
  also links at exit 0 — and the SDK does not ship the enum. Residual risk if a name were
  wrong is a **backup-time** failure, not a launch failure. See
  `FINAL-AAPT2-INVESTIGATION.md`.
- **Also:** this PR **includes a fix for a defect the audit itself introduced** — an XML
  comment containing `--`, which failed `aapt2 compile` before it was corrected. Do not
  offer the earlier form of this file.

### PR-8 — CI coverage (proposed, not applied)
- **Scope:** run the 336 `aether` unit tests; compile `tun.rs` under `cargo ndk clippy`.
- **Files:** `.github/workflows/ci.yml` (2 lines)
- **Issue IDs:** WA-019, WA-021
- **Build status:** n/a — workflow not executed
- **Test status:** not run — **expect some of the 336 to fail on first execution; that is
  the point, not a regression**
- **Runtime status:** n/a
- **Device status:** n/a
- **Evidence level:** **NOT VERIFIED** — no CI run happened
- **Readiness:** **READY FOR REVIEW WITH EXPLICIT VERIFICATION LIMITATION**
- **Blocking reason:** cannot be validated without running the workflow. Needs no new
  infrastructure, which is why it is proposed rather than deferred.

---

## Proposed, NOT applied — `MAINTAINER-ACTION REQUIRED`

| ID | Finding | Can it be fixed without maintainer infrastructure? | Can provenance be independently established? | Can verification metadata come from trusted material? |
| --- | --- | --- | --- | --- |
| **WA-003** | `ca.psiphon:psiphontunnel:2.0.41` resolved from a **mutable `master` branch**, no `gradle/verification-metadata.xml`. A ~44 MB prebuilt Go `.so` that becomes the Psiphon tunnel core. | **No.** Requires resolving the dependency graph to produce correct metadata. | **No.** `master` is mutable; the artifact's origin cannot be pinned after the fact. | **No.** Google Maven is unreachable from the audit machine, so the metadata cannot be generated here. **No checksum was invented, no artifact re-hosted, no mirror substituted.** |
| **WA-004** | 11 GitHub Actions on floating tags, alongside `contents: write` and `pages: write` + `id-token: write` | **No.** Digests must be read and approved by a human. | n/a | n/a |
| **WA-005** | `go mod tidy` + `-mod=mod` re-resolves the exit chain's transitive Go graph on every build | **No.** Needs `native/chain/setup.ps1` run once — PowerShell, network, and the pinned FlClash revision. | **No.** The resolved graph is re-fetched, so today's pin does not describe tomorrow's binary. | n/a |
| **WA-006** | Gradle distribution fetched with no `distributionSha256Sum` | **No.** The digest must be read from Gradle's published checksums by a person. | n/a | n/a |

**All four: `MAINTAINER-ACTION REQUIRED`.** None was "fixed" to make the project look more
secure. Nothing was pinned to an arbitrary commit to silence a warning.

---

## Explicitly not to be sent upstream

| Change | Why |
|---|---|
| Bounding `sessionJob.join()` (WA-010) | The obvious fix lets a dying session's `stop()` kill its successor. Needs a design decision and a device. |
| `hopStages` concurrency (WA-008) | Needs a device to demonstrate. |
| Main-thread teardown (WA-009) | Right idea, real risk to shutdown ordering, unmeasurable here. |
| `panic = "abort"` → `unwind` (WA-017) | Measurably grows a hot-path `.so`; maintainer's trade-off. |
| Consolidating the two update systems (WA-032) | Changes which screens offer an update. |
| Anything under `native/aether/` | Vendored; upstream licence requires notices preserved. |
| Removing the four `device_*` exclusions | Valid syntax; removing valid functionality to silence an unverified warning is the wrong trade. If a maintainer disagrees, the removal is four lines in each block. |

---

## Summary

| PR | Evidence | Readiness |
| --- | --- | --- |
| PR-1 Native stop-channel | VERIFIED | **READY** |
| PR-6 Documentation | VERIFIED | **READY** |
| PR-2 IPv6 redaction | VERIFIED (module) / CODE-REVIEW VERIFIED (call site) | READY WITH LIMITATION |
| PR-3 Kill-switch | CODE-REVIEW VERIFIED | READY WITH LIMITATION — **device required** |
| PR-4 Backup/widget reliability | CODE-REVIEW VERIFIED | READY WITH LIMITATION |
| PR-5 Release logging privacy | CODE-REVIEW VERIFIED | READY WITH LIMITATION |
| PR-7 Backup extraction domains | VERIFIED (XML) / NOT VERIFIED (domain enum) | READY WITH LIMITATION |
| PR-8 CI coverage | NOT VERIFIED | READY WITH LIMITATION |

**2 READY · 6 READY WITH EXPLICIT VERIFICATION LIMITATION · 0 NOT READY · 4 maintainer-action items.**

No PR was opened. No repository, fork or remote branch was created. No URL was fabricated.