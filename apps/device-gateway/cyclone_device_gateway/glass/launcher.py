"""`cyclone-device-gateway glass`: open Cyclone Glass in the browser with a fresh one-tab session.

Uses the gateway that is already running on this PC (for example Cyclone One's runtime) when it answers; otherwise it
starts one in this process and keeps it running until Ctrl+C.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from dataclasses import dataclass
from typing import Any, Callable

from ..tooling_seam import load_connection


@dataclass(frozen=True)
class LaunchTarget:
    base_url: str
    path: str

    @property
    def url(self) -> str:
        return self.base_url.rstrip("/") + self.path

    @property
    def public_url(self) -> str:
        return self.base_url.rstrip("/") + "/glass/"


def request_launch_code(base_url: str, token: str, timeout: float = 2.5) -> LaunchTarget | None:
    """Ask a running gateway for a launch code. None when nothing answers or it predates Glass."""
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/glass/launch-code",
        data=b"{}",
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 - loopback URL from the locator
            body: Any = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None
    path = body.get("path") if isinstance(body, dict) else None
    if not isinstance(path, str) or not path.startswith("/glass/#code="):
        return None
    return LaunchTarget(base_url=base_url, path=path)


def run_glass(
    *,
    open_browser: bool = True,
    print_url: bool = False,
    serve: Callable[[Callable[[LaunchTarget], None]], int] | None = None,
    connection_loader: Callable[[], dict[str, str] | None] = load_connection,
    code_requester: Callable[[str, str], LaunchTarget | None] = request_launch_code,
    opener: Callable[[str], bool] = webbrowser.open,
    out: Callable[[str], None] | None = None,
) -> int:
    # Flush: the launcher keeps a gateway running, so buffered output would never reach a log or pipe.
    out = out or (lambda line: print(line, flush=True))

    def announce(target: LaunchTarget) -> None:
        if print_url:
            out(target.url)
        else:
            out(f"Cyclone Glass: {target.public_url}")
        if open_browser:
            opener(target.url)

    connection = connection_loader()
    if connection and connection.get("token") and connection.get("url"):
        target = code_requester(connection["url"], connection["token"])
        if target is not None:
            announce(target)
            return 0
    if serve is None:
        serve = serve_in_process
    return serve(announce)


def serve_in_process(announce: Callable[[LaunchTarget], None]) -> int:
    """Start the full local gateway here, then open Glass on it. Blocks until the server stops."""
    import uvicorn

    from ..config import Settings
    from ..cli import build_serve_app

    try:
        settings = Settings.from_env()
    except RuntimeError as exc:
        print(f"ACTION REQUIRED: {exc}. Start Cyclone One once, or set up the gateway with `cyclone-device-gateway serve`.", flush=True)
        return 2
    app = build_serve_app(settings)
    server = uvicorn.Server(uvicorn.Config(app, host=settings.host, port=settings.port, log_level="warning"))
    thread = threading.Thread(target=server.run, name="cyclone-glass-gateway", daemon=True)
    thread.start()
    deadline = time.monotonic() + 20.0
    while not server.started and thread.is_alive() and time.monotonic() < deadline:
        time.sleep(0.05)
    if not server.started:
        print("ACTION REQUIRED: the local gateway did not start. Is another program using port %d?" % settings.port, flush=True)
        return 2
    code = app.state.glass_codes.issue()
    announce(LaunchTarget(base_url=f"http://{settings.host}:{settings.port}", path=f"/glass/#code={code}"))
    print("Glass is running. Press Ctrl+C to stop.", flush=True)
    try:
        while thread.is_alive():
            thread.join(0.5)
    except KeyboardInterrupt:
        server.should_exit = True
        thread.join(5.0)
    return 0
