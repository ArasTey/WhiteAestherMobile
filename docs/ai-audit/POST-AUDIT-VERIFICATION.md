# POST-AUDIT VERIFICATION

**Date:** 2026-09-30
**Subject:** the working tree produced by the forensic audit, re-verified.
**Baseline:** `POST-AUDIT-ENVIRONMENT.md` for toolchain and network evidence;
`01-BASELINE.md` for the original baseline.

Nothing here is inferred. Every line is the output of a command that ran.

---

## 1. Status

```
Build:            BLOCKED — Google Maven unreachable; AGP 9.4.0 cannot resolve
Unit tests (Kotlin): BLOCKED — same cause
Native tests:     PASS — cargo test --locked, 10 passed / 0 failed
Lint:             BLOCKED — same cause
Instrumentation:  NOT RUN — no device, and no CI job that runs them either
Device:           NOT VERIFIED — NO PHYSICAL DEVICE
Security:         AUDITED — one fix applied, four High/Medium left unchanged by design
CI:               AUDITED — no change proposed without maintainer infrastructure
Documentation:    VERIFIED — corrections applied and the documented commands tested where possible
```

### The build blocker, restated with its mechanism

`./gradlew --version` works — Gradle 9.7.1 on JVM 17.0.20.
`./gradlew tasks` fails at plugin resolution.

`dl.google.com` resolves to `192.178.202.91/.90/.136/.93` — a **SoftLayer/IBM** range,
not Google — and answers with `server: downloads` and a Google-branded
`Error 404 (Not Found)!!1` page. `repo1.maven.org` (Cloudflare) and `github.com` resolve
normally and return 200. AGP and `androidx.*` are published **only** on Google Maven; the
Gradle Plugin Portal proxies to Maven Central, which does not carry them; and
`maven-central.storage-download.googleapis.com` mirrors Maven Central only.

**No legitimate mirror exists for what is missing.** No substitute repository was added,
no dependency was removed, no artifact was re-hosted, and no checksum was invented.

---

## 2. Baseline → post-audit

| Check | Baseline | Post-audit | Change |
| --- | --- | --- | --- |
| Rust unit tests | 9 passed | **10 passed, 0 failed** | +1 (WA-001 regression) |
| Rust clippy | 0 warnings | **0 warnings** | — |
| Kotlin compilation | not run | **not run (BLOCKED)** | — |
| Kotlin unit tests | not run | **not run (BLOCKED)** | — |
| Kotlin redaction module + test | verified via a **Java mirror** | **compiled and run as real Kotlin** | upgraded |
| Device | none | none | — |

---

## 3. What was actually executed this pass

### 3.1 Rust — WA-001 re-verified

```
$ cd native/android-bridge && cargo test --locked
running 10 tests
test tests::refusing_a_second_run_leaves_the_first_runnable ... ok
  … 9 others … ok
test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out

$ cargo clippy --locked --all-targets
0 warnings
```

The regression proof from the first pass still holds: with the unconditional
`STOP_SENDER.lock().take()` restored in the test's model, the suite reports
`FAILED. 9 passed; 1 failed`. It was not re-run this pass — that evidence is in
`phases/PHASE-02.md` — but the production guard was re-read at `lib.rs:864-893` and is
present and correct.

**Limitation stated plainly:** `refusing_a_second_run_leaves_the_first_runnable` models
the guard's body rather than calling `nativeRun` (which needs a JVM). It proves the
*logic* and demonstrates the bug; it does not exercise the JNI entry point itself. If
someone later removed the `armed` guard from production code, this test would still pass.

### 3.2 Kotlin — WA-024 promoted from proxy to real

The first pass verified the redaction logic with a **Java transliteration**, because
Gradle could not run. That was honest but indirect. This pass compiled and ran the
**actual Kotlin**:

```
$ kotlinc /tmp/kt/src -cp kotlin-stdlib:junit-4.13.2:hamcrest-core-1.3 -d out
(no output — compiles clean, no warnings, no errors)

$ java -cp out:kotlin-stdlib:junit-4.13.2:hamcrest-core-1.3 \
      org.junit.runner.JUnitCore com.whitedns.whiteaesther.data.DiagnosticsRedactionTest
JUnit version 4.13.2
.......
Time: 0.024

OK (7 tests)
```

