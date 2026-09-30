# REJECTED-IDEAS

Changes that were considered during this audit and deliberately **not** made. Recorded
so the reasoning is not re-derived, and so a later reader can tell the difference
between "we looked at this and said no" and "nobody looked at this".

Each entry: `Idea → Why considered → Why rejected → Evidence → Revisit condition`.

---

## 1. Fix `AppUpdatePolicy.parts()` to fail on an overflowing version component — **WRITTEN, THEN REVERTED**

**Idea.** `data/AppUpdate.kt:256-259` uses `mapNotNull { it.toLongOrNull() }`, so a
component too large for a `Long` is dropped rather than failing the whole version. The
finding (`MASTER-ISSUES.md` WA-031) reported that `"1.99999999999999999999"` therefore
compares as `[1]` and loses to `"1.5.0"`, so a release the user was owed was never
offered. Change `mapNotNull` to a loop that returns `null` on the first unparseable
component.

**Why considered.** It is a genuine silent-data-loss bug in a comparison, it is three
lines, and the brief says every bug fix should have a regression test — which implies
fixing it.

**Why rejected.** **The change was implemented, a regression test was written, and then
the test was run against both implementations. The old and new code agree on every
realistic input.**

A Java mirror of `normalizedVersion`/`parts`/`isNewer` was built and run with both
variants:

| Case | Fixed | Old (`mapNotNull`) |
| --- | --- | --- |
| `1.99999999999999999999` vs `1.5.0` | false | false — same |
| `99999999999999999999` vs `1.0.0` | false | false — same |
| `1.10.0` vs `1.9.9` | true | true — same |
| `1.9.1.1` vs `1.9.1` | true | true — same |
| `1.9.0` vs `1.9.0` | false | false — same |
| `1.8.9` vs `1.9.0` | false | false — same |
| `2.99999999999999999999` vs `1.99999999999999999999` | false | **true — differs** |

The only case that distinguishes them requires a 20-digit component on **both** sides,
and "which of these two absurd strings is newer" is not a question with a defensible
answer. The `dropped component` path can only ever make a version look *smaller* than it
is, and no realistic candidate-then-installed ordering produces a wrong answer.

**Evidence.** The harness is `/tmp/probe/VersionParts.java`; the result is the table
above. The fix and its test were both written into the tree and both removed; `git status`
confirms neither file differs from `b55320a` for this change.

**Revisit condition.** If a release tag were ever observed containing a component
outside `Long` range, or if `normalizedVersion`'s 100-character allowance were widened
far enough for such a tag to pass validation.

**Note on process.** This is the clearest case in the audit of why the brief's rule
exists: *a test that passes both before and after the fix is not proof of the fix.* The
test was written, and the test is what killed the change.

---

## 2. Split `AetherVpnService` because it is 3,350 lines

**Idea.** Extract the state machine, the retry policy, the `Builder` configuration and
carrier orchestration into separate classes.

**Why considered.** 3,350 lines is a large class, and "God class" is on every code-review
checklist.

**Why rejected.** The parts are not separable: they share mutable state (`generation`,
`sessionJob`, `commandMutex`, `autoSteps`, `blackhole`) that must stay consistent. And
the `generation` mechanism — every publish checks it so a late callback from a dying
session cannot overwrite a newer one — is *the* correctness argument for the whole file,
and it is much easier to break when spread across four objects with callbacks between
them. The extracted pieces would need a fake `VpnService`, and the thing that actually
breaks (ordering between a native reply, a network callback and a user stop) is exactly
what a fake would not reproduce.

**Evidence.** `05-ARCHITECTURE-AUDIT.md` §4; the `generation` discipline at
`AetherVpnService.kt:2689`, `:2975`, `:2746-2749`, and the `hopAttempt` keying rationale
at `:156-168`.

**Revisit condition.** If the file grows again, extract the *pure* part — the transition
table — not a service class. That is also the single highest-value test seam available
(`06-TESTING-AUDIT.md` §7.5).

---

## 3. Split `Screens.kt` because it is 2,830 lines

**Idea.** Per-screen composables, a navigation graph, possibly a screen-per-file layout.

**Why considered.** Same reason as above.

**Why rejected.** It holds the screens; the navigation is already separate
(`WhiteAestherApp.kt`), and the shared components are already separate
(`AetherComponents.kt`, `ConnectOrb.kt`, `Summaries.kt`, `TvUiPolicy.kt`). Moving
declarations between files changes no boundary.

**Evidence.** `app/src/main/java/.../ui/` already has 9 files; the split the concern
assumes has largely happened.

**Revisit condition.** The real improvement here is the `Summaries.kt` pattern —
`coverageSummary()`/`routingSummary()` are `@Composable` and therefore testable, while
`SplitTunnel.summary()` (WA-058) is a hardcoded English string rendered on screen. Extending
the pattern is worth doing; shuffling files is not.

---

## 4. Replace `DataStore` with `EncryptedSharedPreferences` for the settings store

**Idea.** Settings are in plaintext DataStore; encrypt them.

**Why considered.** "Settings should be encrypted" is a common default recommendation.

**Why rejected.** There is no secret in the settings store. It holds carrier choice,
protocol preference, theme, language, split-tunnel package list. The one value of any
sensitivity is the LAN-sharing password, and the engine must present it in plaintext to
authenticate a client — so a hash is not usable, and the file is app-private and
backup-excluded regardless.

**Evidence.** `SettingsRepository.kt` key list; the rationale comment at
`:254-255` (WA note in `02-SECURITY-AUDIT.md` §4) already says this.

**Revisit condition.** If a credential that *can* be hashed is ever stored.

---

## 5. Introduce a DI framework (Hilt / Koin)

**Idea.** Replace the hand-wired singletons with a container.

