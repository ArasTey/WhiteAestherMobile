# FINAL-VERIFICATION

Baseline (`01-BASELINE.md`) versus the state of the working tree after all phases.
Every line below is a command that was run and its actual output.

---

## Baseline

| Check | Command | Result |
| --- | --- | --- |
| Rust unit tests | `cargo test --locked` (in `native/android-bridge`) | **9 passed, 0 failed** |
| Rust lints | `cargo clippy --locked --all-targets` | **0 warnings** |
| Gradle configure | `./gradlew testStableDebugUnitTest` | **FAILED** — `com.android.application:9.4.0` unresolvable; Google Maven returns 404 for every path including long-existing AGP 8.7.3, while Maven Central returns 200 |
| Kotlin tests | — | **NOT RUN** — Gradle blocked |
| Lint | — | **NOT RUN** — Gradle blocked |
| APK assembly | — | **NOT ATTEMPTED** — Gradle blocked, NDK 29 absent, cargo-ndk absent, no Android Rust targets |
| Instrumentation | — | **NOT RUN** — no device; no CI job either |

## Final

| Check | Command | Result |
| --- | --- | --- |
| Rust unit tests | `cargo test --locked` | **10 passed, 0 failed** |
| Rust lints | `cargo clippy --locked --all-targets` | **0 warnings** |
| Redaction logic | `java DiagnosticsRedactionTest` (16 assertions, Java mirror of the Kotlin test) | **16/16 PASS** |
| Version-comparison logic | `java VersionParts` (both implementations) | old ≡ new on every realistic input → **fix reverted** |
| Gradle / Kotlin | — | **STILL NOT RUN** — unchanged environmental block |
| Device | — | **STILL NOT RUN** |

## Improvements

| Suite | Baseline | Final | Change |
| --- | --- | --- | --- |
| `native/android-bridge` unit tests | 9 | **10** | +1 — `refusing_a_second_run_leaves_the_first_runnable` |
| `native/android-bridge` failures | 0 | **0** | — |
| clippy warnings | 0 | **0** | — |
| Verified Kotlin assertions | 0 | **16** | out-of-band, Java mirror |
| Documentation defects | 6 | **0** | all corrected |
| Registered issues | 0 | **86** | `MASTER-ISSUES.md` |
| Fixes applied | 0 | **9** | WA-001, 002, 007, 014, 016, 023, 024, 037, 038 |
| Fixes attempted and rejected | 0 | **1** | WA-031 — evidence in `phases/PHASE-05.md` |

### The one improvement that is genuinely *proven*

`refusing_a_second_run_leaves_the_first_runnable` was **run against the original code**
by temporarily restoring the unconditional `STOP_SENDER.lock().take()`:

```
test result: FAILED. 9 passed; 1 failed
    tests::refusing_a_second_run_leaves_the_first_runnable
```

and against the fix:

```
test result: ok. 10 passed; 0 failed
```

A test that passes both before and after proves nothing; this one demonstrably does not.

### The improvement that was proven *absent*

The version-comparison fix. A harness ran both implementations over six cases plus one
constructed to distinguish them. The old and new code agreed on all six realistic inputs;
only `2.99999999999999999999` vs `1.99999999999999999999` differed. Fix and test were
removed. `git diff` confirms `AppUpdate.kt` and `AppUpdatePolicyTest.kt` carry **no change
from that work**.

## Remaining failures

**None in any suite that runs.**

## Pre-existing failures

**None observed.** The only runnable suite passed at baseline and passes now.

## Newly introduced failures

**ZERO.** Stated explicitly, as required, and checked three ways:

1. **Execution.** The runnable suite went 9/9 → 10/10 with no failure.
2. **Diff scope.** `git diff --stat` = 15 files, +190/−33. No Kotlin file outside the
   nine intended ones; no build file; no CI file except the dependabot comment.
3. **Content hygiene.** `git diff | grep -nE '^\+.*(TODO|FIXME|XXX|println|api[_-]?key|password\s*=\s*")'`
   → **none**.

Nothing that this audit could run regressed, because the only suite it could run is
unchanged apart from the added test.

## The honest caveat

**Zero newly introduced failures in the Kotlin build is NOT a verified claim.** The Kotlin
code was never compiled. What can be said:

- Each change was reviewed against the file's existing idiom and typing.
- `establishTun` was checked for early `return`s before being wrapped in `runCatching`,
  because `runCatching` is inline and a non-local return would bypass `.onFailure`.
- `PluggableTransport`'s `when` was confirmed to be a statement, so mixing `Unit` and
  `Int` branches is legal.
- All new imports were added to the files that need them.
- `data_extraction_rules.xml` is well-formed with both blocks closed.

What cannot be said: that `./gradlew testStableDebugUnitTest` passes. **It has never been
run.** That is the single most important thing a maintainer must do before merging PR-3.

## Changes by file

```
 .github/dependabot.yml                             |  5 +-    (WA-079 comment)
 README.md                                          |  9 ++-    (WA-080, WA-081)
 design/PORT-STATUS.md                              |  3 +-    (WA-085)
 docs/RELEASE.md                                    | 12 ++--   (WA-083, WA-084)
 native/android-bridge/src/lib.rs                   | 80 ++++   (WA-001 + test)
 .../data/AddressReporter.kt                        |  9 ++     (WA-014)
 .../data/SettingsRepository.kt                     | 10 ++     (WA-014)
 .../data/UpdateChecker.kt                          |  9 ++     (WA-014)
 .../service/AetherVpnService.kt                    | 20 ++-    (WA-002, WA-007)
 .../service/AetherWidgetProvider.kt                | 10 ++-    (WA-037)
 .../service/PluggableTransport.kt                  |  4 +-     (WA-023)
 .../service/PsiphonService.kt                      |  4 +-     (WA-023)
 .../service/TorCarrierService.kt                   |  8 +-     (WA-023)
 .../ui/Screens.kt                                  | 20 ++--   (WA-016, WA-024)
 app/src/main/res/xml/data_extraction_rules.xml    | 20 ++     (WA-038)

 NEW  app/src/main/java/.../data/DiagnosticsRedaction.kt            (WA-024)
 NEW  app/src/main/java/.../service/CarrierLog.kt                  (WA-023)
 NEW  app/src/test/java/.../data/DiagnosticsRedactionTest.kt       (WA-024)
 NEW  docs/ai-audit/**                                              (this audit)
```

## How to re-run this verification

```bash
# Rust — works today
cd native/android-bridge
export PATH="/opt/homebrew/bin:$PATH"     # cmake lives here
cargo test --locked
cargo clippy --locked --all-targets

# Kotlin — needs a network that can reach Google Maven, and
# ANDROID_HOME (or a local.properties with sdk.dir)
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew testStableDebugUnitTest
./gradlew assembleStableDebug          # needs NDK 29 + cargo-ndk + Android Rust targets

# Redaction expectations, without Gradle
cd /tmp/probe && javac DiagnosticsRedactionTest.java && java DiagnosticsRedactionTest
```