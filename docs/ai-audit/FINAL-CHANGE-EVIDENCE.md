# FINAL-CHANGE-EVIDENCE

> ## ADDENDUM — 2026-09-30, after the build phase. THIS SUPERSEDES "COMPILATION VERIFIED" BELOW.
>
> The Google Maven blocker was resolved (Google's own CDN, via an external init script —
> no project build file touched) and **the Kotlin now compiles**:
> `compileStableDebugKotlin` succeeds, **282/282 unit tests pass, 0 skipped**, and 4
> debug APKs were produced, installed and launched. See `BUILD-ARTIFACTS.md`.
>
> **Every entry below that reads "Compilation verified: no" should now read "yes".**
>
> **One entry was wrong and is now corrected — WA-014.** Its fix used
> `produceCorruptionHandler`, which is not a parameter of `preferencesDataStore` and
> **failed to compile in all three DataStore files**:
> ```
> e: AddressReporter.kt:72:9   No parameter with name 'produceCorruptionHandler' found.
> e: SettingsRepository.kt:28:5 No parameter with name 'produceCorruptionHandler' found.
> e: UpdateChecker.kt:40:9     No parameter with name 'produceCorruptionHandler' found.
> ```
> The real parameter is `corruptionHandler`, and the handler is passed **directly**, not
> as a producer — confirmed with `javap` against the resolved
> `datastore-preferences-android-1.2.0` artifact rather than assumed. Corrected, and the
> build now succeeds. **WA-014 is now `VERIFIED`** (compiles, tests pass, app starts).
> Recovery from a genuinely corrupt file is still untested.
>
> The remaining `CODE-REVIEW VERIFIED` entries keep that level as **evidence**: they
> compile now, but their behaviour is lifecycle-, VPN- or device-bound and was not
> exercised. The emulator launch proves the app starts and the UI draws — **it proves
> nothing about tunnel establishment, kill-switch handover, or revoked-consent handling.**

Per-file evidence for every source change the audit made. One entry per file, no
grouping of unrelated files.

## Evidence levels used

| Level | Meaning |
| --- | --- |
| **VERIFIED** | Compiled and/or executed; tests pass; regression behaviour demonstrated where applicable. |
| **CODE-REVIEW VERIFIED** | Inspected in full; static reasoning supports correctness; required build/runtime verification unavailable in this environment. |
| **NOT VERIFIED** | Insufficient evidence. |

**The environment:** Google Maven serves no artifacts to this machine
(`POST-AUDIT-ENVIRONMENT.md` §2), so no Gradle task configures. Two toolchains *were*
available and were used: `cargo` (Rust) and a standalone `kotlinc 2.4.20` (Kotlin, for
files with no Android dependency), plus `aapt2` from build-tools 36.0.0 for resources.

---

### 1. `native/android-bridge/src/lib.rs`

- **Issue:** WA-001
- **Change:** Added `use std::cell::Cell`; a local `armed` flag set at the moment this
  call installs `STOP_SENDER`; the blanket failure cleanup now takes the sender only when
  `armed` is set. Added the regression test.
- **Compilation verified:** yes — `cargo test --locked` compiled the crate.
- **Tests verified:** yes — 10 passed, 0 failed, 0 ignored.
- **Runtime verified:** yes — the test executes the guard's body and `nativeStop`'s
  send-then-drop behaviour.
- **Device verified:** n/a (no Android API surface).
- **Evidence:** `cargo clippy --locked --all-targets` → 0 warnings. Regression proof:
  restoring the unconditional `STOP_SENDER.lock().take()` yields `FAILED. 9 passed;
  1 failed` (`phases/PHASE-02.md`).
- **Status:** **VERIFIED**
- **Limitation recorded:** the test models the guard's body rather than invoking the JNI
  entry point. Removing the production `armed` guard later would not fail this test.

### 2. `app/src/main/java/com/whitedns/whiteaesther/data/DiagnosticsRedaction.kt` *(new)*

- **Issue:** WA-024
- **Change:** New `internal object` holding the three redaction rules and `fun redact`.
  Extracted from `Screens.kt` so the rules could be tested.
