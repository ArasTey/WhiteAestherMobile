# BUILD-ARTIFACTS

**Date:** 2026-09-30
**Result:** `BUILD SUCCESS`
**Artifacts:** 4 installable debug APKs
**Installed and launched:** YES — on an Android 14 emulator

This supersedes the "BUILD BLOCKED" recorded in `POST-AUDIT-VERIFICATION.md` and
`01-BASELINE.md`. The blocker was real then and is resolved now.

---

## Build environment

| Component | Value |
| --- | --- |
| OS | macOS 26.3 (Darwin 25.3.0), arm64 |
| JDK | 17.0.20 Homebrew (project asks for 21 — **the build worked on 17**) |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 |
| Android SDK | `platforms/android-34`, `android-37.0`; build-tools 36.0.0 |
| NDK | **29.0.14206865** (the project's pin — installed for this build) |
| CMake | **3.22.1** + ninja 1.10.2 (the project's pin — installed for this build) |
| Rust | 1.98.0, targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` |
| cargo-ndk | 4.1.2 |

### How the Google Maven blocker was resolved

`dl.google.com` returns HTTP 404 for every Maven path from this machine — verified
repeatedly, including from genuine Google IPv6 addresses (`2a00:1450:400a::`).

`https://redirector.gvt1.com/edgedl/android/maven2/` is **Google's own CDN front-end for
the identical repository** and it works here. It is the same publisher serving the same
bytes, not a third-party mirror.

It was added **through a Gradle init script** (`-I /tmp/gvt1.init.gradle`), which is
**external scaffolding**. No project file was modified: `google()`, `mavenCentral()`,
`gradlePluginPortal()` and the Psiphon repository are all still declared, and the CDN was
*added*, not substituted. The init script lives in `/tmp` and is not part of the
repository.

The NDK and CMake were likewise installed from Google's CDN and **verified against the
SHA-1 values published in Google's own SDK repository manifest**
(`repository2-3.xml`):

| Package | URL | Published SHA-1 | Result |
| --- | --- | --- | --- |
| NDK 29.0.14206865 | `android-ndk-r29-darwin.zip` | `03d29fbb57e3c05a7d53597dd011d856c1456a4f` | **match** |
| CMake 3.22.1 | `cmake-3.22.1-darwin.zip` | `8604eeef9adadb626dbb70a7ff58a87e6a7b967a` | **match** |

No checksum was chosen here. Both were read from Google's manifest.

## Build commands

```bash
export JAVA_HOME=<homebrew jdk 17>
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$HOME/.cargo/bin:$PATH"

./gradlew testStableDebugUnitTest -I /tmp/gvt1.init.gradle          # BUILD SUCCESSFUL
./gradlew assembleStableDebug   -I /tmp/gvt1.init.gradle \
          --no-daemon --no-configuration-cache                     # BUILD SUCCESSFUL
```

`--no-daemon` is required: a reused Gradle daemon holds the environment it was started
with, and the `ninja` added to `PATH` for the CMake step was not visible to it. The first
two attempts failed with `CMake was unable to find a build program corresponding to
"Ninja"` for exactly that reason.

## Unit test result

```
282 tests, 36 test classes, 0 failures, 0 errors, 0 skipped
```

This includes the 7 new `DiagnosticsRedactionTest` cases, now running under the **real
Gradle test runner** rather than only the standalone compiler.

Compiler warnings in project sources: 3, all **pre-existing** and unrelated to this work
(`AppUpdateManager.kt:107` deprecated `setVisibleInDownloadsUi`, `ChainScreen.kt:534`
unused expression, `TypeInitOrderTest.kt:34`).

## APK artifacts

All in `app/build/outputs/apk/stable/debug/`.

| Variant | Size (bytes) | SHA-256 |
| --- | --- | --- |
| `app-stable-arm64-v8a-debug.apk` | 37 960 338 | `d59972535c2816ca7b0e02bc58f8e6f1ea797a1bbcd9fd734b14c9559e0e18d6` |
| `app-stable-armeabi-v7a-debug.apk` | 36 332 380 | `248eee522ca00892beef45b50572fcd77e2f78b916b61c45b4c966b800008c43` |
| `app-stable-x86_64-debug.apk` | 40 824 735 | `468b9029a42c8cacecdc89e298185423e0557654d60b07e312fb760643a68eec` |
| `app-stable-universal-debug.apk` | 86 534 819 | `881c7d7a9a97aa69c61c6965a0b5f267ba5b2de35e03616195b86392017b76e0` |

**For a phone, install `app-stable-arm64-v8a-debug.apk`** (or `-universal-` if unsure).

### Structural verification (`aapt2 dump badging`, `apksigner verify`)

Every APK reports:

```
package: name='com.whitedns.whiteaesther' versionCode='1' versionName='1.10.0'
compileSdkVersion='37'  targetSdkVersion:'36'
application-label:'WhiteAesther'
uses-permission: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
                 FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS,
                 REQUEST_INSTALL_PACKAGES, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                 CHANGE_NETWORK_STATE, ACCESS_WIFI_STATE
```

`apksigner verify --verbose` → **Verifies** (v2 scheme; debug signing key, as expected
for a `stableDebug` build).

### Native payload — the engine is really in there

`libwhiteaesther_core.so` **24 803 912 bytes**, built for Android by the cargo-ndk task
during this build. Alongside it: `libgojni.so` (31.2 MB, Psiphon) and `libtor.so`
(7.6 MB, Tor).

`libquiche.so` and `libboringtun-*.so` are **absent**, which is correct — they are
build-time only, and `release.yml` asserts exactly this. Quiche compiled into the engine;
its standalone library does not ship.

### What is NOT in these APKs

The **exit chain** (`libwhiteaestherchain.so`) and **Tor's pluggable transports** are
absent. They are not Gradle tasks — `native/chain/setup.ps1` + `build.ps1` and
`native/tor/setup.ps1` + `build.ps1` build them separately, and the project states a build
missing either still installs, with the dependent feature reporting itself unavailable.

So in these APKs the exit-chain second hop and Tor's bridge modes are unavailable. The
Aether, Psiphon and WireGuard carriers are present.

## Installation

```
$ adb devices
emulator-5554	device          (Android 14 / API 34, arm64-v8a, AVD "Waira_Emulator")

$ adb -s emulator-5554 install -r app-stable-arm64-v8a-debug.apk
Performing Streamed Install
Success

$ adb -s emulator-5554 shell am start -n com.whitedns.whiteaesther/.MainActivity
Starting: Intent { cmp=com.whitedns.whiteaesther/.MainActivity }
```

- **Installed: YES**
- **Launched: YES** — pid 4877, `topResumedActivity=com.whitedns.whiteaesther/.MainActivity`
- **No crash.** `pidof` still returns the same pid; logcat shows no `FATAL EXCEPTION` and
  no ANR for the package. Only benign warnings (`base.dm` absent — normal for a debug
  build; a disposed splash-screen input channel).

**This is an emulator, not a physical device.** Behaviour specific to real hardware or
real networks is not exercised by this.

## Runtime verification

### Verified — by execution, on the emulator

- The APK installs and launches.
- `MainActivity` starts and renders.
- The Compose UI draws correctly: connection orb, tab bar, page indicator.
- **Persian RTL localisation renders correctly** — the emulator's locale is
  Persian, and the whole layout is right-to-left with Persian text and Persian-Indic
  digits in the status bar. This exercises `values-fa` and `AppLocale`'s layout direction.
- The dark theme applies.
- The **native library loads** without an `UnsatisfiedLinkError` — `nativeloader`
  processed `base.apk!/lib/arm64-v8a` and the app continued to a usable screen.
- `androidx` DataStore and the settings screen work, so the WA-014 code path is live and
  did not crash on startup.

### Not verified — explicitly

**VPN runtime behaviour: NOT VERIFIED.**

Nothing below was tested, and nothing below should be inferred from the app launching:

- tunnel establishment, endpoint probing, MASQUE handshake
- VPN interface creation, route/DNS configuration, MTU
- kill-switch behaviour, **interface handover** (the WA-002 change)
- revoked-consent handling (the WA-007 change)
- DataStore corruption recovery (the WA-014 change)
- release-build logcat suppression (the WA-023 change)
- traffic routing, DNS leak prevention, IPv4/IPv6 behaviour
- network handoff, Wi-Fi ↔ cellular, screen-off, backgrounding, OEM behaviour
- widget, quick-settings tile, notification actions
- diagnostics report and the WA-024 redaction, in the running app

These require a real device and real network conditions. **An app that starts is not an
app whose tunnel works.**

### One thing that was attempted and could not be exercised

`NEXT-ACTIONS.md` item 2 asked for the `device_*` backup domains to be confirmed against a
real rules parser. A backup was triggered:

```
$ adb shell bmgr backupnow com.whitedns.whiteaesther
Backup finished with result: Backup is not allowed
```

The app declares `android:allowBackup="false"`, so the framework refuses before the
extraction rules are ever parsed. **The parser was not reached**, and the domain names
remain unverified by any tool or runtime available here. That item stays open.

## Build-time defect found and fixed during this phase

`WA-014`'s fix **did not compile.** The compiler reported, in all three DataStore files:

```
e: AddressReporter.kt:72:9   No parameter with name 'produceCorruptionHandler' found.
e: SettingsRepository.kt:28:5 No parameter with name 'produceCorruptionHandler' found.
e: UpdateChecker.kt:40:9     No parameter with name 'produceCorruptionHandler' found.
```

The `preferencesDataStore` delegate takes the handler **directly** as
`corruptionHandler`, not a producer. `produceCorruptionHandler` is the *builder* name
used by `PreferenceDataStoreFactory.create`. The correct signature was confirmed by
`javap` against the resolved `datastore-preferences-android-1.2.0` artifact rather than
assumed.

Corrected to:

```kotlin
corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
```

and the build now compiles. **This is the concrete payoff of the earlier passes refusing to
call uncompiled Kotlin "verified"** — the standing caveat was correct, and the defect was
real.

## Known limitations of these artifacts

1. **Debug builds** — signed with the standard Android debug key, not the release key, so
   they cannot be installed over a release install and have no Play-Store signature.
   `minifyEnabled`/`shrinkResources` are off, so R8 behaviour is not exercised.
2. **No exit chain, no Tor transports** (see above).
3. **Version is `1.10.0` / versionCode `1`**, derived from `git describe` outside CI.
4. **Built on JDK 17**, not the 21 the project documents. It worked; this is not a claim
   that 17 is supported.
5. **Emulator only.** Nothing here says anything about physical-device behaviour.
6. The `device_*` backup domains remain unverified.

## Reproducing

```bash
git clone https://github.com/WhiteDNS/WhiteAestherMobile.git
cd WhiteAestherMobile
./gradlew assembleStableDebug
```

On a machine with normal Google Maven access, **no init script is needed** — the CDN
workaround exists solely because of this network. You need JDK 21, Android SDK 37, NDK
29.0.14206865, CMake 3.22.1, Rust 1.98.0 with the three Android targets, and
`cargo-ndk`.
