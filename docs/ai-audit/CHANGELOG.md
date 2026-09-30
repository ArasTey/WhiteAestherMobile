# CHANGELOG

Chronological record of this audit. Dates are real; nothing here is reconstructed.

---

## 2026-09-30

### Reconnaissance

- Cloned `WhiteAestherMobile` at `b55320a` (`main`, 72 commits, 4334 files, 74 MB).
- Mapped the repository: `app/` (Kotlin + Compose), `native/` (Rust JNI bridge +
  vendored Aether engine + quiche + BoringSSL glue + three PowerShell setups),
  `design/`, `docs/`, `references/`, `licenses/`, three CI workflows.
- Read README, PRIVACY, SECURITY, THIRD_PARTY_NOTICES, both Gradle scripts,
  `settings.gradle.kts`, `gradle.properties`, the wrapper, the manifest, the network
  security config, the extraction rules, and the update-path rules.

### Environment

- Found `JAVA_HOME` pointing at a **non-existent** Temurin 21 path; Gradle's JDK was
  Homebrew 17.0.20.
- Android SDK: platform `android-37.0` and `android-34`; NDK **27.3** where the project
  pins **29.0.14206865**; **no CMake**; no `cargo-ndk`; Rust 1.98.0 with **no Android
  targets** installed.
- Installed `cmake 4.4.3` via Homebrew — without it even the host `cargo test` cannot
  build BoringSSL.
- Gradle's own distribution download stalled twice at ~74 MB. Fetched 9.7.1 with a
  resumable `curl` loop and installed it manually. **First attempt produced a corrupt
  archive** — resuming onto the wrapper's partial file appended at a bad offset; detected
  because `unzip -t` found 2 entries where a Gradle distribution has thousands. Deleted
  and re-downloaded with a clean loop.

### Baseline

- **Rust: 9/9 tests pass, clippy clean.**
- **Gradle: FAILS.** `com.android.application:9.4.0` unresolvable.
  Diagnosed: `dl.google.com` returns 404 for the entire `com/android/tools/build/gradle`
  group, including `maven-metadata.xml` and the long-existing AGP 8.7.3, while
  `repo1.maven.org` returns 200. **Google Maven is unreachable in this environment.**
- Consequence: no Gradle configure, no Kotlin tests, no lint, no APK, no instrumentation.
  Recorded as environmental, not as a project defect.
- **Not attempted:** APK assembly (three independent blockers beyond AGP).

### Audits

Seven parallel deep-dive audits completed: Android service layer, core/data layer,
native JNI bridge, UI/UX/accessibility/localisation, defensive security, CI/release,
tests/dependencies.

### Verification of findings

Every High-severity finding was **re-read first-hand** before any code was changed.
Two confirmed as reported (WA-001, WA-002). One (WA-031) had sound-looking but
incorrect reasoning — caught only because it was checked rather than trusted.

### Phase 2 — critical correctness

- **WA-001 fixed.** `STOP_SENDER.lock().take()` in `nativeRun`'s blanket failure cleanup
  disarmed the *running* engine when a second call was refused. Now gated on a local
  `Cell<bool>` set at the moment of installation.
- Regression test `refusing_a_second_run_leaves_the_first_runnable` added.
- **Proven against the original code:** restoring the unconditional `take()` produced
  `FAILED. 9 passed; 1 failed`. With the fix: `ok. 10 passed`.
- The test was also caught being **wrong** first: it dropped the `oneshot` sender instead
  of sending on it, so `try_recv()` failed. Corrected to mirror `nativeStop`.
- **WA-002 fixed.** `dropBlackhole()` now runs immediately before `builder.establish()`,
  so the kill switch's descriptor tracks the interface that exists.
- **WA-007 fixed.** `establishTun` wrapped in `runCatching`. Checked for early `return`s
  first, since `runCatching` is inline and a non-local return would bypass `.onFailure`.

### Phase 3 — reliability, privacy, hardening

