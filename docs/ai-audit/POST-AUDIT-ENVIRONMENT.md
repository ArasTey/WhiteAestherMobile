# POST-AUDIT ENVIRONMENT

Re-tested on **2026-09-30**, after the first audit pass. The instruction was explicit:
*do not pretend the Google Maven problem is solved*. It was re-tested from scratch, by
DNS, by raw URL, by redirect-following, and by protocol variant.

---

## 1. Toolchain

```
Repository:       <local clone of WhiteAestherMobile>
                  upstream https://github.com/WhiteDNS/WhiteAestherMobile
                  HEAD b55320a, branch main, working tree dirty (audit changes only)

Gradle:           9.7.1          (wrapper; distribution downloaded manually)
                  "Gradle 9.7.1 / Revision 92f0512e7f06d84621afba191f75e265363890cf"
JDK:              17.0.20 Homebrew (project requires 21)
Android SDK:      ~/Library/Android/sdk
Platforms:        android-34, android-37.0  (API 34 and 37.0; no android-36)
Build Tools:      34.0.0, 36.0.0
NDK:              27.3.13750724   (project pins 29.0.14206865)  MISMATCH
CMake:            4.4.3           (installed by this audit; project pins 3.22.1)  MISMATCH
Rust:             1.98.0          (matches native/rust-toolchain.toml)
                  targets installed: aarch64-apple-darwin only
                  required: aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android
cargo-ndk:        NOT INSTALLED
Go:               1.27.1 darwin/arm64
gh:               2.98.0
cmake:            /opt/homebrew/bin/cmake 4.4.3 (installed by this audit)
kotlinc:          /tmp/kt/kotlinc/bin/kotlinc — 2.4.20 (installed by this audit,
                  downloaded from GitHub releases)
```

`JAVA_HOME` was set to `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`,
which **does not exist**. Every Gradle invocation overrode it.

`local.properties` was created for the build attempt and has been **removed** in the
cleanup. A developer must create it:

```properties
sdk.dir=$ANDROID_HOME
```

It is git-ignored and must never be committed.

---

## 2. Network reachability — re-tested

Every URL Gradle actually uses was requested directly. Results:

| Repository | URL tested | Result |
| --- | --- | --- |
| **Google Maven** | `https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.0/gradle-9.4.0.pom` | **404** |
| **Google Maven** | `.../gradle/8.7.3/gradle-8.7.3.pom` (long-existing control) | **404** |
| **Google Maven** | `.../gradle/maven-metadata.xml` | **404** |
| **Google Maven** | `.../androidx/core/core-ktx/1.18.0/core-ktx-1.18.0.pom` | **404** |
| Maven Central | `https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom` | **200** |
| Maven Central | `.../hamcrest-core/1.3/hamcrest-core-1.3.jar` | **200** |
| Gradle Plugin Portal | `.../com.android.application.gradle.plugin-9.4.0.pom` → redirects to `repo.maven.apache.org` | **404** (AGP is not on Maven Central) |
| Gradle distributions | `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip` | **200** (85–142 MB, slow, stalls) |
| GitHub | `https://github.com` | **200** |
| GitHub releases | `https://github.com/JetBrains/kotlin/releases/download/v2.4.20/kotlin-compiler-2.4.20.zip` | **200** |
| Google GCS mirror of Maven Central | `https://maven-central.storage-download.googleapis.com/maven2/...` | **200** |
| Android SDK repository | not tested separately — the local SDK is already installed | — |

### The blocker, with its mechanism

`dl.google.com` does **not** resolve to Google:

```
$ dig +short dl.google.com
192.178.202.91
192.178.202.190
192.178.202.136
192.178.202.93
```

`192.178.202.0/24` is a **SoftLayer/IBM** range. For comparison,
`repo1.maven.org` resolves to `104.18.19.12` (Cloudflare) and `github.com` to
`140.82.121.3`.

The response is served with `server: downloads` and a genuine-looking Google
`Error 404 (Not Found)!!1` HTML page, so the interception is presenting a
Google-branded response rather than a connection refusal. The exact mechanism —
a transparent egress filter, an allowlist that answers 404 instead of refusing —
is **not determined** and does not need to be: the operational fact is that
**Google Maven serves no artifacts to this machine.**

Consequences that rule out workarounds:

- AGP and the `androidx.*` libraries are published **only** on Google Maven.
- The Gradle Plugin Portal proxies to Maven Central, which does not carry AGP.
- `maven-central.storage-download.googleapis.com` mirrors **Maven Central only** —
  it returned 200 for JUnit and would return 404 for `com.android.application`.

**No legitimate mirror exists for the artifacts that are missing.** Fabricating one
would mean substituting third-party copies of the Android Gradle Plugin, which is
precisely the supply-chain risk this audit exists to reduce. It was not done.

---

## 3. What was actually run

```
$ ./gradlew --version
------------------------------------------------------------
Gradle 9.7.1
------------------------------------------------------------
Launcher JVM:  17.0.20 (Homebrew 17.0.20+0)
OS:            Mac OS X 26.3 aarch64
```
→ **Gradle itself works.**

```
$ ./gradlew tasks
FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'com.android.application', version: '9.4.0', apply: false] was not found
  Searched in the following repositories:
    Google
    MavenRepo
    Gradle Central Plugin Repository
BUILD FAILED in 7s
```
→ **The build cannot configure.** This is the blocking fact for Phase 4.

```
$ cd native/android-bridge && cargo test --locked
test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out

$ cargo clippy --locked --all-targets
0 warnings
```

---

## 4. What was installed by this audit

These are machine-local and are **not** part of the repository:

| Item | Why |
| --- | --- |
| `cmake 4.4.3` (Homebrew) | `boring-sys` builds BoringSSL with CMake; without it even host `cargo test` cannot run |
| Gradle 9.7.1 distribution | the wrapper's own download stalled twice at ~74 MB |
| `kotlin-compiler-2.4.20` at `/tmp/kt` | lets the project's real Kotlin be compiled and its real tests run without Gradle |
| `junit-4.13.2.jar`, `hamcrest-core-1.3.jar` at `/tmp/kt` | same |

All are outside the repository. `git status` is clean of them.

---

## 5. Verdict

**Gradle build: BLOCKED.** Not by the project — by this machine's egress.

**The Android build, lint, and the 275-test Kotlin unit suite cannot be run here**, and
no claim about them is made anywhere in these documents.

**Rust build and tests: RUNNABLE**, and run.

**Real Kotlin compilation and JUnit execution: POSSIBLE** via the standalone compiler
(Phase 11), because `DiagnosticsRedaction.kt` and its test depend only on the Kotlin
standard library and JUnit — no Android, no Compose, no DataStore. That is how the
previous pass's "verified by Java mirror" claim was upgraded to "verified by running the
actual Kotlin".