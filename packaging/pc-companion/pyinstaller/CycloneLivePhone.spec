from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
entrypoints = repo / "scripts" / "pc-companion" / "entrypoints"
a = Analysis(
    [str(entrypoints / "live_phone.py")],
    pathex=[
        str(repo / "tools" / "cyclone-agent-mcp"),
        str(repo / "tools" / "codex-phone-mcp"),
        str(repo / "apps" / "device-gateway"),
        str(entrypoints),
    ],
    binaries=[],
    datas=[],
    hiddenimports=["mcp", "cyclone_phone_mcp", "secure_gateway_token", "cyclone_device_gateway.tooling_seam"],
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
    name="CycloneLivePhone",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    # AgentMCP is a CLI protocol sidecar and Tauri captures its stdout JSON. Keep the console
    # subsystem for working stdio, but have the PyInstaller bootloader hide an owned console before
    # Python starts so users never see a command-prompt window.
    console=True,
    hide_console="hide-early",
)
