#!/usr/bin/env python3
"""Fail closed on signed Mobile payload and named physical acceptance evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_PHYSICAL = (
    "pixel8_in_place_upgrade", "mobile_boot", "settings_app_maps_and_vault",
    "follow_me_real_app", "atlas_persisted_after_reopen",
    "glass_real_source_matches_phone", "glass_operator_explanation",
    "privacy_review",
)


def file_hash(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify(
    mobile_dir: Path, glass_dir: Path, evidence_path: Path | None, source_sha: str,
    mobile_run_id: int, signing_run_id: int, glass_run_id: int, observed_signer_sha256: str,
    skip_physical_testing: bool = False,
) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Invalid source SHA")
    metadata = tomllib.loads((ROOT / "release/version.toml").read_text(encoding="utf-8"))
    release = metadata["release"]
    if release["publication_authorized"] is not True:
        raise ValueError("Release publication is not authorized")
    # Physical evidence is committed separately after installing this exact source.
    # Changing version.toml after signing would change the source SHA under test.
    mobile_version = metadata["components"]["mobile"]
    glass_version = metadata["components"]["pc_companion"]
    apk_name = f"Cyclone-{mobile_version}.apk"
    installer_name = f"Cyclone-PC-Companion-{glass_version}-Setup.exe"
    mobile_files = {
        apk_name, apk_name + ".sha256", "source-sha.txt", "run-id.txt",
        "mobile-metadata.json", "signing-state.txt", "signing-lineage.bin", "signing-lineage.txt",
    }
    if not mobile_dir.is_dir() or {p.name for p in mobile_dir.iterdir()} != mobile_files:
        raise ValueError("Signed Mobile artifact files are incomplete or unexpected")
    if any(not (mobile_dir / name).is_file() for name in mobile_files):
        raise ValueError("Signed Mobile artifact contains a non-file entry")
    if (mobile_dir / "source-sha.txt").read_text(encoding="utf-8-sig").strip() != source_sha:
        raise ValueError("Signed Mobile source SHA mismatch")
    if (mobile_dir / "run-id.txt").read_text(encoding="utf-8-sig").strip() != str(mobile_run_id):
        raise ValueError("Signed Mobile source run ID mismatch")
    if (mobile_dir / "signing-state.txt").read_text(encoding="utf-8-sig").strip() != "SIGNED_PROTECTED_ENVIRONMENT_ROTATED":
        raise ValueError("Mobile payload is not protected-environment signed")
    android = json.loads((mobile_dir / "mobile-metadata.json").read_text(encoding="utf-8-sig"))
    if (android.get("application_id"), android.get("version_name"), android.get("version_code"), android.get("apk_name"), android.get("artifact_name")) != (
        "com.cyclone.mobile", mobile_version, metadata["android_version_code"], apk_name,
        f"Cyclone-Android-{mobile_version}",
    ):
        raise ValueError("Signed Mobile package or version identity mismatch")
    apk = mobile_dir / apk_name
    apk_digest = file_hash(apk)
    if (mobile_dir / (apk_name + ".sha256")).read_text(encoding="utf-8-sig").strip() != f"{apk_digest}  {apk_name}":
        raise ValueError("Signed APK checksum mismatch")
    if not (mobile_dir / "signing-lineage.bin").stat().st_size or not (mobile_dir / "signing-lineage.txt").stat().st_size:
        raise ValueError("Signing lineage is missing")
    installer = glass_dir / installer_name
    if not installer.is_file():
        raise ValueError("Glass installer is absent")
    glass_digest = file_hash(installer)
    if not re.fullmatch(r"[0-9a-f]{64}", observed_signer_sha256):
        raise ValueError("Verified APK signer fingerprint is missing")
    if skip_physical_testing:
        if "-alpha." not in mobile_version or "-alpha." not in glass_version:
            raise ValueError("Physical testing waiver is only allowed for alpha prereleases")
        return {
            "schema": 1, "source_sha": source_sha,
            "mobile_version": mobile_version, "mobile_version_code": metadata["android_version_code"],
            "signed_apk": apk_name, "signed_apk_sha256": apk_digest,
            "signed_apk_cert_sha256": observed_signer_sha256,
            "glass_version": glass_version, "glass_installer": installer_name,
            "glass_installer_sha256": glass_digest,
            "mobile_ci_run_id": mobile_run_id, "signing_run_id": signing_run_id,
            "glass_ci_run_id": glass_run_id,
            "physical_acceptance": "NOT_TESTED_USER_WAIVED",
            "physical_evidence": None, "physical_evidence_sha256": None,
        }
    if evidence_path is None:
        raise ValueError("Physical evidence or explicit alpha testing waiver is required")
    physical = json.loads(evidence_path.read_text(encoding="utf-8"))
    if physical.get("schema") != 1 or physical.get("status") != "PASS" or physical.get("publication_authorized") is not True:
        raise ValueError("Physical acceptance status is not PASS")
    if (physical.get("source_sha"), physical.get("mobile_version"), physical.get("mobile_version_code"), physical.get("glass_version")) != (
        source_sha, mobile_version, metadata["android_version_code"], glass_version,
    ):
        raise ValueError("Physical acceptance release identity mismatch")
    if (physical.get("mobile_ci_run_id"), physical.get("signing_run_id"), physical.get("glass_ci_run_id")) != (
        mobile_run_id, signing_run_id, glass_run_id,
    ):
        raise ValueError("Physical acceptance CI run identity mismatch")
    if (physical.get("signed_apk_sha256"), physical.get("glass_installer_sha256")) != (apk_digest, glass_digest):
        raise ValueError("Physical acceptance artifact checksum mismatch")
    if physical.get("device_model") != "Pixel 8" or not re.fullmatch(r"[A-Za-z0-9]{4,8}", str(physical.get("device_serial_suffix", ""))):
        raise ValueError("Named Pixel 8 identity is missing")
    signer_digest = physical.get("signed_apk_cert_sha256")
    if not isinstance(signer_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", signer_digest):
        raise ValueError("Accepted APK signer certificate fingerprint is missing")
    if signer_digest != observed_signer_sha256:
        raise ValueError("Accepted APK signer differs from verified APK signature")
    if any(physical.get(key) is not True for key in REQUIRED_PHYSICAL):
        raise ValueError("Physical acceptance step is missing or failed")
    return {
        "schema": 1, "source_sha": source_sha,
        "mobile_version": mobile_version, "mobile_version_code": metadata["android_version_code"],
        "signed_apk": apk_name, "signed_apk_sha256": apk_digest,
        "signed_apk_cert_sha256": signer_digest,
        "glass_version": glass_version, "glass_installer": installer_name,
        "glass_installer_sha256": glass_digest,
        "mobile_ci_run_id": mobile_run_id, "signing_run_id": signing_run_id,
        "glass_ci_run_id": glass_run_id, "physical_evidence": evidence_path.name,
        "physical_evidence_sha256": file_hash(evidence_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobile-dir", type=Path, required=True)
    parser.add_argument("--glass-dir", type=Path, required=True)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--skip-physical-testing", action="store_true")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--mobile-run-id", type=int, required=True)
    parser.add_argument("--signing-run-id", type=int, required=True)
    parser.add_argument("--glass-run-id", type=int, required=True)
    parser.add_argument("--observed-signer-sha256", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    manifest = verify(args.mobile_dir, args.glass_dir, args.evidence, args.source_sha,
                      args.mobile_run_id, args.signing_run_id, args.glass_run_id,
                      args.observed_signer_sha256, args.skip_physical_testing)
    args.manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Accepted paired Mobile {manifest['mobile_version']} and Glass {manifest['glass_version']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
