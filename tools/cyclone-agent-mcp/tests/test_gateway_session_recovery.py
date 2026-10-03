from __future__ import annotations

from cyclone_agent_mcp.gateway import GatewayClient, GatewayError


class RecoveringGateway(GatewayClient):
    def __init__(self):
        super().__init__("http://127.0.0.1:8765", "stale-token")
        self.calls = 0

    def _request_once(self, method, path, payload=None):
        self.calls += 1
        if self.calls == 1:
            raise GatewayError("stale session", status=401, body={"error": {"code": "AUTH_REJECTED"}})
        return {"ok": True, "url": self.base_url}

    def _reload_secure_connection(self):
        self.token = "fresh-token"
        self.base_url = "http://127.0.0.1:9900"
        return True


def test_gateway_recovers_after_companion_rotates_port_and_token():
    gateway = RecoveringGateway()
    assert gateway._request("GET", "/v1/fleet") == {"ok": True, "url": "http://127.0.0.1:9900"}
    assert gateway.calls == 2
    assert gateway.token == "fresh-token"


def test_gateway_never_retries_auth_failure_without_a_new_secure_session():
    gateway = RecoveringGateway()
    gateway._reload_secure_connection = lambda: False
    try:
        gateway._request("GET", "/v1/fleet")
    except GatewayError as exc:
        assert exc.status == 401
    else:
        raise AssertionError("stale authorization was accepted")
    assert gateway.calls == 1