**Why considered.** Standard advice for a codebase this size; `ChainController`,
`CarrierClient` and the carrier abstraction would benefit from scoping.

**Why rejected.** Adding Hilt means the annotation processor, the Gradle plugin, a
version to keep current, and kapt/KSP configuration — against three hand-constructed
dependencies. `WhiteAestherApplication` and the two `by preferencesDataStore` delegates
already do the job.

**Evidence.** The dependency graph is one-directional and shallow; nothing in
`05-ARCHITECTURE-AUDIT.md` §2 shows a container would remove coupling.

**Revisit condition.** If the carrier set grows past three, or if test doubles stop being
expressible without reflection.

---

## 6. Add certificate pinning

**Idea.** Pin the Cloudflare and GitHub endpoints.

**Why considered.** It is standard advice for an app whose traffic metadata is the thing
being protected.

**Why rejected as a blanket recommendation, with one exception.** The app's traffic
already runs through a MASQUE/HTTP3 or HTTP2 tunnel to a rotating set of Cloudflare
addresses, and pinning to the handful of API/trace/download hosts would add a rotation
failure mode for a marginal gain — the in-app update path already pins the *signing
certificate*, which is the control that actually matters and is far stronger than TLS
pinning for this threat model.

**Evidence.** `AppUpdateManager.kt:216-254` compares the downloaded APK's signer set
against the installed app's own. That is a better guarantee than a pinned SPKI.

**Revisit condition.** If the app ever gains an authenticated API surface.

---

## 7. Add `QUERY_ALL_PACKAGES`, or add more permissions for diagnostics

**Idea.** Easier split-tunnelling enumeration, better diagnostics.

**Why rejected.** Both are unnecessary: `InstalledApps.kt:41-46` already enumerates
correctly via `queryIntentActivities` with exactly the two categories declared in
`<queries>`, and the diagnostics report is built from what the app already logs.

**Evidence.** `AndroidManifest.xml:4-8` explains the absence in comments; the security
review confirmed the current permission set is minimal and each is justified.

**Revisit condition.** Never, for the permissions as they stand.

---

## 8. Remove the `0.0.0.0` LAN-sharing bind

**Idea.** `ChainConfig.kt:68` binds mihomo's DNS listener on all interfaces while the
external controller is deliberately kept off them. Bind to `127.0.0.1`.

**Why considered.** It is genuinely inconsistent, and `AppSettings.lanSharingWarning`
already acknowledges the shared-network case.

**Why rejected as this audit's change.** The bind is inside the mihomo-generated config
that LAN sharing itself turns on. The app already gates it behind an explicit opt-in with
a warning, and `CarriedSocket`/`socks::allowed_source` hold the *proxy* to the local
network when sharing is off. Changing generated third-party config is a design decision
about the exit-chain feature, not an audit fix.

**Evidence.** `ChainConfig.kt:63-66` shows the controller refusal is deliberate;
`AetherVpnService` gates sharing behind `lanSharingWarning`.

**Revisit condition.** If the maintainer wants a tighter default, the change is one
string in one file — but it should be a deliberate decision, reviewed as one.

---

## 9. Make the Go module graph reproducible now

**Idea.** Commit the tidied `go.sum` and build with `-mod=readonly` (WA-005, a real
High finding).

**Why rejected here, and this one is close.** The fix is right. But the committed
`go.sum` is discarded by the script's own comment because it was generated against a
different Clash.Meta than the one pinned, and nobody in this audit has ever successfully
run `native/chain/setup.ps1` — it needs Windows/PowerShell, network access to
`proxy.golang.org`, and the pinned FlClash revision. Committing a `go.sum` produced by an
audit that has not run the setup once would be worse than the current state.

**Evidence.** `native/chain/setup.ps1:81-94`; the "not changed" note in
`MASTER-ISSUES.md` WA-005.

**Revisit condition.** The maintainer runs the setup once, confirms the build, and
commits the result as its own change.

---

## 10. Add the missing `gradle/verification-metadata.xml`

**Idea.** For WA-003 — the highest-severity supply-chain finding.

**Why rejected here.** The metadata must be produced by resolving the dependency graph
*from a trusted network and from the maintainer's own resolution*. This environment
cannot even reach Google Maven, so any metadata generated here would be wrong or
absent, and a wrong verification file **fails the build** in a way that looks like
tampering.

**Evidence.** `01-BASELINE.md` §2.1.

**Revisit condition.** The maintainer runs
`./gradlew --write-verification-metadata sha256 ...` on a trusted machine.

---

## 11. Rework `EngineStatus` so the service emits `@StringRes` instead of English prose

**Idea.** Fixes three UX findings at once (`04-UX-AUDIT.md` §1.2, §1.3, and the
Persian status line).

**Why rejected here.** It changes a published contract between the service and every
screen that renders status, and the retry-attempt fields it would add are exactly the
data `EngineStatus` deliberately keeps out of the UI. Correct, but it is a design change
with a wide blast radius and no way to check it here.

**Evidence.** `Screens.kt:411-416`, `:797-798`; `AetherVpnService.kt:2789-2790`.

**Revisit condition.** Whenever localisation is next worked on.

---

## 12. Add an `AlertDialog` to every destructive action

**Idea.** There is no dialog anywhere in the app; identity restore, clear log, forget
endpoint and subscription removal are single taps (WA-066).

**Why considered.** Identity restore overwrites the device private key, which is
genuinely destructive.

**Why rejected as a blanket change.** A confirmation on "remove one subscription from a
list of fifty" is noise, and a blanket dialog is a known pattern users learn to dismiss.
The one that warrants it is identity restore.

**Revisit condition.** Add a dialog to identity restore specifically. That is a small,
local, low-risk change and the strongest candidate in this list for actual implementation.