- **Compilation verified:** **yes** — `kotlinc 2.4.20`, no errors, no warnings.
- **Tests verified:** **yes** — 7 JUnit tests, `OK (7 tests)`.
- **Runtime verified:** yes — executed on the JVM.
- **Device verified:** n/a (pure `kotlin.text.Regex`).
- **Evidence:** 30-case probe covering the full required list, all pass, including a
  partial-redaction check. Pre-fix implementation fails **5 of 5** assertions.
- **Status:** **VERIFIED**

### 3. `app/src/test/java/com/whitedns/whiteaesther/data/DiagnosticsRedactionTest.kt` *(new)*

- **Issue:** WA-024
- **Change:** 7 tests / 16 assertions.
- **Compilation verified:** **yes** — kotlinc 2.4.20.
- **Tests verified:** **yes** — `OK (7 tests)` under JUnit 4.13.2.
- **Runtime verified:** yes.
- **Device verified:** n/a.
- **Evidence:** the same assertions were run against the pre-fix code; 5 of 5 fail there.
  The test is a real regression test, not a tautology.
- **Status:** **VERIFIED**

### 4. `app/src/main/java/com/whitedns/whiteaesther/ui/Screens.kt`

- **Issues:** WA-016, and the WA-024 call site
- **Change (WA-016):** the blocked-state card now matches `status.message` on containment
  as well as equality; `blockedNotice` hoisted for reuse by `CardHead`.
- **Change (WA-024):** private regexes and `redactAddresses` removed; the single call
  site in `buildReport` now calls `DiagnosticsRedaction.redact`; import added.
- **Compilation verified:** **no.** Depends on Compose and Android; not compilable with
  the standalone compiler.
- **Tests verified:** no. No test covers either change.
- **Runtime verified:** no.
- **Device verified:** no.
- **Evidence:** read in full. The `contains` change cannot misfire — the string is
  produced only by `sayNow(R.string.traffic_is_blocked)`. The call site is a one-line
  substitution against an `internal object` in the same module.
- **Status:** **CODE-REVIEW VERIFIED**

### 5. `app/src/main/java/com/whitedns/whiteaesther/service/AetherVpnService.kt`

- **Issues:** WA-002, WA-007
- **Change (WA-002):** `dropBlackhole()` called immediately before `builder.establish()`
  in `establishTun`.
- **Change (WA-007):** the `establishTun` body wrapped in
  `runCatching { … }.onFailure { … }.getOrNull()`; the failure is logged at WARN.
- **Compilation verified:** **YES** — `compileStableDebugKotlin` succeeds (2026-09-30 build).
- **Tests verified:** no.
- **Runtime verified:** no.
- **Device verified:** **no — this is the change that most needs it.**
- **Evidence:** the `establishTun` body was checked for early `return`s before wrapping,
  because `runCatching` is inline and a non-local return would bypass `.onFailure` — there
  are none. The return type stays `ParcelFileDescriptor?`, which all three call sites
  already handle. `dropBlackhole()` is defined above `establishTun`, so it is in scope.
