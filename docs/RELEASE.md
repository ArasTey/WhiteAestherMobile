# Release procedure

1. Complete `docs/DEVICE_TEST_PLAN.md` and archive the evidence.
2. Confirm the vendored Aether revision, the pinned FlClash and Clash.Meta
   revisions in `native/chain/setup.ps1`, and the AGPL-3.0 and GPL-3.0 notices
   all match what the APK actually contains.
3. Run the local Rust, Gradle test, lint, preview, and stable release tasks.
   Confirm the generated `armeabi-v7a`, `arm64-v8a`, `x86_64`, and universal
   APKs contain the intended native libraries -- both `libwhiteaesther_core.so`
   and `libwhiteaestherchain.so`. An APK missing the second installs and runs
   with the exit chain quietly reporting itself unavailable, so the release
   workflow asserts it rather than trusting the build.
4. Configure the four Android signing secrets, read by
   `.github/workflows/release.yml`: `ANDROID_KEYSTORE_BASE64` (the keystore,
   base64-encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
   `ANDROID_KEY_PASSWORD`.
5. Push a signed `vX.Y.Z` tag. GitHub Actions builds and verifies all split APKs,
   the universal APK, and the signed Android App Bundle.
6. Download the correct ABI APK (or universal APK), verify `SHA256SUMS` and
   `apksigner verify`, install it over the tested release candidate, and repeat
   a short smoke test.
7. Confirm the GitHub release exposes the corresponding source at the same tag.

Releases happen on tags alone. A push to `main` used to trigger a full signed
build and a rolling prerelease, and every commit produced two long runs and a
new release; that is gone, and `ci.yml` now only builds and tests. Tagged
releases are immutable except for re-uploading assets for the same verified tag.
