# 08 — CI/CD & Release Audit

Workflows audited: `ci.yml`, `release.yml`, `fdroid-repo.yml`,
`.github/actions/android-toolchain/action.yml`, `.github/dependabot.yml`.

**None of the workflows were executed.** Findings are from reading. Claims about what a
workflow *would* do are labelled as such.

---

## 1. What is right, and should not change

These were checked deliberately:

- **Least privilege.** All three workflows declare `permissions:` at workflow level, all
  `contents: read`. Only two jobs widen: release `contents: write`, and F-Droid
  `contents: read, pages: write, id-token: write`. No `id-token` anywhere near signing.
- **No `pull_request_target` anywhere.** Verified across all three files. Fork PRs
  therefore get no secrets and no elevated token.
- **`fdroid-repo.yml:19-22` `workflow_run` correctly runs the default-branch copy** of
  the workflow, not the run's code. The right pattern.
- **`release.yml:42` builds the tag's tree**, not `main`.
- **`concurrency` group at `release.yml:26-27` with no `cancel-in-progress`**, and a
  comment explaining why. Correct: a half-cancelled release is worse than a queued one.
- **`fdroid-repo.yml:147-148` deletes the signing key and `config.yml` before the Pages
  upload**, inside the same `set -euo pipefail` step as `fdroid update`.
- **`release.yml:206-220` greps `classes*.dex` for the four names native code looks up by
  string** (`torConfiguration`, `org/torproject/jni/TorService`, `ca/psiphon/PsiphonTunnel`,
  `go/Seq`) — added after a real 1.4.0 `NoSuchFieldError` shipped to users. This is the
  check that only a minified build can reveal, and it is exactly right.
- **`SHA256SUMS` is generated at `release.yml:233` *after* all five artefacts are copied**
  (three splits, universal, `.aab`), so it covers everything uploaded including itself
  correctly — and it does not include itself.
- **`RepositoriesMode.FAIL_ON_PROJECT_REPOS`** in `settings.gradle.kts`.
- **The Psiphon Maven repo is scoped** with `content { includeGroup("ca.psiphon") }`.

---

## 2. Findings

### High

**WA-004 — no action is SHA-pinned.** 11 `uses:` references on floating tags
(`actions/checkout@v7`, `setup-go@v7`, `setup-node@v7`, `setup-java@v5`,
`gradle/actions/setup-gradle@v6`, `android-actions/setup-android@v4`,
`upload-artifact@v7`, `configure-pages@v6`, `upload-pages-artifact@v5`,
`deploy-pages@v5`). These run alongside `contents: write` and
`pages: write` + `id-token: write`. Dependabot *will* offer PRs for these, but that is
advisory, not enforcement.
Verify: `grep -rn 'uses:' .github | grep -v '@[0-9a-f]\{40\}'`.
**Not applied** — a mass SHA rewrite is opaque to a reviewer; the digests should be
approved by the maintainer.

The one exception is correct: `dtolnay/rust-toolchain@1.98.0`, where the tag *is* the
compiler version and `native/rust-toolchain.toml` pins the same `1.98.0`.

### Medium

| ID | Finding |
| --- | --- |
| WA-025 | `apksigner verify --verbose` (`:174`, `:195`) succeeds for **any** self-consistent signature. If the keystore secret were repointed, every check still passes and a different-key APK ships. The script already has the digest via `--print-certs`. Fix: assert a hardcoded certificate SHA-256. |
| WA-026 | `-PWHITEAESTHER_KEYSTORE_PASSWORD=…` (`:151-158`) puts secrets in the runner's process table. Gradle's `ORG_GRADLE_PROJECT_*` env vars are the correct channel. Related: `:74-78` promotes both passwords to `$GITHUB_ENV`, exposing them to every later step of a ~90-minute job. |
| WA-027 | `fdroid-repo.yml:96` — `tag="${{ inputs.tag \|\| github.event.workflow_run.head_branch }}"`. `head_branch` is **empty for a tag-triggered run**, so `gh release download ""` fails. Only `workflow_dispatch` with an explicit `tag` works. A distribution channel may silently never publish. |
| WA-030 | `--prerelease` and `--generate-notes` (`:247-256`) are applied only on the `gh release create` branch. If creation succeeds and an asset upload fails, the rerun takes `upload --clobber` — assets fixed, prerelease flag and notes never applied. **A build intended to be quiet ships publicly.** Related: `:111` derives `versionCode` from `GITHUB_RUN_NUMBER`, so a retry yields a different version code than the failed attempt. |
| WA-029 | No CodeQL/SAST, no `dependency-review-action`, no `actions/attest-build-provenance`, no SBOM. **Attestation is the one that matters** — this project ships signed, self-updating binaries to users in adversarial networks, where "is this really your APK" is the whole question. |
| — | Nothing re-verifies the release **after** publication. Worse, `fdroid-repo.yml:103` ingests the universal APK into a signed user-facing index with **no checksum and no signature check**. |
| — | `release.yml:44` calls the toolchain action with no inputs, so `setup-gradle` runs the release job with a read-write shared cache on a fresh runner. `cache-read-only: true` is free. |
| — | `ci.yml:90` runs `assemblePreviewDebugAndroidTest` and nothing ever installs it. No emulator job. The step advertises device coverage it does not provide (see `06-TESTING-AUDIT.md` §2). |

### Low

- `go-version: "1.25"` resolves to the newest 1.25.x at run time; the compiler drifts
  between releases.
- `ANDROID_BUILD_TOOLS: "36.0.0"` is declared in `ci.yml:27` and `release.yml:30` **and**
  hardcoded at `action.yml:44` — two sources of truth.
