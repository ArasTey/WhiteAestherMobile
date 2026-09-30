# 01 — Baseline

**Date:** 2026-09-30
**Commit:** `b55320a` ("Let Automatic search on its own terms, and carry the 2.1.0 engine fixes (#73)")
**Machine:** macOS 26.3 (Darwin 25.3.0), arm64, Apple Silicon

This document records what was actually run, what passed, and what could not be
run at all. Nothing here is inferred from reading; every line below is the output
of a command that was executed on this machine.

---

## 1. Environment

| Component | Required by the project | Present here | Notes |
| --- | --- | --- | --- |
| JDK | 21 (`README.md`, CI `action.yml:17-20`) | **17.0.20** (Homebrew) | `JAVA_HOME` was set to a **non-existent** path (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/...`); it does not exist. Every Gradle invocation had to override it. |
| Gradle | 9.7.1 (wrapper) | 9.7.1 | Downloaded manually — see §2. |
| Android SDK | compileSdk 37 | `~/Library/Android/sdk`, platforms `android-34`, **`android-37.0`** | `android-37.0` is `AndroidVersion.ApiLevel=37.0`, the new minor-version scheme. No `android-36`. |
| Build tools | 36.0.0 | 34.0.0, 36.0.0 | OK |
| NDK | `29.0.14206865` (`app/build.gradle.kts:56`) | **27.3.13750724** | Wrong version. Blocks any native APK build. |
| CMake | 3.22.1 (`app/build.gradle.kts:274`) | **absent** | Installed `cmake 4.4.3` via Homebrew as part of this audit, to build BoringSSL for the host Rust tests. Not the pinned version. |
| Rust | 1.98.0 (`native/rust-toolchain.toml`) | **1.98.0** ✓ | Matches. But `rustup target list --installed` shows only `aarch64-apple-darwin` — **no Android targets**. |
| cargo-ndk | required by the Gradle cargo task | **absent** | Blocks `cargoBuildAndroidDebug`/`Release`. |
| Go | 1.25 (CI) | 1.27.1 | Only needed for the exit chain and Tor transports, neither of which is built. |
| `gh` | — | 2.98.0 | — |

### Installed by this audit (not present beforehand)

- `cmake 4.4.3` via `brew install cmake` — required because `boring-sys` builds
  BoringSSL with CMake, and without it even the **host** `cargo test` cannot run.
- Gradle 9.7.1 distribution, fetched with `curl` after the wrapper's own download
  stalled twice at ~74 MB (see §2).
- `local.properties` with `sdk.dir` (git-ignored; not a project file).

---

## 2. Build status

### 2.1 Gradle — **BLOCKED (environmental, not a project defect)**

```
$ ./gradlew testStableDebugUnitTest
FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'com.android.application', version: '9.4.0', apply: false] was not found
  Searched in the following repositories:
    Google
    MavenRepo
    Gradle Central Plugin Repository
BUILD FAILED in 22s
```

**Root cause, established:** Google Maven is unreachable from this environment.

```
$ curl -sI https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
  → HTTP 404   (the whole group 404s, including maven-metadata.xml)

$ curl -sI .../gradle/8.7.3/gradle-8.7.3.pom        → HTTP 404
$ curl -sI .../gradle/9.4.0/gradle-9.4.0.pom        → HTTP 404

$ curl -sI https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom
  → HTTP 200     (Maven Central is reachable)
```

AGP 8.7.3 has existed for years; its 404 alongside Maven Central's 200 shows the
block is not about the version. **No AGP version can resolve here, so no Gradle
task that needs a plugin can configure.**

Gradle itself did install, after two stalls:

```
$ ./gradlew --version
  → SocketTimeoutException reading services.gradle.org (twice, at ~74 MB of ~142 MB)

$ curl -L --retry 5 https://services.gradle.org/distributions/gradle-9.7.1-bin.zip
  → 142 364 352 bytes, unzipped to gradle-9.7.1, marked .ok
```

**Consequence for this audit:** every Kotlin change below is **unexecuted**. It is
reviewed by reading, and its pure logic is verified out-of-band (§4). The
Kotlin unit tests this audit adds were **not run**.

### 2.2 Rust — **PASS**

```
$ cd native/android-bridge && cargo test --locked
   Compiling whiteaesther-android v0.1.0
    Finished `test` profile [unoptimized + debuginfo] target(s) in 4m 54s
     Running unittests src/lib.rs
