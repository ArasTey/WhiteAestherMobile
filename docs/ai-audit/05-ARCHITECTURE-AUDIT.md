# 05 — Architecture Audit

**Principle applied: do not rewrite architecture for stylistic reasons.** Only structural
changes with clear engineering benefit are recommended, and most of this document is a
list of things that are *already right* and should be left alone.

---

## 1. The shape of the problem

`AetherVpnService.kt` is ~3350 lines and holds the connection state machine, the
`VpnService.Builder` configuration, retry policy, the kill switch, split tunnelling,
engine driving, carrier orchestration, the notification, and the diagnostic hooks.
`Screens.kt` is ~2830 lines and holds most of the settings and routing UI.

Both are too large. **Neither is worth splitting in this project's current state**, for
reasons set out in §4.

---

## 2. Coupling — and why most of it is correct

| Coupling | Verdict |
| --- | --- |
| `AetherVpnService` → `core/*` → native | **Necessary.** The service *is* the orchestrator. |
| Service → `data/AutoRoute` for lane planning | **Correct.** Planning is pure and separately tested; executing it belongs to the service. |
| `Screens.kt` → `AppSettings` model directly | Acceptable for a settings UI. |
| Kotlin ↔ Rust via `NativeAetherBridge` / `NativeChainBridge` | **Necessary and well-encapsulated** — two files own the whole ABI surface. |
| mihomo Go library loaded into the app process | Documented decision (`native/chain/README.md`), with the rationale that Psiphon already owns one Go runtime. |

There is no accidental dependency inversion, no domain logic importing Android framework
classes it does not need, and no platform-specific code leaking into `data/` or
`AutoRoute` planning. **This is a better-layered codebase than its file sizes suggest.**

---

## 3. Real duplication

### 3.1 `PsiphonClient.bind()` / `TorClient.bind()` — ~55 near-identical lines
`PsiphonClient.kt:140-193`, `TorClient.kt:110-159`. Identical `bind()`/`unbind()`/
`ServiceConnection`/`Messenger` plumbing; the only differences are the service class,
three extra-name constants, and `PsiphonClient`'s `MSG_NOTICE` handling.
> A shared `MessengerCarrierBinding` would remove the drift risk.
> **Low value, low risk, and not urgent.** Two call sites is not yet duplication that
> has caused a bug. Recorded, not changed — splitting it now is churn.

### 3.2 Two update systems — *the* duplication that has cost something
`UpdateChecker.kt` and `AppUpdateManager.kt` both hit
`api.github.com/repos/WhiteDNS/WhiteAestherMobile/releases/latest`:

- two comparators (`UpdateChecker.isNewer:80` with its own `numericParts`, and
  `AppUpdatePolicy.isNewer:73` with its own `parts`)
- two skip stores — `UpdateChecker.dismiss` stores the **raw tag** (`"v1.9.0"`, `:68`),
  `AppUpdateManager.skip` stores the **normalised** version (`:62-64`), so dismissing in
  one screen leaves the other still asking
- no shared throttle, so a session can spend GitHub's 60/hr unauthenticated budget twice
  as fast
- one unbounded read (`UpdateChecker.kt:110 readText()`) where the newer code bounds to
  1 MiB (`AppUpdateManager.kt:291-301`)

> This is genuine, observed drift between two implementations of one rule. Consolidating
> on `AppUpdatePolicy` and deleting `UpdateChecker` is the right fix.
> **Not applied** — it changes user-visible behaviour (which screens offer an update) and
> is a maintainer decision about which screen survives.

### 3.3 One error-handling idiom, used consistently
`runCatching` for anything expected, `runCatching { … }.getOrNull()` where a value is
wanted, and a `finally` on session teardown that stops any route not kept. Error
handling is duplicated in *shape* but not in *rule*, which is the correct outcome.

---

## 4. Why `AetherVpnService` should not be split now

The usual argument — "3,300 lines is too big" — does not hold here, and splitting would
probably make it worse:

1. **The parts are not separable.** The state machine, the generation counter, the
   `Builder`, and the retry policy share mutable state (`generation`, `sessionJob`,
   `commandMutex`, `autoSteps`, `blackhole`). Splitting produces callbacks between
   objects that must agree.
2. **The generation mechanism is the correctness argument.** A late callback from a
   dying session cannot overwrite a newer session's status because every publish checks
   `sessionGeneration`. That invariant is much easier to verify in one file, and much
   easier to break when spread across four.
