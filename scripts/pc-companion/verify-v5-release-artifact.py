#!/usr/bin/env python3
"""Verify an exact-source Glass CI payload before a paired V5 release publishes it."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ACCEPTANCE_KEYS = (
    "installed", "bundled_adb", "gateway_ready", "gateway_authenticated",
    "transport_onboarding_api", "camera_streaming_api", "first_run_guide",
    "vmos_image_tip", "mobile_tip", "trust_pairing_tip",
    "chatgpt_attach_sync", "cloud_control_health",
)


def verify(directory: Path, source_sha: str, version: str) -> tuple[Path, str]:
    if re.fullmatch(r"[0-9a-f]{40}", source_sha) is None:
        raise ValueError("Invalid source SHA")
    if re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+-]*", version) is None:
        raise ValueError("Invalid Glass version")
    release = tomllib.loads((ROOT / "release/version.toml").read_text(encoding="utf-8"))
    if release["components"]["pc_companion"] != version:
        raise ValueError("Glass version differs from checked-out release metadata")
    installer = directory / f"Cyclone-PC-Companion-{version}-Setup.exe"
    names = {
        installer.name, "release-provenance.json", "installer-acceptance.json",
        "source-sha.txt", "THIRD_PARTY_NOTICES", "SHA256SUMS.txt",
    }
    if not directory.is_dir() or {p.name for p in directory.iterdir()} != names:
        raise ValueError("Missing or unexpected CI artifact files")
    if any(not (directory / name).is_file() for name in names):
        raise ValueError("CI payload contains a non-file entry")
    provenance = json.loads((directory / "release-provenance.json").read_text(encoding="utf-8-sig"))
    acceptance = json.loads((directory / "installer-acceptance.json").read_text(encoding="utf-8-sig"))
    if (provenance.get("product"), provenance.get("version"), provenance.get("source_sha")) != (
        "Cyclone One", version, source_sha
    ):
        raise ValueError("Glass provenance identity or source mismatch")
    if (directory / "source-sha.txt").read_text(encoding="utf-8-sig").strip() != source_sha:
        raise ValueError("Source SHA sidecar mismatch")
    if any(acceptance.get(key) is not True for key in ACCEPTANCE_KEYS):
        raise ValueError("Installed Glass acceptance is incomplete")
    if acceptance.get("bundled_adb_version") != "37.0.1":
        raise ValueError("Bundled ADB version is unexpected")
    digest = hashlib.sha256(installer.read_bytes()).hexdigest()
    artifacts = provenance.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 1 or artifacts[0] != {
        "name": installer.name, "sha256": digest, "size_bytes": installer.stat().st_size
    }:
        raise ValueError("Installer checksum, size, or provenance mismatch")
    if (directory / "SHA256SUMS.txt").read_text(encoding="utf-8-sig").strip() != f"{digest}  {installer.name}":
        raise ValueError("SHA256SUMS sidecar mismatch")
    if not (directory / "THIRD_PARTY_NOTICES").stat().st_size:
        raise ValueError("Third-party notices are empty")
    return installer, digest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    installer, digest = verify(args.artifact_dir, args.source_sha, args.version)
    print(f"Verified Glass CI installer: {installer.name} SHA-256 {digest}")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"installer_path={installer}\ninstaller_sha256={digest}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
