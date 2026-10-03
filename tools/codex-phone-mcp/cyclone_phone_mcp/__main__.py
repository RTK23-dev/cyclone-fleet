from __future__ import annotations

import argparse
import json

from .gateway import GatewayClient, GatewayError
from .mcp_server import McpServer
from .tooling import apply_gateway_env


def main(argv: list[str] | None = None) -> int:
    apply_gateway_env()
    parser = argparse.ArgumentParser(description="Cyclone Phone MCP server")
    parser.add_argument(
        "--self-test",
        nargs="?",
        const="status",
        metavar="TARGET",
        help="Check gateway readiness (status|devices) and exit instead of starting MCP stdio",
    )
    args = parser.parse_args(argv)
    if args.self_test:
        try:
            client = GatewayClient(timeout=5)
            if args.self_test == "devices":
                print(json.dumps({"ok": True, "devices": client.devices()}, ensure_ascii=False))
            else:
                status = client.status()
                print(json.dumps({"ok": True, "gateway": status}, ensure_ascii=False))
        except GatewayError as exc:
            print(json.dumps({"ok": False, "error": str(exc)}))
            return 1
        return 0
    McpServer().serve_stdio()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
