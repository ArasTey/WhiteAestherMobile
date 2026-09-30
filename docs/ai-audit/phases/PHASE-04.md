# Phase 04 — UI-visible correctness, and a rejected change

## Objective

Fix the two defects where the app shows the user something untrue — a recovery control
that is hidden exactly when it is needed, and a privacy toggle that does not do what it
says — and establish whether a third proposed fix was real at all.

## Starting State

Phases 2–3 complete. Rust 10/10, clippy clean.

## Files Inspected

`ui/Screens.kt` (`HomeScreen`'s blocked-state card, `homeAttention`, the diagnostics
`redactAddresses`/`buildReport`), `service/AetherVpnService.kt:2993-2994` (`giveUp`'s
message construction), `data/DnsServers.kt:50-70` (what `isIpv6` accepts),
`service/EngineLog.kt` (what a report line looks like), `data/AppUpdate.kt`
(`normalizedVersion`/`parts`/`isNewer`), `data/AppUpdateManager.kt:96-102`
(the independent `versionCode` check).

## Problems Found

### WA-016 — the "Lift the block" card is unreachable in the path it exists for

```kotlin
if (status.message == stringResource(R.string.traffic_is_blocked)) {
```

`giveUp` builds its message as
`listOfNotNull(said, …, sayNow(R.string.traffic_is_blocked)).joinToString(" ")` — the
notice arrives **inside** a sentence, so equality never holds. The card, which carries
the only in-app "Lift the block" button, is not rendered exactly when retries are spent
under the kill switch — while the notification says "Tap to open and lift".

### WA-024 — IP redaction missed bare IPv6

```kotlin
private val IPV6 = Regex("""\[[0-9a-fA-F:]+](:\d+)?""")
```

Square brackets required. `DnsServers.isIpv6` accepts bare literals, and
`AetherVpnService.kt:2663-2664` logs one verbatim when the platform refuses the
resolver. So that address reached the report in plain text with **Hide IP addresses**
switched on — a toggle that was inconsistent rather than absent.

### WA-031 — proposed, then rejected

`AppUpdatePolicy.parts()` used `mapNotNull { it.toLongOrNull() }`, dropping a component
that overflows `Long`. See Phase 05 below for what happened.

## Changes Made

| File | Change |
| --- | --- |
| `ui/Screens.kt` | Blocked-state condition matches containment as well as equality; `blockedNotice` hoisted so `CardHead` reuses it. |
| `data/DiagnosticsRedaction.kt` | **new** — `internal object`, three rules, `fun redact(line)`. |
| `ui/Screens.kt` | the private regexes and `redactAddresses` removed; the one call site now uses `DiagnosticsRedaction.redact`. |
| `app/src/test/.../DiagnosticsRedactionTest.kt` | **new** — 7 tests, 16 assertions. |

## Why Each Change Was Made

**WA-016** — `giveUp` is right to compose a full sentence; the UI's equality test was
the wrong shape. Matching on containment fixes the give-up path without changing the
message, and works in both locales because `sayNow` already produces the localised
string.

**The extraction was forced by the test, not chosen for tidiness.** The rules were
`private` file-level declarations in a 2,830-line file. A redaction rule that stops
matching **fails nothing** — the report still builds, still previews, still sends — so
without a test this class of defect ships unnoticed. Making it testable was the only way
to write the regression test the brief requires.

**The regex shape is structural, not colon-counting.** The naive rule — "three or more
colon-separated hex groups" — is wrong in both directions, and both mistakes were caught
before the code was written:

- it misses the most common form, `2606:4700::1`, because the leading repetitions
  consume the first colon of `::`;
- it matches `16:04:31`, and **every EngineLog line in the report opens with a
  timestamp**.

The shipped rule matches two structural shapes: a compressed address (always contains
`::`) and a full one (always eight groups). A clock time has two colons and no `::`, so
it matches neither.

## Tests Added

`DiagnosticsRedactionTest` — bare IPv6; the loopback and full 8-group forms; a port after
a bare address; two addresses on one line; IPv4 and bracketed IPv6 unchanged; **a clock
time is not mistaken for an address**; hostnames and ordinary words untouched.

