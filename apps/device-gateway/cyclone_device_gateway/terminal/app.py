"""`cyclone`: update Cyclone when a newer release exists, then open Glass in its own window until the terminal closes.

Flow (plan 31, web-only): quick release check (cached, never blocks when offline) -> optional verified update (the
cyclone.cmd shim runs install.ps1 with the downloaded zip after this process exits, exit code 10) -> Glass launch link
from a gateway another `cyclone` window already runs, or one started here for this terminal -> app window tied to this
process, and the run/stop card.
"""

from __future__ import annotations

import argparse
import os
import secrets
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import release as rel
from .install import PACKAGE_NAME, UPDATE_EXIT_CODE



class TerminalIO:
    def __init__(self, out: Callable[[str], None] | None = None, ask: Callable[[str], str] | None = None, interactive: bool = True):
        self.out = out or (lambda line: print(line, flush=True))
        self._ask = ask or input
        self.interactive = interactive

    def ask(self, question: str) -> str:
        if not self.interactive:
            return ""
        try:
            return self._ask(question)
        except (EOFError, OSError):
            return ""


def parse(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="cyclone", description="Open Cyclone Glass from any terminal.")
    parser.add_argument("command", nargs="?", choices=["update", "version"], help="update: install the newest Cyclone now")
    parser.add_argument("--no-update", action="store_true", help="skip the update check")
    parser.add_argument("--browser", action="store_true", help="open Glass in your normal browser instead of its own window")
    return parser.parse_args(argv)


def check_for_update(io: TerminalIO, *, force: bool, installed: str, runtime_dir: Path, updates_dir: Path,
                     latest: Callable[..., rel.Release | None] | None = None,
                     download: Callable[..., Path] | None = None) -> int | None:
    """Returns UPDATE_EXIT_CODE when a verified package is ready for the shim, else None to continue to Glass."""
    from .updater import UpdateError, download_package, latest_release

    latest = latest or latest_release
    download = download or download_package
    found = latest(runtime_dir / "update-check.json", force=force)
    if found is None or not rel.is_newer(found.version, installed):
        if force:
            io.out(f"You have the newest Cyclone ({installed})." if found else "Could not reach GitHub to check for updates.")
        return None
    io.out(f"Cyclone {found.version} is available (you have {installed}).")
    if not force:
        answer = io.ask("Update now? Cyclone restarts by itself after it installs. [Y/n] ").strip().lower()
        if answer not in ("", "y", "yes", "j", "ja"):
            io.out("Skipped. Type `cyclone update` any time.")
            return None
    if os.name != "nt":
        io.out(f"Download it from {found.page_url}")
        return None
    try:
        download(found, updates_dir / PACKAGE_NAME, progress=io.out)
    except UpdateError as exc:
        io.out(str(exc))
        return None
    except Exception as exc:  # network drop mid-download: open Glass anyway
        io.out(f"The download failed ({type(exc).__name__}); opening Glass on your current version.")
        return None
    return UPDATE_EXIT_CODE


@dataclass
class Launch:
    url: str
    stop: Callable[[], None]
    base_url: str
    token: str
    runtime: str


def launch_target(io: TerminalIO) -> Launch:
    """Glass launch link: the gateway another `cyclone` window runs, else a gateway owned by this terminal."""
    from ..glass.launcher import request_launch_code
    from ..tooling_seam import load_connection

    connection = load_connection()
    if connection and connection.get("token") and connection.get("url"):
        target = request_launch_code(connection["url"], connection["token"])
        if target is not None:
            return Launch(target.url, lambda: None, connection["url"], connection["token"], "shared with another cyclone window")
    return _gateway_for_this_terminal(io)


def _gateway_for_this_terminal(io: TerminalIO):
    import time

    import uvicorn

    from ..cli import build_serve_app
    from ..config import Settings
    from ..tooling_seam import apply_gateway_env, one_runtime_dir

    apply_gateway_env()
    # Same shape as Cyclone One's runtime: the owner's saved bearer when there is one, pairing on first use.
    os.environ.setdefault("CYCLONE_DEVICE_GATEWAY_TOKEN", secrets.token_urlsafe(32))
    os.environ.setdefault("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "1")
    os.environ.setdefault("CYCLONE_DEVICE_GATEWAY_RUNTIME", str(one_runtime_dir()))
    settings = Settings.from_env()
    try:
        from cyclone_phone_mcp.live_phone_ipc import start as start_live_phone

        start_live_phone()
    except Exception:
        pass
    app = build_serve_app(settings)
    server = uvicorn.Server(uvicorn.Config(app, host=settings.host, port=settings.port, log_level="warning"))
    thread = threading.Thread(target=server.run, name="cyclone-terminal-gateway", daemon=True)
    thread.start()
    deadline = time.monotonic() + 20.0
    while not server.started and thread.is_alive() and time.monotonic() < deadline:
        time.sleep(0.05)
    if not server.started:
        raise RuntimeError(f"the local gateway did not start; is another program using port {settings.port}?")
    def stop() -> None:
        server.should_exit = True
        thread.join(5.0)

    code = app.state.glass_codes.issue()
    base = f"http://{settings.host}:{settings.port}"
    # The retired window used to save where the gateway is, so Codex/Cursor's Cyclone MCP can find it. Now this does.
    try:
        from ..tooling_seam import persist_runtime_bearer, resolve_one_mcp_executable

        persist_runtime_bearer(settings.token, base, port=settings.port, write_cursor=False,
                               mcp_executable=resolve_one_mcp_executable())
    except Exception:
        pass
    return Launch(f"{base}/glass/#code={code}", stop, base, settings.token, "started for this terminal")


def run_terminal(argv: list[str], io: TerminalIO | None = None) -> int:
    from ..tooling_seam import one_install_dir, one_runtime_dir
    from .console import attach_parent_console, on_terminal_close
    from .window import open_glass_window

    interactive = attach_parent_console()
    io = io or TerminalIO(interactive=interactive)
    args = parse(argv)
    installed = rel.installed_version()
    if args.command == "version":
        io.out(f"Cyclone {installed}")
        return 0
    from .banner import Overview, enable_color, fetch_phones, render

    color = enable_color()
    view = Overview(version=installed)
    if args.command == "update" or not args.no_update:
        code = check_for_update(io, force=args.command == "update", installed=installed,
                                runtime_dir=one_runtime_dir(), updates_dir=one_install_dir() / "updates")
        if code is not None:
            return code
        if args.command == "update":
            return 0
        view.update = "up to date"
    try:
        launch = launch_target(io)
    except Exception as exc:
        io.out(f"Could not start Glass: {exc}. Close other Cyclone windows, then type cyclone again.")
        return 2
    view.runtime, view.address = launch.runtime, launch.base_url
    view.phones = fetch_phones(launch.base_url, launch.token)
    window = None if args.browser else open_glass_window(launch.url, one_install_dir() / "glass-window")
    if window is None or not window.owned:
        if window is None:
            import webbrowser

            webbrowser.open(launch.url)
        view.glass = "open in your browser"
    else:
        view.glass = "open in its own window · closes with this terminal"
    for line in render(view, color):
        io.out(line)
    stop = threading.Event()

    def cleanup() -> None:
        if window is not None:
            window.close()
        launch.stop()

    on_terminal_close(stop, cleanup)
    try:
        while not stop.is_set():
            if window is not None and window.owned and not window.alive():
                break
            stop.wait(0.5)
    except KeyboardInterrupt:
        pass
    cleanup()
    io.out("Cyclone Glass closed.")
    return 0