- `action.yml:43-44` installs `platforms;android-36` (unused; `compileSdk` is 37) and
  `platforms;android-37.2` while leaving `platforms;android-37` to AGP auto-provisioning.
- `release.yml:226` `jarsigner -verify "$aab"` without `-strict` returns 0 on warnings, so
  the AAB check is weaker than the APK's.
- Nothing binds a tag to reviewed code. `--verify-tag` confirms the tag exists; no check
  requires it to be on `main` or to have had a green CI run. That is what a GitHub
  environment protection rule is for.
- `dependabot.yml`'s comment claimed a `RUST_VERSION` variable that does not exist.
  **Corrected** (WA-079).

---

## 3. PowerShell scripts — the best-engineered part of the repo

`$ErrorActionPreference = 'Stop'` in all five scripts; every native command's
`$LASTEXITCODE` checked (`chain/setup.ps1:44,58,90`, `chain/build.ps1:110`,
`tor/setup.ps1:58`, `tor/build.ps1:123`, `psiphon/setup.ps1:81`); every `Push-Location`
block in `try/finally`. **They are not Windows-only** — `chain/build.ps1:74` and
`tor/build.ps1:78` compute a host tag for Linux/macOS/Windows, and the workflows invoke
them with `shell: pwsh` on `ubuntu-latest`. The same scripts a developer runs are the
ones CI runs. **That is the right call.**

Pinning is real: `chain/setup.ps1:22,24` pin full commit SHAs with the GPL traceability
rationale spelled out; `tor/setup.ps1:22,28` pin release tags; `psiphon/setup.ps1:33,35,41`
pin a revision, a SHA-256 **and** the tunnel-core tag.

| ID | Defect |
| --- | --- |
| WA-043 | `chain/build.ps1:69`, `tor/build.ps1:76` — `(Get-ChildItem $ndkRoot -Directory \| Sort-Object Name -Descending)[0]`. `Sort-Object` on strings is lexicographic, so `9.x` sorts *above* `29.0.14206865` and the wrong NDK wins. CI is safe (one NDK installed); a developer machine with several is not. |
| WA-044 | `chain/setup.ps1:69` reads `$?` **after** an assignment, so "already applied" detection reflects the assignment, not `git apply`. A re-run on a patched tree can attempt a second `git apply` and fail. `$LASTEXITCODE` is used correctly elsewhere in the same file. |
| WA-035 | Tor transports pinned by mutable **tag** (`lyrebird-0.8.1`, `v2.14.1`) where `chain/setup.ps1` pins SHAs *and explains why a tag is not an answer*. `tor/README.md:14-16` overstates by claiming the tag is "for the same reason". |
| WA-036 | `verify/main.go:37-59` never checks `scanner.Err()`; a read error truncates the loop, `total` counts only what was read, `verified == total` passes, and the script exits 0 for a partially-checked list. Unreachable for the fetch path (the SHA-256 pin covers it), so defence-in-depth only. |
| — | `chain/setup.ps1:40,55` and `tor/setup.ps1:55` pipe `git clone` through `Out-Null` without checking its exit code, only the later `git checkout`. Survivable, but a failed clone surfaces as a confusing "could not check out". |
| — | Neither `build.ps1` cleans its own `build/` output, so a stale `.so` from a previous ABI set can survive a narrow local `-Abi` build. |
| — | No `git verify-commit` / `verify-tag` where upstreams sign. |

---

## 4. Doc ↔ workflow mismatches

**Corrected by this audit:**
- `docs/RELEASE.md:21-23` claimed continuous main-branch prereleases. `ci.yml:4-7` says
  that behaviour was removed and `release.yml:6-8` triggers on tags only. A maintainer
  reading it would expect a rolling prerelease that will never exist. **Now corrected.**
- `docs/RELEASE.md:13` pointed at a signing-secrets table "documented in `README.md`".
  README has no such table; the four names exist only in `release.yml:49-52`.
  **Now corrected.**

**Not corrected (needs a maintainer or a live check):**
- `fdroid-repo.yml:132` declares `repo_url: …/fdroid/repo`, and `docs/FDROID.md:54-56`
  repeats it, but `:156` uploads `path: fdroid` — which publishes the *contents* of
  `fdroid/` at the site root, putting `fdroid/repo/index.xml` at `<base>/repo/`, not
  `<base>/fdroid/repo/`. **Verify by opening the F-Droid add-repository dialog, or by
  checking whether `…/WhiteAestherMobile/repo/index.xml` resolves.** Either the path or
  the URL is wrong.
- `docs/RELEASE.md` never mentions that a successful release automatically triggers the
  F-Droid Pages deploy, and never mentions `workflow_dispatch` for re-running a release.
- `docs/RELEASE.md:16-18` tells the operator to run `apksigner verify` but ships no
  certificate fingerprint to compare — the same identity gap as WA-025.

---

## 5. Recommendation

The highest-value change is small and does not require new infrastructure:

1. SHA-pin the 11 actions.
2. Add `actions/attest-build-provenance` to the release job — it slots straight into the
   existing `SHA256SUMS` + `AppUpdateManager` model and answers the one question this
   project exists to answer for its users.
3. Assert the signing certificate digest in the release job.
4. Fix the F-Droid tag fallback, and either add an emulator job or drop the misleading
   `assemblePreviewDebugAndroidTest` step.

Items 1–4 are each small and independent, which is why `UPSTREAM-PR-PLAN.md` splits them
into separate PRs rather than one "CI hardening" PR.