#!/usr/bin/env python3
"""Cheap, dependency-free Android identity and architecture guards for CI."""

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "apps/mobile/app/build.gradle.kts"
MANIFEST = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
SOURCES = ROOT / "apps/mobile/app/src/main/java"
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def exactly_one(pattern: str, text: str, label: str) -> str:
    values = re.findall(pattern, text, flags=re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"expected exactly one {label} assignment, found {len(values)}")
    return values[0]


def read_metadata() -> dict[str, str | int]:
    gradle = GRADLE.read_text(encoding="utf-8")
    version_name = exactly_one(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, "versionName")
    version_code = int(exactly_one(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", gradle, "versionCode"))
    application_id = exactly_one(r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', gradle, "applicationId")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){2}(?:[-+][0-9A-Za-z.-]+)?", version_name):
        raise ValueError(f"malformed versionName: {version_name}")
    if version_code < 1:
        raise ValueError("versionCode must be positive")
    if application_id != "com.cyclone.mobile":
        raise ValueError(f"unexpected applicationId: {application_id}")
    application = ET.parse(MANIFEST).getroot().find("application")
    if application is None:
        raise ValueError("manifest must declare an application")
    launchers: list[str] = []
    for component_tag in ("activity", "activity-alias"):
        for component in application.findall(component_tag):
            for intent_filter in component.findall("intent-filter"):
                actions = {item.get(ANDROID_NAME) for item in intent_filter.findall("action")}
                categories = {item.get(ANDROID_NAME) for item in intent_filter.findall("category")}
                if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                    launchers.append(component.get(ANDROID_NAME, ""))
    if launchers != [".MainActivity"]:
        raise ValueError(f"manifest must have one .MainActivity launcher, found {launchers}")
    executor_declarations = 0
    forbidden_rowscope_imports: list[str] = []
    declaration = re.compile(
        r"^\s*(?:(?:public|private|protected|internal|open|final|abstract|sealed|data|expect|actual|static)\s+)*"
        r"(?:object|class|interface|enum\s+class|record)\s+PhoneToolExecutor\b",
        re.MULTILINE,
    )
    for source in (*SOURCES.rglob("*.kt"), *SOURCES.rglob("*.java")):
        content = source.read_text(encoding="utf-8")
        executor_declarations += len(declaration.findall(content))
        if source.suffix == ".kt" and "import androidx.compose.material3.RowScope" in content:
            forbidden_rowscope_imports.append(str(source.relative_to(ROOT)))
    if executor_declarations != 1:
        raise ValueError(f"expected one canonical PhoneToolExecutor, found {executor_declarations}")
    if forbidden_rowscope_imports:
        raise ValueError(f"invalid Material3 RowScope imports: {forbidden_rowscope_imports}")
    safe_version = re.sub(r"[^0-9A-Za-z._-]+", "-", version_name).strip("-")
    return {
        "version_name": version_name,
        "version_code": version_code,
        "application_id": application_id,
        "apk_name": f"Cyclone-{safe_version}.apk",
        "artifact_name": f"Cyclone-Android-{safe_version}",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    try:
        metadata = read_metadata()
    except (OSError, ValueError, ET.ParseError) as error:
        parser.error(str(error))
    rendered = json.dumps(metadata, indent=2, sort_keys=True) + "\n"
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(rendered, encoding="utf-8")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            for key, value in metadata.items():
                output.write(f"{key}={value}\n")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