running 10 tests
test tests::credentials_are_ignored_while_the_proxy_is_private ... ok
test tests::every_transport_names_the_tunnel_it_builds ... ok
test tests::half_a_credential_pair_is_refused_rather_than_ignored ... ok
test tests::refusing_a_second_run_leaves_the_first_runnable ... ok
test tests::reports_core_version ... ok
test tests::sharing_without_a_password_is_allowed_and_says_so ... ok
test tests::the_engine_flags_the_app_never_sent_are_validated ... ok
test tests::the_listener_stays_on_loopback_until_sharing_is_asked_for ... ok
test tests::the_two_default_on_flags_are_only_set_to_turn_them_off ... ok
test tests::validates_proxy_port_and_mode ... ok

test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
   Doc-tests whiteaesther_core: 0 passed; 0 failed
```

**Before the audit's change: 9 passed.** The tenth is the regression test added
for WA-001.

```
$ cargo clippy --locked --all-targets
  → 0 warnings
```

### 2.3 Full APK assembly — **NOT ATTEMPTED, known blocked**

Three independent blockers, each sufficient on its own:

1. **AGP unresolvable** (§2.1) — the build cannot configure.
2. **NDK 29.0.14206865 absent** (27.3.13750724 present) — `ndkVersion` is pinned.
3. **cargo-ndk absent and no Android Rust targets installed** —
   `cargoBuildAndroidDebug` shells out to `cargo ndk`.

`cargo ndk -t arm64-v8a clippy` and APK packaging are therefore **not verified**,
and no claim is made about them.

---

## 3. Test status

| Suite | Count | Runs in CI | Run here |
| --- | --- | --- | --- |
| `app/src/test` (JVM) | 275 `@Test` | Yes | **No** — Gradle blocked |
| `app/src/androidTest` | 93 `@Test` | **Compiled only** | No device, and none in CI either |
| `native/android-bridge` | 9 → **10** `#[test]` | Yes | **Yes — 10/10 pass** |
| `native/aether/aether` | 336 `#[test]` | **No** — path dependency; Cargo does not run a dependency's unit tests | No |
| `design/` node check | — | Yes | No |

### Pre-existing failures

**None observed**, because only one suite could run and it passes.

### Failures introduced by this audit

**None.** See `FINAL-VERIFICATION.md` — the Rust suite went 9/9 → 10/10 and clippy
stayed at zero.

### Environmental failures — the honest list

| Failure | Environment cause |
| --- | --- |
| Gradle cannot resolve `com.android.application:9.4.0` | Google Maven returns 404 for every path |
| `assembleDebug` not run | consequence of the above |
| `connectedAndroidTest` not run | no device or emulator; no CI job either |
| `cargo ndk` not run | NDK 29 absent, cargo-ndk absent, no Android Rust targets |
| Kotlin unit tests this audit adds | Gradle blocked |

---

## 4. Out-of-band verification of Kotlin logic

Because Gradle cannot run, the two Kotlin fixes whose behaviour is pure logic were
checked against `java.util.regex` and a Java transliteration — the same engine
`kotlin.text.Regex` delegates to, executed with `javac`/`java` (JDK 17, available).

| Artefact | Asserts | Result |
| --- | --- | --- |
| `DiagnosticsRedactionTest.java` | the exact regexes and the exact expected strings of `app/src/test/.../DiagnosticsRedactionTest.kt` | **16/16 PASS** |
| `VersionParts.java` | both the old and new version-comparison behaviour | showed the proposed fix made **no observable difference** → fix reverted (see `REJECTED-IDEAS.md`) |

**What this does and does not establish.** It establishes that the regexes match
what the Kotlin test claims, on the JVM, today. It does **not** establish that the
Kotlin compiles, that the test class is discovered, or that it passes under
Gradle. Those remain **NOT VERIFIED**.

---

## 5. Pre-existing conditions found during baseline

Not caused by this audit; recorded because they shape everything else.

| Finding | Detail |
| --- | --- |
| `README.md` understates the toolchain | said Rust 1.88.0 and Android SDK 36; the project pins 1.98.0 and `compileSdk = 37`. A new developer following the README fails to build. **Corrected** (WA-080, WA-081). |
| `docs/RELEASE.md` described removed behaviour | claimed rolling main-branch prereleases that `ci.yml` explicitly removed. **Corrected** (WA-083). |
| 429 tests never execute on any push | 93 instrumentation tests are compiled but never installed (no emulator job); 336 `aether` unit tests are skipped because it is a path dependency. See `06-TESTING-AUDIT.md` and WA-021. |

---

## 6. Baseline summary

| Dimension | Baseline |
| --- | --- |
| Rust unit tests | 9/9 pass, clippy clean |
| Kotlin unit tests | **could not run** — Google Maven unreachable |
| Android build | **could not run** — Google Maven, NDK 29, cargo-ndk all absent |
| Instrumented tests | **could not run** — no device, and none in CI |
| Documentation accuracy | 6 defects found, all now corrected |
| Defects found | 86 registered in `MASTER-ISSUES.md` |