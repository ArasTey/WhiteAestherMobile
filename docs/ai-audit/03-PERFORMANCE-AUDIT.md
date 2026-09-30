# 03 — Performance Audit

**Measurement caveat, stated once and applying throughout:** this audit had no device,
no emulator, and no working Gradle build (`01-BASELINE.md` §2.1). Nothing here is a
profiler reading. Findings are either (a) counted from source, (b) a contrast/threshold
computed from literal values, or (c) a structural argument from the code path. Each is
labelled. **No claim is made about real-world frame times, battery draw, or throughput.**

Format per finding: `Current → Evidence → Bottleneck → Proposal → Expected effect → Risk`.

---

## 1. Startup and connection

**VERIFIED (structural) — the connect path is careful; the teardown path is not.**

`AetherVpnService.kt:2244` wraps even a log-only `chain.nodes()` call in
`Dispatchers.IO`, with a comment at `:2239-2243` saying that doing otherwise "is the
freeze people saw on connect". Every blocking JNI call on the connect path is
off-main: `:731-732`, `:822-823`, `:1240-1254`, `:1710-1717`, `:2211-2217`, `:2244`.

The three teardown paths are not:

- `onDestroy` `:566-569`
- `replaceSession` `:599-601`
- `stopFromUser` `:2691-2693`

Each calls `runCatching { chain.stop() }`, which drains the event stream, stops the log,
stops the TUN pump and **shuts down a Go runtime** — on `Dispatchers.Main.immediate`.

> Current: `chain.stop()` on the main looper in three places
> Evidence: the connect path's own comment identifies this exact hazard
> Bottleneck: Go runtime teardown on the UI thread
> Proposal: wrap the three sites in `withContext(Dispatchers.IO)`
> Expected effect: removes a main-thread stall of unbounded length on every disconnect
> Risk: touches shutdown ordering — **needs a device profile before applying.** Recorded
> as WA-009, not changed.

**VERIFIED (structural) — a deadline that cannot fire is computed the right way.**
`AetherCarrierClient.start:66-85` computes the deadline *before* the blocking JNI
`prepare` and runs the watchdog on a separate coroutine, because "nothing suspends
inside it for a timeout to fire at". Most implementations get this wrong. Keep.

**VERIFIED (structural) — `AetherVpnService.kt:606-607` joins a session job with no
timeout while holding `commandMutex` on Main.** `sessionCancellable` is set true only
at `:1291` and `:1649`; `runSession` sets it false at `:617`. The identical wait in
`stopFromUser:2705-2707` *is* bounded by `withTimeoutOrNull(4_000)`. If a session is
wedged in a native read, the mutex is held forever on Main and every later
`replaceSession`/`networkMayHaveChanged` queues behind it.

Recorded as WA-010, **not changed** — timing out the join and starting a new session
would let a dying session's `NativeAetherBridge.stop()` kill its successor, and the
right fix is a session-ownership design decision that needs a device.

---

## 2. Bounded work — the good part

Counted from source, and this is the strongest area of the project.

| Property | Where | Verdict |
| --- | --- | --- |
| Search is deadline-bounded | `AetherVpnService.deadlineMs`, `AutoRoute.kt:308 fitsAgain` | No unbounded search |
| Reconnect attempts capped | `MAX_RECONNECT_ATTEMPTS = 8` (`:3270`) | Terminal `giveUp` |
| Backoff bounded | `reconnectDelayMs` = `3s shl (attempt-1)`, `coerceIn(0,5)` → ≤60 s | Capped |
| Lane pause bounded | `AutoRoute.kt:269-273` `LANE_ROUND_FLOOR_MS shl … coerceIn(0,3)` | ≤8 min |
| Registration backoff bounded | `identity.rs:150-172` `.max(step)` | Cloudflare's `Retry-After` can only lengthen |
| Engine log bounded | `EngineLog` 400 entries, in-memory | No unbounded growth |
| Route memory expires | `RouteMemory.FORGET_AFTER_MS` 14 d, `ENGINE_RETRY_AFTER_MS` 6 h | No ossification |
| Proxy credential list | `CarriedSocket`/`ChainConfig` single MATCH rule | O(1) matching |
| `RealityNodes.detect` | `@Synchronized`, cached on a directory-listing stamp | Not re-decoded per call |
| No `GlobalScope`, no `runBlocking`, no `WakeLock`, no `onTaskRemoved` | whole service | Clean |
| No leaked coroutine scope | `serviceScope` cancelled in `onDestroy:570`; `lanesScope` cancelled in `finally:1853` | Clean |

Two subtle correctness-of-backing-off details worth keeping:
- `autoStages` is `@Volatile` and **replaced**, not mutated, because cross-thread reads
  of a mutated collection are unsafe. `hopStages` missed this — see WA-008.
- `outcomes` visibility in `AetherCarrierClient` is safe despite a non-`@Volatile` `var`:
  it is only read on the `job.onJoin` path, which supplies the happens-before edge, and
  the timeout branch does not read it at all.

---

## 3. UI recomposition