- **Status:** **CODE-REVIEW VERIFIED**
- **Why not more:** VPN interface handover has no JVM analogue. A `VpnService.Builder`
  cannot be constructed off-device, and the invariant ("traffic stays blocked during
  interface handover") is a kernel/OS behaviour.

### 6. `app/src/main/java/com/whitedns/whiteaesther/data/SettingsRepository.kt`

- **Issue:** WA-014
- **Change:** added `produceCorruptionHandler = { ReplaceFileCorruptionHandler { emptyPreferences() } }`
  and two imports.
- **Compilation verified:** no.
- **Tests verified:** no.
- **Runtime verified:** no.
- **Device verified:** no.
- **Evidence:** `androidx.datastore.core.handlers.ReplaceFileCorruptionHandler` and
  `androidx.datastore.preferences.core.emptyPreferences` are the correct packages for
  DataStore 1.2.0; import style matches the file's existing ordering.
- **Status:** **CODE-REVIEW VERIFIED**

### 7. `app/src/main/java/com/whitedns/whiteaesther/data/UpdateChecker.kt`

- **Issue:** WA-014
- **Change:** identical corruption handler and the same two imports.
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** as above.
- **Status:** **CODE-REVIEW VERIFIED**

### 8. `app/src/main/java/com/whitedns/whiteaesther/data/AddressReporter.kt`

- **Issue:** WA-014
- **Change:** identical corruption handler and the same two imports.
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** as above.
- **Status:** **CODE-REVIEW VERIFIED**

### 9. `app/src/main/java/com/whitedns/whiteaesther/service/CarrierLog.kt` *(new)*

- **Issue:** WA-023
- **Change:** new `internal fun debugLog(tag, priority, message)` applying the
  `BuildConfig.DEBUG` rule that `EngineLog.kt:58` already documented.
- **Compilation verified:** no — depends on `android.util.Log` and `BuildConfig`.
- **Tests verified:** no.
- **Runtime verified:** no.
- **Device verified:** no.
- **Evidence:** body is `if (BuildConfig.DEBUG) { runCatching { Log.println(priority, tag, message) } }`.
  `runCatching` is required, not decorative: `EngineLog.kt:56` documents that `Log` is a
  throwing stub under JVM unit tests.
- **Status:** **CODE-REVIEW VERIFIED**

### 10. `app/src/main/java/com/whitedns/whiteaesther/service/PsiphonService.kt`

- **Issue:** WA-023
- **Change:** two `Log.*` calls replaced with `debugLog`, using `Log.DEBUG` and
  `Log.ERROR` as the priorities. `Log` import retained (the constants are still used).
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** line 175 logs the raw tunnel-core notice *before* the `consider()` filter
  — that is the finding, and it is now gated. The `Log` import is still required for
  `Log.DEBUG`/`Log.ERROR`, so it is not an unused import.
- **Status:** **CODE-REVIEW VERIFIED**

### 11. `app/src/main/java/com/whitedns/whiteaesther/service/TorCarrierService.kt`

- **Issue:** WA-023
- **Change:** four `Log.*` calls replaced with `debugLog`.
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** as above; `Log` import still needed for the priority constants.
- **Status:** **CODE-REVIEW VERIFIED**

### 12. `app/src/main/java/com/whitedns/whiteaesther/service/PluggableTransport.kt`

- **Issue:** WA-023
- **Change:** two `Log.*` calls replaced with `debugLog`.
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** the `when` is a **statement**, not an expression — the other branches
  already returned mixed types (`Unit`, `Boolean`, `String`) while the replaced branch
  returned `Int`. Changing it to return `Unit` is therefore legal. `Log` import still
  needed for `Log.DEBUG`/`Log.WARN`.
- **Status:** **CODE-REVIEW VERIFIED**

### 13. `app/src/main/java/com/whitedns/whiteaesther/service/AetherWidgetProvider.kt`

- **Issue:** WA-037
- **Change:** `widgetIds.forEach { manager.updateAppWidget(it, draw(context, stage)) }`
  replaced by a `for` loop with `runCatching` per id.
- **Compilation / Tests / Runtime / Device verified:** no to all four.
- **Evidence:** `draw(context, stage)` is hoisted out of the per-id lambda as before, so
  no extra work per id. The `stage` is read once, as before.
- **Status:** **CODE-REVIEW VERIFIED**

### 14. `app/src/main/res/xml/data_extraction_rules.xml`

- **Issue:** WA-038
- **Change:** added `external`, `device_root`, `device_file`, `device_database`,
  `device_sharedpref` exclusions to both blocks; added a comment.
- **Compilation verified:** **yes — and it caught a real defect.**
- **Tests verified:** n/a (declarative resource).
- **Runtime verified:** no.
- **Device verified:** no.
- **Evidence:**
  - First `aapt2 compile` run **failed**:
    `app/src/main/res/xml/data_extraction_rules.xml:9: error: not well-formed (invalid token)`.
    The audit's own comment contained `storage -- neither of which`, and **XML comments
    cannot contain `--`**. This was a genuine build break introduced by this audit.
  - Fixed by rewording the comment. Re-run:
    `aapt2 compile --dir app/src/main/res` → **exit 0, 38 files**;
    `aapt2 link` with the real manifest (package attribute supplied, as AGP does) →
    **exit 0, 885 265-byte APK**.
- **Status:** **VERIFIED** for XML validity and resource linking.
- **Explicitly not verified:** the `domain` enum values. `aapt2` does **not** validate
  them — a differential probe substituting `domain="definitely_not_a_domain"` also
  compiled and linked with exit 0. The framework's domain list is not in `android.jar`,
  not in `android-stubs-src.jar`, and not in `attrs_manifest.xml`. **The domain names are
  CODE-REVIEW VERIFIED at best; no tool in this environment can confirm them.** Residual
  risk if a name were wrong: the framework throws when it parses the rules for a backup
  or device transfer — a backup-time failure, not a launch crash. See
  `FINAL-AAPT2-INVESTIGATION.md`.

### 15. `README.md`

- **Issue:** WA-080, WA-081
- **Change:** "Rust 1.88.0" → "1.98.0"; "Android SDK 36" → "37", with an added paragraph
  explaining the compile-37 / target-36 split.
- **Compilation verified:** n/a.
- **Tests verified:** n/a.
- **Evidence:** `native/rust-toolchain.toml` contains `channel = "1.98.0"` (read);
  `app/build.gradle.kts:55` contains `compileSdk = 37` and `:61` `targetSdk = 36` (read);
  CI uses `dtolnay/rust-toolchain@1.98.0` (read). Three independent sources agree.
- **Status:** **VERIFIED**

### 16. `docs/RELEASE.md`

- **Issue:** WA-083, WA-084
- **Change:** replaced the rolling-prerelease paragraph with what CI actually does; wrote
  out the four signing secret names instead of pointing at a README table that does not
  exist.
- **Evidence:** `.github/workflows/ci.yml:4-7` states the behaviour was removed;
  `release.yml:6-8` triggers on `tags: ["v*"]`; `grep -n ANDROID_KEY README.md` returns
  nothing; the four names are at `release.yml:49-52`. All read directly.
- **Status:** **CODE-REVIEW VERIFIED** (documentation, but every claim was checked
  against the file it describes)

### 17. `design/PORT-STATUS.md`

- **Issue:** WA-085
- **Change:** `inter_{regular,medium,semibold,bold}.ttf` → the actual `ui_*`, `fa_*` and
  `plex_mono_*` filenames.
- **Evidence:** `ls app/src/main/res/font/` returns exactly `fa_{bold,medium,regular,semibold}.ttf`,
  `plex_mono_{medium,regular}.ttf`, `ui_{bold,medium,regular,semibold}.ttf`.
- **Status:** **VERIFIED**

### 18. `.github/dependabot.yml`

- **Issue:** WA-079
- **Change:** comment corrected — it claimed a `RUST_VERSION` variable that does not
  exist; it now names the tag in `action.yml:59` and `native/rust-toolchain.toml`.
- **Evidence:** `grep -rn RUST_VERSION` across the repository returns only this comment.
- **Status:** **VERIFIED**

---

## Tally

| Evidence level | Files |
| --- | --- |
| **VERIFIED** | 6 — `lib.rs`, `DiagnosticsRedaction.kt`, `DiagnosticsRedactionTest.kt`, `data_extraction_rules.xml`, `README.md`, `design/PORT-STATUS.md`, `.github/dependabot.yml` (7 entries) |
| **CODE-REVIEW VERIFIED** | 11 — `Screens.kt`, `AetherVpnService.kt`, `SettingsRepository.kt`, `UpdateChecker.kt`, `AddressReporter.kt`, `CarrierLog.kt`, `PsiphonService.kt`, `TorCarrierService.kt`, `PluggableTransport.kt`, `AetherWidgetProvider.kt`, `docs/RELEASE.md` |
| **NOT VERIFIED** | 0 — every file has at least code-review evidence |

**By issue:** WA-001 VERIFIED · WA-024 VERIFIED for the extracted module and its test,
CODE-REVIEW VERIFIED for the `Screens.kt` call site · WA-038 VERIFIED for XML validity,
domain enum not tool-verifiable · WA-002/007/014/016/023/037 CODE-REVIEW VERIFIED ·
WA-079/080/081/083/084/085 documentation-only, VERIFIED against their sources.

**No device was used for any entry.**