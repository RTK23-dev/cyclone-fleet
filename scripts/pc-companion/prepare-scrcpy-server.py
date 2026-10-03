from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch Cyclone's pinned scrcpy server artifact")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()

    root = args.repo.resolve()
    scrcpy_dir = root / "apps" / "device-gateway" / "third_party" / "scrcpy"
    metadata_path = scrcpy_dir / "scrcpy-v4.0.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected_name = f"scrcpy-server-v{metadata['version']}"
    expected_sha = str(metadata["sha256"]).lower()
    destination = scrcpy_dir / expected_name

    if destination.is_file() and sha256(destination) == expected_sha:
        print(f"Verified pinned scrcpy server: {destination}")
        return 0

    scrcpy_dir.mkdir(parents=True, exist_ok=True)
    request = Request(str(metadata["source"]), headers={"User-Agent": "Cyclone-PC-Companion-build"})
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(prefix="scrcpy-server-", suffix=".tmp", dir=scrcpy_dir, delete=False) as output:
            temporary = Path(output.name)
            with urlopen(request, timeout=60) as response:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
        actual_sha = sha256(temporary)
        if actual_sha != expected_sha:
            raise RuntimeError(f"scrcpy checksum mismatch: expected {expected_sha}, got {actual_sha}")
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

    print(f"Downloaded and verified pinned scrcpy server: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
