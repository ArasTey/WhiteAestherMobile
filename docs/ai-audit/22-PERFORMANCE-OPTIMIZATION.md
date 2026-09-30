# 22 — Performance Optimisation

Nothing here is speculative. Each item names the work, the evidence that it happens, and
what it costs when it does.

## Baseline vs. after

| Measurement | Before | After | How measured |
| --- | --- | --- | --- |
| JNI crossings in the root composable body | ~1 per emission (≈1/s while connected, plus one per log line) | **1 for the process lifetime** | static + `remember` semantics |
| Update check per connect | 1 HTTPS GET ≤1 MiB + full `ZipFile` walk of the ~40 MB APK, **per connect and per reconnect** | **once per process** | static |
| Raw engine text rendered to the user | yes, in red, in the results card | **none** | UI-tree assertion, before/after |
| Unit tests | 282 | **288** | Gradle |

## Changes applied

### 1. `NativeAetherBridge.versionOrNull()` — JNI on every recomposition
`MainActivity.kt:321`

```kotlin
nativeVersion = com.whitedns.whiteaesther.core.NativeAetherBridge.versionOrNull(),
```

It sat in the root `setContent` body, which re-executes whenever any of ~16 collected
flows emits. Each execution performed a JNI crossing (`install_logger`, a `format!`, and
a JNI string allocation) to read a version that cannot change under a running process.

**Fix:** `remember { … }`. One call per process. Risk: none.

### 2. Update check on every connect
`MainViewModel.kt`, on the `EngineStage.CONNECTED` branch

```kotlin
mutableInstallable.value = runCatching { updates.check(acceptPrereleases = false) }.getOrNull()
mutableUpdate.value = UpdateChecker.check(getApplication(), BuildConfig.VERSION_NAME)
```

This ran on **every** `CONNECTED` transition — every connect and every reconnect. Each run
fetched the GitHub release listing over a fresh TLS connection, then called
`AppUpdatePolicy.variantOf(...)`, which **opened the installed ~40 MB APK as a `ZipFile`
and ran a regex over every entry** to read its architecture back.

`UpdateChecker` already throttles itself to once a day. `AppUpdateManager` throttled
nothing.

**Fix:** an `installableChecked` guard — a release cannot appear and the installed APK
cannot change its architecture while the process is alive, so a second check can only
repeat work. Risk: low; a manual refresh still goes through its own path.

### 3. Endpoint scan outcome classification
See `21-ENDPOINT-FINDER-FIX.md`. Removes a two-minute dead wait from the *appearance* of
the app and replaces an unreadable error with an actionable one. Pure function, 6 tests.

## Measured but not changed

Reported because they are real, with the reason each was left alone.

| Finding | Site | Why not now |
| --- | --- | --- |
| `chain.stop()` on the main thread at 7 sites | `AetherVpnService.kt` | Real and worth fixing, but it is the teardown path of a 3,350-line service. The same class is already wrapped correctly in two places (`:2244`, `:2725`) with a comment naming the freeze, so the pattern is established — this is a careful mechanical pass, not a drive-by edit, and it cannot be measured without a device. |
| `EngineLog` copies a 400-element list per line | `EngineLog.kt:53` | Needs a `recordAll` batch API and changes at both drain sites. Real, but the engine emits few lines when idle; the fix is safe and should be its own change. |
| Whole app recomposes per emission | `MainActivity.kt:297-375` | 16 collections in one root scope. The right fix is to move each into the screen that reads it, which touches every screen. **Highest-value remaining item**, and a large, mechanical, low-risk refactor. |
| `traffic` is a `HomeScreen` parameter | `WhiteAestherApp.kt:220` | Same shape as above; collect it in a thin `HomeScreen(vm)` overload. |
| Diagnostics report rebuilt per log line | `Screens.kt:2622` | `buildReport` runs 120 lines × 3 regexes per log line, and is only read on Copy/Send. Build it in the click handlers instead. |
| `ConnectOrb` animates at 60 fps at `IDLE` | `ConnectOrb.kt:139` | The idle breath is a deliberate "press me" affordance. Only the per-frame `Brush`/`Path`/`PathEffect` allocation hoisting is unambiguously safe. |
| Non-lazy region list with a `Locale` per row | `Screens.kt:1144` | `LazyColumn` + `remember` on `offered`. Independent and safe; not done because the screen was not reported as slow and it is a visible layout change. |
| `CarriedSocket` read has no overall deadline | `CarriedSocket.kt:107` | A real robustness gap (a peer trickling one byte per `soTimeout` holds an IO thread indefinitely). Fixing it is a loop against a deadline — correct, but it is a networking change, not a performance one. |
| `FileObserver` fans out unbounded region reads | `MainViewModel.kt:201` | Needs the same `?.isActive` guard the rest of the file already uses. One line. |

## What was deliberately not done

- **No dependency was added or upgraded.** Every change is stdlib or Compose.
- **No architecture was changed.** The `traffic`-per-frame and recomposition issues are
  scope-of-collection problems, not architecture problems, and the existing structure
  already has the right pattern used correctly in `ChainScreen` and `SplitTunnelScreen`.
- **No work was moved to `Dispatchers.IO` for its own sake.** The audit found the
  dispatcher choices are already correct on the connect path; the exceptions are
  teardown-only.
- **Nothing was optimised on intuition.** Every item above names the work it removes.