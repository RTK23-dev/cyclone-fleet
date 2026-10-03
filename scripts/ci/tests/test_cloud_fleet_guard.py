"""Plan 44 run 1 (alpha 90): cloud phones, kept connected within fixed limits.

- The provider is only the way in: Cyclone asks it for a phone list and a remote-ADB link, nothing else. No
  provider-native taps, typing or shell (VMOS asyncCmd / simulated touch, DuoPlus command) in this run.
- adb is used for connect, disconnect and the device list only; ssh only as a foreground tunnel on 127.0.0.1.
- Provider keys never reach a response: they live in the DPAPI vault (memory only off Windows) and reach ssh only
  through its askpass environment, never a command line or a plain file.
- No model-facing tool can add an account or keep a phone connected: it is the owner's page in Glass.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CLOUD = ROOT / "apps/device-gateway/cyclone_device_gateway/cloud_fleet"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def source() -> str:
    return "\n".join(text(p) for p in sorted(CLOUD.rglob("*.py")))


def test_providers_only_list_phones_and_open_adb():
    body = source()
    endpoints = re.findall(r'"(/[A-Za-z0-9/_\-.]+)"', "\n".join(text(p) for p in (CLOUD / "providers").glob("*.py")))
    assert set(endpoints) == {"/vcpcloud/api/padApi/infos", "/vcpcloud/api/padApi/adb", "/api/v1/cloudPhone/list"}, endpoints
    for forbidden in ("asyncCmd", "syncCmd", "simulateTouch", "inputText", "cloudPhone/command", "switchRoot", "installApp",
                      "uploadFile", "shell=True", "os.system"):
        assert forbidden not in body, forbidden


def test_adb_is_connect_disconnect_and_devices_only():
    body = source()
    runs = set(re.findall(r"\.run\(\[([^\]]*)\]", body))
    assert runs == {'"disconnect", serial'}, runs
    assert ".shell(" not in body.replace("read_installed(session.adb)", "")
    assert set(re.findall(r"self\.adb\.(\w+)\(", body)) <= {"connect", "devices", "run"}


def test_ssh_is_a_foreground_loopback_tunnel_and_the_key_only_travels_by_askpass():
    tunnel = text(CLOUD / "tunnel.py")
    assert '"-N"' in tunnel and '"-f"' not in tunnel and "-Nf" not in tunnel.split('"""', 2)[2]
    assert 'f"127.0.0.1:{local_port}:' in tunnel
    assert "ExitOnForwardFailure=yes" in tunnel
    assert "ASKPASS_SECRET: secret" in tunnel and "0o600" in tunnel
    # The secret is never put on ssh's argv.
    argv = tunnel[tunnel.index("def ssh_argv"):tunnel.index("def explain")]
    assert "secret" not in argv
    entry = text(ROOT / "scripts/pc-companion/entrypoints/pc_runtime.py")
    assert entry.index("askpass_main()") < entry.index("persist_runtime_bearer(")


def test_keys_are_kept_in_the_vault_and_never_returned():
    vault = text(CLOUD / "vault.py")
    assert "_dpapi_transform" in vault and "MEMORY_ONLY" in vault
    service = text(CLOUD / "service.py")
    public = service[service.index("def account_public"):service.index("def metadata_for_serial")]
    assert '"secrets":' not in public and "lease.secret" not in public and '"hasKey": bool(account.get("secrets"))' in public
    models = text(CLOUD / "models.py")
    public_link = models[models.index("class AdbLink"):]
    public_link = public_link[public_link.index("def public"):public_link.index("def normalize_address")]
    assert "secret" not in public_link
    api = text(CLOUD / "api.py")
    assert api.count('extra="forbid"') == 3
    for word in ("command", "argv", "shell", "url"):
        assert not re.search(rf"^\s+{word}\s*:", api, re.M), word


def test_no_model_facing_tool_can_manage_cloud_phones():
    for tools in (ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp"):
        for path in tools.rglob("*.py"):
            body = text(path)
            assert "/v1/cloud" not in body and "cloud_fleet" not in body, path
    ai = text(ROOT / "apps/device-gateway/cyclone_device_gateway/command/ai.py")
    assert "/v1/cloud" not in ai and "cloud_fleet" not in ai
