from __future__ import annotations

import os
import sys
from pathlib import Path
from urllib.parse import urlparse

from cyclone_agent_mcp.__main__ import main as connector_main
from cyclone_phone_mcp.mcp_server import McpServer
from secure_gateway_token import DEFAULT_GATEWAY_URL, load_connection


def _bootstrap_gateway_env() -> None:
    try:
        from cyclone_device_gateway.tooling_seam import apply_gateway_env
    except ImportError:
        apply_gateway_env = None
    if apply_gateway_env is not None:
        apply_gateway_env()
        return
    connection = load_connection()
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip() and connection:
        os.environ["CYCLONE_DEVICE_GATEWAY_TOKEN"] = connection["token"]
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "").strip():
        os.environ["CYCLONE_DEVICE_GATEWAY_URL"] = connection["url"] if connection else DEFAULT_GATEWAY_URL
    url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL", DEFAULT_GATEWAY_URL)
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_PORT", "").strip():
        try:
            port = urlparse(url).port
        except ValueError:
            port = None
        os.environ["CYCLONE_DEVICE_GATEWAY_PORT"] = str(port or 8765)
    if not os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", "").strip():
        local = os.getenv("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        os.environ["CYCLONE_DEVICE_GATEWAY_RUNTIME"] = str(Path(local) / "Cyclone One" / "runtime")


if __name__ == "__main__":
    _bootstrap_gateway_env()
    # Keep the Companion's connector/status CLI, but serve the canonical Codex-facing phone
    # surface from the same frozen executable. The previous build accidentally served the
    # legacy generic MCP implementation, which omitted locate and skill tools.
    if len(sys.argv) > 1 and sys.argv[1] == "serve":
        McpServer().serve_stdio()
        raise SystemExit(0)
    raise SystemExit(connector_main())
