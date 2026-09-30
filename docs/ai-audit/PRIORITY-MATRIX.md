# PRIORITY-MATRIX

Ordered by the criteria below, applied in that order. **No numeric scores are used** —
a score implies a precision this audit does not have. Each item states the criterion
that put it where it is.

## Criteria, in order

1. **Security impact** — can it lead to code execution, credential loss, or a user's
   traffic being exposed to someone who should not see it?
2. **Data/privacy impact** — does it disclose identifying information the user asked to
   keep, or behave differently from what the UI says?
3. **Crash/correctness impact** — can it take the app down, install the wrong thing, or
   make the UI lie about the connection?
4. **Connection reliability** — can it strand the user with a tunnel that is not working
   and no way out?
5. **User-visible breakage** — does a user hit it?
6. **Performance impact** — measurable only where stated; never guessed.
7. **Maintainability**
8. **Testability**
9. **Compatibility**
10. **Implementation risk** — higher risk pushes an item *down*, it never pushes up.

Two rules applied throughout:

- **A defect that a user cannot reach and cannot observe outranks a defect they can.**
  WA-020 (key material crosses FFI as a Java `String`) scores higher on criterion 1 than
  almost anything else here, and is still not being changed — because the fix is not
  available, not because it is unimportant.
- **Verification cost counts.** An item that can only be confirmed on hardware is
  recorded honestly rather than quietly fixed and claimed.

---

## Must fix

Blocking correctness, security, or reliability.

| ID | Item | Criterion that puts it here |
| --- | --- | --- |
| **WA-001** | A refused second `nativeRun` disarms the running session's stop channel | **3, 4.** A live tunnel becomes unstoppable through JNI while the app reports it stopped. **Fixed and verified.** |
| **WA-002** | The kill switch silently stops blocking after a failed reconnect | **3, 4.** The UI says "Traffic is blocked" while traffic flows unblocked — the exact leak the feature exists to prevent. **Fixed.** |
| **WA-003** | Psiphon binary from a mutable branch, no checksum pin | **1.** The only finding with a plausible path to code execution on every device. |
| **WA-004** | No GitHub Action is SHA-pinned | **1.** `contents: write` and `id-token: write` jobs on floating tags. |
| **WA-005** | Go module graph re-resolved on every build | **1, 7.** Reproducibility for binaries that carry user traffic. |
| **WA-006** | Gradle distribution fetched with no integrity check | **1.** One-line fix. |
| **WA-007** | `establishTun` is not exception-safe | **3.** Process death instead of a reported error. **Fixed.** |
| **WA-014** | No DataStore corruption handler | **3.** A truncated preferences file prevents the app starting at all. **Fixed.** |
| **WA-016** | The "Lift the block" card is unreachable on the give-up path | **3, 5.** The only control that undoes the block, hidden at the moment it is needed. **Fixed.** |
| **WA-024** | IP redaction misses bare IPv6 | **2.** The toggle says it hides addresses; it did not. **Fixed.** |
| **WA-023** | Release builds write carrier diagnostics to logcat | **2.** Psiphon's raw notices carry server and country, where the rest of the app deliberately keeps nothing. **Fixed.** |

## Should fix

Meaningful engineering improvements. Not blocking.

| ID | Item | Criterion |
| --- | --- | --- |
| WA-010 | Unbounded `sessionJob.join()` under `commandMutex` on Main | 4 — but the fix has a design cost and needs a device |
| WA-008 | `hopStages` read cross-thread; never cleared on the direct-engine path | 3 |
| WA-009 | Blocking JNI and Go-runtime teardown on the main thread | 6, 3 |
| WA-011 | `POST_NOTIFICATIONS` never requested from tile/widget | 5 |
| WA-012 | `TorCarrierService` state published from a raw `Thread` unsynchronised | 3 |
| WA-013 | `TorCarrierService.start()` stays wedged after an early failure | 4 |
| WA-015 | Diagnostics report discloses visited hostnames | 2 — but the fix changes what support offers |
| WA-017 | `catch_unwind` inert under `panic = "abort"`; 15/22 entry points unwrapped | 1, 3 — needs a size/robustness trade-off decision |
| WA-018 | TUN fd leaked on a failed carrier start | 7 |
| WA-019 | `tun.rs` never compiled or linted by CI | 8 — and it holds every `dup`/`from_raw_fd` in the project |
| WA-021 | 429 tests never execute on any push | 8 |
| WA-025 | `apksigner verify` proves consistency, not identity | 1 |
| WA-026 | Keystore passwords as `-P` args and in `$GITHUB_ENV` | 1 |
| WA-027 | F-Droid auto-publish cannot fire from its trigger | 5 — a channel may silently never publish |
| WA-028 | `quiche` shipped but absent from the notices, no revision | 1 — attribution obligation to investigate |
| WA-029 | No SAST / dependency review / build attestation | 1, 7 |
| WA-030 | A partial release cannot restore its prerelease flag | 5 |
| WA-037 | Exported widget receiver crashable by any local app | 3 |
| WA-038 | Extraction rules omit `external` and `device_*` | 2 — no exposure today, but the guarantee should survive |
| WA-032 | Two update systems, two comparators, two skip stores | 7 — observed drift already |