**COUNTER (structural) — two one-second clocks restart the whole Home body.**
`Screens.kt:289-315` — two `LaunchedEffect` loops with `delay(1000)` write
`mutableLongStateOf`. `elapsed` (`:482-490`) and `searching` (`:437`) are read *inside*
the `ScreenColumn` content lambda, so the nearest restart scope is that whole lambda:
every second the entire Home subtree re-executes — all `stringResource` calls,
`formatBytes`, `formatRate`, the `homeAttention` `when`.
> Proposal: extract each clock into a small child composable that owns its
> `LaunchedEffect`, so only those restart. Low risk, purely local. **Not applied** — it
> is a visual-behaviour change that should be eyeballed on a device. (WA-062)

**COUNTER — app icons are re-rasterised on every scroll-off/scroll-on.**
`SplitTunnelScreen.kt:239-248` launches `produceState(context, packageName)` per row and
`InstalledApps.icon():65-66` has **no cache**, so `drawable.toBitmap(96,96)` runs again
each time the item re-enters composition. With 200 packages and a fast flick this
repeats. The comment at `:236-238` believes deferring to the row solved it.
> Proposal: `LruCache<String, ImageBitmap>` keyed by package name. (WA-063)

**COUNTER — a `remember`d network lookup goes stale.**
`Screens.kt:1777-1779` caches `LocalAddress.onLocalNetwork()` on a boolean, so joining a
different Wi-Fi shows the previous network's address with no way to refresh. (WA-064)

**COUNTER (benign) — `ChainScreen.kt:395-399`'s `remember`ed `togglePick` does not do
what its comment claims.** The Compose compiler treats `Function1` parameters as
unstable, so `NodeRow` is non-skippable regardless. The code is harmless; the comment is
wrong. Bounded by the viewport via `items(ordered, key = { it.name })`. (Noted, not
changed — correcting a comment without the associated refactor adds noise.)

**COUNTER (low impact) — `ScreenColumn` composes every row.** All screens except Split
tunnel and the chain node list use `verticalScroll`, so `RoutesScreen`'s ~15 `OptionRow`s
are composed regardless of scroll position. `ChainScreen` shows the author knew: it uses
a `LazyColumn` with a fixed `heightIn(max = 420.dp)`. Impact is bounded at these list
sizes; the unbounded case is `EndpointScreen.kt:1562` `scannerState.results.forEach`,
which is non-lazy and has no cap.

---

## 4. Memory and threads

**VERIFIED — thread hygiene is good.** `tun.rs:89-97` sets the cancel flag then `join()`s
both the reader and the writer, with the reader's 250 ms `poll` bounding the join;
`lib.rs:941-943` calls `pump.stop()` on every exit from `block_on`, after the tokio
runtime is dropped. No mutex is held across a JNI call into Java — checked every lock in
the JNI path.

**Gap — `TunPump` has no `Drop`.** `stop(mut self)` is the only joiner. Every current
path reaches it, but any future early `return` between `TunPump::start` and the end of
the `block_on` would detach both threads and leak both fds. A `Drop` impl closing it
would be free insurance. Not applied — it is a change in a file that cannot be compiled
here (`tun.rs` is `cfg(target_os = "android")`; see WA-019).

**Low — `AppUpdateManager.kt:234-237` opens the installed ~50 MB APK as a `ZipFile` on
every `installed()` call.** Bounded, not a hot path: `progress()` only calls `verified()`
on the `apk.isFile`/success branches, never while running. Still redundant work at the
moment of decision; `variant` is a pure function of the APK and could be `lazy`-cached
per process. (WA-005 note in the register.)

**Low — `PsiphonService.kt:59-65` never dedups its `Messenger` client list.** A client
dropped without `MSG_UNREGISTER` leaks an entry for the life of the `:psiphon` process,
and `broadcast()` then pays `clients.toList()` on each of tunnel-core's hundreds of
notices. (WA-042)

---

## 5. What was deliberately chosen and should not be "optimised"

- **`jniLibs.useLegacyPackaging = true`** — compresses ~82 MB of native engines instead
  of storing them page-aligned. Costs install time and disk; buys a large download
  reduction on a metered connection in a country where that is the whole point. The
  comment at `app/build.gradle.kts:157-172` reasons it properly. **Leave it.**
- **`org.gradle.configuration-cache=true` with a `ValueSource`** instead of a
  config-time `exec` (`app/build.gradle.kts:349-370`). Correct and deliberate.
- **No `apply { }` chasing; no `LazyColumn` everywhere.** The laziness is used where
  lists can actually be long.
- **`AddressReporter` reports the device IP to Cloudflare only**, with the reasoning
  written down. That is one call, on user action, to a party that already terminates the
  tunnel.

---

## 6. Performance summary

| Rank | Finding | Status |
| --- | --- | --- |
| 1 | Teardown on the main thread (WA-009) | Not changed — needs device profile |
| 2 | Unbounded `join` under `commandMutex` (WA-010) | Not changed — design decision |
| 3 | Home recomposes every second while connected (WA-062) | Proposed upstream |
| 4 | Icon rasterisation uncached (WA-063) | Proposed upstream |
| 5 | Non-lazy scan-results list, uncapped (EndpointScreen) | Proposed upstream |
| 6 | Stale LAN address (WA-064) | Proposed upstream |
| 7 | Unbounded `Messenger` client list (WA-042) | Not changed |

**No performance regression was introduced by this audit.** The only behavioural change
with a plausible cost is WA-007, which wraps `establishTun` in `runCatching` — one
allocation per connection attempt, on a path that already does a multi-minute network
scan.