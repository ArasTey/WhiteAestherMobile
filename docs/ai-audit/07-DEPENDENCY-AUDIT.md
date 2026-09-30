# 07 — Dependency Audit

Every version below is the literal in the build files at `b55320a`. No upgrade is
recommended without a stated reason, and none is applied by this audit.

---

## 1. Build plugins

| Coordinate | Version | Source | Why it exists |
| --- | --- | --- | --- |
| `com.android.application` | **9.4.0** | `build.gradle.kts:2` | The Android build system. |
| `org.jetbrains.kotlin.plugin.compose` | **2.4.20** | `build.gradle.kts:3` | Compose compiler plugin. |
| `kotlin-gradle-plugin` | **2.4.20** | `build.gradle.kts:buildscript` | On the buildscript classpath. |
| Gradle | **9.7.1** | `gradle/wrapper/gradle-wrapper.properties` | Wrapper. **No `distributionSha256Sum`** (WA-006). |

All exact literals — there is **no `gradle/libs.versions.toml`**. That is a defensible
choice for a project this size.

## 2. Android runtime dependencies

| Coordinate | Version | Used for | Notes |
| --- | --- | --- | --- |
| `androidx.core:core-ktx` | 1.18.0 | base | Dependabot ignores `>=1.19.0` — needs compileSdk 37 |
| `androidx.activity:activity-compose` | 1.13.0 | host activity | |
| `lifecycle-{runtime-ktx, service, viewmodel-ktx, viewmodel-compose}` | 2.10.0 ×4 | VM + service scoping | consistent; dependabot ignores `>=2.11.0` |
| `androidx.datastore:datastore-preferences` | 1.2.0 | 3 stores + 1 `DataMigration` | |
| `kotlinx-coroutines-android` | 1.11.0 | everywhere | |
| **`ca.psiphon:psiphontunnel`** | **2.0.41** | Psiphon carrier, own `:psiphon` process | **~44 MB prebuilt Go `.so`. Served from a mutable `master` ref with no checksum pin — WA-003, High.** |
| `info.guardianproject:tor-android` | 0.4.9.11 | Tor carrier (`libtor.so`) | **Two patch releases behind (0.4.9.13 exists).** |
| `info.guardianproject:jtorctl` | 0.4.5.7 | Tor control port | **Current, but last published 2021-05-04 — five years without a release. Factual, not a claim of abandonment.** |
| `androidx.compose.ui:ui`, `ui-tooling-preview`, `foundation` | 1.11.4 | UI | |
| `androidx.compose.material3:material3` | 1.4.0 | widgets | different version line from the 1.11.4 set — expected |

### Why the commented-out IPtProxy matters
`app/build.gradle.kts:~246-260` records that `IPtProxy` was rejected because it is a
gomobile library shipping `libgojni.so` + `go.*` classes, which **Psiphon also ships**.
Two of them in one APK is a duplicate-class failure at build time and, worse, one
`libgojni.so` silently winning over the other at packaging time. `release.yml` greps
`classes*.dex` for `go/Seq` to catch a reintroduction. **Any future gomobile transport
addition is the thing to watch here.**

## 3. Android test dependencies

| Coordinate | Version | Note |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | |
| `kotlinx-coroutines-test` | 1.11.0 | matches the main version — no split |
| `org.json:json` | 20260814 | **JVM test shim only; never ships.** android.jar ships `org.json` stubs that throw, so anything touching JSON is untestable on the JVM without this. |
| `androidx.test.ext:junit` | 1.3.0 | **Declared but unused** — no test imports `androidx.test.ext.*` (WA-072) |
| `androidx.test.espresso:espresso-core` | 3.7.0 | used for **one** call: `Espresso.pressBack()` in `WhiteAestherAppTest.kt:23,289` |
| `androidx.compose.ui:ui-test-junit4` | 1.11.4 | imports are `…ui.test.junit4.v2` |

**Duplication, deliberate and documented:** two `org.json` implementations on the
unit-test classpath. The JSON behaviour asserted in `AppUpdatePolicyTest`/`AutoPlannerTest`
is the Maven artifact's, not the device's. Worth knowing when a test fails only on device.

## 4. Rust

Two lockfiles, **no `[workspace]`**:

| Lockfile | Status |
| --- | --- |
| `native/android-bridge/Cargo.lock` | **Authoritative** — the Gradle cargo task runs `--locked` in `native/android-bridge` |
| `native/aether/aether/Cargo.lock` | Standalone only — and resolves the *same crates to different versions*: serde 1.0.229 vs 1.0.228, serde_json 1.0.151 vs 1.0.150, tokio 1.53.1 vs 1.52.3, rustls 0.23.43 vs 0.23.41, thiserror 2.0.20 vs 2.0.18, libc 0.2.189 vs 0.2.186 (WA-076). A drifting second view of the shipped graph. |

**Manifest specs are caret ranges, not pins** (`tokio = "1.52"`, `serde_json = "1.0.142"`,
`boring = "4.22"`, `rand = "0.10"`). Pinned in practice by the committed lockfiles plus
`--locked` in both CI and the Gradle cargo task. **Correct.**

