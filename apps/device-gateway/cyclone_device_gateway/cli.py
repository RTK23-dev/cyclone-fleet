from __future__ import annotations

import argparse
import getpass
import json
from pathlib import Path
import sys

import uvicorn

from .adb.client import ADBClient
from .adb.onboarding import ADBTransportOnboarding, TransportOnboardingError
from .config import Settings, resolve_adb_path
from .desktop_runtime.api import create_desktop_app
from .desktop_runtime.transport_api import create_transport_router
from .doctor import BridgeDoctor, format_human
from .skill_http import attach_to_app


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-device-gateway")
    subcommands = parser.add_subparsers(dest="command")
    doctor = subcommands.add_parser("doctor", help="Diagnose the Cyclone USB bridge without printing tokens")
    doctor.add_argument("--json", action="store_true", dest="json_output", help="Emit machine-readable JSON")

    transport = subcommands.add_parser("transport", help="Onboard USB, Android Wireless Debugging, or VMOS ADB")
    transport_subcommands = transport.add_subparsers(dest="transport_command")

    usb = transport_subcommands.add_parser("usb", help="Show USB/ADB authorization state")
    usb.add_argument("--json", action="store_true", dest="json_output")

    wifi = transport_subcommands.add_parser("wifi", help="Pair and connect Android Wireless debugging")
    wifi.add_argument("--pair", required=True, dest="pair_endpoint", help="Pairing address shown by Android")
    wifi.add_argument("--connect", required=True, dest="connect_endpoint", help="Device IP address and port shown on Wireless debugging")
    wifi.add_argument("--json", action="store_true", dest="json_output")

    vmos = transport_subcommands.add_parser("vmos", help="Connect a VMOS/remote ADB endpoint")
    vmos.add_argument("--endpoint", required=True, help="VMOS ADB endpoint in host:port form")
    vmos.add_argument("--json", action="store_true", dest="json_output")

    subcommands.add_parser("serve", help="Run the loopback PC Device Gateway and Desktop V1 fleet runtime")
    glass = subcommands.add_parser("glass", help="Open Cyclone Glass in the browser (starts the local gateway if needed)")
    glass.add_argument("--no-browser", action="store_true", help="Do not open a browser window")
    glass.add_argument("--print-url", action="store_true", help="Print the one-time launch link instead of the plain address")
    subcommands.add_parser("terminal", help="What the `cyclone` command runs: update check, then Glass in its own window")
    install_cli = subcommands.add_parser("install-cli", help="Install (or --remove) the `cyclone` terminal command for this user")
    install_cli.add_argument("--remove", action="store_true")
    install_cli.add_argument("--runtime", help="Runtime executable the command starts (default: this one)")
    install_cli.add_argument("--bin-dir", help="Folder for cyclone.cmd (default: %%LOCALAPPDATA%%\\Cyclone One\\bin)")
    return parser


def _print_transport(result: dict[str, object], *, json_output: bool) -> None:
    if json_output:
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return
    mode = str(result.get("mode") or "transport").upper()
    print(f"{mode}: {'READY' if result.get('ok') is True else 'ACTION REQUIRED'}")
    next_step = result.get("next")
    if isinstance(next_step, str) and next_step:
        print(next_step)
    devices = result.get("devices")
    if isinstance(devices, list):
        for item in devices:
            if not isinstance(item, dict):
                continue
            print(f"- {item.get('serial', 'unknown')} · {item.get('state', 'unknown')} · {item.get('model') or 'Android'}")
    device = result.get("device")
    if isinstance(device, dict):
        print(f"- {device.get('serial', 'unknown')} · {device.get('state', 'unknown')} · {device.get('model') or 'Android'}")


def _run_transport(args: argparse.Namespace) -> int:
    onboarding = ADBTransportOnboarding(ADBClient(resolve_adb_path()))
    try:
        if args.transport_command == "usb":
            result = onboarding.usb_status()
        elif args.transport_command == "wifi":
            # Never accept the pairing code as a command-line argument: argv is routinely retained
            # by shells/process inspectors. Prompt without echo, then keep it only in local memory.
            pairing_code = getpass.getpass("Android Wireless debugging 6-digit pairing code: ")
            result = onboarding.pair_wireless(args.pair_endpoint, pairing_code, args.connect_endpoint)
        elif args.transport_command == "vmos":
            result = onboarding.connect(args.endpoint, mode="vmos")
        else:
            raise TransportOnboardingError("TRANSPORT_MODE_REQUIRED", "Choose transport usb, wifi, or vmos.")
    except TransportOnboardingError as exc:
        payload = {"ok": False, "code": exc.code, "message": exc.safe_message}
        if getattr(args, "json_output", False):
            print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        else:
            print(f"ACTION REQUIRED: {exc.safe_message}")
        return 2
    _print_transport(result, json_output=getattr(args, "json_output", False))
    return 0 if result.get("ok") is True else 2


def main(argv: list[str] | None = None) -> int:
    raw = list(sys.argv[1:] if argv is None else argv)
    if raw[:1] == ["terminal"]:
        # `cyclone` (cyclone.cmd) lands here with the owner's own arguments, parsed by the terminal module.
        from .terminal.app import run_terminal

        return run_terminal(raw[1:])
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "install-cli":
        from .terminal.install import install, runtime_executable

        runtime = Path(args.runtime).expanduser() if args.runtime else runtime_executable()
        print(install(runtime, Path(args.bin_dir).expanduser() if args.bin_dir else None, remove=args.remove), flush=True)
        return 0
    if args.command == "doctor":
        report = BridgeDoctor().run()
        if args.json_output:
            print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
        else:
            print(format_human(report))
        return 0 if report["overall"] == "READY" else 2

    if args.command == "transport":
        return _run_transport(args)

    if args.command == "glass":
        from .glass.launcher import run_glass

        return run_glass(open_browser=not args.no_browser, print_url=args.print_url)

    settings = Settings.from_env()
    app = build_serve_app(settings)
    uvicorn.run(app, host=settings.host, port=settings.port)
    return 0


def build_serve_app(settings: Settings):
    """The full loopback gateway: desktop runtime, V5 contract, Glass hosting and transport onboarding."""
    app = create_desktop_app(settings)
    # Part B transport onboarding is attached only to Cyclone One's authenticated loopback API.
    # The router itself is allowlisted and exposes no generic adb/shell command surface.
    app.include_router(create_transport_router(app.state.desktop_runtime, settings.token))
    # Plan 31: Remote MCP, ChatGPT Attach and share, which used to live in the Cyclone One window.
    from .pc.api import create_pc_router

    pc_router, share = create_pc_router(settings.token, settings.port)
    app.include_router(pc_router)
    app.add_event_handler("shutdown", share.stop)
    attach_to_app(app)
    return app


if __name__ == "__main__":
    raise SystemExit(main())
