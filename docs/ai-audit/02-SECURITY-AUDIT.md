# 02 — Security Audit

Defensive review of a public open-source project. No exploit tooling was written.
Severity is impact-based; a VPN app in an adversarial network is held to a stricter
standard than a typical app, but not an arbitrary one.

**Overall posture: disciplined.** The exported surface is minimal and correctly
permissioned, backups are fully disabled, cleartext is off with no trust-anchor
overrides, there are no secrets in the tree or in reachable git history, and the
self-update path enforces package + signer-set + version + architecture before
handing anything to the installer.

**The material weakness is build-time supply chain, not runtime.**

---

## 1. Critical

None.

## 2. High

### 2.1 WA-003 — Psiphon binary resolved from a mutable branch, no checksum pin
`settings.gradle.kts:21-25`
```kotlin
maven {
    name = "psiphon"
    url = uri("https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
    content { includeGroup("ca.psiphon") }
}
```
`ca.psiphon:psiphontunnel:2.0.41` is a ~44 MB prebuilt Go shared library that becomes
the Psiphon tunnel core — it terminates TLS to Psiphon's servers and carries the
user's traffic. `master` is a mutable ref, and there is no
`gradle/verification-metadata.xml` anywhere in `gradle/`. A compromised account or a
force-push substitutes native code into every build and every device that takes an
update.

The `content { includeGroup }` scoping is good practice and correctly limits the blast
radius to this one coordinate. The serving ref is the gap.

**Fix:** generate `verification-metadata.xml` with `--write-verification-metadata sha256`.
**Not applied:** the metadata must be produced against the maintainer's own resolution;
generating it from an untrusted network is exactly the thing being guarded against.

### 2.2 WA-004 — Every GitHub Action pinned to a floating tag
11 `uses:` references across `ci.yml`, `release.yml`, `fdroid-repo.yml` and
`android-toolchain/action.yml`; zero 40-character SHA pins. These run alongside
`contents: write` (release) and `pages: write` + `id-token: write` (F-Droid).
**Not applied** — a mass SHA rewrite is opaque to a reviewer and the digests should be
approved by the maintainer. Proposed in `UPSTREAM-PR-PLAN.md`.

### 2.3 WA-005 / WA-006 — Build reproducibility
`native/chain/setup.ps1:85-94` runs `go mod tidy` with `GOFLAGS=-mod=mod`, discarding
the committed `go.sum`, so the several hundred transitive Go modules behind two
SHA-pinned repositories are re-resolved from `proxy.golang.org` on every setup.
`gradle/wrapper/gradle-wrapper.properties` has no `distributionSha256Sum`.
Both are documented in `MASTER-ISSUES.md` with remediation; neither applied.

---

## 3. Medium

### 3.1 WA-023 — Release builds wrote carrier diagnostics to logcat — **FIXED**
`EngineLog.kt:58` gates its logcat mirror on `BuildConfig.DEBUG` and documents "A
release build keeps its log in memory only." Seven sites ignored that:
`PsiphonService.kt:175,282`, `TorCarrierService.kt:207,290,300,326`,
`PluggableTransport.kt:67,76`.

`PsiphonService.kt:175` logged the **raw, unfiltered** tunnel-core notice *before* the
`consider()` filter at `:214-245` selected a safe subset. Psiphon's notices carry the
server it dialled and the country it believes the phone is in.

Logcat is UID-scoped, so this is exposure to `adb logcat`, OEM bug-report collectors and
rooted devices — for a tool whose threat model includes a hostile local environment,
that is fingerprinting metadata written where the rest of the app deliberately keeps
nothing.

**Fix:** all eight call sites now go through `debugLog()` in `service/CarrierLog.kt`,
which applies the same `BuildConfig.DEBUG` rule and the same `runCatching` (JVM unit
tests have a throwing `Log` stub).

### 3.2 WA-024 — IP redaction missed bare IPv6 — **FIXED**
`ui/Screens.kt` had `Regex("""\[[0-9a-fA-F:]+](:\d+)?""")` — square brackets required.
`DnsServers.kt:50-70` accepts bare literals and `AetherVpnService.kt:2663-2664` logs one
verbatim when the platform refuses the resolver, so the address reached the report in
plain text with **Hide IP addresses** switched on.

**Fix:** extracted to `data/DiagnosticsRedaction.kt` with a bare-literal rule, and
covered by `DiagnosticsRedactionTest`. The rule is structural, not colon-counting: a
compressed address contains `::` and a full one has eight groups, while a wall-clock
time — which opens every `EngineLog` line in the report — has two colons and no `::`.
Verified against 16 assertions in `01-BASELINE.md` §4.