Compiler: **kotlinc-jvm 2.4.20** — the project's exact Kotlin version, from
`root build.gradle.kts` and `native/rust-toolchain.toml`'s sibling pins.

#### Phase 11 required case list — 30 cases, all pass

Run against the real Kotlin:

| Required case | Result |
| --- | --- |
| `::1` | pass — redacted |
| bare `::` | pass — redacted |
| `2001:db8::1` | pass — redacted |
| `2001:db8:0:0:0:0:0:1` | pass — redacted |
| `2606:4700::1111` | pass — redacted |
| `fe80::1` | pass — redacted |
| IPv4 addresses | pass — redacted |
| timestamps | pass — **not** touched |
| normal log text | pass — not touched |
| URLs | pass — bracketed redacted, plain URL untouched |

Plus, from the same probe:

| Additional case | Result |
| --- | --- |
| Prose with a colon (`note: something at 12:34:56`) | not touched |
| Hostnames (`dns.google`, `api.example-cloud.com`) | not touched |
| `Time::now` (C++ scope) | not touched |
| `carrier AETHER on MASQUE_H3` | not touched |
| MAC address `00:1b:44:11:3a:b7` | not touched |
| **Partial-redaction check** — 8 addresses | none half-replaced |

#### What is and is not guaranteed

**Guaranteed, by the assertions above:**

- Compressed and full-form IPv6 literals are replaced **whole**, never partially.
- Bracketed literals and their ports are replaced.
- IPv4 literals and their ports are replaced.
- Wall-clock times, elapsed clocks, prose, hostnames, transport names, version strings
  and MAC addresses are returned byte-identical.
- The rule distinguishes the two IPv6 shapes **structurally** (contains `::`, or eight
  groups), not by counting colons — which is why timestamps survive.

**Not guaranteed, and stated rather than assumed:**

- **Hostnames are not redacted.** `redact` replaces addresses, not names. A hostname in
  a log line passes through. This is by design and is unchanged from the original code,
  but it means the toggle is not an anonymiser — see WA-015, which is *not* fixed.
- **The IPv4 rule over-matches inside hostnames.** `1.1.1.1.nip.io` becomes
  `0.0.0.0:port.nip.io`. Pre-existing, harmless in the over-redacting direction, left
  alone as out of scope.
- **Only these two files were compiled.** `Screens.kt`, which calls
  `DiagnosticsRedaction.redact`, was not.
- **The rules were probed against the cases listed here, not against every possible
  input.** A string shaped like an 8-group IPv6 that is not an address would be
  redacted — which is the safe direction to fail.

#### Regression proof

The pre-fix implementation was transcribed from `b55320a`, compiled alongside the fix,
and the same assertions run against both:

```
DIFFERS  the interface would not take the resolver 2606:4700::1
         pre-fix : ...2606:4700::1   <-- FAILS
         fixed   : ...[ipv6]         <-- passes
DIFFERS  resolver ::1 rejected
DIFFERS  tunnelled to 2001:0db8:85a3:0000:0000:8a2e:0370:7334 ok
DIFFERS  peer 2606:4700::1111:443 closed
DIFFERS  2606:4700::1 and 2001:db8:0:0:0:0:0:2 seen

RESULT: 5 of 5 assertions fail on the pre-fix code.
The shipped test is a real regression test, not a tautology.
```

This answers Phase 24's question — *did we add tests that don't actually fail against the
old bug?* — **no.** The Kotlin test fails on the old code and passes on the new one.

---

## 4. Issue-by-issue status

