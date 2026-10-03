"""Conformance checker: does a running plugin follow ``cyclone.ports/1``?

    python -m cyclone_ports.conformance http://127.0.0.1:8771 --secret dev-secret

Checks the manifest and /health; signature enforcement (none, wrong secret, stale, replayed, signed for another
path); unknown ports; a sample envelope on every out port; **forward compatibility** (a newer hub's unknown stages,
fields and match keys must not break the plugin); a malformed envelope; and await, repeated await and cancel on every
in port. Exit code 0 when every required check passes. The real Port Hub runs the same checks when the owner adds a
plugin, so a plugin that passes here is accepted there.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

from .catalog import CATALOG, CONTRACT, port_spec
from .devhub import DevHub
from .sdk import CONTRACT_HEADER, SIGNATURE_HEADER, error_code, sign, validate_manifest

SAMPLE_RUN = {"runId": "run_conformance", "taskId": "task_conformance", "rowId": "row_conformance",
              "app": "com.example.app"}
SAMPLE_DATA: dict[str, dict[str, Any]] = {
    "run.event": {"stage": "started"},
    "screen.shot": {"width": 1, "height": 1},
    "account.fields": {"fields": {"name": "Sam Example", "dateOfBirth": "1999-04-02", "email": "sam@example.com"}},
    "page.text": {"text": "Choose a username"},
    "file.out": {"name": "receipt.png"},
    "log.line": {"text": "conformance check"},
}
SAMPLE_MATCH: dict[str, dict[str, Any]] = {
    "code.in": {"from": "Example", "pattern": r"\b\d{6}\b"},
    "link.in": {"from": "Example"},
    "file.in": {"kind": "image"},
    "value.in": {"ask": "a caption"},
}


@dataclass
class Check:
    name: str
    ok: bool
    detail: str = ""
    required: bool = True


def _request(url: str, body: bytes | None = None, headers: dict[str, str] | None = None) -> tuple[int, Any]:
    request = urllib.request.Request(url, data=body, method="POST" if body is not None else "GET",
                                     headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read() or b"{}")
        except ValueError:
            return error.code, {}
    except (urllib.error.URLError, OSError, ValueError) as error:
        return 0, {"error": str(error)}


def _headers(secret: str, path: str, raw: bytes, t: int | None = None, request_id: str | None = None) -> dict:
    return {"Content-Type": "application/json", CONTRACT_HEADER: CONTRACT,
            SIGNATURE_HEADER: sign(secret, "POST", path, raw, t, request_id)}


def _signed(endpoint: str, path: str, secret: str, body: dict[str, Any], t: int | None = None,
            sign_path: str | None = None) -> tuple[int, Any]:
    """Signs the full request path, including any path prefix in the plugin's endpoint."""
    raw = json.dumps(body).encode()
    prefix = urllib.parse.urlsplit(endpoint).path
    return _request(endpoint + path, raw, _headers(secret, prefix + (sign_path or path), raw, t))


