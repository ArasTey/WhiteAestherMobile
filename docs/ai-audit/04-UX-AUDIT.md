# 04 — UX Audit

No device and no rendered build (`01-BASELINE.md`). Findings are from source, except
where a contrast ratio is **computed** from the literal palettes in `Theme.kt:49-91` —
those are labelled COMPUTED and are exact, not sampled.

Format: `Current UX → Problem → Proposed UX → Why → Implementation location`.

**Design intent was read first** (`design/PORT-STATUS.md`, `design/README.md`) and is
mostly *honoured* by the code. This is not an app that needs redesigning; it needs a
dozen targeted fixes.

---

## 1. State clarity

### 1.1 WA-016 — The "Lift the block" card was unreachable in the path it exists for — **FIXED**
`Screens.kt:504` tested `status.message == stringResource(R.string.traffic_is_blocked)`.
But `giveUp` (`AetherVpnService.kt:2993-2994`) builds its message as
`listOfNotNull(said, …, sayNow(R.string.traffic_is_blocked)).joinToString(" ")`, so the
notice arrives *inside* a sentence.

> When retries are spent under the kill switch — the one moment the card exists for — the
> only in-app control that undoes the block was not rendered, while the notification said
> "Tap to open and lift".

**Fixed** to match on containment as well as equality. Both locales work, because
`sayNow` already produces the localised string.

### 1.2 English status text under a Persian headline
`Screens.kt:411-416` renders `status.message` verbatim as the primary status text, and
every status the service builds is English prose. `MainViewModel.say()` localises the
ViewModel side; this is produced inside the service. A Persian user sees an English
sentence under a Persian headline on the app's main screen.
> Proposal: have the service emit a `@StringRes` + args (as `sayNow` does elsewhere) and
> resolve it in `HomeScreen`. Touches the service→UI contract — **not applied.**

### 1.3 Retry body built by string surgery on a localised resource
`Screens.kt:797-798` splits `status.message` around `stringResource(R.string.retry)`.
In `values-fa` that resource is `" · تلاش دوباره"`, which never matches an English
service message, so Persian gets the whole message *plus* a duplicated tail. The
`". "`, `" · "` and capitalisation are also hardcoded.
> Proposal: carry `retryAttempt`/`retryTotal` on `EngineStatus` and format from resources.
> **Not applied** — changes a published contract.

### 1.4 Two controls look enabled but silently swallow the tap
`Screens.kt:1673`, `:1683` guard with `if (!engineBusy)` on the `onClick` but give
`ChoiceCard` no `enabled` parameter, so the cards keep full-contrast styling while the
engine is busy. `EndpointScreen:1508` does it correctly. Adding `enabled` to `ChoiceCard`
and dimming it is a small, local fix.

### 1.5 Raw `Throwable.message` in the update card
`Screens.kt:540-547` renders `failed.reason`, which is the raw exception message; the
localised `the_update_could_not_be_verified` is only the `message == null` fallback and
is effectively unreachable.

### 1.6 No confirmation for any destructive action
There is **no `AlertDialog` anywhere in the app**. Identity restore (which overwrites the
device private key, `Screens.kt:2521-2528`), clear log, forget endpoint, and per-
subscription removal are all single taps. (WA-066)

### 1.7 The four-state orb is honest
`Screens.kt:171-176` derives the orb state directly from `EngineStage` and never invents
progress. `STOPPING` is deliberately non-actionable with the reason written out
(`WhiteAestherApp.kt:234-243`). The press-scale at `ConnectOrb.kt:117-123` exists
precisely because the stage change can take seconds. **This part is right.**

---

## 2. Accessibility

### 2.1 No selection or state semantics anywhere — the largest accessibility gap
`WhiteAestherApp.kt:437,515` (tab bar / nav rail), `AetherComponents.kt:532,573,634`
(`SegGroup`, `ChoiceCard`, `OptionRow`) and `ConnectOrb.kt:130` all use
`Modifier.clickable`, which yields `Role.Button` and **no `selected`**.

> A TalkBack user hears four buttons named "Home / Routes / Traffic / Settings" with
> nothing marking the current one, and on option lists one button per option with nothing
> marking the chosen carrier or protocol. `ConnectOrb` has no `stateDescription`, so the
> connection state is not announced on the control itself — it is two nodes later, in
> the pill.

Proposal: `Modifier.selectable(selected = active, role = Role.Tab)` on tabs,
`Role.RadioButton` on the option rows, and
`Modifier.semantics { stateDescription = <localised stage> }` on `ConnectOrb`.