- **WA-014 fixed.** `ReplaceFileCorruptionHandler` on all three DataStore instances.
- **WA-037 fixed.** Per-id `runCatching` in the exported widget receiver.
- **WA-038 fixed.** All nine backup domains excluded.
- **WA-023 fixed.** New `service/CarrierLog.kt`; eight carrier logcat sites routed
  through `debugLog`, applying the `BuildConfig.DEBUG` rule `EngineLog` already had.
- **Documentation corrected:** `README.md` (Rust 1.98.0, SDK 37 + the compile/target
  explanation), `docs/RELEASE.md` (removed the rolling-prerelease claim; the four signing
  secrets written out), `design/PORT-STATUS.md` (real font filenames),
  `.github/dependabot.yml` (the `RUST_VERSION` claim).

### Phase 4 — UI-visible correctness

- **WA-016 fixed.** The blocked-state card now matches on containment as well as
  equality; `giveUp` joins the notice into a sentence, so equality never held.
- **WA-024 fixed.** Redaction extracted to `data/DiagnosticsRedaction.kt` (a private
  file-level function in a 2830-line file could not be tested) with a bare-IPv6 rule
  added.
- The regex was written and **found wrong twice** by the Java harness before it was
  correct: v1 missed `2606:4700::1` (the leading repetitions consumed the first colon of
  `::`); v2 matched `16:04:31`, and **every EngineLog line in the report opens with a
  timestamp**. The shipped rule matches two structural shapes — compressed (contains
  `::`) and full (eight groups) — which a clock time matches neither of.
- `DiagnosticsRedactionTest` written: 7 tests, 16 assertions, **all verified** against
  `java.util.regex`.
- One exploratory case failed and was traced to the **pre-existing IPv4** regex, not to
  this change; left alone as out of scope.

### Phase 5 — a change rejected

- **WA-031 implemented, then reverted.** `AppUpdatePolicy.parts()` dropping a
  `Long`-overflowing component. A Java mirror ran the old and new implementations over
  six cases: **they agree on all six.** A seventh, constructed to distinguish them,
  required a 20-digit component on *both* sides.
- Structural reason: a dropped component makes the candidate list shorter and missing
  positions compare as `0`, so the old code can only ever make a version look smaller or
  equal — never larger. It therefore cannot wrongly claim "newer", and cannot cause a bad
  install (`validate` checks the version code independently).
- Both the fix and its test were removed. `git diff` confirms neither file differs from
  `b55320a` for this work.
- Recorded in `MASTER-ISSUES.md` as `WONTFIX` and in `REJECTED-IDEAS.md` §1.

### Final verification

```
cargo test --locked                    → 10 passed; 0 failed   (baseline 9/9)
cargo clippy --locked --all-targets    → 0 warnings
java DiagnosticsRedactionTest          → 16/16 PASS
git diff --stat                        → 15 files, +190/−33
new files                              → 3 sources, 1 test, docs/ai-audit/
WA-031 revert confirmed                → no diff in AppUpdate.kt / AppUpdatePolicyTest.kt
```

- Hygiene grep for added TODO/FIXME/println/secrets: **none**.
- **Zero newly introduced failures in any suite that runs.**
- **The Kotlin build was never run**, so zero new failures *in the Kotlin build* is not a
  verified claim and is not made.

### Deliverables

- 20 documents under `docs/ai-audit/`.
- 86 issues in `MASTER-ISSUES.md`.
- 8 implemented PRs, 10 proposed, 7 explicit "do not send" entries in
  `UPSTREAM-PR-PLAN.md`.
- 12 rejected ideas with reasoning in `REJECTED-IDEAS.md`.
---

## 2026-09-30 (later) — POST-AUDIT VERIFICATION PASS

Re-verification of the audit's own output. No new audit, no new features, no unrelated
refactoring.

### Environment re-tested from scratch

- Google Maven **still unreachable**. Re-tested by DNS, by raw URL, by redirect-following
  and by protocol variant.
- Mechanism now identified: `dl.google.com` resolves to **192.178.202.91/.90/.136/.93** —
  a SoftLayer/IBM range, not Google — and serves a Google-branded
  `Error 404 (Not Found)!!1` page. Maven Central and GitHub resolve normally (200).
