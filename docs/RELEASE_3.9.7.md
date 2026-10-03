# Cyclone 3.9.7 personal-development prerelease

- Android package: `com.cyclone.mobile`; versionName `3.9.7`; versionCode `61`.
- Source: release branch `release/cyclone-mobile-v3.9.7`, based on development checkpoint `49c41d1c2bf4c4303427c491eb4e317111470ae1`.
- Publication must wait for successful Mobile CI at the exact source SHA. The publisher verifies candidate identity, checksum and provenance, signs those APK bytes, verifies the certificate against 3.9.6, and uploads APK, checksum and provenance to an immutable prerelease.
- Signing retains the historical development certificate for in-place updates. This certificate is publicly exposed and is not production-secure.
- The initial checkpoint CI failed with Kotlin compiler heap exhaustion. This release bounds Gradle workers to one and explicitly assigns a 4 GiB Kotlin compiler heap. No build/test gate is removed.

Includes session-scoped live vision, consented MediaProjection capture, an Android 15+ Shizuku workspace prototype and task controls. The existing approval boundaries remain authoritative.

**Physical device: UNVERIFIED.** This is not the completed V4 vertical slice. See [the implementation handoff](CYCLONE_397_WIP_HANDOFF.md) for hardware/OEM and remaining feature gaps, including Scrcpy, background screenshot fallback, wider text input, secure-surface classification and post-human completion verification.

The handoff records the earlier checkpoint and its original stop instruction. The user's subsequent request to release the APK authorizes this version increment, release branch and signed prerelease publication. CI and publisher run results are the authoritative release evidence.
