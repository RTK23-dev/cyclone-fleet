# scrcpy server pin for Cyclone 3.3

Cyclone's media plane uses the **scrcpy 4.0 server only**. The runtime never resolves or downloads
`latest`.

Pinned upstream:

- Repository: `Genymobile/scrcpy`
- Tag: `v4.0`
- Commit: `2322868e9e256eb5fce0b3d659ab2a409f29bae1`
- Release asset: `scrcpy-server-v4.0`
- SHA-256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`
- License: Apache-2.0

## Packaging

Agent 3 / the installer owner should retrieve the exact release asset during a controlled
build/install step, verify its SHA-256, and place it at:

`apps/device-gateway/third_party/scrcpy/scrcpy-server-v4.0`

Packaged builds may instead set `CYCLONE_SCRCPY_SERVER` to the installed absolute path. The gateway
verifies the file again before every new media session. A missing or mismatched artifact fails the
H.264 backend closed and allows only Cyclone's explicitly degraded single-frame/screenshot path.

Do not commit a different server under the same filename. To update scrcpy, change the tag, commit,
asset URL, checksum, protocol tests, attribution metadata, and ADR together, then run physical
media acceptance before promotion.
