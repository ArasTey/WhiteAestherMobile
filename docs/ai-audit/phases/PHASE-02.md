# Phase 02 — Critical correctness fixes

## Objective

Fix the three defects where the app can misreport or lose control of a live tunnel:
a stop channel that disarms itself, a kill switch that silently stops blocking, and a
`VpnService.Builder` path that can crash the process.

## Starting State

`b55320a`, unmodified. Rust suite 9/9. Gradle unusable (`01-BASELINE.md` §2.1).

## Files Inspected

`native/android-bridge/src/lib.rs` (the `nativeRun`/`nativeStop` pair and the test
module), `service/AetherVpnService.kt` (`raiseBlackhole`, `dropBlackhole`,
`establishTun`, `giveUp`, `replaceSession`, `reportConnected`, `onDestroy`,
`ACTION_LIFT_BLOCK`).

## Problems Found

### WA-001 — a refused second `nativeRun` disarms the *running* session's stop channel

`STOP_SENDER` is one process-global `oneshot::Sender`. `nativeRun` installs it under a
guard, and its blanket failure cleanup cleared it:

```rust
let mut sender = STOP_SENDER.lock();
if sender.is_some() {
    return Err("engine is already running".into());   // returns from the closure
}
*sender = Some(stop_tx);
```
```rust
.unwrap_or_else(|error| {
    STOP_SENDER.lock().take();                        // runs for that error too
    error_response(error)
});
```

The refusal returns **before installing a sender of its own**, so the cleanup took the
**other** session's sender. `nativeStop()` then returns `JNI_FALSE` and the live tunnel
cannot be stopped through JNI — only by the engine ending on its own. The Kotlin stop
path calls `NativeAetherBridge.stop()` and reads that `false` as success.

### WA-002 — the kill switch silently stops blocking

The platform allows one VPN interface per owning package, and a second
`Builder.establish()` deactivates the first. `dropBlackhole()` is called from exactly
three places — `ACTION_LIFT_BLOCK`, `onDestroy`, `reportConnected` — and **not** from
`replaceSession`, while `establishTun` is called at three sites that all run after a
blackhole may be up.

So: kill switch on → connect → 8 attempts fail → `giveUp` raises the blackhole → the
user taps Connect → `establishTun` takes over the interface while `blackhole` still holds
a dead descriptor → if that session also fails, `raiseBlackhole` hits
`if (blackhole != null) return true` and reports **traffic blocked while it is not**.

### WA-007 — `establishTun` is not exception-safe

`return builder.establish()` is unguarded. `Builder.establish()` throws
`IllegalStateException`/`SecurityException` if consent is revoked between `prepare()`
and the call; `addAddress` throws via `require()`. `serviceScope` has no
`CoroutineExceptionHandler`, so a throw reaches
`Thread.defaultUncaughtExceptionHandler` — a crash, not a reported error.

**The inconsistency is the evidence:** every other failure in that method is wrapped
(`addDnsServer:2663`, the allow/deny calls `:2445-2496`), and the sibling
`raiseBlackhole:2534` uses `runCatching { builder.establish() }.getOrNull()`.

## Changes Made

| File | Change |
| --- | --- |
| `native/android-bridge/src/lib.rs` | Added `use std::cell::Cell`; a `let armed = Cell::new(false)` set at the moment this call installs its sender; the blanket cleanup now takes only when `armed` is set. |
| `native/android-bridge/src/lib.rs` | Added `refusing_a_second_run_leaves_the_first_runnable`. |
| `service/AetherVpnService.kt` | `establishTun` body wrapped in `runCatching { … }.onFailure { … }.getOrNull()`; `dropBlackhole()` called immediately before `builder.establish()`. |

## Why Each Change Was Made

**WA-001** — the cleanup exists so a half-built engine cannot leave a stop channel
nobody owns. That is right; it just needs to clean up only what it created. Tracking
ownership with a local `Cell` is the smallest expression of that, and it cannot leak:
every path that installs a sender sets it, and every path that fails after installing
one now clears it.

**WA-002** — `dropBlackhole()` immediately before `establish()` keeps the field
describing the interface that actually exists. It is also the ordering the platform
itself imposes: establishing a new interface already tears the old one down, so dropping
first is not a new race — it is making the code say what is about to happen.

**WA-007** — returning `null` is what every call site already handles (each of the three
is nullable-aware). Wrapping makes the method consistent with its own siblings and turns
a crash into the failure the user should see.

## Tests Added

`refusing_a_second_run_leaves_the_first_runnable` — models the guard's own body up to
the point the sender is installed, then the blanket failure path, then asserts the
running engine's channel survived and delivered.

**No test for WA-002 or WA-007.** Both are `VpnService` lifecycle defects; proving them
needs a device with VPN consent. This is stated rather than papered over.

## Tests Run

```
$ cd native/android-bridge && cargo test --locked
running 10 tests
test tests::refusing_a_second_run_leaves_the_first_runnable ... ok
  … 9 others … ok
test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out

$ cargo clippy --locked --all-targets
  → 0 warnings
```

## Test Results

**10/10 pass** (was 9/9). Clippy clean.

## Build Results

Rust: compiles and tests clean. Kotlin: **not built** — Gradle blocked.

## Regressions Checked

- The new test was **proven to fail against the original code**: restoring the
  unconditional `STOP_SENDER.lock().take()` in the test's model produced
  `test result: FAILED. 9 passed; 1 failed` on `refusing_a_second_run_leaves_the_first_runnable`.
  A test that passes both before and after would prove nothing.
- The test was also caught being **wrong**: its first version did
  `STOP_SENDER.lock().take()` and dropped the sender, which *cancels* a `oneshot` channel
  rather than releasing it, so `try_recv()` failed. `nativeStop` sends before the sender
  drops; the test now mirrors that, with a comment saying why.
- `establishTun` was checked for early `return`s before being wrapped — `runCatching` is
  inline, so a non-local return would have bypassed `.onFailure`. There are none.
- Kotlin build not verified. This is the honest gap.

## Known Limitations

- WA-002 and WA-007 are verified **by reading only**.
- The Kotlin changes have not been compiled. `runCatching` + `onFailure` + `getOrNull`
  is a standard shape and the types line up (`ParcelFileDescriptor?`), but "should
  compile" is not "compiles".

## Remaining Problems

- WA-008 (`hopStages` cross-thread and never cleared on the direct-engine path) is in
  the same file and is **not** fixed — it needs a device.
- WA-009 (teardown on the main thread) is in the same file and is **not** fixed.
- WA-010 (unbounded join) is in the same file and is **not** fixed.

## Next Phase

Phase 3 — reliability, privacy and hardening.

## Instructions For Another AI

`STOP_SENDER` is a single process-global. If you add any other JNI entry point that
installs or clears it, it needs the same `armed` discipline: a local flag set at the
moment of installation, and cleanup that takes only what it installed.

`establishTun` is now the only place a new VPN interface is created for a session. If
you add another `Builder().establish()` anywhere, it must drop the blackhole first —
otherwise WA-002 returns in a new form. `raiseBlackhole` is the *other* `establish()`
and is correct as-is because it returns early when a blackhole is already held.

The `runCatching` around `establishTun` returns `null` on failure. Do not change it to
throw: three call sites are nullable-aware and one of them is a carrier path that must
degrade rather than abort.
