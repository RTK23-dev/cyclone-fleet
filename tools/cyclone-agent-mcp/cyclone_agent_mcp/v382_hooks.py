from __future__ import annotations

from typing import Any
import urllib.parse


def _install() -> None:
    from .gateway import GatewayClient
    from .phone_mcp import normalize_desktop_action

    original = GatewayClient.action

    def action(self, tool: str, params: dict[str, Any], goal: str, device_id: str | None = None, **kwargs: Any) -> Any:
        response = original(self, tool, params, goal, device_id, **kwargs)
        if isinstance(response, dict) and (
            "execution" in response or "transport" in response or response.get("protocol_version")
        ):
            target = device_id or response.get("device_id") or response.get("deviceId") or ""
            return normalize_desktop_action(str(target), tool, response)
        return response

    GatewayClient.action = action  # type: ignore[method-assign]

    if not hasattr(GatewayClient, "skill_save"):
        def skill_save(self, payload: dict[str, Any], device_id: str | None = None) -> Any:
            selected = self.select_device(device_id)
            if selected.legacy_unscoped:
                return self._bounded_route("POST", "/v1/skills/save", payload)
            return self._bounded_route("POST", self._agent_path(selected, "/skills"), payload)

        def skill_run(self, skill_id: str, *, dry_run: bool = False, params: dict[str, Any] | None = None, device_id: str | None = None) -> Any:
            selected = self.select_device(device_id)
            forwarded = dict(params or {})
            body = {"dryRun": bool(dry_run), "params": forwarded}
            if forwarded.get("sessionId"):
                body["sessionId"] = forwarded["sessionId"]
            if forwarded.get("displayId") is not None:
                body["displayId"] = forwarded["displayId"]
            quoted = urllib.parse.quote(skill_id, safe="")
            if selected.legacy_unscoped:
                return self._bounded_route("POST", f"/v1/skills/{quoted}/runs", body)
            return self._bounded_route("POST", self._agent_path(selected, f"/skills/{quoted}/runs"), body)

        def skill_match(self, goal: str, page_key: str = "", device_id: str | None = None) -> Any:
            selected = self.select_device(device_id)
            query = urllib.parse.urlencode({"goal": goal, "pageKey": page_key})
            if selected.legacy_unscoped:
                return self._bounded_route("GET", f"/v1/skills/match?{query}")
            return self._bounded_route("GET", self._agent_path(selected, f"/skills?{query}"))

        def skill_get(self, skill_id: str, device_id: str | None = None) -> Any:
            selected = self.select_device(device_id)
            quoted = urllib.parse.quote(skill_id, safe="")
            if selected.legacy_unscoped:
                return self._bounded_route("GET", f"/v1/skills/{quoted}")
            return self._bounded_route("GET", self._agent_path(selected, f"/skills/{quoted}"))

        GatewayClient.skill_save = skill_save  # type: ignore[attr-defined]
        GatewayClient.skill_run = skill_run  # type: ignore[attr-defined]
        GatewayClient.skill_match = skill_match  # type: ignore[attr-defined]
        GatewayClient.skill_get = skill_get  # type: ignore[attr-defined]


_install()