3. **There is no test harness for the alternative.** Splitting would make it *more*
   testable in principle, but the extracted pieces would need a fake `VpnService`, and
   the thing that actually breaks — ordering between a native reply, a network callback
   and a user stop — is exactly what a fake would not reproduce.

> **Recommendation: leave it.** If it ever grows again, the honest seam is not
> "extract a service class" but "extract the *pure* parts" — which the project has
> already done well (`AutoPlanner`, `AppUpdatePolicy`, `Roaming`, `SplitTunnel`,
> `DnsServers`, `ChainConfig` are all pure and all unit-tested).
>
> The counter-example worth noting: `Screens.kt` at 2830 lines has no equivalent pure
> extraction for `coverageSummary()`/`routingSummary()`, which live in `Summaries.kt` —
> so there *is* a pattern to extend, and `SplitTunnel.summary()` (WA-058) is the obvious
> next candidate.

---

## 5. God classes, unclear responsibilities, fragile state

| Question | Answer |
| --- | --- |
| God classes? | Two large ones, both argued for above. No third. |
| Unclear responsibilities? | No. Every file has one job and says so in its header comment. |
| Poor abstraction boundaries? | No. `AutoRoute` is pure; `AppUpdatePolicy` is pure; the native boundary is two files. |
| Fragile state management? | **Yes, in three specific places**, all recorded as issues: `hopStages` cross-thread (WA-008), `TorCarrierService`'s unsynchronised fields (WA-012), the unbounded join (WA-010). Each is a *specific* defect, not a structural complaint. |
| Difficult-to-test components? | Yes — and correctly so. `AetherVpnService`, `MainViewModel`, both carrier services, `NetworkIdentity`, and all the UI have zero tests. That is a testing gap (`06-TESTING-AUDIT.md`), not an architecture fault. |
| Configuration duplication? | Minor: `data_extraction_rules.xml` vs `Android.xml` backup attributes (correctly reconciled with `tools:replace`), and the widget palette duplicated from Compose (WA in `04`). |
| Inconsistent naming? | No. Conventions are consistent across packages. |
| Unnecessary dependencies? | One: `thiserror` in `android-bridge/Cargo.toml:42` with zero uses in `src/`. One unused androidTest dependency: `androidx.test.ext:junit` (no test imports it). |

---

## 6. What must not change

Explicitly recorded, because an audit that only lists problems invites someone to "fix"
these:

- **`generation` discipline.** `stopFromUser:2689` and `giveUp:2975` bump `generation`
  *outside* `commandMutex` so a session wedged in a native call cannot make the service
  unstoppable. Every reconnect re-checks it. This is subtle, correct, and load-bearing.
- **`hopAttempt`/`collapsedAttempt` keying (`:156-168`, `:1082-1088`).** Stops two failing
  hops from spending two attempt budgets. The comment explains why keying on `generation`
  was wrong.
- **`clearSocketProtector` generation check (`:2746-2749`).** A dying session must not
  unprotect its successor's sockets.
- **`NativeChainBridge.kt:49-50`'s TUN-fd ownership contract.** One call site tracks
  `handedOff` and closes on failure, the other does not (WA-018) — but the contract itself
  is right and must be respected by any caller.
- **`chain.rs:307-315` deliberately leaks the events callback `Box`.** Go holds it for
  the process lifetime; documented and correct.
- **`host_protect` invoking outside the lock** (`chain.rs:216-221`). Deliberate: no mutex
  is held across a JNI call into Java, and the comment says so.
- **`jniLibs.excludes` for `libboringtun-*.so` and `libquiche.so`** plus the CI assertion
  that they are absent from the APK. Build-time only; the assertion is the point.
- **`app/proguard-rules.pro`.** Keeps JNI-looked-up class names alive, with the 1.4.0
  `NoSuchFieldError` on `TorService.torConfiguration` documented in-file.

---

## 7. Architecture verdict

**KEEP — no change required** for the layering, the native boundary, the state
publication model, and the two large files.

**Worth doing, in order:** consolidate the two update systems (3.2) → extend the
`Summaries.kt` pattern to `SplitTunnel.summary()` → drop the two unused dependencies.

**Explicitly rejected:** splitting `AetherVpnService`, splitting `Screens.kt` "because it
is large", introducing a DI framework, converting the `VpnService` to an
`AndroidViewModel`, and replacing `DataStore` with `EncryptedSharedPreferences`. See
`REJECTED-IDEAS.md`.