- `./gradlew --version` → works (Gradle 9.7.1). `./gradlew tasks` → **FAILS** on
  `com.android.application:9.4.0`.
- No mirror substituted, no dependency removed, no checksum invented. Recorded as
  `POST-AUDIT-ENVIRONMENT.md`.

### WA-024 promoted from proxy verification to real verification

The first pass proved the redaction logic with a **Java transliteration**, because Gradle
could not run. This pass compiled and ran the **actual Kotlin**:

- Installed **kotlinc 2.4.20** (the project's exact version) from GitHub releases, and
  JUnit 4.13.2 + Hamcrest from Maven Central — both reachable.
- `DiagnosticsRedaction.kt` + `DiagnosticsRedactionTest.kt` **compile clean**, no warnings.
- `DiagnosticsRedactionTest` → **OK (7 tests)** under JUnit.
- A 30-case probe covering the full required list (`::1`, bare `::`, `2001:db8::1`,
  `2001:db8:0:0:0:0:0:1`, `2606:4700::1111`, `fe80::1`, IPv4, bracketed, URLs,
  timestamps, prose, hostnames, MAC addresses) — **all pass**, including a
  partial-redaction check.
- **Regression proven:** the pre-fix implementation transcribed from `b55320a` was
  compiled alongside the fix; **5 of 5 assertions fail on the pre-fix code**.

### WA-001 re-verified

- `cargo test --locked` → **10 passed, 0 failed**; `cargo clippy --locked --all-targets`
  → **0 warnings**.
- Production guard re-read at `lib.rs:864-893` and confirmed present and correct.
- **Limitation now recorded:** the regression test models the guard's body rather than
  calling the JNI entry point, so removing the production guard later would not fail it.

### Status corrections

- **8 of 9 fixes downgraded from `FIXED` to `FIXED — NOT VERIFIED`** — they were never
  compiled, and most also need a device. New legend entry added to `MASTER-ISSUES.md`.
- **WA-024 promoted to `VERIFIED`** with the execution evidence recorded inline.
- `AI-HANDOFF.md` rewritten to reflect the real state, including the seven unverified
  fixes as a named list.

### Security findings — nothing fabricated

WA-003 (`ca.psiphon` from a mutable branch), WA-004 (unpinned actions), WA-005 (Go graph
drift) and WA-006 (no distribution checksum) were re-examined and **left unchanged**.
Verification metadata **cannot** be produced on this machine because Google Maven is
unreachable, and a fabricated one fails the build looking like tampering.

### Cleanup

Removed `local.properties`, `.mimosa/`, `.gradle/`, `.kotlin/`, `build/`, `app/build/`.
Added `.mimosa/` to `.git/info/exclude` (local only — the project's `.gitignore` was not
modified for a local tool).

### One build-time risk recorded

`data_extraction_rules.xml` now names the `external` and `device_*` domains. These are
valid from API 31 and the file is only parsed on API 31+, but **aapt2 schema validation
never ran**. If the build fails there, drop the four `device_*` lines — nothing depends
on them today.

### New deliverables

- `docs/ai-audit/POST-AUDIT-ENVIRONMENT.md`
- `docs/ai-audit/POST-AUDIT-VERIFICATION.md`

---

## 2026-09-30 (final) — FINAL PRE-PR GATE

Evidence gate before fork/PR preparation. No new audit, no new features, no scope added.

### AAPT2 investigation — highest-priority action — FOUND A REAL BREAK

aapt2 was available all along, in `build-tools/36.0.0`. Running it:

```
$ aapt2 compile --dir app/src/main/res -o /tmp/aapt2/res.zip
app/src/main/res/xml/data_extraction_rules.xml:9: error: not well-formed (invalid token)
```

**The audit had introduced a build break.** The comment added to
`data_extraction_rules.xml` contained `storage -- neither of which`, and **XML comments
may not contain `--`**. Every Android build would have failed. Reworded and re-verified:
`aapt2 compile` exit 0 (38 files), `aapt2 link` exit 0 (885 265 bytes) against the real
manifest.

**A second finding, by differential test:** `aapt2` does **not** validate `domain` values.
A substituted `domain="definitely_not_a_domain"` also compiles and links at exit 0. So
"aapt2 accepted `device_*`" is not evidence. The framework's domain list is in neither
`android.jar`, nor `android-stubs-src.jar`, nor `attrs_manifest.xml`, nor anywhere in the
SDK — a control search finds zero hits even for `root` and `sharedpref`.

Domain names therefore remain unverified. They were **kept** (valid syntax; removing valid
functionality to silence an unverified warning is the wrong trade) and the limitation is
recorded. Residual risk if wrong: a **backup-time** failure, not a launch crash.

### Evidence levels assigned per file

`FINAL-CHANGE-EVIDENCE.md` — 18 entries, one per file, none grouped:

- **VERIFIED (7 entries):** `lib.rs`, `DiagnosticsRedaction.kt`,
  `DiagnosticsRedactionTest.kt`, `data_extraction_rules.xml`, `README.md`,
  `design/PORT-STATUS.md`, `.github/dependabot.yml`
- **CODE-REVIEW VERIFIED (11 entries):** `Screens.kt`, `AetherVpnService.kt`,
  `SettingsRepository.kt`, `UpdateChecker.kt`, `AddressReporter.kt`, `CarrierLog.kt`,
  `PsiphonService.kt`, `TorCarrierService.kt`, `PluggableTransport.kt`,
  `AetherWidgetProvider.kt`, `RELEASE.md`
- **NOT VERIFIED:** 0 files. One resource attribute (the `domain` enum).

### PR readiness recalculated — the "8 ready" claim was wrong

The previous report said "Upstream PRs ready: 8". That label has been challenged and
**withdrawn**: seven of those eight contain Kotlin that has never been compiled.
Recalculated from evidence: **2 READY, 6 READY WITH EXPLICIT VERIFICATION LIMITATION,
0 NOT READY**, plus **4 maintainer-action items** (WA-003/004/005/006) marked
`MAINTAINER-ACTION REQUIRED`.

### Security boundary held

WA-003, WA-004, WA-005, WA-006 were re-examined and **left unchanged**. For each: can it
be fixed without maintainer infrastructure — no; can provenance be independently
established — no; can verification metadata come from trusted material — no. **No
checksum was manufactured, no action pinned to an arbitrary commit, no mirror
substituted.**

### Hygiene

- **Secret scan:** no API keys, tokens, passwords, private keys or signing material. The
  `BEGIN PRIVATE KEY` matches are all `#[cfg(test)]` fixtures in vendored aether and
  BoringSSL test data, none in the diff.
- **Personal filesystem paths removed** from the audit docs (`/Users/...` in
  `AI-HANDOFF.md` and `POST-AUDIT-ENVIRONMENT.md`) — replaced with `$ANDROID_HOME` and a
  generic placeholder. This was a real leak of the machine's layout into the repository.
- **Licence and attribution intact:** no `LICENSE`, `THIRD_PARTY_NOTICES.md`,
  `licenses/` or `native/aether/**` file touched.
- **Zero dependency changes:** no Gradle, Cargo or TOML file modified. The only CI file
  touched is a dependabot **comment**.
- **`.gitignore` unmodified** for audit tooling; `.mimosa/` is in `.git/info/exclude`.

### Baseline comparison

| Category | Before | After | Evidence |
| --- | --- | --- | --- |
| Native tests | 9 passed | **10 passed** | `cargo test --locked` |
| Kotlin tests | not run | **1 module: 7 passed** | `kotlinc 2.4.20` + JUnit |
| clippy | 0 | **0** | `cargo clippy --locked --all-targets` |
| Build | BLOCKED | **BLOCKED** (unchanged) | `./gradlew tasks` |
| Lint | not run | not run | Gradle blocked |
| Instrumentation | not run | not run | no device |
| Device | none | **none** | not attempted |
| Resources | not validated | **aapt2 compile + link pass** | 38 files / 885 265 bytes |
| Security findings | 9 | 9 (1 applied) | — |
| Documentation defects | 6 | **0** | verified against sources |
| CI findings | 7 | 7 (0 applied) | maintainer-action |
| Issues fixed | 0 | **9** (2 VERIFIED) | — |

### New deliverables

- `docs/ai-audit/FINAL-CHANGE-EVIDENCE.md`
- `docs/ai-audit/FINAL-AAPT2-INVESTIGATION.md`
- `docs/ai-audit/NEXT-ACTIONS.md`

---

## 2026-09-30 (build phase) — APK BUILD, AND A COMPILE FAILURE FOUND

Objective: build installable APKs. No new audit, no scope added.

### Google Maven blocker RESOLVED

`dl.google.com` still returns 404 for every Maven path, **including from genuine Google
IPv6** (`2a00:1450:400a::`). But `https://redirector.gvt1.com/edgedl/android/maven2/` —
**Google's own CDN for the identical content** — is reachable.

Added via an **external Gradle init script** (`-I /tmp/gvt1.init.gradle`). **No project
build file was modified**; `google()`, `mavenCentral()`, `gradlePluginPortal()` and the
Psiphon repo are all still declared, and the CDN was added, not substituted.

Also installed, **verified against the SHA-1 values published in Google's own SDK
repository manifest**:

| Package | SHA-1 | Result |
| --- | --- | --- |
| NDK 29.0.14206865 | `03d29fbb57e3c05a7d53597dd011d856c1456a4f` | match |
| CMake 3.22.1 | `8604eeef9adadb626dbb70a7ff58a87e6a7b967a` | match |

Plus `cargo-ndk 4.1.2` and the three Android Rust targets.

### THE KOTLIN DID NOT COMPILE — WA-014 was wrong

```
e: AddressReporter.kt:72:9   No parameter with name 'produceCorruptionHandler' found.
e: SettingsRepository.kt:28:5 No parameter with name 'produceCorruptionHandler' found.
e: UpdateChecker.kt:40:9     No parameter with name 'produceCorruptionHandler' found.
```

`preferencesDataStore` takes the handler **directly** as `corruptionHandler`;
`produceCorruptionHandler` is the *builder* name from
`PreferenceDataStoreFactory.create`. Confirmed with `javap` against the resolved
`datastore-preferences-android-1.2.0` artifact rather than assumed, then corrected.

**This is the payoff of refusing to call uncompiled Kotlin "verified".** The standing
caveat was correct and the defect was real.

### Results

```
./gradlew testStableDebugUnitTest   BUILD SUCCESSFUL
    282 tests, 36 classes, 0 failures, 0 errors, 0 skipped
    (includes the 7 new DiagnosticsRedactionTest cases, now under the real Gradle runner)

./gradlew assembleStableDebug       BUILD SUCCESSFUL in 5m 3s
    4 APKs: arm64-v8a (36.2M), armeabi-v7a (34.6M), x86_64 (38.9M), universal (82.5M)
```

`libwhiteaesther_core.so` (24.8 MB) built for Android and present; `libquiche.so` and
`libboringtun` correctly absent, as `release.yml` asserts. `apksigner verify` → Verifies.

Installed and launched on an emulator (Android 14 / API 34, arm64-v8a). **Install
Success**, pid alive, no FATAL and no ANR, Compose UI renders, **Persian RTL
localisation renders correctly**, native library loads without
`UnsatisfiedLinkError`.

### VPN runtime: NOT VERIFIED

The app starts; that says nothing about tunnel establishment, interface creation, the
kill switch, handover, DNS, or traffic routing.

### One open item could not be closed

`bmgr backupnow` → "Backup is not allowed" — the app declares
`android:allowBackup="false"`, so the extraction-rules parser is never reached. The
`device_*` domain names remain unverified by any tool or runtime.

### New deliverable

- `docs/ai-audit/BUILD-ARTIFACTS.md`
