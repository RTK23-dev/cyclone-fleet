from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--artifact-dir", type=Path, required=True)
    p.add_argument("--source-sha", required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--third-party-lock", type=Path, required=True)
    args = p.parse_args()
    if not args.source_sha or any(c not in "0123456789abcdef" for c in args.source_sha.lower()):
        raise ValueError("source SHA must be hexadecimal")
    root = args.artifact_dir
    root.mkdir(parents=True, exist_ok=True)
    payloads = sorted(
        [p for p in root.iterdir() if p.is_file() and p.suffix.lower() in {".exe", ".msi"}],
        key=lambda p: p.name.lower(),
    )
    hashes = [{"name": p.name, "sha256": sha256(p), "size_bytes": p.stat().st_size} for p in payloads]
    (root / "SHA256SUMS.txt").write_text("".join(f"{x['sha256']}  {x['name']}\n" for x in hashes), encoding="utf-8", newline="\n")
    (root / "source-sha.txt").write_text(args.source_sha.lower() + "\n", encoding="utf-8", newline="\n")
    third_party = json.loads(args.third_party_lock.read_text(encoding="utf-8"))
    provenance = {
        "schema_version": 1,
        "product": "Cyclone One",
        "version": args.version,
        "source_sha": args.source_sha.lower(),
        "artifacts": hashes,
        "third_party_binaries": sorted(third_party.get("binaries", []), key=lambda x: (x.get("name", ""), x.get("version", ""))),
    }
    (root / "release-provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