## Nice to have

| ID | Item | Criterion |
| --- | --- | --- |
| WA-020 | Identity crosses FFI as an immutable Java `String` | 1 — accepted: no zeroization path is available to the engine process anyway |
| WA-022 | Instrumentation tests gated behind opt-in flags | 8 — deliberate and documented |
| WA-054 | No `values-night/styles.xml`; white-on-white status bar in light mode | 5, 9 |
| WA-055 | Six measured contrast failures | 5 |
| WA-056 | No selection/state semantics anywhere | 5 |
| WA-057 | Touch targets under 48 dp | 5 |
| WA-058 | `SplitTunnel.summary()` and three other strings are English on screen | 5 |
| WA-059 | `Back`/`Chevron` do not mirror under RTL | 5, 9 |
| WA-060 | Mono face with no Persian glyphs carries localised text | 5 |
| WA-061 | `maxLines = 1` + `Clip` cuts labels at large font scale | 5 |
| WA-062 | Home recomposes every second while connected | 6 |
| WA-063 | Icon rasterisation uncached | 6 |
| WA-064 | LAN address cached on a boolean | 5 |
| WA-065 | On TV, remote BACK and gamepad B disagree | 5, 9 |
| WA-066 | No confirmation for destructive actions | 5 |
| WA-034 | `tor-android` two patch releases behind; `jtorctl` unmaintained since 2021 | 7 |
| WA-035 | Tor transports pinned by mutable tag | 1 |
| WA-036 | `verify/main.go` never checks `scanner.Err()` | 8 |
| WA-039 | Verified APK shared via implicit intent | 1 — negligible impact |
| WA-042 | Psiphon client list unbounded; Tor watcher never stopped | 6 |
| WA-043 | Lexicographic NDK selection in the build scripts | 7 |
| WA-044 | `$?` read after an assignment in `chain/setup.ps1` | 7 |
| WA-046 | Malformed identity paste renders key material on screen | 2 |
| WA-047 | `AppLocale` mirror can diverge from DataStore | 3 |
| WA-048 | Cellular network key collapses when MCC/MNC is absent | 4 |
| WA-049 | `longestPassMs` under-estimates for a Psiphon lane | 4 |
| WA-050 | Carrier path dials DoH to a resolver it knows refuses | 4 |
| WA-051 | `Identity` derives `Debug` while holding secrets | 1 — latent |
| WA-070 | `mac_test.rs` never compiled | 8 |
| WA-071 | `thiserror` unused in `android-bridge` | 7 |
| WA-072 | `androidx.test.ext:junit` unused | 7 |
| WA-073–075 | Font, BSD-3 and BoringSSL notices missing | 1 — attribution |
| WA-076 | Two Rust lockfiles resolving different versions | 7 |
| WA-077 | `assemblePreviewDebugAndroidTest` advertises coverage it does not give | 8 |
| WA-078 | CI installs `android-36` (unused) and duplicates the build-tools pin | 7 |

## Experimental

Ideas requiring further validation before anyone acts on them.

| Item | Why experimental |
| --- | --- |
| Move teardown off the main thread (WA-009) | The fix is obvious; the risk is shutdown ordering, and it cannot be measured without a device profile |
| Bound the `sessionJob.join()` (WA-010) | Needs a decision about session ownership, or a dying session's `stop()` kills its successor |
| Consolidate the two update systems (WA-032) | Changes which screens offer an update — a product decision |
| Compile the chain logs at `warning` or default `includeEvents` to `false` (WA-015) | Reduces what the maintainer offers users for support |
| Switch `panic = "abort"` → `unwind` and wrap all 22 JNI entry points (WA-017) | Measurably grows the `.so` for a hot-path tunnel |
| Add an emulator job, or drop the instrumentation build step (WA-021, WA-077) | CI cost, and whether instrumentation tests are worth running without accounts and a subscription URL |
| `gradle/verification-metadata.xml` for the Psiphon artifact (WA-003) | Must be generated against the maintainer's own resolution |
| Remove `panic` abuse / restructure `AetherVpnService` | See `05-ARCHITECTURE-AUDIT.md` §4 and `REJECTED-IDEAS.md` — **rejected**, not merely deferred |

---

## Ordering rationale

The Must-fix list is not sorted by severity alone. WA-003 through WA-006 rank above
most correctness bugs on criterion 1, but they are all **unchanged**, because each is a
supply-chain decision the maintainer owns and each is worse if done blind than if left
for them: a SHA-pin rewrite with unreviewed digests, verification metadata generated on
an untrusted network, a `go.sum` committed by someone who has never run the setup, and a
checksum copied from a page the maintainer did not read.

The four items that *were* fixed are exactly those where the defect was demonstrable
from the code, the fix was small, and the risk of being wrong was near zero. That is the
honest dividing line, and it is not the same line as severity.