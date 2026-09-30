# NEXT-ACTIONS

Actionable remaining work only. Every item states what to do, why, what it is blocked on,
and what evidence it should produce.

Nothing here is speculative. Items that were considered and rejected are not listed;
those are in `REJECTED-IDEAS.md`.

---

## Must happen before upstream PR

These gate offering a pull request at all.

### 1. Run the Android build and unit tests

- **Action:** `./gradlew testStableDebugUnitTest`, then `./gradlew assembleStableDebug`,
  on a machine that can reach Google Maven.
- **Reason:** seven of the nine fixes are Kotlin that has **never been compiled**. The
  only two verified fixes cover a Rust file and a pure-Kotlin module; nothing that touches
  Android, Compose or DataStore has been through a compiler.
- **Related issue:** all of WA-002, 007, 014, 016, 023, 037, 038
- **Prerequisite:** network access to `dl.google.com`. On the audit machine it resolves
  to a non-Google address and serves nothing — see `POST-AUDIT-ENVIRONMENT.md` §2.
  `local.properties` with `sdk.dir` must exist (git-ignored).
- **Expected evidence:** a passing `testStableDebugUnitTest`, an assembled APK, and the
  compiler warning list. Any warning introduced by these changes should be triaged.

### 2. Confirm the `device_*` domains are accepted

- **Action:** build a release APK and run a backup or device-to-device transfer on an
  API 31+ device.
- **Reason:** `aapt2` provably does not validate `domain` values — a nonsense domain
  also links at exit 0 — and the SDK does not ship the enum. The names are correct to the
  best of available evidence, but nothing here has proven it.
- **Related issue:** WA-038
- **Prerequisite:** PR-7 must build (item 1) and an API 31+ device.
- **Expected evidence:** a successful backup or transfer with no
  `IllegalArgumentException` from the rules parser. **If it fails, remove the four
  `device_*` lines from both blocks** — nothing in the app depends on them today, as it
  uses no device-protected storage.

### 3. Re-verify WA-001's regression proof still holds

- **Action:** temporarily restore the unconditional `STOP_SENDER.lock().take()` and run
  `cargo test --locked`; expect `refusing_a_second_run_leaves_the_first_runnable` to fail.
- **Reason:** the evidence is from an earlier pass in the same session. It should be
  re-confirmed immediately before the PR is offered, and it is cheap.
- **Related issue:** WA-001
- **Prerequisite:** `cmake` on `PATH` (BoringSSL needs it).
- **Expected evidence:** `FAILED. 9 passed; 1 failed`, then `ok. 10 passed` after
  restoring.

---

## Requires Android build environment

### 4. Re-verify PR-2's call site

- **Action:** after item 1, confirm `Screens.kt` compiles and
  `DiagnosticsRedactionTest` is discovered and passes under Gradle.
- **Reason:** the module and its test are verified under standalone `kotlinc 2.4.20`, but
  the Gradle test runner has never seen them and the `Screens.kt` call site has never
  been compiled.
- **Related issue:** WA-024
- **Prerequisite:** item 1.
- **Expected evidence:** `DiagnosticsRedactionTest` appearing in the Gradle test report
  with 7 passing.

### 5. Confirm no new lint findings

- **Action:** `./gradlew lintStableRelease`
- **Reason:** `data_extraction_rules.xml` and the new Kotlin files have never been linted;
  `isEnable = minifyEnabled = true` means an unused import in a release build is a
  warning a maintainer will see.
- **Related issue:** WA-023 (the `Log` import in three files is retained only for the
  priority constants — confirm it is not flagged as unused)
- **Prerequisite:** item 1.
- **Expected evidence:** a lint report with no new issues attributable to these changes.

---

## Requires physical device

### 6. Verify the kill-switch invariant (WA-002)

- **Action:** enable the kill switch, connect on a network that cannot be escaped, let
  the attempts be exhausted, then connect again and let that fail too. Confirm traffic is
  still blocked throughout.
- **Reason:** the fix restores an OS-level invariant — that the held descriptor tracks the
  interface that actually exists. `VpnService.Builder` cannot be constructed off-device,
  and the failure mode it prevents ("traffic is blocked" shown while traffic flows) is a
  privacy regression, not a cosmetic one.
- **Related issue:** WA-002
- **Prerequisite:** PR-3 building (item 1) and a device with VPN consent
  (`adb shell appops set <pkg> ACTIVATE_VPN allow`).
- **Expected evidence:** `adb shell dumpsys connectivity` showing the interface is
  present, plus confirmation that the notification's claim matches reality. **Without
  this, PR-3 must carry an explicit device-verification limitation.**

### 7. Verify revoked-consent handling (WA-007)

- **Action:** start a connect, revoke VPN consent from system settings mid-flight, confirm
  the app reports a failed connect rather than dying.
- **Reason:** `Builder.establish()` throws `IllegalStateException`/`SecurityException` when
  consent is revoked between `prepare()` and the call; the fix wraps it.
- **Related issue:** WA-007
- **Prerequisite:** item 1 and a device.
- **Expected evidence:** a logged `the interface could not be built: …` at WARN and a
  normal error state, with no `FATAL EXCEPTION` in logcat.

