# Phase 03 — Reliability, privacy and hardening

## Objective

Close the availability gap that can stop the app starting, remove release-build
egress that the app deliberately avoids elsewhere, and correct six documentation
defects that cost a new developer real time.

## Starting State

Phase 2 complete: Rust 10/10, clippy clean, three correctness fixes landed.

## Files Inspected

All three `preferencesDataStore` declarations; `service/EngineLog.kt:55-62` (the rule
that already existed); the eight carrier logcat sites; `service/AetherWidgetProvider.kt`;
`res/xml/data_extraction_rules.xml`; `AndroidManifest.xml` (backup attributes,
`tools:replace`); `README.md`; `docs/RELEASE.md`; `design/PORT-STATUS.md`;
`.github/dependabot.yml`; `native/rust-toolchain.toml`; `res/font/`.

## Problems Found

**WA-014 — no DataStore corruption handler.** `grep -rn CorruptionHandler app/src` →
none, on any of the three stores. `MainViewModel.kt:127` collects
`repository.settings` in `viewModelScope` and `MainActivity.kt:298` collects it, so a
`CorruptionException` from a truncated `.preferences_pb` is not contained: a phone that
loses the atomic rename cannot start the app at all.

**WA-037 — the exported widget receiver is crashable by any local app.**
`AetherWidgetProvider` is exported (a home-screen widget must be) and accepts
`APPWIDGET_UPDATE` with no permission. Any app can broadcast forged
`EXTRA_APPWIDGET_IDS`; `updateAppWidget` throws `SecurityException` for ids the caller
does not own, uncaught inside `onReceive`. Repeatable process crash, no privilege gain.

**WA-038 — extraction rules omit `external` and the four `device_*` domains.** The
sensitive domains *are* covered — identity is in `filesDir`, DataStore and
SharedPreferences in `file`/`sharedpref` — so there is no exposure today. The only thing
in `external` is the staged update APK, which is public. The gap is that the guarantee
does not survive future use.

**WA-023 — release builds wrote carrier diagnostics to logcat.**
`EngineLog.kt:58` gates its mirror on `if (BuildConfig.DEBUG)` and documents "A release
build keeps its log in memory only." Seven sites ignored that: `PsiphonService.kt:175,282`,
`TorCarrierService.kt:207,290,300,326`, `PluggableTransport.kt:67,76`.
`PsiphonService.kt:175` logs the **raw, unfiltered** tunnel-core notice *before* the
`consider()` filter at `:214-245` selects a safe subset — and those notices carry the
server dialled and the country Psiphon believes the phone is in.

**WA-079/080/081/083/084/085 — documentation.** See `09-DOCUMENTATION-AUDIT.md` §1.

## Changes Made

| File | Change |
| --- | --- |
| `data/SettingsRepository.kt` | `produceCorruptionHandler = { ReplaceFileCorruptionHandler { emptyPreferences() } }` |
| `data/UpdateChecker.kt` | same |
| `data/AddressReporter.kt` | same |
| `service/CarrierLog.kt` | **new** — `debugLog(tag, priority, message)`, applying the `EngineLog` rule |
| `service/PsiphonService.kt` | 2 sites → `debugLog` |
| `service/TorCarrierService.kt` | 4 sites → `debugLog` |
| `service/PluggableTransport.kt` | 2 sites → `debugLog` |
| `service/AetherWidgetProvider.kt` | per-id `runCatching` |
| `res/xml/data_extraction_rules.xml` | all nine domains excluded, both blocks |
| `README.md` | Rust 1.88.0 → 1.98.0; SDK 36 → 37 + the compile/target explanation |
| `docs/RELEASE.md` | removed the rolling-prerelease claim; wrote out the four signing secrets |
| `design/PORT-STATUS.md` | `inter_*.ttf` → the actual `ui_*` / `fa_*` / `plex_mono_*` |
| `.github/dependabot.yml` | the `RUST_VERSION` claim → the real control |

## Why Each Change Was Made

**The corruption handler** is the difference between losing settings and not starting.
For an app whose whole purpose is being reachable when a network is hostile, "the app
will not open" is a worse failure than "the app forgot your theme".

**`debugLog` rather than eight `if (BuildConfig.DEBUG)` guards.** The rule already
existed and was already documented; it simply was not applied. Eight copies of the guard
would leave eight chances to forget it again, and the next person would have to find the
rule before applying it. One helper makes it a single decision, and its doc comment
carries the reasoning with it.

**The widget `runCatching`** costs nothing and removes a crash any installed app can
trigger at will.

**The extraction rules** cost nothing and make the guarantee a property of the
configuration rather than of the current set of writes.

**The documentation fixes** are small, but WA-080 and WA-081 are the ones that
actually cost time: following the README installs Rust 1.88 and SDK 36, and the build
fails on both — while `native/rust-toolchain.toml` silently pulls 1.98 anyway, so the
doc and the pin disagree in the direction that wastes a morning.

## Tests Added

None. Each of these is a configuration or a call-site change whose correctness is
evident from the code, and the Gradle suite cannot run here regardless
(`01-BASELINE.md` §2.1). Adding tests that could not be executed would be worse than
saying so.

## Tests Run

```
$ cd native/android-bridge && cargo test --locked   → 10 passed; 0 failed
$ cargo clippy --locked --all-targets              → 0 warnings
```

Unchanged from Phase 2, as expected: nothing in this phase touches Rust.

## Test Results

No change. Rust 10/10, clippy clean.

## Build Results

Rust: unchanged. Kotlin: **not built** — Gradle blocked.

## Regressions Checked

- `PluggableTransport.kt`'s `when` is a statement, not an expression, so changing
  `Log.w(...)` (returns `Int`) to `debugLog(...)` (returns `Unit`) in one branch is
  legal — the other branches already returned mixed types (`Unit`, `Boolean`, `String`).
- `CarrierLog.kt` uses `runCatching` because JVM unit tests have a throwing `Log` stub —
  the same reason `EngineLog.kt:56` documents.
- All three DataStore files' imports were updated; `ReplaceFileCorruptionHandler` comes
  from `androidx.datastore.core.handlers` and `emptyPreferences` from
  `androidx.datastore.preferences.core` — both verified against the existing import
  style in each file.
- `data_extraction_rules.xml` is well-formed XML; all eight new `<exclude>` elements are
  inside the correct parent block.

## Known Limitations

- **None of the Kotlin changes were compiled.** This is the real limitation of the phase.
- WA-023's benefit is conditional on the build actually being a release build;
  `SELF_UPDATE` and `BuildConfig.DEBUG` are independent and the gating is on `DEBUG`,
  which is the correct invariant.

## Remaining Problems

- WA-015 (diagnostics discloses visited hostnames) — **not** fixed, and deliberately:
  the fix changes what the maintainer offers users for support.
- WA-028, WA-073–075 (attribution gaps) — maintainer/legal calls.
- WA-034 (`tor-android` two releases behind) — not bumped; the maintainer's call.

## Next Phase

Phase 4 — the two correctness fixes in UI-visible behaviour, where a regression test was
possible out of band.

## Instructions For Another AI

**Every new logcat write in the carrier services must go through `debugLog`.** That is
the whole point of the helper. `android.util.Log` called directly in `service/` is now
a bug, and `EngineLog.kt` is the one exception (it owns the in-memory log and its own
gate).

**Any new DataStore must declare a corruption handler.** Three of three do today. A
fourth without one reopens WA-014 on that store alone.

**Do not add a `Log` import** to `PluggableTransport.kt` for anything but the priority
constants — the call itself goes through `debugLog`.