Resolved versions in the shipped graph: `jni 0.21.1`, `libc 0.2.189`, `tokio 1.53.1`,
`serde_json 1.0.151`, `boring 4.22.0`, `quiche 0.29.3`, `ring 0.17.14`, `rustls 0.23.43`.

### Path/patched dependencies

| Dependency | Source | Note |
| --- | --- | --- |
| `aether` | `../aether/aether` | Vendored engine |
| `quiche 0.29.3`, `octets` | `../quiche/quiche`, `../quiche/octets` | Vendored. **No revision recorded, no `THIRD_PARTY_NOTICES.md` entry — WA-028.** `libquiche.so` is excluded from the APK, so it is build-time only. |
| `boring-sys` | `[patch.crates-io] ../third-party/boring-sys` | Vendored BoringSSL; build script patched for Windows paths only, per the notices |

### Duplicate majors in the shipped lock
`thiserror` 1.0.69 + 2.0.20 (1.x comes from `jni 0.21.1`), `syn` 2.0.119 + 3.0.3,
`bitflags` 1/2, `chacha20` 0.9/0.10, `getrandom` 0.2/0.4, `rand_core` 0.6/0.10, four
`windows-sys` majors. All are transitive consequences of the two big native engines;
none is actionable without a coordinated bump. `jni-sys` 0.3.1 + 0.4.1 is **not** a real
duplicate — 0.3.1 is a re-export shim inside `jni 0.21.1`.

### Unused
`thiserror = "2.0.16"` is a direct dependency of `android-bridge` with **zero references**
in `src/` (WA-071). Dropping it removes surface for free.

## 5. Toolchain

| Tool | Pinned how |
| --- | --- |
| Rust | `native/rust-toolchain.toml` → `channel = "1.98.0"`, targets aarch64/armv7/x86_64 Android; CI `dtolnay/rust-toolchain@1.98.0`; dependabot ignores it. **The two agree.** (The `RUST_VERSION` claim in the dependabot comment was wrong — corrected, WA-079.) |
| `cargo-ndk` | `cargo install cargo-ndk --version 4.1.2 --locked` in CI |
| Go | `go-version: "1.25"` in CI — resolves to the newest 1.25.x at run time, so the compiler drifts between releases |
| JDK | 21 |
| NDK | `29.0.14206865` |
| CMake | `3.22.1` |

## 6. Supply-chain observations

**No advisories are asserted here.** Naming a CVE against these exact versions requires
data this audit did not fetch. What *is* verifiable in-tree: Tor 0.4.9.11,
lyrebird 0.8.1, snowflake v2.14.1, Psiphon 2.0.41, BoringSSL 4.22.0, quiche 0.29.3,
rustls 0.23.43, ring 0.17.14, AGP 9.4.0, Kotlin 2.4.20. **Compare those against upstream
advisories before shipping.**

Ranked by how much they matter here:

1. **`ca.psiphon` from a mutable branch with no checksum** (WA-003) — the only finding
   with a plausible path to code execution on every device.
2. **No Gradle dependency locking or verification metadata.** Direct versions are exact
   literals, but the whole transitive androidx/Tor/Psiphon graph floats.
3. **Go module graph re-resolved at build time** (WA-005).
4. **GitHub Actions on floating tags** (WA-004).
5. **No Gradle distribution checksum** (WA-006).

For contrast, `native/psiphon/setup.ps1` is the strongest supply-chain control in the
repository: the bootstrap list is fetched from a *third-party* mirror
(`mbm110/MSN-GUARD`, not Psiphon) at a pinned revision **and** a pinned SHA-256, then
every entry is Ed25519-verified with tunnel-core's own `DecodeServerEntryFields` /
`VerifySignature`, and the script refuses rather than warns. The digest catches
truncation; the signature catches a list that is not Psiphon's. The marker is deleted
*before* verification, never after, so an interrupted run cannot leave a false "verified"
claim. **That is the model the rest of the supply chain should follow.**

## 7. Upgrade recommendations

None applied. For the record:

| Current | Proposed | Reason | Risk | Verification |
| --- | --- | --- | --- | --- |
| `tor-android` 0.4.9.11 | 0.4.9.13 | Two patch releases behind | Low — Guardian's own build, same ABI | Build + `TorSessionTest` on a device |
| `jtorctl` 0.4.5.7 | *nothing to upgrade to* | Last release 2021 | — | Note it in `THIRD_PARTY_NOTICES.md` as unmaintained |
| `thiserror` (unused) | remove | Zero references | Very low | `cargo test` + `cargo clippy` |
| `androidx.test.ext:junit` | remove | No imports | Very low | `./gradlew assembleDebugAndroidTest` |
| `ca.psiphon` | pin ref + checksum | Mutable branch, no verification | Medium — needs the maintainer's resolution | `dependencyVerification` + a device test |
| `go-version: "1.25"` | `1.25.x` pinned | Compiler drifts between releases | Very low | Build both chains |

**Do not** bump `psiphontunnel`: it is current, and it is the one dependency where
"whatever is newest" is explicitly not a thing to relax about.