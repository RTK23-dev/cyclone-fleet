"""Plan 44 run 0: call each cloud provider once and record its answers, with every key removed, as test fixtures.

Run from a repo checkout on a PC that can reach the providers (this is read-only on the provider side except that
VMOS opens remote ADB on the one test phone you name):

    python -m pip install -e apps/device-gateway
    set CYCLONE_VMOS_AK=...  & set CYCLONE_VMOS_SK=...  & set CYCLONE_VMOS_PAD=AC...
    set CYCLONE_DUOPLUS_KEY=...
    python scripts/pc/cloud_probe.py out-dir

Share `out-dir` back: it holds the paths called, the HTTP status and the JSON answers. The access key, secret key,
API key and VMOS's SSH key are replaced by "<redacted>" before anything is written.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from cyclone_device_gateway.cloud_fleet.http import urllib_transport
from cyclone_device_gateway.cloud_fleet.models import CloudPhone, ProviderError
from cyclone_device_gateway.cloud_fleet.providers.duoplus import DuoPlus
from cyclone_device_gateway.cloud_fleet.providers.vmos import VmosCloud

SECRET_KEYS = {"key", "connectKey", "password", "apiKey", "accessKey", "secretKey", "token", "stsToken"}


def redact(value, secrets: list[str]):
    if isinstance(value, dict):
        return {k: ("<redacted>" if k in SECRET_KEYS else redact(v, secrets)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, secrets) for v in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "<redacted>")
    return value


def recorder(records: list[dict], secrets: list[str]):
    def transport(method, url, headers, body, timeout):
        status, raw = urllib_transport(method, url, headers, body, timeout)
        try:
            answer = json.loads(raw.decode("utf-8"))
        except ValueError:
            answer = {"raw": raw[:400].decode("utf-8", "replace")}
        records.append({"path": "/" + url.split("/", 3)[3], "request": redact(json.loads(body or b"{}"), secrets),
                        "status": status, "answer": redact(answer, secrets)})
        return status, raw
    return transport


def main(out: Path) -> int:
    out.mkdir(parents=True, exist_ok=True)
    ak, sk, pad = os.getenv("CYCLONE_VMOS_AK", ""), os.getenv("CYCLONE_VMOS_SK", ""), os.getenv("CYCLONE_VMOS_PAD", "")
    duo = os.getenv("CYCLONE_DUOPLUS_KEY", "")
    secrets = [ak, sk, duo]
    if ak and sk:
        records: list[dict] = []
        vmos = VmosCloud(ak, sk, transport=recorder(records, secrets))
        try:
            phones = vmos.list_phones()
            print(f"VMOS: {len(phones)} phones: " + ", ".join(f"{p.remote_id} ({p.power}, Android {p.android})" for p in phones))
            if pad:
                link = vmos.open_adb(CloudPhone("vmos", pad, pad))
                print(f"VMOS ADB: {link.kind} via {link.ssh_host}:{link.ssh_port} → {link.target_host}:{link.target_port}, "
                      f"expires {link.expires_at_ms}")
        except ProviderError as exc:
            print(f"VMOS: {exc.code}: {exc.message}")
        (out / "vmos.json").write_text(json.dumps(records, indent=2, ensure_ascii=False), encoding="utf-8")
    if duo:
        records = []
        try:
            phones = DuoPlus(duo, transport=recorder(records, secrets)).list_phones()
            print(f"DuoPlus: {len(phones)} phones: " + ", ".join(f"{p.remote_id} ({p.power}, {p.android}, adb {p.address})" for p in phones))
        except ProviderError as exc:
            print(f"DuoPlus: {exc.code}: {exc.message}")
        (out / "duoplus.json").write_text(json.dumps(records, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Written to {out.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1] if len(sys.argv) > 1 else "cloud-probe")))
