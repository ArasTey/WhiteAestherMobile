# 10 — Compatibility Audit

**Verification level for this entire document: static analysis only.**

No physical device and no emulator was available, and no APK could be built
(`01-BASELINE.md` §2.1). **Nothing here is a claim that the app works on anything.**
Every item is either read from a manifest/build file (VERIFIED-STATIC) or is an explicit
statement of what is unknown (NOT VERIFIED).

---

## 1. Platform configuration

| Setting | Value | Source | Assessment |
| --- | --- | --- | --- |
| `minSdk` | **26** (Android 8.0) | `app/build.gradle.kts:60` | Matches the README. `ParcelFileDescriptor.detachFd()` (API 12) and `Instant` usage are well under this. |
| `targetSdk` | **36** | `:61` | Deliberate, with the reason written in the comment at `:52-55`. |
| `compileSdk` | **37** | `:55` | Required to compile tor-android. The 37/36 split is documented in-code and now in the README. |
| `ndkVersion` | `29.0.14206865` | `:56` | Hard pin. |
| JDK target | 17 | `:153-154`, `kotlin { jvmTarget }` | Consistent with AGP 9.x. |
| ABIs | `armeabi-v7a`, `arm64-v8a`, `x86_64` + universal | `:20`, `:96-107` | Matches the README and the release matrix; `ci.yml:97` and `release.yml:171` both assert the same three. **No architecture ships untested.** |

VERIFIED-STATIC — the compile/target/ABI story is internally consistent everywhere it
appears. (The README said otherwise; corrected, WA-081.)

---

## 2. Android version behaviour

### Handled correctly — VERIFIED-STATIC

| Behaviour | Where | Note |
| --- | --- | --- |
| Foreground service type, API 34+ | `manifest:100-107`, `AetherVpnService.kt:3028-3040` | `specialUse` declared, `FOREGROUND_SERVICE_SPECIAL_USE` permission present, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` set, and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` passed only on API 34+. **Correct and complete.** |
| Edge-to-edge, Android 15 | `MainActivity.kt:295` `enableEdgeToEdge()` + `statusBarsPadding()`/`navigationBarsPadding()` in the UI | Handled properly, no opt-out escape hatch. |
| Background start restrictions | `AetherWidgetProvider.kt:84-90` routes a widget tap through an invisible activity, with a comment explaining that starting an FGS from a broadcast is refused on recent Android | The right pattern. |
| `registerReceiver` on API 34+ | `TorCarrierService.kt:134-139` uses `RECEIVER_NOT_EXPORTED`; unregistered in `onDestroy:162` | Correct. |
| Notification permission, API 33+ | `MainActivity.kt:398-405` requests it, and proceeds if denied | Correct at the activity. **Gap at the other entry points — WA-011.** |
| `VpnService` consent | `prepare(this)` then `establish()`; `Builder.setConfigureIntent` supplied | Correct. |
| `PendingIntent` mutability | **Every** `PendingIntent` in scope is `FLAG_IMMUTABLE` — notification content `:41`, stop `:47`, tile `:106`, widget `:108`, with a distinct request code per widget action `:102-109` and the reason written down | A real bug class, explicitly avoided. |
| Network callback | `NET_CAPABILITY_NOT_VPN` so the app never watches its own interface (`:356-359`); unregistered in `onDestroy` with `runCatching` (`:557-563`) | Correct, no leak. |
| `START_STICKY` restart | `:514-542` reaches `startForeground` before `replaceSession`; proxy mode is deliberately non-sticky and its persisted config is erased on connect (`:496-510`) | A sticky restart cannot resurrect a whole-device tunnel nobody asked for. Correct. |

### Concerns

**WA-011 (Medium) — `POST_NOTIFICATIONS` is requested only from the activity.**
`AetherTileService.connect:76-89` and `WidgetActionActivity.connect:45-79` call
`ContextCompat.startForegroundService` directly. On API 33+ with the permission denied,
`startForeground` still succeeds but nothing is shown — and the notification is
`setOngoing(true)` (`AetherNotification.kt:57`), so there is no stop action anywhere and
no swipable notification. A user who connects from the tile and denied notifications has
a VPN they can only stop by force-stopping the app.
**SUSPECTED.** The right remedy is a notification shown by the service itself, which is an
OS-policy question. Not changed.

**WA-040 (Low) — the tile's foreground-service start may be refused on Android 12+.**
`TileService` is bound, not visible, and has no FGS of its own. If
`ActiveServices.shouldAllowStartForegroundService` has no exemption for a bound
`TileService`, `ForegroundServiceStartNotAllowedException` is thrown inside a
`scope.launch` with no handler — a crash. The sibling widget path deliberately avoids
exactly this by routing through an invisible activity.
**SUSPECTED.** Test on API 31+ with the app backgrounded and notifications denied.

**SUSPECTED — content under the display cutout in landscape.** `MainActivity:295` uses
`enableEdgeToEdge()` with the default automatic style, and `WhiteAestherApp.kt:394-399`
consumes only `statusBarsPadding()` on the non-TV branch — no `safeDrawing`, no
`displayCutoutPadding`, no horizontal insets. The TV branch *does* apply
`TvUiPolicy.safeHorizontalInset = 48.dp`, so the protection exists but is TV-only.
Test with `adb shell wm size` on a notched device in landscape.

