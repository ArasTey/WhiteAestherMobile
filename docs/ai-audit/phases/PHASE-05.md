# Phase 05 — A proposed fix that was rejected

## Objective

Establish whether WA-031 (`AppUpdatePolicy.parts()` dropping a `Long`-overflowing
version component) is a real defect worth changing.

## Starting State

Phases 2–4 complete. The finding was recorded as Medium with a recommended fix.

## Files Inspected

`data/AppUpdate.kt:256-259` (`parts`), `:38-46` (`isNewer`), `:60-72`
(`normalizedVersion`), `:143-167` (`validate`, including the independent
`candidate.versionCode > installed.versionCode` check); `data/AppUpdateManager.kt:96-102`.

## The finding as recorded

`parts()` used `mapNotNull { it.toLongOrNull() }`. `normalizedVersion` allows up to 100
characters of `[0-9.]`, so a 20-digit component passes validation and is then silently
dropped. `"1.99999999999999999999"` therefore compared as `[1]` and lost to `[1,5,0]` —
so a genuinely newer release was suppressed.

The finding claimed the fix was three lines and recommended it.

## What was done

**The fix was implemented.** `parts()` became an explicit loop returning `null` on the
first unparseable component, with a comment explaining why.

**A regression test was written**, covering six cases and asserting the ordinary range
(including four-part versions) still works.

**A verification harness was built** — a Java transliteration of
`normalizedVersion`/`parts`/`isNewer` carrying *both* the old `mapNotNull` behaviour and
the new one, run against the same six cases.

## The result

```
--- against the FIXED code ---
PASS 1.99999999999999999999 vs 1.5.0   expected=false actual=false
PASS 99999999999999999999 vs 1.0.0      expected=false actual=false
PASS 1.10.0 vs 1.9.9                     expected=true  actual=true
PASS 1.9.1.1 vs 1.9.1                    expected=true  actual=true
PASS 1.9.0 vs 1.9.0                      expected=false actual=false
PASS 1.8.9 vs 1.9.0                      expected=false actual=false

--- the same cases against the OLD mapNotNull code ---
same 1.99999999999999999999 vs 1.5.0    old=false new=false
same 99999999999999999999 vs 1.0.0       old=false new=false
same 1.10.0 vs 1.9.9                     old=true  new=true
same 1.9.1.1 vs 1.9.1                    old=true  new=true
same 1.9.0 vs 1.9.0                      old=false new=false
same 1.8.9 vs 1.9.0                      old=false new=false

WARNING: old code agrees everywhere -- the test would not prove anything.
```

A further case was then added to hunt for any distinguishing input:

```
DIFF 2.99999999999999999999 vs 1.99999999999999999999   old=true  new=false
```

That is the **only** input that separates the two, and it requires a 20-digit component
on **both** sides.

## Why it was reverted

The finding's reasoning was wrong in a specific, checkable way. A dropped component makes
the candidate list *shorter*, and missing positions compare as `0`. So the old code can
only ever make a version look **smaller or equal**, never larger. That means it cannot
wrongly claim "newer", and the specific claim in the finding — that `"1.999…999"`
compared as `[1]` and lost to `"1.5.0"` — is **true, and also exactly what the new code
does**, because the new code returns `null` and `isNewer` treats `null` as "not newer".

The two implementations agree on every realistic input. The change was therefore:

- **not demonstrable** on any input a user could encounter;
- **not testable** — a test that passes before and after proves nothing;
- **gated anyway** by `validate`'s independent `versionCode` check, so it could never
  have installed the wrong thing even if it did differ.

Both the fix and the test were removed. `git status` confirms `AppUpdate.kt` and
`AppUpdatePolicyTest.kt` carry no change from this phase.

## What this changes about the finding

The underlying observation is still true — silently dropping a parse failure is a poor
idiom, and a stricter `parts()` is defensible on robustness grounds. But it solves no
demonstrable problem, adds code, and cannot be covered by a regression test. That places
it squarely under the brief's rule about speculative changes.

## Tests Added

**None.** The test that would have justified the fix was deleted with it.

## Tests Run

The Java harness above. Rust unchanged: 10/10, clippy clean.

## Build Results

Unchanged. Neither the fix nor its removal affects a build.

## Regressions Checked

The only regression check that mattered was proving the *absence* of a behavioural
difference, and the harness did that.

## Known Limitations

- The harness is a transliteration, not the Kotlin itself. It was sufficient here because
  the logic is a dozen lines of `Long` comparison with no Kotlin-specific behaviour.
- A different, more thorough input search could not have found a realistic case: the
  argument above is structural, not empirical.

## Remaining Problems

WA-031 is recorded as `WONTFIX` in `MASTER-ISSUES.md` and §1 of `REJECTED-IDEAS.md`.

## Next Phase

Phase 6 — documentation and final verification.

## Instructions For Another AI

**Do not re-open WA-031 without new evidence.** The structural argument is in
`REJECTED-IDEAS.md` §1; if you find a distinguishing case, it must be one a real release
tag could produce.

If the maintainer decides a stricter `parts()` is worth having on robustness grounds
anyway, do it as a one-line `map` → explicit-loop change **with no regression test
claimed**, and say plainly in the commit that no behavioural difference was found.