### 8. Verify DataStore recovery (WA-014)

- **Action:** truncate `files/datastore/whiteaesther_settings.preferences_pb`, relaunch.
- **Reason:** the fix is supposed to turn a launch crash into a reset to defaults. Only a
  real corrupt file proves it.
- **Related issue:** WA-014
- **Prerequisite:** item 1 and a debuggable build.
- **Expected evidence:** the app starts with default settings instead of crashing.

### 9. Verify the widget cannot be crashed (WA-037)

- **Action:** broadcast `APPWIDGET_UPDATE` with forged `EXTRA_APPWIDGET_IDS` from another
  app; confirm no process death.
- **Related issue:** WA-037
- **Prerequisite:** item 1 and two apps on one device.
- **Expected evidence:** the process survives; the widget simply does not update.

### 10. Confirm release builds emit no carrier logcat output

- **Action:** install a **release** APK, connect with a carrier, and read logcat.
- **Reason:** `debugLog` gates on `BuildConfig.DEBUG`, which is only meaningful in a
  release build. No release build has been produced in this audit.
- **Related issue:** WA-023
- **Prerequisite:** item 1 plus a signed or debug-signed release variant.
- **Expected evidence:** no `psiphon` / `tor` / `pt` tags carrying server, region or bridge
  transport text.

---

## Requires maintainer infrastructure

### 11. Pin `ca.psiphon` and generate dependency verification metadata (WA-003)

- **Action:** generate `gradle/verification-metadata.xml` with
  `./gradlew --write-verification-metadata sha256` **on the maintainer's own machine and
  network**, and stop resolving the artefact from a mutable `master` ref.
- **Reason:** this is the only finding with a plausible path to native code execution on
  every device. `master` is mutable and there is no checksum, so the artefact is accepted
  on trust.
- **Related issue:** WA-003
- **Prerequisite:** a network that can reach Google Maven and Psiphon-Labs. **It cannot
  be done from the audit machine, and must not be approximated** — a wrong verification
  file fails the build looking like tampering.
- **Expected evidence:** the committed metadata file, and a build that fails if the
  artefact's bytes change.

### 12. SHA-pin the GitHub Actions (WA-004)

- **Action:** pin all 11 `uses:` references to 40-character commit SHAs, keeping the
  version as a trailing comment.
- **Related issue:** WA-004
- **Prerequisite:** maintainer review of each digest.
- **Expected evidence:** a workflow file with no floating tags, and
  `grep -rn 'uses:' .github | grep -v '@[0-9a-f]\{40\}'` returning only the
  `dtolnay/rust-toolchain` case, where the tag *is* the compiler version.

### 13. Commit a tidied `go.sum` and build with `-mod=readonly` (WA-005)

- **Related issue:** WA-005
- **Prerequisite:** run `native/chain/setup.ps1` and `native/tor/setup.ps1` once and
  confirm the resulting binaries still work.
- **Expected evidence:** two consecutive setup runs producing an identical `go.mod` and
  `go.sum`.

### 14. Add the Gradle distribution checksum (WA-006)

- **Action:** add `distributionSha256Sum` to `gradle/wrapper/gradle-wrapper.properties`,
  taken from Gradle's published checksums.
- **Related issue:** WA-006
- **Prerequisite:** a human reading the official checksum.
- **Expected evidence:** the property present and the build still resolving.

### 15. Add build attestation to the release workflow

- **Related issue:** WA-029
- **Expected evidence:** a provenance attestation published alongside the release assets.

---

## Optional future work

Listed for completeness; **none of it is a precondition for a PR**.

| Action | Issue | Expected evidence |
| --- | --- | --- |
| Run the 336 `aether` unit tests in CI (workspace or a second `cargo test`) | WA-021 | the tests executing on a push — **expect some to fail on first run; that is information** |
| `cargo ndk -t arm64-v8a clippy` so `tun.rs` compiles at all | WA-019 | `tun.rs` appearing in clippy output |
| Add an emulator job, or drop the misleading `assemblePreviewDebugAndroidTest` step | WA-077 | either a green instrumentation run or a CI step removed |
| Fix the four tautological tests | `06-TESTING-AUDIT.md` §4 | a test that fails when the production code is broken |
| Make `mtuFor` non-private and rewrite `TunnelMtuTest` against it | WA-067 | a test that fails if `mtuFor` returns 0 |
| Accessibility: selection semantics, 48 dp targets, RTL icon mirroring | WA-056, 057, 059 | reviewed on a device by someone who can see the result |
| Contrast fixes in the light palette | WA-055 | measured ratios ≥ 4.5:1 for text, ≥ 3:1 for control borders |
| Resolve the diagnostics hostname disclosure | WA-015 | a decision by the maintainer about what support offers users |
| Complete `THIRD_PARTY_NOTICES.md` (quiche revision, fonts, BSD-3/MIT texts) | WA-028, 073–075 | a maintainer/legal judgement, not an engineering one |

**None of the optional items should be started before items 1–3, and none is a
precondition for offering a PR.**