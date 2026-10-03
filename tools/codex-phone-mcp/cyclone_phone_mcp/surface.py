from __future__ import annotations

import re
from typing import Any

from .gateway import GatewayError
from .gateway_skills import GatewayClient
from .reports import SessionRecorder
from .skills import (
    android_skill_ops_missing,
    attach_missing_android_skill_ops,
    build_save_payload,
    draft_run_denied,
    matched_verified_skill,
    missing_android_skill_ops,
    normalize_run,
    save_success,
    strip_secret_slots,
)
from .session import attach_execution_scope, parse_tool_execution_scope, require_tool_execution_scope
from .tools import PhoneTools as CorePhoneTools, _identity_kwargs

SKILL_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


class PhoneTools(CorePhoneTools):
    """Default V3.8 PC surface: live AutomationStore save/run plus locate skillMatch."""

    def __init__(self, gateway: Any | None = None, recorder: SessionRecorder | None = None):
        super().__init__(gateway or GatewayClient(), recorder)

    def phone_locate(self, args: dict[str, Any]) -> Any:
        result = super().phone_locate(args)
        if not isinstance(result, dict) or result.get("kind") != "phone_locate":
            return result
        page_card = result.get("pageCard") if isinstance(result.get("pageCard"), dict) else {}
        page_key = str(page_card.get("pageKey") or "")
        goal = str(result.get("goal") or args.get("goal") or "")
        device_id = str(args.get("device_id") or "").strip()
        match_raw: Any = None
        try:
            if device_id and hasattr(self.gateway, "device_skill_match"):
                match_raw = self.gateway.device_skill_match(device_id, goal, page_key)
            elif hasattr(self.gateway, "skill_match"):
                match_raw = self.gateway.skill_match(goal, page_key)
        except GatewayError as exc:
            if android_skill_ops_missing(exc):
                return attach_missing_android_skill_ops(result)
            match_raw = None
        except (AttributeError, TypeError, ValueError):
            match_raw = None
        if android_skill_ops_missing(match_raw):
            return attach_missing_android_skill_ops(result)
        matched = matched_verified_skill(match_raw, goal, page_key)
        result["matchedSkill"] = matched
        result["skipModel"] = bool(matched)
        if matched:
            result["next"] = matched["next"]
        return result

    def phone_act(self, args: dict[str, Any]) -> Any:
        return _with_act_contract(super().phone_act(args))

    def phone_skill_save(self, args: dict[str, Any]) -> Any:
        built = build_save_payload(args)
        if built.get("written") is False:
            return built
        payload = built.get("_compile")
        if not isinstance(payload, dict):
            raise ValueError("skill compile payload is invalid")
        scope = parse_tool_execution_scope(args)
        if scope:
            payload = attach_execution_scope(payload, scope)
        device_id = str(args.get("device_id") or "").strip()
        try:
            if device_id:
                result = self.gateway.device_skill_save(device_id, payload)
            else:
                result = self.gateway.skill_save(payload)
        except GatewayError as exc:
            if android_skill_ops_missing(exc):
                return missing_android_skill_ops("phone_skill_save")
            raise
        return save_success(result, payload)

    def phone_skill_run(self, args: dict[str, Any]) -> Any:
        skill_id = str(args.get("skill_id") or args.get("skillId") or "").strip()
        if not SKILL_ID.fullmatch(skill_id):
            raise ValueError("skill_id is invalid")
        dry_run = bool(args.get("dryRun") or args.get("dry_run"))
        params = args.get("params") if isinstance(args.get("params"), dict) else {}
        params = strip_secret_slots(params)
        scope = require_tool_execution_scope(args)
        params = attach_execution_scope(params, scope)
        identity = _identity_kwargs(scope)
        device_id = str(args.get("device_id") or "").strip()
        if not dry_run:
            meta = _skill_meta(self.gateway, device_id, skill_id)
            if isinstance(meta, dict) and str(meta.get("status") or "").lower() == "draft":
                return draft_run_denied(skill_id, "draft")
        try:
            if device_id:
                result = _call_skill_run(
                    self.gateway.device_skill_run, device_id, skill_id, dry_run=dry_run, params=params, identity=identity,
                )
            else:
                result = _call_skill_run(
                    self.gateway.skill_run, skill_id, dry_run=dry_run, params=params, identity=identity,
                )
        except GatewayError as exc:
            if android_skill_ops_missing(exc):
                return missing_android_skill_ops(
                    "phone_skill_run",
                    {"skillId": skill_id, "dryRun": dry_run, "steps": []},
                )
            raise
        return normalize_run(result, skill_id=skill_id, dry_run=dry_run)


def _call_skill_run(method: Any, *positional: Any, dry_run: bool, params: dict[str, Any], identity: dict[str, Any]) -> Any:
    try:
        return method(*positional, dry_run=dry_run, params=params, **identity)
    except TypeError:
        return method(*positional, dry_run=dry_run, params=params)


def _skill_meta(gateway: Any, device_id: str, skill_id: str) -> dict[str, Any] | None:
    """Optional lookup used to deny draft live runs locally before POST /runs."""
    try:
        if device_id and hasattr(gateway, "device_skill_get"):
            raw = gateway.device_skill_get(device_id, skill_id)
        elif hasattr(gateway, "skill_get"):
            raw = gateway.skill_get(skill_id)
        else:
            return None
    except (GatewayError, AttributeError, TypeError, ValueError, KeyError):
        return None
    if not isinstance(raw, dict):
        return None
    skill = raw.get("skill") if isinstance(raw.get("skill"), dict) else raw
    return skill if isinstance(skill, dict) else None


def _with_act_contract(result: Any) -> Any:
    """Add frozen act-envelope aliases without dropping existing Page Card keys."""
    if not isinstance(result, dict) or result.get("kind") != "phone_action_result":
        return result
    before_card = result.get("beforePageCard") if isinstance(result.get("beforePageCard"), dict) else None
    after_card = result.get("afterPageCard") if isinstance(result.get("afterPageCard"), dict) else None
    result.setdefault("generation", _generation(before_card))
    result.setdefault("before", _location(before_card))
    after_location = _location(after_card)
    if after_card is not None:
        after_location = {**after_location, "pageCard": after_card}
        result.setdefault("after", after_location)
    else:
        result.setdefault("after", None)
    return result


def _generation(card: dict[str, Any] | None) -> str | None:
    if not isinstance(card, dict):
        return None
    scope = card.get("observationScope") if isinstance(card.get("observationScope"), dict) else {}
    value = scope.get("id")
    return str(value) if value else None


def _location(card: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(card, dict):
        return None
    location = card.get("location") if isinstance(card.get("location"), dict) else {}
    result = {}
    for key in ("pageKey", "package", "activity"):
        value = card.get(key) or location.get(key)
        if isinstance(value, str) and value:
            result[key] = value
    return result
