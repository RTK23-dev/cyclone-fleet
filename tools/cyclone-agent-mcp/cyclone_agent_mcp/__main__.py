from __future__ import annotations

import argparse
import json
import sys

from .adapters import discover_adapters, get_adapter
from .connector import connect, disconnect, verify_tools_list
from .profiles import deepseek_copilot_notes, deepseek_opencode_notes, dumps_json
from .server import run_stdio
from .status import connection_status, get_ai_status, get_phone_status


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-agent-mcp", description="Generic Cyclone Agent MCP connector")
    sub = parser.add_subparsers(dest="command", required=True)

    serve = sub.add_parser("serve", help="Run MCP over STDIO")
    serve.add_argument("--stdio", action="store_true", help="Explicitly select STDIO (the only V1 transport)")

    for action in ("connect", "disconnect"):
        cmd = sub.add_parser(action)
        cmd.add_argument("host", choices=["codex", "opencode", "copilot", "cursor", "grok", "generic"])
        cmd.add_argument("--dry-run", action="store_true")
        if action == "connect":
            cmd.add_argument("--executable")
            cmd.add_argument("--verify", action="store_true")

    copy = sub.add_parser("copy-config")
    copy.add_argument("host", choices=["codex", "opencode", "copilot", "cursor", "grok", "generic"])
    copy.add_argument("--executable")

    sub.add_parser("status")
    status = sub.choices["status"]
    status.add_argument("--probe-gateway", action="store_true")
    sub.add_parser("adapters", help="Discover Local AI adapters on this PC")
    verify = sub.add_parser("verify")
    verify.add_argument("--executable")

    profile = sub.add_parser("profile")
    profile.add_argument("name", choices=["opencode-deepseek", "copilot-deepseek"])
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.command == "serve":
        run_stdio()
        return 0
    if args.command == "connect":
        result = connect(args.host, dry_run=args.dry_run, executable=args.executable)
        if args.verify and not args.dry_run:
            adapter = get_adapter(args.host)
            tools = adapter.verify(executable=args.executable)
            diagnostics = connection_status(probe_gateway=True)
            gateway = diagnostics.get("phone") or diagnostics.get("details", {}).get("gateway", {})
            result["verification"] = {
                **tools,
                "ok": bool(tools.get("ok")),
                "gateway": gateway,
                "ai": diagnostics.get("ai"),
                "phone": diagnostics.get("phone"),
            }
            result["message"] = _connect_message(result)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    if args.command == "disconnect":
        print(json.dumps(disconnect(args.host, dry_run=args.dry_run), indent=2, ensure_ascii=False))
        return 0
    if args.command == "copy-config":
        result = connect(args.host, dry_run=True, executable=args.executable)
        config = result["configuration"]
        print(config if isinstance(config, str) else dumps_json(config), end="")
        return 0
    if args.command == "status":
        print(json.dumps(connection_status(probe_gateway=args.probe_gateway), separators=(",", ":")))
        return 0
    if args.command == "adapters":
        print(json.dumps({"adapters": discover_adapters(), "ai": get_ai_status(), "phone": get_phone_status()}, separators=(",", ":")))
        return 0
    if args.command == "verify":
        result = verify_tools_list(args.executable)
        print(json.dumps(result, indent=2))
        return 0 if result["ok"] else 2
    if args.command == "profile":
        value = deepseek_opencode_notes() if args.name == "opencode-deepseek" else deepseek_copilot_notes()
        print(json.dumps(value, indent=2, ensure_ascii=False))
        return 0
    return 2


def _connect_message(result: dict) -> str:
    verification = result.get("verification") or {}
    phone = verification.get("phone") or verification.get("gateway") or {}
    host = str(result.get("host") or "Local AI")
    label = "Local AI" if host in {"codex", "generic"} else host.capitalize()
    if host == "codex":
        label = "Codex"
    if verification.get("ok") is not True:
        return f"{label} configuration was written, but the Cyclone MCP server still needs attention."
    ready = int(phone.get("ready_device_count") or 0)
    if ready < 1:
        return f"{label} is connected to Cyclone. Connect a phone in Control, then restart the AI app once."
    suffix = "phone" if ready == 1 else "phones"
    return f"{label} is connected to Cyclone with {ready} ready {suffix}. Restart the AI app once, then use the Cyclone phone tools."


if __name__ == "__main__":
    sys.exit(main())
