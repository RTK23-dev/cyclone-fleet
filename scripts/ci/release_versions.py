#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "release" / "version.toml"


def read_text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def extract(pattern: str, text: str, label: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise ValueError(f"Could not read {label}")
    return match.group(1)


def collect() -> dict[str, str | int]:
    metadata = tomllib.loads(METADATA.read_text(encoding="utf-8"))
    product = str(metadata["product_version"])
    components = metadata.get("components", {})
    mobile_version = str(components.get("mobile", product))
    pc_version = str(components.get("pc_companion", product))
    glass_version = str(components.get("glass", ""))
    gateway_version = str(components.get("device_gateway", metadata.get("python_version", product)))
    mcp_version = str(components.get("mcp", metadata.get("python_version", product)))
    version_code = int(metadata["android_version_code"])

    gradle = read_text("apps/mobile/app/build.gradle.kts")
    gateway = read_text("apps/device-gateway/pyproject.toml")
    mcp = read_text("tools/codex-phone-mcp/pyproject.toml")
    agent_mcp = read_text("tools/cyclone-agent-mcp/pyproject.toml")
    package_json = read_text("apps/pc-companion/package.json")
    package_lock = read_text("apps/pc-companion/package-lock.json")
    cargo = read_text("apps/pc-companion/src-tauri/Cargo.toml")
    tauri = read_text("apps/pc-companion/src-tauri/tauri.conf.json")
    glass_package = read_text("apps/glass/package.json")

    return {
        "product": product,
        "expectedMobile": mobile_version,
        "expectedPc": pc_version,
        "expectedGlass": glass_version,
        "expectedGatewayPython": gateway_version,
        "expectedMcpPython": mcp_version,
        "androidVersionName": extract(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, "Android versionName"),
        "androidVersionCode": int(extract(r'^\s*versionCode\s*=\s*(\d+)', gradle, "Android versionCode")),
        "gatewayPython": extract(r'^version\s*=\s*"([^"]+)"', gateway, "gateway package version"),
        "mcpPython": extract(r'^version\s*=\s*"([^"]+)"', mcp, "MCP package version"),
        "agentMcpPython": extract(r'^version\s*=\s*"([^"]+)"', agent_mcp, "agent MCP package version"),
        "pcPackage": extract(r'^\s*"version"\s*:\s*"([^"]+)"', package_json, "PC package version"),
        "pcPackageLock": extract(r'^\s*"version"\s*:\s*"([^"]+)"', package_lock, "PC lockfile version"),
        "pcCargo": extract(r'^version\s*=\s*"([^"]+)"', cargo, "PC Cargo version"),
        "pcTauri": extract(r'^\s*"version"\s*:\s*"([^"]+)"', tauri, "Tauri version"),
        "glassPackage": extract(r'^\s*"version"\s*:\s*"([^"]+)"', glass_package, "Glass package version"),
        "expectedAndroidVersionCode": version_code,
    }


def check(values: dict[str, str | int]) -> list[str]:
    mobile_version = str(values["expectedMobile"])
    pc_version = str(values["expectedPc"])
    gateway_version = str(values["expectedGatewayPython"])
    mcp_version = str(values["expectedMcpPython"])
    expected_code = int(values["expectedAndroidVersionCode"])
    errors: list[str] = []

    if values["androidVersionName"] != mobile_version:
        errors.append(f"androidVersionName={values['androidVersionName']!r} expected {mobile_version!r}")
    # package.json / Cargo / tauri.conf are the shipped versions. The lockfile root
    # version can lag a coordinated beta bump without changing resolved packages.
    for field in ("pcPackage", "pcCargo", "pcTauri"):
        if values[field] != pc_version:
            errors.append(f"{field}={values[field]!r} expected {pc_version!r}")
    # Cyclone Glass (apps/glass) has its own line; collect() always reports it for the live tree.
    if "expectedGlass" in values:
        glass_version = str(values["expectedGlass"])
        if not glass_version:
            errors.append("components.glass is required: Cyclone Glass (apps/glass) has its own version line")
        elif values.get("glassPackage") != glass_version:
            errors.append(f"glassPackage={values.get('glassPackage')!r} expected {glass_version!r}")
    if values["gatewayPython"] != gateway_version:
        errors.append(f"gatewayPython={values['gatewayPython']!r} expected {gateway_version!r}")
    for field in ("mcpPython", "agentMcpPython"):
        if values[field] != mcp_version:
            errors.append(f"{field}={values[field]!r} expected {mcp_version!r}")
    if int(values["androidVersionCode"]) != expected_code:
        errors.append(f"androidVersionCode={values['androidVersionCode']} expected {expected_code}")
    if expected_code <= 37:
        errors.append("Distributed Cyclone Android builds must remain above the preserved 3.5.1 versionCode 37")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Cyclone release component versions")
    parser.add_argument("--check", action="store_true", help="fail when component versions drift")
    args = parser.parse_args()
    values = collect()
    errors = check(values)
    for key, value in values.items():
        print(f"{key}={value}")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1 if args.check else 0
    print("Cyclone release version metadata is coherent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
