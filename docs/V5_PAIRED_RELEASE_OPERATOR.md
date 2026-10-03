# Paired V5 Mobile + Glass publication

## Owner-authorized alpha waiver (2026-09-23)

Physical-device testing is no longer required for this alpha, by explicit owner request. No connected phone or evidence branch is required. The publisher now takes only `mobile_ci_run_id`, `signing_run_id`, and `glass_ci_run_id`; after steps 1–3 below, dispatch it directly. Steps 4–6 and the evidence schema below describe the former device-tested process and are not release gates for this alpha.

The manifest records `NOT_TESTED_USER_WAIVED`, and release notes disclose that the phone update and real Phone-to-Glass behavior are unverified. Do not mark physical tests as passed. Protected APK signing, identity, checksums, and source provenance remain required. Back up important app data; do not uninstall the existing app to resolve update errors.

The publisher is `.github/workflows/v5-combined-publish.yml`. Merge that workflow onto the repository default branch before using **Run workflow** with the exact `release/cyclone-mobile-v<version>` ref. It creates **one GitHub prerelease** containing the signed update APK and Windows installer, with checksums and provenance. It never converts an unsigned Android CI candidate into an installable update.

## Required order

1. Freeze the source on the release branch with `publication_authorized=true` in `release/version.toml`. This authorizes a later release attempt; it does not claim physical acceptance. Do not change source after producing artifacts.
2. Run Mobile and PC Companion **push CI** on that exact commit. Record both run IDs. The PC run must produce installed Windows acceptance and the exact-source installer.
3. Configure the five Android signing secrets in the protected `mobile-release-approval` GitHub environment through the authorized owner route. Run `mobile-release.yml` on the same release ref using the exact Mobile CI run ID and `Cyclone-Android-<version>` artifact name. Record the successful signing run ID. Never paste key material in a PR or evidence file.
4. Verify the signed APK updates the named Pixel 8 **in place** and retain app data. Complete the bounded Follow Me → phone Atlas → real Glass acceptance in `Cyclone V5 plan/orchestrators/mobile/returns/RETURN-V5-ALPHA2-PHYSICAL.md`. Record only sanitized identity, status, counts, revisions, hashes and observations.
5. Commit a `v5-physical-acceptance.json` to the separate branch `release-evidence/v5-alpha2-<exact-release-source-SHA>`. Record that branch's commit SHA. The evidence record must match the exact source, CI runs and artifact digests, and include the tested signer certificate SHA-256. No credentials, raw phone contents or private logs belong in this record. This separate commit leaves the release source SHA unchanged.
6. Dispatch the paired publisher with the four run/evidence inputs. It downloads and verifies the precise GitHub Actions runs, signed APK, Glass installer and physical evidence. It refuses overwriting an existing tag.

## Evidence JSON shape

`v5-physical-acceptance.json` on the separate evidence branch must be an object with these fields. Replace placeholders only with observed facts after the actual physical run; keep every boolean `false` until that step passes.

```json
{
  "schema": 1,
  "status": "PASS",
  "publication_authorized": true,
  "source_sha": "<40 lowercase hex characters>",
  "mobile_version": "5.0.0-alpha.2.dev3",
  "mobile_version_code": 143,
  "glass_version": "1.6.0-alpha.2",
  "mobile_ci_run_id": 0,
  "signing_run_id": 0,
  "glass_ci_run_id": 0,
  "signed_apk_sha256": "<64 lowercase hex characters>",
  "signed_apk_cert_sha256": "<64 lowercase hex characters>",
  "glass_installer_sha256": "<64 lowercase hex characters>",
  "device_model": "Pixel 8",
  "device_serial_suffix": "<4-8 alphanumeric characters>",
  "pixel8_in_place_upgrade": false,
  "mobile_boot": false,
  "settings_app_maps_and_vault": false,
  "follow_me_real_app": false,
  "atlas_persisted_after_reopen": false,
  "glass_real_source_matches_phone": false,
  "glass_operator_explanation": false,
  "privacy_review": false
}
```

The publisher requires `PASS` and all booleans `true`. The evidence branch head must match the supplied immutable evidence commit SHA. A new V5 source commit requires new CI, signing, physical acceptance and evidence.