**SUSPECTED — no large-screen or foldable layout.** `TvUiPolicy.maxShellWidth = 1120.dp`
caps only the TV shell; the phone/tablet branch is a fixed `Column` + `TabBar` with
`ScreenColumn` hard-coding `padding(horizontal = 18.dp)`. There is no `res/values-w*` or
`values-sw*` directory. On a tablet, cards stretch to full width.

---

## 3. OEM behaviour — entirely unverified

**NOT VERIFIED.** Nothing in this audit ran on hardware, and no OEM was involved.
Specifically unknown:

- Xiaomi/HyperOS, Huawei, Oppo, Vivo, Samsung background-killing behaviour
- Whether the tunnel survives screen-off on any vendor
- Battery-optimisation exemption prompts and whether manufacturers honour them
- Notification behaviour on OEM ROMs (notably `AetherNotification.build`'s
  `addAction(0, …)` at `:59` — a zero icon, which modern API levels tolerate but some OEM
  skins object to)
- App-launch behaviour of the QS tile and widget across skins

`README.md` step 3 tells users a card appears if Android may still suspend the app. That
is the correct design; whether it works is device-specific and untested here.

`docs/DEVICE_TEST_PLAN.md` exists and is the right artefact for this — it should be run
by someone with hardware.

---

## 4. Network behaviour — unverified, and stated as such

**NOT VERIFIED.** No restrictive network was available. The audit could and did inspect
state machines, timeouts, retry and backoff bounds, cancellation, mocked failures,
protocol handling and configuration. It did **not** test:

- real connectivity through a hostile network
- IPv4/IPv6 path behaviour on a network with broken IPv6
- network handoff (Wi-Fi ↔ cellular) under real conditions
- MASQUE-in-MASQUE, HTTP/2 fallback, or QUIC blocking behaviour in practice
- DNS leak prevention under real conditions

What *is* established statically, and is worth stating because it is usually the thing
that goes wrong:

- **Retries are bounded.** `MAX_RECONNECT_ATTEMPTS = 8`; `reconnectDelayMs` is
  `3s shl (attempt-1)` clamped to 60 s; `giveUp` is terminal. No infinite retry.
- **Lane pause is bounded** to ≤8 min (`AutoRoute.kt:269-273`).
- **Registration backoff is bounded** and Cloudflare's own `Retry-After` can only
  *lengthen* it, never shorten below the ladder (`identity.rs:150-172`).
- **Every search has a deadline** (`deadlineMs`, `fitsAgain`).
- **Remembered routes expire** (`RouteMemory.FORGET_AFTER_MS` 14 d,
  `ENGINE_RETRY_AFTER_MS` 6 h).

**Specific network findings, static:**

| ID | Finding |
| --- | --- |
| WA-050 | `ChainConfig.kt:279-280` dials DoH to the `1.1.1.1` literal *through* the carrier, which `AddressReporter.kt:112-115` states refuses a port forward to 1.1.1.1. Dead on the Psiphon and Tor paths; `dns.google` carries the load. **SUSPECTED** — needs a live carrier run to confirm. |
| — | `ChainConfig.kt:68` sets `log-level: info`, whose lines carry matched destination hostnames — combined with `collectEvents:262` and `redactAddresses` (IP-literals only), the default diagnostics report discloses browsing history with the chain enabled. **CONFIRMED by reading**, see WA-015. Not fixed: the correct remedy changes what the maintainer offers users for support. |
| — | `ChainConfig.kt:68` binds mihomo's DNS listener to `0.0.0.0:1053` while the external controller is deliberately kept off all interfaces (`:63-66`). On a shared network that is an open resolver. Low; `AppSettings.lanSharingWarning` acknowledges the shared-network case. |

---

## 5. Split tunnelling and whole-device modes

VERIFIED-STATIC — the four modes are modelled (`EngineMode`, `Coverage`), routes are
configured conditionally on `splitTunnel`, and `applySplitTunnel(builder, …,
excludeSelf = forChain)` keeps the app's own sockets off the interface when the chain
runs, which is what makes the kernel-level protection comment at `:2572-2577` meaningful.

`app/src/androidTest/.../SplitTunnelSessionTest.kt` exercises the real `Builder` **on a
device only**, behind `split=true` — so it is off in any normal run. `NOT VERIFIED`
here.

---

## 6. Compatibility verdict

| Dimension | Status |
| --- | --- |
| SDK/ABI configuration | **VERIFIED-STATIC** — internally consistent everywhere |
| FGS type, edge-to-edge, PendingIntent mutability, network callbacks | **VERIFIED-STATIC** — all correct |
| Device behaviour on any physical device | **NOT VERIFIED** — no device |
| OEM-specific behaviour | **NOT VERIFIED** — not attempted |
| Real restrictive-network behaviour | **NOT VERIFIED** — not attempted |
| Landscape cutout, tablet/foldable layout | **SUSPECTED** — plausible, needs a device |
| Tile FGS start on API 31+ | **SUSPECTED** — needs a device |
| Notification-permission gap on tile/widget paths | **CONFIRMED-STATIC** (WA-011), device would confirm impact |

**Compatibility was NOT AUDITED in the sense of "tested".** It was audited as *"is the
configuration coherent and does the code do what the platform requires"* — and that part
is in good shape. The gap is entirely in runtime verification, which needs hardware this
environment does not have.