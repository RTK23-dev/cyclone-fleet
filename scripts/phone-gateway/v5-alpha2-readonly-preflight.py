#!/usr/bin/env python3
"""Read-only identity preflight for a named V5 alpha.2 physical Pixel run.

This deliberately never installs, launches, taps, forwards ports, or records phone content.
It reports only bounded identity fields and blockers. An unsigned CI APK is evidence of a
build, not an installable update candidate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.cyclone.mobile"
SHA = re.compile(r"[0-9a-fA-F]{64}\Z")
GIT_SHA = re.compile(r"[0-9a-fA-F]{40}\Z")
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){2}(?:[-+][0-9A-Za-z.-]+)?\Z")


def command(args: list[str], timeout: int = 15) -> tuple[int, str]:
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return 1, ""
    # Never surface raw ADB/aapt/dumpsys output: it can contain user data.
    return result.returncode, result.stdout


def sdk_tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        return None
    suffixes = (".exe", ".bat") if os.name == "nt" else ("",)
    tools = sorted(
        (path for suffix in suffixes for path in (Path(sdk) / "build-tools").glob("*/" + name + suffix)),
        reverse=True,
    )
    return str(tools[0]) if tools else None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_candidate(args: argparse.Namespace, report: dict, blockers: list[str]) -> None:
    apk = args.apk
    if not apk:
        blockers.append("No installable candidate APK supplied.")
        return
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        blockers.append("Candidate APK path is missing or invalid.")
        return
    actual = sha256(apk)
    report["candidate"] = {"file": apk.name, "sha256": actual}
    if actual != args.apk_sha256.lower():
        blockers.append("Candidate APK SHA-256 differs from the expected checksum.")
    aapt = sdk_tool("aapt")
    if not aapt:
        blockers.append("Android aapt is unavailable; APK identity was not verified.")
    else:
        code, output = command([aapt, "dump", "badging", str(apk)])
        match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.MULTILINE)
        if code or not match:
            blockers.append("Could not read candidate APK package/version metadata.")
        else:
            package, version_code, version_name = match.groups()
            report["candidate"].update({"package": package, "versionCode": int(version_code), "versionName": version_name})
            if (package, int(version_code), version_name) != (PACKAGE, args.version_code, args.version):
                blockers.append("Candidate APK identity differs from the intended release identity.")
    apksigner = sdk_tool("apksigner")
    if not apksigner:
        blockers.append("Android apksigner is unavailable; candidate signature was not verified.")
    else:
        code, output = command([apksigner, "verify", "--print-certs", str(apk)])
        match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
        if code or not match:
            blockers.append("Candidate is unsigned or its Android signature could not be verified.")
        else:
            report["candidate"]["signerCertSha256"] = match.group(1).lower()


def check_provenance(args: argparse.Namespace, report: dict, blockers: list[str]) -> None:
    directory = args.ci_provenance
    if not directory:
        blockers.append("CI source provenance directory was not supplied.")
        return
    try:
        source = (directory / "source-sha.txt").read_text(encoding="utf-8").strip().lower()
        state = (directory / "signing-state.txt").read_text(encoding="utf-8").strip()
        metadata = json.loads((directory / "mobile-metadata.json").read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError):
        blockers.append("CI source provenance files are incomplete or invalid.")
        return
    if not isinstance(metadata, dict):
        blockers.append("CI mobile metadata is not an object.")
        return
    report["ciProvenance"] = {
        "sourceSha": source if GIT_SHA.fullmatch(source) else "invalid",
        "signingState": state if state == "UNSIGNED_VERIFIED_CANDIDATE" else "unexpected",
        "versionName": metadata.get("version_name") if isinstance(metadata.get("version_name"), str) else "invalid",
        "versionCode": metadata.get("version_code") if isinstance(metadata.get("version_code"), int) else "invalid",
    }
    if source != args.source_sha.lower():
        blockers.append("CI provenance source SHA differs from the requested source SHA.")
    if state != "UNSIGNED_VERIFIED_CANDIDATE":
        blockers.append("CI provenance signing state is unexpected.")
    if (metadata.get("application_id"), metadata.get("version_name"), metadata.get("version_code")) != (PACKAGE, args.version, args.version_code):
        blockers.append("CI provenance package/version identity differs from the candidate target.")
    ci_name = f"Cyclone-{args.version}.apk"
    ci_apk = directory / ci_name
    try:
        checksum_line = (directory / (ci_name + ".sha256")).read_text(encoding="utf-8").strip()
        declared_hash, declared_name = checksum_line.split(maxsplit=1)
        measured_hash = sha256(ci_apk)
    except (OSError, ValueError):
        blockers.append("CI unsigned APK or its SHA-256 sidecar is missing.")
    else:
        report["ciProvenance"]["unsignedApkSha256"] = measured_hash
        if not SHA.fullmatch(declared_hash) or declared_name.strip("*") != ci_name or measured_hash != declared_hash.lower():
            blockers.append("CI unsigned APK does not match its SHA-256 sidecar.")
    # Signing an APK changes its bytes. The external signing record must link the signed
    # candidate hash to this CI artifact; matching version alone is insufficient proof.
    report["signedApkToCiLineage"] = "requires external signing record"


def check_device(args: argparse.Namespace, report: dict, blockers: list[str]) -> None:
    adb = shutil.which("adb")
    if not adb:
        blockers.append("ADB is unavailable.")
        return
    code, output = command([adb, "devices"], timeout=10)
    if code:
        blockers.append("ADB could not list devices.")
        return
    devices = [m.groups() for line in output.splitlines() if (m := re.fullmatch(r"([^\s]+)\s+(device|offline|unauthorized)", line.strip()))]
    report["adbDeviceCount"] = len(devices)
    if not devices:
        blockers.append("No Android device is connected through ADB.")
    if not args.serial:
        blockers.append("Pass --serial for the named physical Pixel; auto-selection is disabled.")
        return
    selected = next((state for serial, state in devices if serial == args.serial), None)
    report["device"] = {"serialSuffix": args.serial[-6:], "adbState": selected or "absent"}
    if selected != "device":
        blockers.append("Named Pixel is absent or not authorized in ADB.")
        return
    def prop(key: str) -> str:
        rc, value = command([adb, "-s", args.serial, "shell", "getprop", key])
        return value.strip()[:80] if rc == 0 else ""
    model = prop("ro.product.model")
    report["device"].update({"model": model, "androidRelease": prop("ro.build.version.release")})
    if model != "Pixel 8":
        blockers.append("Named device does not report Pixel 8.")
    rc, package_output = command([adb, "-s", args.serial, "shell", "dumpsys", "package", PACKAGE])
    version_code = re.search(r"\bversionCode=(\d+)", package_output)
    version_name = re.search(r"\bversionName=([^\s]+)", package_output)
    if rc or not version_code or not version_name:
        report["device"]["installedPackage"] = "absent-or-unreadable"
    else:
        report["device"].update({"installedPackage": PACKAGE, "installedVersionCode": int(version_code.group(1)), "installedVersionName": version_name.group(1)})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Exact ADB serial of the physical Pixel")
    parser.add_argument("--apk", type=Path, help="Signed installable candidate APK")
    parser.add_argument("--apk-sha256", help="Independent expected SHA-256 of the signed APK")
    parser.add_argument("--ci-provenance", type=Path, help="Directory with Mobile CI source-sha.txt and mobile-metadata.json")
    parser.add_argument("--source-sha", required=True, help="Exact candidate Git source SHA")
    parser.add_argument("--version", required=True, help="Expected mobile versionName")
    parser.add_argument("--version-code", type=int, required=True, help="Expected Android versionCode")
    parser.add_argument("--report", type=Path, help="Write sanitized JSON report here")
    args = parser.parse_args()
    if not GIT_SHA.fullmatch(args.source_sha) or not VERSION.fullmatch(args.version) or args.version_code < 1:
        parser.error("invalid source SHA or version identity")
    if args.apk and (not args.apk_sha256 or not SHA.fullmatch(args.apk_sha256)):
        parser.error("--apk requires independent --apk-sha256")
    report: dict = {"schema": 1, "observedAtUtc": datetime.now(timezone.utc).isoformat(), "sourceShaRequested": args.source_sha.lower(), "status": "BLOCKED"}
    blockers: list[str] = []
    check_candidate(args, report, blockers)
    check_provenance(args, report, blockers)
    check_device(args, report, blockers)
    if not blockers:
        report["status"] = "PREFLIGHT_READY"
    report["blockers"] = blockers
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if not blockers else 2


if __name__ == "__main__":
    sys.exit(main())
