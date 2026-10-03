# V5 Android signing operator steps

The V5 CI artifact is unsigned. The `mobile-release-approval` environment needs five private signing inputs before `.github/workflows/mobile-release.yml` can create an update-compatible APK. A previous run stopped at the secret check; it produced no signed file.

## Prepare on the owner's Windows PC

Use a trusted PC with PowerShell, Git, authenticated GitHub CLI (`gh auth login`), a JDK (`keytool`), and Android SDK Build Tools 35 (`apksigner`). The GitHub account must be allowed to configure secrets in the repository's `mobile-release-approval` environment and dispatch Actions. Make a secure offline backup of the resulting keystore, signing lineage, and password. Never paste those files or passwords into chat, a PR, a log, or a Git commit.

From the Cyclone checkout, after checking the current source SHA and successful release-branch Mobile CI run:

```powershell
./scripts/release/rotate-android-signing-and-release.ps1 `
  -Version '5.0.0-alpha.2.dev2' `
  -BuildRunId '35858293602' `
  -ExpectedSourceSha '1a47ca1fc2aa167b4321f408c386f924887ac388'
```

If the Pixel 8 is connected and authorized, add `-PixelSerial '<exact adb serial>'`. The script reads the installed base APK, checks its signer against the published 4.8.0 APK and the historical lineage source, and does not modify phone data. Without the optional serial, the same phone comparison remains mandatory before installation. **All three candidate arguments are required.** To use a newer dev3 or later candidate, provide its reviewed version, exact successful **push** Mobile CI run ID, and 40-character source SHA. The script requires the matching release branch and artifact name and refuses a mismatched run.

The script creates a fresh private key and proof-of-rotation lineage, writes exactly five protected environment secrets through standard input, and dispatches the V5 signing workflow. The new private key stays in `$HOME/.cyclone-signing` on the operator's PC; its password backup is encrypted with that Windows account's DPAPI. An offline backup is essential before relying on it for future upgrades. If uploading a secret or dispatching the workflow fails, **rerun the same command on the same Windows account**: the script verifies and reuses the existing keystore, password, and lineage without generating another key. If only some of those three files exist, it stops for manual recovery from the owner's backup; do not delete or replace a key without reviewing it.

## Review the result

In GitHub Actions, open **Cyclone Mobile Release Signing** for the dispatched release branch. Confirm the run passes, then download its `Cyclone-Android-<version>-signed` artifact. Check `source-sha.txt`, `run-id.txt`, `mobile-metadata.json`, APK SHA-256 sidecar, `signing-state.txt`, and `signing-lineage.txt`. Only the signed APK inside that successful protected run is an install candidate. A green Mobile CI run alone contains an unsigned APK.

On the Pixel, compare the installed APK signer with the published 4.8.0 signer and ensure the signed V5 APK validates with its rotation lineage before attempting `adb install -r`. Never uninstall 4.8.0 to get around `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: that would lose local data. Run the named Pixel 8 and real Glass acceptance steps in `Cyclone V5 plan/orchestrators/mobile/returns/RETURN-V5-ALPHA2-PHYSICAL.md` after a successful update. A signed artifact alone does not make the V5 alpha.2 release physically verified or production ready.

The old 3.9.1 publisher does not publish V5. The historical exposed signer is used only to sign the proof-of-rotation lineage, not a V5 APK. **Rotation does not undo the old key's exposure for existing installations**: others with that old private key may be able to create their own update lineage. Treat the resulting V5 APK as a testing preview while a production signer migration plan is established. A stable GitHub Release should wait for source CI, protected signing, update compatibility, the named physical acceptance record, and an explicit security decision about that old key.

## Current signer (since 5.0.0-alpha.34.dev1)

Installed Cyclone now requires the rotated release key: `CN=Cyclone Mobile, OU=Release`, certificate SHA-256
`e78c6e0b32d66da05839243c65e9987ba54722d402543f00408b57b1585dbf60`, with a lineage from the historical key
`cc2a7a5d8e5e3686d8f7afe0a0e6e76ca42a1e6e6306e26f0eba84e4e69f3965`. Its five secrets are in the
`mobile-release-approval` environment. **Any APK signed with the historical key alone will not install over
alpha.34 or later.** Do not recreate the key: reuse it (`scripts/release/upload-rotated-signing-for-alpha39.ps1` shows
the reuse-only checks). Alpha.35–38 were signed with the historical key only and were not re-signed; alpha.39 was
re-signed with the release key (see `docs/RELEASE_5.0.0-alpha.39.dev1.md`).