| ID | Status | Basis |
| --- | --- | --- |
| **WA-001** | **VERIFIED** | `cargo test` 10/10; regression fails on pre-fix code; clippy 0 |
| **WA-024** | **VERIFIED** | real Kotlin compiles (kotlinc 2.4.20); 7 JUnit tests pass; 30-case probe passes; 5/5 assertions fail on pre-fix code |
| WA-002 | **FIXED — NOT VERIFIED** | Kotlin never compiled; **and needs a device** — the VPN interface handover has no JVM analogue |
| WA-007 | **FIXED — NOT VERIFIED** | Kotlin never compiled |
| WA-014 | **FIXED — NOT VERIFIED** | Kotlin never compiled; needs a device to demonstrate a corrupt file |
| WA-016 | **FIXED — NOT VERIFIED** | Kotlin never compiled; condition is inside a composable |
| WA-023 | **FIXED — NOT VERIFIED** | Kotlin never compiled |
| WA-037 | **FIXED — NOT VERIFIED** | Kotlin never compiled; needs a device to broadcast forged ids |
| WA-038 | **FIXED — NOT VERIFIED** | XML well-formed, but aapt2 schema validation never ran — see §6 |
| WA-031 | **WONTFIX** | fix implemented, tested, reverted; harness showed no behavioural difference |
| WA-080…085 | **DOCUMENTATION-ONLY (corrected)** | corrections applied |
| WA-003…006, 025–030 | **CONFIRMED, unchanged** | maintainer decisions — see §7 |
| Everything else | unchanged from the register | — |

**Eight of the nine fixes are `FIXED — NOT VERIFIED`.** That is the honest state and it is
why Phase 20 downgraded them rather than leaving them marked plain `FIXED`.

---

## 5. Repository state

```
$ git status --porcelain
 M .github/dependabot.yml
 M README.md
 M app/src/main/java/com/whitedns/whiteaesther/data/AddressReporter.kt
 M app/src/main/java/com/whitedns/whiteaesther/data/SettingsRepository.kt
 M app/src/main/java/com/whitedns/whiteaesther/data/UpdateChecker.kt
 M app/src/main/java/com/whitedns/whiteaesther/service/AetherVpnService.kt
 M app/src/main/java/com/whitedns/whiteaesther/service/AetherWidgetProvider.kt
 M app/src/main/java/com/whitedns/whiteaesther/service/PluggableTransport.kt
 M app/src/main/java/com/whitedns/whiteaesther/service/PsiphonService.kt
 M app/src/main/java/com/whitedns/whiteaesther/service/TorCarrierService.kt
 M app/src/main/java/com/whitedns/whiteaesther/ui/Screens.kt
 M app/src/main/res/xml/data_extraction_rules.xml
 M design/PORT-STATUS.md
 M docs/RELEASE.md
 M native/android-bridge/src/lib.rs
?? app/src/main/java/com/whitedns/whiteaesther/data/DiagnosticsRedaction.kt
?? app/src/main/java/com/whitedns/whiteaesther/service/CarrierLog.kt
?? app/src/test/java/com/whitedns/whiteaesther/data/DiagnosticsRedactionTest.kt
?? docs/ai-audit/

15 files changed, 190 insertions(+), 33 deletions(-)
```

**Removed as machine-local:** `local.properties` (SDK pointer, git-ignored),
`.mimosa/` (audit tooling, now added to `.git/info/exclude` so it cannot be committed),
`.gradle/`, `.kotlin/`, `build/`, `app/build/`.

Nothing untracked remains that is not an audit deliverable or a deliberate source change.
**Nothing was pushed and no PR was opened.**

---

## 6. Phase 24 — final diff review

| Question | Answer |
| --- | --- |
| Anything changed unnecessarily? | No. Every hunk traces to a registered issue; the diff is 15 files / +190/−33. |
| Behaviour changed without tests? | Yes, for WA-002/007/014/016/023/037/038 — they have **no** tests, because the build cannot run and the behaviour is lifecycle- or device-bound. Stated on every entry rather than glossed. |
| Dependencies introduced? | **No.** Zero build-file dependency changes. `org.json` and JUnit were fetched to `/tmp`, outside the repo. |
| UI behaviour changed accidentally? | One deliberate change (WA-016). |
| Secrets exposed? | No. Grep for added credentials, keys, tokens: none. |
| Licences or attribution altered? | **No.** `LICENSE`, `THIRD_PARTY_NOTICES.md`, `native/aether/**` and `licenses/` are untouched. |
| Generated files changed? | No. |
| Debugging code left? | No. |
| Unrelated formatting changes? | No. |
| Compatibility broken? | **One item to confirm at build time:** `data_extraction_rules.xml` now names the `external` and `device_*` domains. These are valid from API 31 and the file is only parsed on API 31+, but **aapt2 schema validation never ran**. If the build fails there, drop the five `device_*` lines — nothing depends on them today. |
| Tests added that do not fail against the old bug? | **No.** Both regressions were run against the pre-fix code: the Rust one fails (9/1), the Kotlin one fails (5/5 assertions). |