def check_plugin(endpoint: str, secret: str) -> list[Check]:
    endpoint = endpoint.rstrip("/")
    checks: list[Check] = []
    status, manifest = _request(endpoint + "/cyclone-plugin.json")
    if status != 200:
        return [Check("manifest is served at /cyclone-plugin.json", False, f"HTTP {status}")]
    problems = validate_manifest(manifest)
    checks.append(Check("manifest is valid", not problems, "; ".join(problems)))
    if problems:
        return checks
    checks.append(Check("manifest endpoint matches", manifest["endpoint"].rstrip("/") == endpoint,
                        f"manifest says {manifest['endpoint']}", required=False))
    status, health = _request(endpoint + "/health")
    checks.append(Check("/health answers 200 with ok", status == 200 and health.get("ok") is True, f"HTTP {status}"))

    served = {s["port"]: s["way"] for s in manifest["serves"]}
    first, first_way = next(iter(served.items()))
    probe_path = f"/ports/{first}" + ("/await" if first_way == "in" else "")
    probe = json.dumps({"v": 1}).encode()
    prefix = urllib.parse.urlsplit(endpoint).path
    status, body = _request(endpoint + probe_path, probe, {"Content-Type": "application/json"})
    checks.append(Check("refuses an unsigned request (401)", status == 401, f"HTTP {status}"))
    checks.append(Check("errors use {error: {code, message, retryable}}",
                        isinstance(body.get("error"), dict) and bool(error_code(body)), json.dumps(body)[:120],
                        required=False))
    status, _ = _signed(endpoint, probe_path, secret + "-wrong", {"v": 1})
    checks.append(Check("refuses a wrong secret (401)", status == 401, f"HTTP {status}"))
    status, _ = _signed(endpoint, probe_path, secret, {"v": 1}, t=int(time.time()) - 3600)
    checks.append(Check("refuses a stale signature (401)", status == 401, f"HTTP {status}"))
    other_path = f"/ports/{first}/cancel" if first_way == "in" else f"/ports/{first}/await"
    status, _ = _signed(endpoint, other_path, secret, {"v": 1}, sign_path=probe_path)
    checks.append(Check("refuses a signature made for another path (401)", status == 401, f"HTTP {status}"))
    unserved = next(p for p in CATALOG if p not in served and CATALOG[p].plugin_served)
    status, _ = _signed(endpoint, f"/ports/{unserved}", secret, {"v": 1, "port": unserved})
    checks.append(Check("answers 404 for a port it does not serve", status == 404, f"{unserved}: HTTP {status}"))

    hub = DevHub(secret, quiet=True).start()
    try:
        for port, way in served.items():
            spec = port_spec(port, way)
            if spec.way == "out":
                hub.bind(port, endpoint, way)
                status = hub.emit(SAMPLE_RUN, port, SAMPLE_DATA.get(port, {"note": "conformance"}),
                                  page_key="conformance:page")
                checks.append(Check(f"{port}: accepts a sample envelope (2xx)", 200 <= status < 300, f"HTTP {status}"))

                # forward compatibility: what a newer hub might send
                future = hub.envelope(SAMPLE_RUN, port, dict(SAMPLE_DATA.get(port, {}), futureField={"x": 1}),
                                      pageKey="conformance:page", futureTopLevel="ignored")
                if port == "run.event":
                    future["data"]["stage"] = "a-stage-added-later"
                if port in ("screen.shot", "file.out"):
                    future["artifactUrl"] = hub.artifact(b"\x89PNG\r\n\x1a\n", "image/png")
                status, _ = _signed(endpoint, f"/ports/{port}", secret, future)
                checks.append(Check(f"{port}: tolerates unknown fields and values (2xx)", 200 <= status < 300,
                                    f"HTTP {status}"))

                raw = json.dumps(hub.envelope(SAMPLE_RUN, port, {"stage": "started"} if port == "run.event" else {})
                                 | ({"artifactUrl": hub.artifact(b"x", "image/png")}
                                    if port in ("screen.shot", "file.out") else {})).encode()
                headers = _headers(secret, f"{prefix}/ports/{port}", raw)
                first_status, _ = _request(f"{endpoint}/ports/{port}", raw, headers)
                status, _ = _request(f"{endpoint}/ports/{port}", raw, headers)
                checks.append(Check(f"{port}: refuses a replayed request (401)", 200 <= first_status < 300
                                    and status == 401, f"HTTP {first_status} then {status}"))

                bad = {"v": 1, "runId": SAMPLE_RUN["runId"], "port": port, "way": "sideways"}
                status, _ = _signed(endpoint, f"/ports/{port}", secret, bad)
                checks.append(Check(f"{port}: rejects a malformed envelope (4xx)", 400 <= status < 500,
                                    f"HTTP {status}", required=False))
            else:
                request = {"v": 1, **SAMPLE_RUN, "port": port, "awaitId": "aw_conformance",
                           "match": dict(SAMPLE_MATCH.get(port, {}), aKeyAddedLater="ignored"), "timeoutS": 5,
                           "deliverUrl": f"{hub.url}/v1/ports/{SAMPLE_RUN['runId']}/{port}/deliver",
                           "token": "conformance-token-not-valid", "futureTopLevel": "ignored"}
                status, _ = _signed(endpoint, f"/ports/{port}/await", secret, request)
                checks.append(Check(f"{port}: accepts an await with unknown match keys (2xx)", 200 <= status < 300,
                                    f"HTTP {status}"))
                status, _ = _signed(endpoint, f"/ports/{port}/await", secret, request)
                checks.append(Check(f"{port}: accepts the same awaitId again (2xx)", 200 <= status < 300,
                                    f"HTTP {status}"))
                status, _ = _signed(endpoint, f"/ports/{port}/cancel", secret,
                                    {"v": 1, "runId": SAMPLE_RUN["runId"], "port": port, "awaitId": "aw_conformance",
                                     "reason": "conformance"})
                checks.append(Check(f"{port}: accepts a cancel (2xx)", 200 <= status < 300, f"HTTP {status}"))
    finally:
        hub.stop()
    return checks


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-ports-check", description=__doc__.splitlines()[0])
    parser.add_argument("endpoint", help="the plugin's base URL, e.g. http://127.0.0.1:8771")
    parser.add_argument("--secret", default=os.environ.get("CYCLONE_PLUGIN_SECRET", ""))
    parser.add_argument("--json", action="store_true", help="print the results as JSON")
    args = parser.parse_args(argv)
    if not args.secret:
        parser.error("--secret or CYCLONE_PLUGIN_SECRET is required")
    checks = check_plugin(args.endpoint, args.secret)
    failed = [c for c in checks if c.required and not c.ok]
    if args.json:
        print(json.dumps({"contract": CONTRACT, "passed": not failed, "checks": [c.__dict__ for c in checks]}, indent=2))
        return 1 if failed else 0
    for c in checks:
        mark = "PASS" if c.ok else ("FAIL" if c.required else "WARN")
        print(f"{mark}  {c.name}" + (f"  ({c.detail})" if c.detail and not c.ok else ""))
    print("\nall required checks passed" if not failed else f"\n{len(failed)} required check(s) failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