### 2.2 `ConnectOrb`'s state lives entirely in a `Canvas`
`ConnectOrb.kt:135-178` correctly adds no semantics node for the drawn bloom/ripples/halo.
But `:200` sets `contentDescription = null` on the one *shape* difference between states
(Power/Radar/ShieldCheck/ShieldAlert). The remaining channels are `signal` colour — where
`signalIdle` on `ink2` is **3.49:1** in light (COMPUTED), below the 3:1 floor for
non-text indicators — and a 10.5 sp uppercase caption. A low-vision user is left with
text alone. Fix is 2.1's `stateDescription`.

### 2.3 Touch targets below 48 dp
`AetherComponents.kt:256` (CrumbBar back, 38 dp), `:517` (`SegGroup` height 38 dp — used
for Language, Theme, Detail level, split mode, IPv4/IPv6, keepalive), `:764`
(attention-card action chips ≈30 dp — **the only route to "Pin an endpoint" / "Change
profile" from Home**), `ChainScreen.kt:272` ("Remove" per subscription ≈28 dp).
Proposal: `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` on each.

### 2.4 A fixed checkbox that looks interactive
`Screens.kt:2685` `CheckRow(..., true, null)` — `CheckRow:2756-2771` drops `toggleable`
when `onCheckedChange == null`, so TalkBack gets a plain text node while the box shows a
brand-filled check. Only the subtitle distinguishes it, and a screen reader skips it.

### 2.5 `AetherCard(tvFocusable = true)` creates focus stops that do nothing
`AetherComponents.kt:294-303` calls `.focusable()` with no action and
`semantics(mergeDescendants = true)`. Harmless today (the four focusable Home cards are
read-only) but a trap: the first interactive element added inside one becomes a single
merged node whose `onClick` is swallowed.

---

## 3. Typography, RTL and localisation

### 3.1 Locale parity is genuinely good — verified by diff
`values/strings.xml` has 563 keys, `values-fa/strings.xml` 557. The six missing are
**all `translatable="false"`** (`app_name`, `https`, `vless`, `elapsed_clock`, and two
example-host keys). **There are no missing Persian translations and no orphan keys in
`values-fa`.**

Nine translated keys are referenced nowhere in code, four of which should be:
`split_every_app`, `split_none_chosen`, `split_only_count`, `split_except_count` — see 3.2.

### 3.2 WA-058 — `SplitTunnel.summary()` is hardcoded English and rendered on screen
`data/SplitTunnel.kt:68-75` returns `"Every app on this phone"`, `"No apps chosen yet"`,
`"${packages.size} app only"`. Its own comment says it is English "on purpose … for the
diagnostics log" — and it is rendered at `SplitTunnelScreen.kt:166` and
`Screens.kt:1647`. The correct `split_*` resources exist in **both** locales and are
referenced nowhere. Same class: `Screens.kt:832-833` (per-app rule note),
`:1432` ("Automatic" — `EndpointMode.label` already carries it), `:2800` (device line).

### 3.3 WA-060 — Mono styles with no Persian glyphs carry localisable text
`Type.kt:224-238` deliberately keeps `Data`/`DataLarge`/`LogLine` in Plex Mono, and
`PersianTypefaceTest.theMonoFaceHasNoPersianCounterpart` asserts it. But `Data` renders
`R.string.shown_size_events` (`Screens.kt:2642` → `"۵ رویداد"`), `tor_bridges_have`
(`:1199-1204`), bridge counts, and the Traffic screen's DNS/port supporting text — all of
which fall back to a system-substituted face, exactly the failure `Type.kt:20-30` avoids
for the main face. Proposal: a `DataText` style on the proportional family for prose,
keeping `Data` for addresses/ports/RTT.

### 3.4 WA-059 — Icons that must mirror in RTL do not
`AetherComponents.kt:63-81` builds every `ImageVector` without `autoMirror = true`;
`:124` `Back` and `:125` `Chevron` are directional. Under Persian
(`AppLocale.wrap` sets `configuration.setLayoutDirection`, `AppLocale.kt:90`) the CrumbBar
back arrow still points left and the row chevron still points right. The *text* arrow at
`Screens.kt:219` is handled correctly, which makes the inconsistency stand out.
Fix: pass `autoMirror = true` for `Back` and `Chevron`.

### 3.5 WA-061 — Text clipped at large font scales
`AetherComponents.kt:799`, `:829` — `Text(text, maxLines = 1)` inside `.height(50.dp)`.
`Text`'s `overflow` defaults to `Clip`, so at `fontScale = 1.3` (a common accessibility
setting) "Download and install" is cut mid-glyph with no ellipsis — unlike every other
truncating `Text` in the app. Same class: `PageTitle:229-233` (`maxLines = 1`) truncates
every page title, and `.height(58.dp)` at `CrumbBar:247-249` / `HomeScreen:319-321` clips
wrapping text.