**Pre-existing, not addressed:** the IPv4 rule also matches inside hostnames
(`1.1.1.1.nip.io` → `0.0.0.0:port.nip.io`). Harmless in the over-redacting direction;
changing it was out of scope.

### 3.3 WA-037 — Exported widget receiver could be crashed by any local app — **FIXED**
`AetherWidgetProvider` is exported (a home-screen widget must be) and accepts
`APPWIDGET_UPDATE` with no permission. Any app could broadcast forged
`EXTRA_APPWIDGET_IDS`; `updateAppWidget` throws `SecurityException` for ids the caller
does not own, and the exception was uncaught inside `onReceive`.
**Impact:** repeatable crash of WhiteAesther by any installed app. No privilege gain.
**Fix:** each id is now updated inside `runCatching`.

### 3.4 WA-038 — Extraction rules omitted `external` and the `device_*` domains — **FIXED**
`data_extraction_rules.xml` covered `root`/`file`/`database`/`sharedpref` only. The
sensitive domains are covered — identity is in `filesDir`, DataStore and
SharedPreferences are in `file`/`sharedpref` — so there was no exposure today. The
only thing in `external` is the staged update APK, which is a public artefact.
**Fix:** all nine domains now excluded, so the guarantee survives future use.

### 3.5 WA-029 / WA-025 / WA-026 / WA-027 — CI release integrity
No SAST, no dependency review, no build attestation (WA-029); `apksigner verify` proves
consistency but not identity (WA-025); keystore passwords passed as `-P` arguments and
promoted to `$GITHUB_ENV` (WA-026); the F-Droid `workflow_run` trigger reads
`head_branch`, which is empty for a tag-triggered run, so that channel cannot fire
(WA-027). All documented, none applied — see `UPSTREAM-PR-PLAN.md`.

---

## 4. Low

| ID | Finding |
| --- | --- |
| WA-039 | The verified APK is shared via an implicit `ACTION_VIEW`, so the URI grant goes to whichever activity resolves it. The artefact is a public release file; impact negligible. |
| WA-046 | A malformed identity paste renders key material on screen: the `toml` `de::Error` Display includes the offending source line, which becomes the visible error text. Own key, own screen — a screenshot / "copy the error to a forum" hazard. |
| WA-047 | `AppLocale`'s SharedPreferences mirror is written with async `apply()` *before* the DataStore write, so a process death between them leaves `wrap()` reading a stale tag. |
| WA-051 | `Identity`/`Device` derive `Debug` while holding secrets. Latent only: the sole `{:?}` uses are inside `#[cfg(test)]`. |
| WA-036 | `verify/main.go` never checks `scanner.Err()`; a read error truncates the loop and `verified == total` then passes for a partially-checked list. |

---

## 5. Verified correct — do not "fix" these

Each was checked deliberately; the check is recorded so a later reader does not
re-open them.

**Exported components.** `AetherVpnService` is `exported="false"` *and* carries
`android:permission="android.permission.BIND_VPN_SERVICE"` (belt and braces).
`AetherTileService` is exported with `BIND_QUICK_SETTINGS_TILE`, which is
`signature|privileged`, so only SystemUI can bind. `WidgetActionActivity` and both
carrier services are not exported. `MainActivity` is exported with only
`LAUNCHER`/`LEANBACK_LAUNCHER` and never reads `getIntent()`, so there is no deep-link
or extra-injection surface. No exported component can be used to connect, disconnect,
change settings, or read identity.

**Permissions.** Minimal and each justified: `INTERNET`, `ACCESS_NETWORK_STATE`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
`REQUEST_INSTALL_PACKAGES` (self-update only, disabled for F-Droid builds),
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
**`QUERY_ALL_PACKAGES` is correctly absent** — `InstalledApps.kt:41-46` enumerates via
`queryIntentActivities` with exactly the `CATEGORY_LAUNCHER` and
`CATEGORY_LEANBACK_LAUNCHER` declared in `<queries>`.