---

## 7. Security findings — can they be fixed now?

| Finding | Evidence | Can it be safely fixed now? |
| --- | --- | --- |
| **WA-003** `ca.psiphon:2.0.41` from a mutable `master` ref, no verification | `settings.gradle.kts:21-25`; no `gradle/verification-metadata.xml` | **No.** Requires resolving the graph on a trusted network to produce correct metadata. Google Maven is unreachable, so it *cannot* be produced here. **No checksum was invented, no artifact re-hosted, no mirror substituted.** Maintainer action. |
| **WA-004** Actions on floating tags | 11 `uses:` across 4 files | **No.** Digests must be reviewed by the maintainer. |
| **WA-005** Go module graph re-resolved | `chain/setup.ps1:85-94` | **No.** Requires running the setup once, which needs PowerShell and the pinned FlClash revision. |
| **WA-006** No `distributionSha256Sum` | `gradle-wrapper.properties` | **No.** The digest must be read from Gradle's published checksums by a human. |
| WA-023 logcat in release | 8 call sites | **Yes — applied.** |
| WA-024 bare IPv6 | `Screens.kt` regex | **Yes — applied and verified.** |
| WA-037 widget crash | `AetherWidgetProvider.kt` | **Yes — applied** (compile still required). |
| WA-038 extraction rules | `data_extraction_rules.xml` | **Yes — applied** (aapt2 validation still required). |

**Nothing was fabricated to close a supply-chain finding.** That is the whole point.

---

## 8. CI — what can be proposed safely

| Change | Current | Problem | Minimal fix | Runner | Runtime | Security impact | Maintenance |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Run the `aether` crate's tests | `cargo test` in `android-bridge` only | 336 tests never run; Cargo does not run a dependency's unit tests | Add `cargo test --locked --manifest-path native/aether/aether/Cargo.toml`, or a `[workspace]` | ubuntu, existing | +2–5 min | none | trivial |
| Compile `tun.rs` | host-only `cargo test`/`clippy` | every `dup`/`from_raw_fd` in the project is never compiled | `cargo ndk -t arm64-v8a clippy` in the toolchain action | ubuntu, existing | +2 min | none | trivial |
| Emulator job | `assemblePreviewDebugAndroidTest` only | compiles the tests, runs none | **needs a decision** — add a managed-devices job, or drop the step | ubuntu + emulator image | +10–20 min, cost | none | recurring cost |

The first two need no new infrastructure and are worth proposing as one small PR. The
third is a cost decision and is documented rather than implemented.

---

## 9. Device verification

**NOT VERIFIED — NO PHYSICAL DEVICE.**

The Phase 19 list (install, first launch, permissions, connect, disconnect, reconnect,
screen off, backgrounding, network transition, Wi-Fi ↔ cellular, IPv4, IPv6, DNS, kill
switch, app restart, reboot, notifications, widget, diagnostics/redaction) was **not
performed**. No emulator was available either.

The following remain device-dependent and are claimed nowhere:

- WA-002 — the VPN interface handover and the kill-switch invariant
- WA-007 — that a revoked-consent `Builder.establish()` is reported, not fatal
- WA-014 — recovery from a truncated preferences file
- WA-037 — the forged-broadcast crash
- Every OEM, battery, notification and connectivity claim in `10-COMPATIBILITY-AUDIT.md`

**JVM tests do not substitute for any of this, and are not offered as a substitute.**

---

## 10. Final position

Two of nine fixes are `VERIFIED`. Seven are `FIXED — NOT VERIFIED`. One was rejected.

The two verified ones were verified properly: compiled and executed, and their
regression tests were shown to fail against the pre-fix code.

The seven unverified ones are unverified for one reason, and it is the same reason for
all of them: **Google Maven does not serve this machine**, and no legitimate substitute
exists for the Android Gradle Plugin. The next action is therefore not another code
change — it is running the build somewhere that can reach it.