### 3.6 Sub-12 sp type no per-locale override can reach
`AetherComponents.kt:588` (9.5 sp tag), `:583`, `:838` (12.5 sp), `ConnectOrb.kt:207`
(10.5 sp — the connect control's only label). These are inline `sp` literals, so
`values/type.xml` and `values-fa/type.xml` cannot raise them even though
`values-fa/type.xml:9-16` argues Persian "is if anything harder to read small".

### 3.7 No Persian coverage in the test suite
Every `androidTest` assertion targets English text, and the tags are derived from the
*resolved label* (`AetherComponents.kt:534,575,636`), so under Persian they become
`option-سایفون`, `choice-شبکهٔ سخت‌گیر`. The elaborate RTL machinery has no test at all.

---

## 4. Theming

### 4.1 WA-054 — Light theme draws light status-bar icons on a near-white background
There is **no `values-night/styles.xml`**. `values/styles.xml:2-8` sets
`windowLightStatusBar=false` and `navigationBarColor=#0B0D0D` unconditionally, while
`MainActivity:295` calls `enableEdgeToEdge()` and `WhiteAestherApp.kt:394-399` paints
`ink1` under `statusBarsPadding()` — `#F6FAF8` in light mode. Clock and battery are
white-on-white, and the navigation bar stays near-black in light mode.

### 4.2 WA-055 — Measured contrast failures (COMPUTED from `Theme.kt:49-91`)

| Pair | Dark | Light | Used for |
| --- | --- | --- | --- |
| `text3` / `ink2` | **3.75** | **3.93** | `Note()`, `SectionLabel`, log timestamps, empty states |
| `text3` / `ink1` | 3.96 | **3.73** | Home-level notes |
| `brand` / `ink2` | 9.36 | **4.07** | `SegGroup` active label, `ChoiceCard` tag, `TabBar` active label |
| `signalLive` / `ink2` | 9.36 | **4.07** | `RateColumn` download rate at 20 sp (under the 24 sp "large text" threshold) |
| `signalIdle` / `ink2` | 5.19 | **3.49** | `CarrierPathRow` stopped hop |
| `line` / `ink2` | **1.99** | **1.38** | Unchecked `Switch`/`CheckRow`/`OptionRow` borders — WCAG 1.4.11 wants 3:1 |

Fix in `Theme.kt:71-91` (light palette, plus `line` in both): darken `text3` to reach
≥4.5 on `ink3`, darken `brand`/`signalLive` ~10% for light, and introduce a distinct
`controlBorder` token at ≥3:1 rather than reusing `line`.

### 4.3 Disabled button keeps full-strength label text
`AetherComponents.kt:790` fades the track to 40% alpha but `:799` does not gate the label
colour on `enabled`. COMPUTED `onBrand` on brand@40% over `ink2`: **2.35:1 dark /
1.69:1 light**. Live in `ChainScreen.kt:305-320` and every empty-state button.
`OutlineButton:827` already gates its label — copy that.

### 4.4 Widget palette is hand-duplicated
`values/widget_colors.xml` and `values-night/widget_colors.xml` repeat `Theme.kt`'s
hexes; the file's own comment acknowledges the drift risk. A unit test asserting each
`widget_*` equals the corresponding `AetherColors` field would be the only mechanism
that catches a palette edit. **No dynamic colour** — consistent with the authored-palette
rationale at `Theme.kt:21-25`, and not flagged as a defect.

---

## 5. UX findings summary

| ID | Finding | Status |
| --- | --- | --- |
| WA-016 | "Lift the block" card unreachable on the give-up path | **Fixed** |
| WA-056 | No selection/state semantics; `ConnectOrb` has no `stateDescription` | Proposed upstream |
| WA-057 | Touch targets under 48 dp, including the only route to two Home actions | Proposed upstream |
| WA-058 | `SplitTunnel.summary()` and three other strings are English on screen | Proposed upstream |
| WA-059 | `Back`/`Chevron` do not mirror under RTL | Proposed upstream |
| WA-060 | Mono face with no Persian glyphs carries localised text | Proposed upstream |
| WA-061 | `maxLines = 1` + `Clip` cuts labels at large font scale | Proposed upstream |
| WA-054 | No `values-night/styles.xml`; white-on-white status bar in light mode | Proposed upstream |
| WA-055 | Six measured contrast failures | Proposed upstream |
| WA-062 | Home recomposes every second while connected | Proposed upstream |
| WA-065 | On TV, remote BACK and gamepad B disagree at top-level tabs | Proposed upstream |
| WA-066 | No confirmation for destructive actions | Proposed upstream |
| — | English status text, retry string surgery, silent busy controls, raw exception text | Documented, not applied |
| — | Landscape cutout, large-screen/foldable layout, colour-only severity | SUSPECTED — need a device |

**No UX regression was introduced by this audit.** The one change, WA-016, adds a
card that was previously unreachable in one state.