from __future__ import annotations
import argparse, json, re
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{64}$")
REQUIRED = ("name", "version", "official_source", "license", "sha256", "method")

def load_lock(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1 or not isinstance(data.get("binaries"), list):
        raise ValueError("Unsupported third-party lock schema")
    seen = set()
    for item in data["binaries"]:
        if not isinstance(item, dict) or any(not str(item.get(k, "")).strip() for k in REQUIRED):
            raise ValueError("Every third-party binary needs pinned provenance fields")
        if not SHA.fullmatch(str(item["sha256"])):
            raise ValueError(f"Invalid SHA256 for {item.get('name', 'unknown')}")
        key = (item["name"], item["version"])
        if key in seen:
            raise ValueError(f"Duplicate third-party binary {key}")
        seen.add(key)
    return data

def render(data: dict) -> str:
    lines = ["Cyclone PC Companion - Third Party Notices", "", "Generated from pinned packaging metadata.", ""]
    items = sorted(data["binaries"], key=lambda x: (x["name"].lower(), x["version"]))
    if not items:
        lines += ["No external binary payloads are bundled by this sidecar packaging definition.", ""]
    for item in items:
        lines += [
            f"{item['name']} {item['version']}",
            f"Official source: {item['official_source']}",
            f"License: {item['license']}",
            f"SHA256: {item['sha256']}",
            f"Download/build method: {item['method']}",
            "",
        ]
    return "\n".join(lines)

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--lock", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(load_lock(args.lock)), encoding="utf-8", newline="\n")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
