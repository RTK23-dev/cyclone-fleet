from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
entrypoints = repo / "scripts" / "pc-companion" / "entrypoints"
scrcpy = repo / "apps" / "device-gateway" / "third_party" / "scrcpy"
glass_dist = repo / "apps" / "glass" / "dist"
# The `cyclone` terminal command compares this with the newest GitHub release.
import re
product_version = re.search(r'^product_version\s*=\s*"([^"]+)"', (repo / "release" / "version.toml").read_text(encoding="utf-8"), re.MULTILINE).group(1)
version_file = Path(workpath) / "product_version.txt"
version_file.parent.mkdir(parents=True, exist_ok=True)
version_file.write_text(product_version, encoding="utf-8")
if not (glass_dist / "index.html").is_file():
    raise SystemExit("Cyclone Glass is not built: run `npm ci && npm run build` in apps/glass (build-sidecars.ps1 does this).")
a = Analysis(
    [str(entrypoints / "pc_runtime.py")],
    pathex=[str(repo / "tools" / "codex-phone-mcp"), str(repo / "apps" / "device-gateway"), str(repo / "tools" / "cyclone-ports-sdk"), str(entrypoints)],
    binaries=[],
    datas=[
        (str(scrcpy / "scrcpy-server-v4.0"), "third_party/scrcpy"),
        (str(scrcpy / "scrcpy-v4.0.json"), "third_party/scrcpy"),
        (str(scrcpy / "LICENSE"), "third_party/scrcpy"),
        (str(scrcpy / "NOTICE.md"), "third_party/scrcpy"),
        # Served by cyclone_device_gateway.glass.resolve_glass_dist() from the package-relative static/ folder.
        (str(glass_dist), "cyclone_device_gateway/glass/static"),
        (str(version_file), "cyclone_device_gateway/terminal"),
    ],
    hiddenimports=["cyclone_phone_mcp.live_phone_ipc", "secure_gateway_token", "cyclone_device_gateway.tooling_seam", "cyclone_device_gateway.glass.launcher",
                   "cyclone_device_gateway.terminal.app", "cyclone_device_gateway.terminal.install",
                   "cyclone_device_gateway.terminal.updater", "cyclone_device_gateway.terminal.window",
                   "cyclone_device_gateway.terminal.console", "cyclone_device_gateway.terminal.release",
                   "cyclone_device_gateway.lab.api", "cyclone_device_gateway.lab.runner", "cyclone_device_gateway.lab.probes",
                   "cyclone_device_gateway.cloud_fleet.api", "cyclone_device_gateway.cloud_fleet.tunnel",
                   "cyclone_device_gateway.cloud_fleet.providers.vmos", "cyclone_device_gateway.cloud_fleet.providers.duoplus",
                   "cyclone_device_gateway.cloud_fleet.providers.remote_adb",
                   "cyclone_device_gateway.lab.missions", "cyclone_device_gateway.lab.verdict", "cyclone_device_gateway.lab.stats",
                   "cyclone_device_gateway.market.api", "cyclone_device_gateway.market.pc_connections",
                   # Plan 31: Remote MCP, ChatGPT Attach and share, served to Glass (imported inside build_serve_app).
                   "cyclone_device_gateway.pc.api", "cyclone_device_gateway.pc.common", "cyclone_device_gateway.pc.tunnel",
                   "cyclone_device_gateway.pc.attach", "cyclone_device_gateway.pc.share",
                   # Plan 48: the Port Hub and the Cyclone Ports kit it imports (the checker is imported when it runs).
                   "cyclone_device_gateway.ports.api", "cyclone_device_gateway.ports.hub", "cyclone_device_gateway.ports.store",
                   "cyclone_device_gateway.ports.id_generator",
                   "cyclone_ports", "cyclone_ports.catalog", "cyclone_ports.sdk", "cyclone_ports.devhub", "cyclone_ports.conformance",
                   # Plan 33: the Command Center (some modules are imported inside CommandCenter.__init__).
                   "cyclone_device_gateway.command.api", "cyclone_device_gateway.command.center", "cyclone_device_gateway.command.schedule",
                   "cyclone_device_gateway.command.vault", "cyclone_device_gateway.command.delivery",
                   "cyclone_device_gateway.command.connections", "cyclone_device_gateway.command.mcp",
                   "cyclone_device_gateway.command.local",
                   # Plan 34 M3/M4: API connectors (imported inside ConnectionStore) and the YAML reader they use.
                   "cyclone_device_gateway.command.openapi", "yaml",
                   # Plan 33 C5: pages (imported inside CommandCenter.__init__), and the step chain.
                   "cyclone_device_gateway.command.pages", "cyclone_device_gateway.command.steps",
                   # Plan 33 §7: the AI project manager and the page Markdown it reads and writes.
                   "cyclone_device_gateway.command.ai", "cyclone_device_gateway.command.pagetext"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas,
    [],
    name="CyclonePCRuntime",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    # Console subsystem so `cyclone` in a terminal gets real output, questions and close events; the bootloader
    # hides a console it owns (Cyclone One, installer hooks) before Python starts, so users never see a window.
    # A windowed one-file build cannot print: its Python child's parent is the console-less bootloader.
    console=True,
    hide_console="hide-early",
    # The Cyclone mark (brand/logo-wormhole) on the app tile; Start menu and desktop shortcuts point at this exe.
    icon=str(repo / "packaging" / "pc" / "cyclone.ico"),
)
