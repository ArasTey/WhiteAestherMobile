# IMPLEMENTATION-PLAN

The plan as it was formed, with the dependency reasoning stated — including where the
order differs from the obvious one.

---

## Phase 0 — Repository understanding — **COMPLETE**

Clone, map, read. Every top-level document, both build scripts, `settings.gradle.kts`,
`gradle.properties`, the Gradle wrapper, all four `.github/` files, the manifest, the
native bridge, and the vendored engine's build files. Seven parallel deep-dive audits
across the Android service layer, the core/data layer, the native JNI bridge, UI/UX,
security, CI/release, and tests/dependencies.

**Output:** `00-PROJECT-MAP.md`, `MASTER-ISSUES.md` (86 entries).

**Dependency:** none. Everything after this depends on it.

## Phase 1 — Baseline and reproducibility — **COMPLETE, and it changed the plan**

Establish what can actually be run *before* changing anything, so that "it worked before"
is a fact rather than an assumption.

**Finding that reordered everything after it:** Google Maven is unreachable from this
environment, so AGP cannot resolve and **no Gradle task can configure**. Consequences:

- No Kotlin build, no Kotlin tests, no lint, no APK.
- Kotlin changes can be reviewed but **not executed**.
- The Rust suite *is* runnable — but only after installing `cmake`, because `boring-sys`
  builds BoringSSL with CMake.

**Output:** `01-BASELINE.md`.

## Phase 2 — Critical correctness fixes — **COMPLETE**

Ordered by *risk of the fix being wrong*, not by severity of the bug.

1. **WA-001** (native stop-channel disarm) — the only finding with a demonstrable,
   self-contained regression test. Done first so that a working test-runner loop
   existed before anything else was touched.
2. **WA-002** (kill switch stops blocking) — one line, restores the invariant that the
   held descriptor describes the interface that exists.
3. **WA-007** (`establishTun` exception safety) — makes the method consistent with every
   other failure path in the same file.

**Why this order.** WA-002 is the worse bug for a user; WA-001 is the one that could be
*proven*. Building the proof first means the remaining changes are made against a
verified baseline rather than an assumed one.

**Dependency:** Phase 1's Rust runner. Nothing else depends on this phase.

## Phase 3 — Reliability, privacy and hardening — **COMPLETE**

4. **WA-014** — DataStore corruption handlers on all three stores.
5. **WA-037** — `runCatching` in the exported widget receiver.
6. **WA-038** — complete the extraction rules.
7. **WA-023** — route all eight carrier logcat writes through one debug-only helper.
8. **WA-079/080/081/083/084/085** — documentation corrections.

**Why WA-023 needed extracting rather than gating in place.** The rule already existed in
`EngineLog.kt:58` and was simply not applied at eight sites in three files. Adding
`if (BuildConfig.DEBUG)` eight times would have left the same eight chances to forget it
next time; one helper makes the rule a single decision.

**Dependency:** none on Phase 2. These were done as a batch because each is independent
and none is harder to verify than any other.

## Phase 4 — Correctness in UI-visible behaviour — **COMPLETE**

9. **WA-016** — the unreachable "Lift the block" card.
10. **WA-024** — bare-IPv6 redaction, plus the extraction to `DiagnosticsRedaction` that
    makes it testable, plus `DiagnosticsRedactionTest`.

**Dependency:** WA-024's extraction was a *consequence* of wanting a regression test.
A private file-level function cannot be tested, and a redaction rule that stops matching
fails nothing — the report still builds, previews and sends. So the test required the
extraction, rather than the extraction being tidiness.

**Verification substitute:** because Gradle is blocked, both Kotlin fixes had their pure
logic checked against `java.util.regex` and a Java transliteration — the engine
`kotlin.text.Regex` delegates to. 16/16 assertions passed for the redaction rules.

## Phase 5 — Rejected changes — **COMPLETE**

11. **WA-031 was implemented, tested, and reverted.** The regression test was run against
    both the old and new implementations; they agreed on every realistic input, so the
    test would have passed before and after — which by definition proves nothing.

**Why this is a phase and not a footnote.** Rejecting a change is work, and an audit that
only lists what it did is misleading about how much was considered. The evidence is in
`REJECTED-IDEAS.md` §1.

## Phase 6 — Documentation — **COMPLETE**

The ten audit reports, this plan, the priority matrix, the rejected-ideas record, the
upstream PR plan, the phase reports, final verification, final status, the handoff, and
the changelog.

**Dependency:** all of the above. Documentation written before the fixes would have been
wrong about which fixes landed.

## Phase 7 — Final verification — **COMPLETE**

Re-run everything runnable, and compare against `01-BASELINE.md`.

---

## Phases that were planned and did NOT run

Recorded so a later reader does not assume they were attempted.

| Phase | Why not |
| --- | --- |
| Kotlin unit tests, lint, APK assembly | Google Maven unreachable; NDK 29 absent; cargo-ndk absent; no Android Rust targets |
| Instrumentation tests | No device, no emulator, and no CI job either |
| Any device verification of WA-002, WA-007, WA-011, WA-040 | No hardware |
| Supply-chain fixes (WA-003, WA-004, WA-005, WA-006) | Maintainer decisions that are worse done blind than left for them — see `PRIORITY-MATRIX.md` |
| Architecture changes | Rejected with reasoning — `05-ARCHITECTURE-AUDIT.md` §4 |
| UX/accessibility work | Real, but each item needs to be *seen*; proposed upstream instead |

---

## Ordering summary

The non-obvious dependencies, stated plainly:

- **Baseline before changes**, always — otherwise "still passes" means nothing.
- **The provable fix before the severe one.** WA-001 was not the worst bug, but it was
  the only one whose correctness could be demonstrated, and having a working test loop
  first made every later change cheaper to check.
- **Extraction follows testability, not tidiness.** `DiagnosticsRedaction` and
  `CarrierLog` exist because a test and a shared rule demanded them. No other
  extraction was performed.
- **Documentation last**, because it must describe what actually landed — which
  included one change being reverted.