## Tests Run

The Kotlin suite **could not run** (Gradle blocked). Instead the identical regexes and the
identical expected strings were executed against `java.util.regex` — the engine
`kotlin.text.Regex` delegates to:

```
$ javac DiagnosticsRedactionTest.java && java DiagnosticsRedactionTest
PASS expected="the interface would not take the resolver [ipv6]"  …
PASS expected="resolver [ipv6] rejected"                          …
PASS expected="tunnelled to [ipv6] ok"                            …
PASS expected="peer [ipv6] closed"                                …
PASS expected="[ipv6] and [ipv6] seen"                            …
PASS expected="peer 0.0.0.0:port closed"                          …
PASS expected="host [ipv6]:port refused"                          …
PASS expected="2026-09-30 16:04:31 INFO engine start"             …
PASS expected="elapsed 00:01:05 connected"                        …
PASS expected="note: something happened at 12:34:56 today"         …
PASS expected="match DOMAIN-SUFFIX,dns.google using PROXY"        …
PASS expected="match DOMAIN,api.example-cloud.com using PROXY"    …
PASS expected="Time::now elapsed"                                 …
PASS expected="version 1.2.3 stable-preview"                      …
PASS expected="carrier AETHER on MASQUE_H3"                       …
PASS expected="retry 3 of 8 in 12s"                               …

ALL 16 ASSERTIONS PASS
```

Plus, unchanged:

```
$ cargo test --locked    → 10 passed; 0 failed
$ cargo clippy …         → 0 warnings
```

## Test Results

**16/16** out-of-band assertions pass; **9/10** on a wider exploratory set initially —
the one failure (`1.1.1.1.nip.io` being partly redacted) was traced to the **pre-existing
IPv4** regex, not to this change, and was left alone as out of scope.

Rust unchanged: 10/10, clippy clean.

## Build Results

Kotlin: **not built.** Rust: unchanged.

## Regressions Checked

- The regex was written, run, and found wrong **twice** before it was correct: first it
  missed compressed addresses, then it ate timestamps. Both were caught by the Java
  harness, not by inspection. Writing it into `Screens.kt` first would have shipped
  either version.
- The over-matching direction was tested explicitly, because a redaction rule that
  destroys the timestamps destroys the report's usefulness.
- The bracket handling is unchanged and still tested.
- `blockedNotice` is computed once from `stringResource` and reused for the condition and
  the card head, so the two cannot disagree about which string they mean.

## Known Limitations

- **The Kotlin test has never been executed by Gradle.** Its *expectations* are verified
  by the Java mirror; that it compiles, is discovered, and passes under Gradle is
  **NOT VERIFIED**.
- WA-016 has no automated test at all — the condition is inside a composable.

## Remaining Problems

- WA-015 (diagnostics discloses hostnames past redaction) — analysed, deliberately not
  fixed. `04-UX-AUDIT.md` §1 records that the `includeEvents` filter's own comment
  claims a protection the redaction rules do not provide.
- The pre-existing IPv4-over-hostname false positive.
- The English-status-text and retry-string-surgery findings in `04-UX-AUDIT.md` §1.

## Next Phase

Phase 05 — the rejected change, and then documentation and final verification.

## Instructions For Another AI

**`redactAddresses` no longer exists in `Screens.kt`.** If you are looking for it, it is
`DiagnosticsRedaction.redact` in `data/`. There is exactly one call site
(`buildReport`).

**The bare-IPv6 rule is deliberately two structural alternatives, not one broad pattern.**
Do not "simplify" it into a colon-counting rule: that regresses `2606:4700::1` and
destroys every timestamp in the report. Both failure modes are asserted in
`DiagnosticsRedactionTest`.

**`DiagnosticsRedaction.kt` and its test must stay in step with
`/tmp/probe/DiagnosticsRedactionTest.java`** if you have it — the Java mirror is what
verified the expectations, and it is not part of the repository. If you change a rule,
re-run an equivalent check rather than trusting the mirror to still hold.

**Keep `/tmp/probe/` files if you want to re-verify.** They are outside the repo by
design: they are audit scaffolding, not project files, and they must not be committed.