from __future__ import annotations

import getpass

from cyclone_device_gateway import cli
from cyclone_device_gateway.adb.onboarding import TransportOnboardingError


class FakeOnboarding:
    def __init__(self, _adb):
        self.calls = []

    def usb_status(self):
        return {"mode": "usb", "ok": True, "next": "ready", "devices": []}

    def pair_wireless(self, pair_endpoint, pairing_code, connect_endpoint):
        self.calls.append((pair_endpoint, pairing_code, connect_endpoint))
        return {"mode": "wifi", "ok": True, "next": "ready", "endpoint": connect_endpoint}

    def connect(self, endpoint, *, mode="vmos"):
        return {"mode": mode, "ok": True, "next": "ready", "endpoint": endpoint}


def test_transport_cli_parser_has_no_pairing_code_argument():
    parser = cli.build_parser()
    wifi = parser.parse_args([
        "transport", "wifi",
        "--pair", "10.0.0.2:37001",
        "--connect", "10.0.0.2:42001",
    ])
    assert wifi.pair_endpoint == "10.0.0.2:37001"
    assert wifi.connect_endpoint == "10.0.0.2:42001"
    assert not hasattr(wifi, "pairing_code")


def test_transport_cli_wifi_reads_pairing_code_from_hidden_prompt(monkeypatch, capsys):
    fake = FakeOnboarding(None)
    monkeypatch.setattr(cli, "ADBTransportOnboarding", lambda _adb: fake)
    monkeypatch.setattr(cli, "ADBClient", lambda _path: object())
    monkeypatch.setattr(cli, "resolve_adb_path", lambda: "bundled-adb")
    monkeypatch.setattr(getpass, "getpass", lambda _prompt: "123456")
    result = cli.main([
        "transport", "wifi",
        "--pair", "10.0.0.2:37001",
        "--connect", "10.0.0.2:42001",
    ])
    assert result == 0
    assert fake.calls == [("10.0.0.2:37001", "123456", "10.0.0.2:42001")]
    assert "123456" not in capsys.readouterr().out


def test_transport_cli_safe_failure_does_not_echo_secret(monkeypatch, capsys):
    class Failing(FakeOnboarding):
        def pair_wireless(self, pair_endpoint, pairing_code, connect_endpoint):
            raise TransportOnboardingError("WIRELESS_PAIR_FAILED", "Wireless pairing failed safely.")

    monkeypatch.setattr(cli, "ADBTransportOnboarding", lambda _adb: Failing(None))
    monkeypatch.setattr(cli, "ADBClient", lambda _path: object())
    monkeypatch.setattr(cli, "resolve_adb_path", lambda: "bundled-adb")
    monkeypatch.setattr(getpass, "getpass", lambda _prompt: "654321")
    result = cli.main([
        "transport", "wifi",
        "--pair", "10.0.0.2:37001",
        "--connect", "10.0.0.2:42001",
    ])
    assert result == 2
    assert "654321" not in capsys.readouterr().out
