# 09 — Documentation Audit

Documentation was compared against the repository, not against its own claims. The
source is the authority.

---

## 1. Corrections applied by this audit

| ID | Claim | Actual | Correction |
| --- | --- | --- | --- |
| **WA-080** | `README.md:128` "Rust 1.88.0" | `native/rust-toolchain.toml` pins `channel = "1.98.0"`; CI uses `dtolnay/rust-toolchain@1.98.0`; local `rustup` has 1.98.0 | README now says 1.98.0 |
| **WA-081** | `README.md:128` "Android SDK 36" | `app/build.gradle.kts:55` sets `compileSdk = 37` (tor-android needs it); `targetSdk` is 36 | README now says SDK 37, and explains the compile/target split and why raising `targetSdk` is deliberately not carried along |
| **WA-083** | `docs/RELEASE.md:21-23` "Continuous main releases are prereleases and are replaced after each successful main build" | `ci.yml:4-7` states that behaviour was removed; `release.yml:6-8` triggers on `tags: ["v*"]` only | Paragraph replaced with what CI actually does |
| **WA-084** | `docs/RELEASE.md:13` "the four Android signing secrets documented in `README.md`" | README has no such table (`grep -n ANDROID_KEY README.md` → nothing); the four names exist only in `release.yml:49-52` | The four names are now written out in `RELEASE.md` |
| **WA-085** | `design/PORT-STATUS.md:14` "`res/font/` has `inter_{regular,medium,semibold,bold}.ttf`" | The directory holds `ui_*`, `fa_*` and `plex_mono_*` (verified by `ls`) | All three faces named |
| **WA-079** | `.github/dependabot.yml:66` "the workflow pins deliberately through `RUST_VERSION`" | No `RUST_VERSION` exists anywhere in the repo. The controls are the tag in `action.yml:59` and `native/rust-toolchain.toml` | Comment now names the real control |

**WA-080 and WA-081 are the ones that actually cost someone time.** A developer
following the README installs Rust 1.88 and SDK 36, and the build fails on both — and
`native/rust-toolchain.toml` will silently pull 1.98 anyway, so the pin and the doc
disagreed in the direction that wastes a morning.

---

## 2. Claims left alone, with reasons

| Claim | Assessment |
| --- | --- |
| `README.md` "Android 8.0 (API 26) or newer" | **Correct** — `minSdk = 26`. |
| `README.md` "on `arm64-v8a`, `armeabi-v7a` or `x86_64`" | **Correct** — `androidAbis` defaults to exactly those three. |
| `README.md` "Needs … NDK `29.0.14206865` and CMake 3.22.1" | **Correct** — `app/build.gradle.kts:56,274`. |
| `README.md` "`./gradlew :app:compileStableDebugKotlin` skips the native build and is the fast loop when only touching Kotlin" | **True but incomplete.** It does skip the cargo-ndk task, but it still *resolves* dependencies, including `ca.psiphon:psiphontunnel` from a network Maven repo. A reader could reasonably take it as "works offline". Left as-is; the qualification lives in `01-BASELINE.md`. |
| `README.md` "A build missing either still installs and runs; the feature that needs it says so" | **Correct and verified** — `app/build.gradle.kts:194-205` adds the chain and transport dirs as optional `jniLibs` source dirs, with comments saying so. |
| `README.md` "verify against `SHA256SUMS`" | **Correct** — `release.yml:233` generates it over every artefact, and `AppUpdate.kt` consumes it. |
| `PRIVACY.md` claims | **Verified true.** No analytics, no telemetry, no accounts. `usesCleartextTraffic=false`, loopback-only proxy unless LAN sharing is explicitly enabled, `allowBackup=false`, and diagnostics are previewed before sending. |
| `SECURITY.md` | Accurate; this audit's findings do not contradict it. |
| `THIRD_PARTY_NOTICES.md` AGPL-3.0 §13 reasoning | Stated as a claim, not a legal conclusion, and it is internally consistent. Gaps in the notices file itself are in `02-SECURITY-AUDIT.md` §6 — they are the maintainer's call, not documentation fixes. |

---

## 3. Stale documentation found, not changed

| Where | Problem | Why not changed |
| --- | --- | --- |
| `THIRD_PARTY_NOTICES.md:113` | Says the Vazirmatn cut ships in `res/font-fa/`. It is unqualified in `res/font/`, and `PersianTypefaceTest` exists specifically to assert no `font-*` directory exists. | Correcting a licence file is a maintainer/legal call, and the correction belongs alongside the missing font notices (WA-073) which were not added. |
| `THIRD_PARTY_NOTICES.md` | No `quiche` entry and no recorded revision, though it is compiled into the shipped `.so` | Same — a legal/attribution change, not a docs change. |
| `docs/FDROID.md:54-56` vs `fdroid-repo.yml:156` | `repo_url` says `…/fdroid/repo`; the upload publishes `fdroid/` as the root, giving `…/repo/` | Needs a live check against the published site to know which side is wrong. |
| `design/PORT-STATUS.md` | Lists obfuscation "Off" as a known gap needing "a warning, not just a label" | **Still true in the code** — `strings.xml:161` carries only a descriptive line, in the same un-gated `AdvancedSection` as the kill switch. A design decision, not a doc error. |
| `.github/actions/android-toolchain/action.yml:43-44` | Installs `platforms;android-36` (unused) and `android-37.2`, leaving `android-37` to AGP | CI config, not documentation; proposed upstream. |

---

## 4. Documentation this audit added

`docs/ai-audit/` — 20 documents covering the architecture map, the baseline (including
what could not be run and why), ten audit areas, the master issue register, the priority
matrix, the implementation plan, rejected ideas, upstream PR plan, phase reports, final
verification, final status, the handoff, and a changelog.

Every one states its own evidence level. Where something is `SUSPECTED` rather than
`CONFIRMED`, it says so and names the device or channel that would settle it.

---

## 5. Documentation verdict

The documentation is **better than average** — unusually so. Code comments explain *why*
a decision was made and what the alternative cost, frequently citing the bug that
motivated the current code (`app/build.gradle.kts:13-16,157-172,349-370`;
`data/SettingsRepository.kt:22-38`; `proguard-rules.pro:9-20`). That is rare and worth
preserving.

The failures are all the ordinary kind: numbers that drifted when a pin moved, and a
paragraph describing behaviour that was deliberately removed later. Six such defects,
all now corrected, none of them load-bearing on the reader's understanding of the design.