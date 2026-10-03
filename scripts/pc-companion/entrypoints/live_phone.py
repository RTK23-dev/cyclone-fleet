"""Typed JSON client. No gateway credentials, dynamic URL, or general command surface."""
from __future__ import annotations
import argparse
import json
import sys
from cyclone_phone_mcp.live_phone import COMMANDS


def parser():
    p = argparse.ArgumentParser(prog="CycloneLivePhone")
    p.add_argument("operation", choices=sorted(COMMANDS))
    p.add_argument("--json", action="store_true")
    for key in ("device", "goal", "element", "text", "direction", "package", "observation-id"):
        p.add_argument("--" + key)
    p.add_argument("--user-authorized", action="store_true")
    return p


def main():
    args = vars(parser().parse_args())
    args.pop("json")
    request = {k: v for k, v in args.items() if v is not None}
    from cyclone_phone_mcp.live_phone_ipc import request_one
    try:
        result = request_one(request)
    except Exception:
        result = {"ok": False, "error": "LIVE_PHONE_UNAVAILABLE", "next": "Open Cyclone One and enable Live Phone; observe again before acting"}
    print(json.dumps(result, ensure_ascii=False))
    return 1 if result.get("ok") is False else 0


if __name__ == "__main__":
    raise SystemExit(main())
