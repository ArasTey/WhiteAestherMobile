# FINAL-STATUS

# Final Status

## Repository Understanding
**COMPLETE**

Every top-level document, both Gradle scripts, the manifest and all network-security
resources, all ~55 production Kotlin files, all 52 test files, the full native JNI
bridge, the vendored engine's build files, and all five native PowerShell scripts were
read. Seven parallel deep-dive audits covered the service layer, core/data, the native
bridge, UI/UX, security, CI/release, and tests/dependencies. Every High-severity finding
was re-read first-hand before any change was made.

## Build
**PARTIAL**

Rust compiles, tests and lints clean. **The Android build was never run** — Google Maven
is unreachable from this environment, so AGP 9.4.0 cannot resolve and no Gradle task
configures. Independently, NDK `29.0.14206865`, CMake 3.22.1, `cargo-ndk` and the
Android Rust targets are all absent. No APK was produced. See `01-BASELINE.md` §2.

## Tests
**PARTIAL**

The one runnable suite passes: `native/android-bridge` 10/10, clippy 0. It went from
9/9 at baseline with one test added, and the new test was **proven to fail against the
original code**. 275 Kotlin tests and 93 instrumentation tests **could not be run**.
Separately, 429 tests never execute on any push in CI, for two structural reasons
documented in `06-TESTING-AUDIT.md` §2.

## Security
**AUDITED** (statically)

Exported components, permissions, backup policy, network security config, TLS handling,
secrets, the update trust chain, and native FFI were all reviewed. Nine findings
confirmed, one fix applied (WA-023), one Medium fix deferred with reasoning (WA-015).
**No Critical finding. One High finding is unchanged and is a maintainer decision**
(WA-003, the Psiphon artefact's mutable source ref).

## Performance
**AUDITED** (structurally — no device, no profiler)

Two Medium findings (main-thread teardown; unbounded join) are documented and unchanged.
One counter-proven in detail (Home recomposition). The bounded-work audit found the
project's retry, backoff and log-bounding discipline to be in good shape.

## UX
**AUDITED** (statically — no device, no rendered build)

One fix applied (WA-016, the unreachable recovery control). Thirteen further findings
with exact implementation locations, all proposed upstream. Contrast ratios were
**computed** from the literal palettes; the six failures are exact, not sampled.

## Compatibility
**PARTIAL**

SDK/ABI configuration and platform-behaviour code were verified statically and are
internally consistent: FGS type on API 34+, edge-to-edge, `FLAG_IMMUTABLE` on every
`PendingIntent`, `RECEIVER_NOT_EXPORTED`, sticky-restart handling, and the
compile-37/target-36 split.

**No device, no emulator, no OEM, no restrictive network.** Everything about real-world
behaviour is **NOT VERIFIED**, and `10-COMPATIBILITY-AUDIT.md` says so per item.

## CI/CD
**AUDITED**

Three workflows and a composite action read in full. The permission model, the absence
of `pull_request_target`, the tag-tree build, the R8 name check and the `SHA256SUMS`
coverage were all verified as correct. **Four High/Medium supply-chain findings are
unchanged**, each proposed as a separate PR in `UPSTREAM-PR-PLAN.md`. No workflow was
executed.

## Documentation
**UPDATED**

Six defects found and corrected. The two that mattered: the README named Rust 1.88.0 and
Android SDK 36 while the project pins 1.98.0 and `compileSdk = 37`, so a new developer
following it installs the wrong toolchain and the build fails on both. And
`docs/RELEASE.md` described continuous main-branch prereleases that `ci.yml` explicitly
removed.

## Upstream Readiness
**PARTIAL**

Eight fixes are implemented and documented well enough to be offered as focused PRs.
**Two of them must be verified before being offered** — PR-2 needs a device, PR-3 needs
Gradle. Ten further PRs are proposed but not implemented, because each is a maintainer
decision rather than a defect.

---

## The honest summary

This is a **well-engineered project** whose documentation outruns most of its peers.
Code comments explain what was tried and what it cost, usually citing the bug that
motivated the current shape. Retry and backoff are bounded everywhere. TLS handling —
specifically the `SSLSocket` layered over a carried connection, where Java validates the
chain but not the identity — is correct and the comment says why. The update path
defeats path traversal, TOCTOU and signer substitution in a way most projects never
attempt. The exported-component surface is minimal and correctly permissioned. The
`generation` mechanism that stops a dying session overwriting a newer one's state is
subtle and correct.

The findings are the ordinary kind. One genuinely severe defect in the native stop path.
One that makes the kill switch lie to a user in a way that matters exactly when they need
it to be truthful. Supply-chain pinning that is good everywhere except the one artefact
where it is most load-bearing. A test suite that is well-chosen where it exists and
quietly not running at all in two places. Documentation numbers that drifted when a pin
moved.

**Nine fixes, one proven before and after, one proposed fix killed by its own test, and
the largest single finding — that the Kotlin was never compiled — stated plainly rather
than worked around.**

## Verification statement

| Claim | Status |
| --- | --- |
| Rust suite 10/10, clippy clean | **VERIFIED** — executed |
| WA-001 regression test fails against the original code | **VERIFIED** — executed both ways |
| WA-024 redaction logic, 16 assertions | **VERIFIED** — executed against `java.util.regex` |
| WA-024 Kotlin test compiles and passes under Gradle | **NOT VERIFIED** — never run |
| The nine Kotlin fixes compile | **NOT VERIFIED** — never built |
| WA-002, WA-007 on a device | **NOT VERIFIED** — no hardware |
| Zero newly introduced failures in any runnable suite | **VERIFIED** |
| Zero newly introduced failures in the Kotlin build | **NOT VERIFIED** — no baseline exists to compare against |
| Real connectivity, battery, OEM behaviour | **NOT VERIFIED** — not attempted |
| No runtime regression on any Android device | **NOT CLAIMED** — explicitly out of reach here |

## Location of the handoff

**`docs/ai-audit/AI-HANDOFF.md`** — read this next.
`docs/ai-audit/MASTER-ISSUES.md` has all 86 findings with IDs.
`docs/ai-audit/01-BASELINE.md` §2.1 explains why the Android build could not run.