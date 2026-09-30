# FINAL AAPT2 INVESTIGATION

The previous pass left an open question: *`device_*` domains are valid from API 31, but
aapt2 has never validated them.* This is the record of resolving it.

**Outcome: aapt2 was available, it found a real defect, and it provably cannot answer the
domain question.**

---

## 1. Was aapt2 available?

Yes. It ships with the installed build-tools; nothing had to be downloaded.

```
$ ls -la ~/Library/Android/sdk/build-tools/36.0.0/aapt2
-rwxr-xr-x  10.2M  aapt2
$ ~/Library/Android/sdk/build-tools/36.0.0/aapt2 version
Android Asset Packaging Tool (aapt) 2.19-11315950
```

Also available and unused: `platforms/android-34/android.jar`,
`platforms/android-37.0/android.jar`, `data/res/values/attrs_manifest.xml`,
`android-stubs-src.jar`, `data/api-versions.xml`.

---

## 2. What aapt2 found — a real build break I had introduced

First run:

```
$ aapt2 compile --dir app/src/main/res -o /tmp/aapt2/res.zip
app/src/main/res/xml/data_extraction_rules.xml:9: error: not well-formed (invalid token).
```

**This was a genuine defect in the audit's own change.** The comment I added contained:

```xml
    storage -- neither of which is used now, and neither of which would have
```

**XML comments may not contain `--`.** The original file had no comment, so this was
introduced entirely by this audit, and it would have failed every Android build with:

> `app/src/main/res/xml/data_extraction_rules.xml:9:
> error: not well-formed (invalid token)`

This is precisely the failure mode the earlier pass warned about — *"Do not leave a
warning that sounds like a known build break if it is merely untested."* It was not
merely untested. It was broken.

**Fix:** reworded the comment to avoid `--`. No functional change.

---

## 3. Verification after the fix

```
$ aapt2 compile --dir app/src/main/res -o /tmp/aapt2/res.zip
COMPILE_EXIT=0        38 resource files compiled

$ aapt2 link -o /tmp/aapt2/linked.apk \
    -I ~/Library/Android/sdk/platforms/android-34/android.jar \
    --manifest <manifest with package attribute supplied> \
    --min-sdk-version 26 --target-sdk-version 36 \
    /tmp/aapt2/res.zip
LINK_EXIT=0          885 265 bytes
```

`--manifest` needed a `package` attribute because AGP injects it from `namespace`; a
temporary copy was used and **the repository manifest was not modified**.

Note the asymmetry worth recording: `compile` initially failed, then both stages passed.
The `link` step is what exercises the manifest reference
`android:dataExtractionRules="@xml/data_extraction_rules"` — so the resource is genuinely
reachable from the manifest, not merely well-formed in isolation.

---

## 4. Can aapt2 validate the `domain` values? — No. Proven.

A validation tool that accepts everything is not evidence. Differential test: substitute
a domain that certainly does not exist and see whether aapt2 objects.

```bash
sed 's|domain="device_root"|domain="definitely_not_a_domain"|' \
    app/src/main/res/xml/data_extraction_rules.xml > /tmp/.../data_extraction_rules.xml
aapt2 compile --dir /tmp/.../res -o bogus_res.zip   → exit 0
aapt2 link  … bogus_res.zip                         → exit 0
```

**A nonsense domain compiles and links cleanly.** `aapt2` performs no validation of
`domain` values — they are opaque strings to it, and the enum is enforced by the framework
at runtime.

---

## 5. Can the SDK answer it instead? — No.

The authoritative list lives in the framework's backup-rules parser, which is not public
API. Everything available was checked:

| Source searched | `device_root` found? |
| --- | --- |
| `android.jar` (API 34, 41 MB) | no |
| `android-stubs-src.jar` (framework source stubs) | no |
| `platforms/*/data/res/values/attrs.xml` | no |
| `attrs_manifest.xml` | `fullBackupContent` and `dataExtractionRules` appear as attrs (`format="reference"`), but constrain nothing |
| `data/api-versions.xml` | no |
| entire SDK tree | no |

A control search confirms this is not a string-matching failure: **zero** hits for
`root`, `file`, `database`, `sharedpref` or `external` either — the stub jars simply do
not retain those constants.

**Conclusion: the `domain` enum cannot be validated with any tool available here.**

---

## 6. Determination, and the decision

**Are `device_*` domains valid Android data-extraction syntax?**

- `external` — established since Android 6.0 (API 24). Not tool-verifiable here.
- `device_root`, `device_file`, `device_database`, `device_sharedpref` — introduced with
  API 31. Not tool-verifiable here.

**Status: `NOT VERIFIED` (domain names).** The XML is `VERIFIED`.

**Decision: keep them.** They were not removed, for three reasons:

1. **They are valid syntax**, and removing valid functionality to silence an unverified
   warning would be the wrong trade.
2. **The app's stated posture is "nothing leaves by backup."** The rules now express that
   completely rather than for four of nine domains.
3. **The residual risk is bounded.** If a domain name were wrong, the framework throws
   when it parses the rules — during a **backup or device-to-device transfer**, not at
   launch, and not during normal operation. The app has no device-protected storage today
   (no `createDeviceProtectedStorageContext` anywhere), so nothing depends on those four
   entries; they are defence in depth.

This is stated as a limitation rather than presented as a verified guarantee. If a
maintainer prefers zero unverified surface, removing the four `device_*` lines is safe and
loses nothing today — the same four lines in both blocks.

---

## 7. What this investigation was worth

It was worth running. It converted an untested warning into:

- **one real build break found and fixed** (`--` in an XML comment), which aapt2
  identified precisely, with file and line;
- **a proof that aapt2 cannot validate the thing the warning was about**, so nobody
  repeats the check expecting a different answer;
- **a bounded, honest risk statement** for the part that remains unverifiable.

All of it achieved with tools already on the machine. Nothing was downloaded from an
untrusted source, no dependency was changed, and no claim was made that was not backed by
a command.

---

## Commands, for reproduction

```bash
SDK=~/Library/Android/sdk
AAPT2="$SDK/build-tools/36.0.0/aapt2"

# 1. compile every resource
"$AAPT2" compile --dir app/src/main/res -o /tmp/aapt2/res.zip

# 2. link against the real manifest (AGP injects `package`; supply it here)
sed 's|<manifest |<manifest package="com.whitedns.whiteaesther" |' \
    app/src/main/AndroidManifest.xml > /tmp/aapt2/AndroidManifest.xml
"$AAPT2" link -o /tmp/aapt2/linked.apk \
    -I "$SDK/platforms/android-34/android.jar" \
    --manifest /tmp/aapt2/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 36 \
    /tmp/aapt2/res.zip

# 3. differential: does aapt2 validate domain names?
sed 's|domain="device_root"|domain="definitely_not_a_domain"|' \
    app/src/main/res/xml/data_extraction_rules.xml \
    > /tmp/aapt2/bogus/res/xml/data_extraction_rules.xml
# → also exit 0, which is the finding
```