**Backup.** `allowBackup="false"`, `fullBackupContent="false"` with
`tools:replace` (deliberately overriding psiphontunnel's declaration), plus the
extraction rules now covering all nine domains.

**TLS.** No custom `TrustManager`, `HostnameVerifier`, `X509Certificate` or `WebView`
anywhere in `app/src`. `network_security_config.xml` sets
`cleartextTrafficPermitted="false"` with `<certificates src="system"/>` only — no
`debug-overrides`, no `user` trust anchors, no `domain-config` exceptions. The manifest
also sets `usesCleartextTraffic="false"`.

**The one place this could have gone wrong, and did not:**
`CarriedSocket.kt:56-65` layers an `SSLSocket` over an existing connection. That
constructor does **not** verify the peer name by default — Java validates the chain but
not the identity — and the code sets
`endpointIdentificationAlgorithm = "HTTPS"` to restore it, with a comment saying so.
`InetSocketAddress.createUnresolved` on line 53 keeps the name in SOCKS5 form. Correct
and deliberate.

**Secrets.** `native/psiphon/server_entry_signature_key.txt` decodes to exactly 32
bytes — Psiphon's **public** Ed25519 key, and `app/build.gradle.kts:69-75` validates the
format at configuration time so a swapped key fails the build. `PsiphonConfig`'s
`REMOTE_SERVER_LIST_SIGNATURE_KEY` is a base64 SPKI (RSA public).
`PROPAGATION_CHANNEL_ID`/`SPONSOR_ID` are tunnel-core's documented test placeholders,
disclosed in `THIRD_PARTY_NOTICES.md`. Every `-----BEGIN PRIVATE KEY-----` in the
tree is a `#[cfg(test)]` fixture. No keystore, `.env`, or token in the working tree or
in reachable git history (72 commits, `main` only).

**Key material at rest.** The WireGuard key is generated in Rust and never crosses
into Kotlin. Files are written `0o600` (`config.rs:225,237`) with a regression test
asserting the mode. Parse errors name the field, never the value. No `AetherError`
variant embeds an `Identity`, closing the key-leak vector at the type level.

**Update verification — the strongest part of the codebase.** Before install:
exact repository; HTTPS + `github.com` + **exact** `rawPath`, with query and userinfo
rejected (so `github.com@evil.com` fails); draft/prerelease refused; asset name built
from a version normalised to `[0-9]+(\.[0-9]+){0,3}`; duplicate-asset and
duplicate-checksum refused; exact-length SHA-256; `getPackageArchiveInfo` signer set
compared **against the installed app's own**; version name, version code, and ABI.
**No path traversal is possible** — the download destination is the constant
`download.apk`, the internal copy `update.apk`, and the server-supplied name is only
ever compared, never used as a path. **No exploitable TOCTOU** — the verified file is
copied into app-private `filesDir/updates/`, which no other app can rewrite between
verification and install.

---

## 6. Licensing / attribution — obligations to investigate, not conclusions

These are stated as text observed → requirement to check. No legal conclusion is offered.

| ID | Observed | Requirement to investigate |
| --- | --- | --- |
| WA-028 | `native/aether/quiche/` is vendored and compiled into the shipped `.so`, has **no** `THIRD_PARTY_NOTICES.md` entry and **no recorded revision** — unlike every other vendored component. Its BSD-2-Clause `COPYING` travels with the source. | BSD-2-Clause retain-notice and no-endorsement clauses. The notices' closing sentence ("the lockfiles identify the exact resolved versions") is not true of a path dependency. |
| WA-075 | BoringSSL itself has no notices entry and no recorded version; only `boring-sys` is described. | Whether a bundled-library notice is required, given `native/third-party/boring-sys/deps/boringssl/LICENSE` travels with the source. |
| WA-073 | Bundled fonts (`ui_*.ttf`, `fa_*.ttf`, `plex_mono_*.ttf`) have no notices entry; `licenses/` holds only `GPL-3.0.txt` and `OFL-1.1-Vazirmatn.txt`. `THIRD_PARTY_NOTICES.md:113` says the Vazirmatn cut ships in `res/font-fa/`, but it is unqualified in `res/font/` — and `PersianTypefaceTest` exists to assert no `font-*` directory exists. | OFL-1.1 notice requirements. The stale path claim was **corrected** (`design/PORT-STATUS.md`); the notices file was not, being a maintainer/legal call. |
| WA-074 | BSD-3-Clause texts (tor, lyrebird, snowflake) and the MIT text (boring-sys) are absent from `licenses/`. | What the Play/F-Droid submissions require. |
| — | `native/chain/third_party/` is not committed; GPL source availability rests on `chain/setup.ps1:21-24` pins staying valid. | AGPL-3.0 §13 combination and GPL source-offer obligations. |
| — | `native/aether/UPSTREAM.md` records repo, revision `0e6f6a52`, import date, the four carried commits, the rename, licence, and "Android-specific refactoring must preserve upstream copyright, trademark, and license notices". | **Well done — the model the other vendored components should follow.** |