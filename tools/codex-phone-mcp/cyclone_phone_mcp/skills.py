"""MCP skill save/run helpers.

These tools are a model-facing adapter. Durable writes go through the existing
Android AutomationStore via SkillCompiler.compile (gateway bridge). This module
does not keep a second JSON skill brain.
"""
from __future__ import annotations

from typing import Any

from .compact import redact

STORE_CLASS = "AutomationStore"
COMPILER = "SkillCompiler.compile"
SKILL_STATUSES = {"draft", "review", "verified", "quarantined"}
SECRET_SLOT_KEYS = {
    "password", "passwd", "passcode", "otp", "token", "api_key", "apikey", "secret",
    "authorization", "cookie", "typed_value", "text_value", "input_value", "credential",
    "pin", "pincode", "cvv", "ssn",
}
OBSERVATION_SCOPED_KEYS = {"elementId", "element_id"}
ANDROID_SKILL_OPS_MISSING = "ANDROID_SKILL_OPS_MISSING"
_MISSING_ANDROID_SKILL_OPS = frozenset({
    "UNKNOWN_OPERATION",
    "CAPABILITY_UNAVAILABLE",
    ANDROID_SKILL_OPS_MISSING,
})


def _is_secret_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_")
    if normalized in SECRET_SLOT_KEYS:
        return True
    return any(part in normalized for part in ("password", "secret", "api_key", "otp", "passcode"))


def strip_secret_slots(value: Any, parent_key: str = "") -> Any:
    """Drop secret slots entirely (do not persist even as redacted placeholders)."""
    if _is_secret_key(parent_key):
        return None
    if isinstance(value, dict):
        stripped: dict[str, Any] = {}
        for key, item in value.items():
            if _is_secret_key(str(key)):
                continue
            nested = strip_secret_slots(item, str(key))
            stripped[str(key)] = nested
        return stripped
    if isinstance(value, list):
        return [strip_secret_slots(item, parent_key) for item in value]
    return value


def _iter_error_codes(value: Any) -> list[str]:
    codes: list[str] = []
    if value is None:
        return codes
    if isinstance(value, BaseException):
        codes.extend(_iter_error_codes(getattr(value, "body", None)))
        message = str(value)
        for token in _MISSING_ANDROID_SKILL_OPS:
            if token in message:
                codes.append(token)
        return codes
    if not isinstance(value, dict):
        return codes
    for key in ("errorClass", "code", "error_class"):
        raw = value.get(key)
        if isinstance(raw, str) and raw:
            codes.append(raw)
    error = value.get("error")
    if isinstance(error, dict):
        codes.extend(_iter_error_codes(error))
    elif isinstance(error, str) and error:
        codes.append(error)
    detail = value.get("detail")
    if isinstance(detail, dict):
        codes.extend(_iter_error_codes(detail))
    return codes


def android_skill_ops_missing(value: Any) -> bool:
    """True when Android skill.compile / skill.run / skill.match is not available."""
    return any(code in _MISSING_ANDROID_SKILL_OPS for code in _iter_error_codes(value))


def missing_android_skill_ops(kind: str, extra: dict[str, Any] | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {
        "kind": kind,
        "ok": False,
        "written": False,
        "skipModel": False,
        "matchedSkill": None,
        "storeClass": STORE_CLASS,
        "compiler": COMPILER,
        "errorClass": ANDROID_SKILL_OPS_MISSING,
        "error": {
            "code": ANDROID_SKILL_OPS_MISSING,
            "layer": "capability",
            "message": (
                "Android skill.compile / skill.run / skill.match ops are missing. "
                "Fail closed until the phone exposes those operations; this is not the V3.8 live path."
            ),
        },
    }
    if extra:
        body.update(extra)
    return body


def attach_missing_android_skill_ops(result: dict[str, Any]) -> dict[str, Any]:
    """Fail closed on locate without dropping the Page Card."""
    result["ok"] = False
    result["skipModel"] = False
    result["matchedSkill"] = None
    result["errorClass"] = ANDROID_SKILL_OPS_MISSING
    result["error"] = {
        "code": ANDROID_SKILL_OPS_MISSING,
        "layer": "capability",
        "message": (
            "Android skill.compile / skill.run / skill.match ops are missing. "
            "Fail closed until the phone exposes those operations; this is not the V3.8 live path."
        ),
    }
    return result


def draft_run_denied(skill_id: str, status: str = "draft") -> dict[str, Any]:
    return _draft_denied(skill_id, status)


def _step_verified(step: Any) -> bool:
    if not isinstance(step, dict):
        return False
    if step.get("verified") is True or step.get("ok") is True:
        return True
    envelope = step.get("envelope") if isinstance(step.get("envelope"), dict) else step
    if envelope.get("ok") is True and not envelope.get("errorClass"):
        return True
    action_status = envelope.get("actionStatus") if isinstance(envelope.get("actionStatus"), dict) else {}
    return action_status.get("verified") is True


def steps_are_verified(steps: Any) -> bool:
    if not isinstance(steps, list) or len(steps) < 2:
        return False
    return all(_step_verified(step) for step in steps)


def _durable_params(params: Any) -> dict[str, Any]:
    if not isinstance(params, dict):
        return {}
    cleaned = strip_secret_slots(params)
    if not isinstance(cleaned, dict):
        return {}
    selector = cleaned.get("selector") if isinstance(cleaned.get("selector"), dict) else {}
    durable_selector = {
        key: value for key, value in selector.items()
        if key not in OBSERVATION_SCOPED_KEYS
    }
    result = {
        key: value for key, value in cleaned.items()
        if key not in OBSERVATION_SCOPED_KEYS and key != "selector"
    }
    if durable_selector:
        result["selector"] = durable_selector
    return result


def _location(card: Any) -> dict[str, Any]:
    if not isinstance(card, dict):
        return {}
    location = card.get("location") if isinstance(card.get("location"), dict) else {}
    result = {}
    for key in ("pageKey", "package", "activity"):
        value = card.get(key) or location.get(key)
        if isinstance(value, str) and value:
            result[key] = value
    return result


def _compile_step(step: dict[str, Any], index: int) -> dict[str, Any]:
    envelope = step.get("envelope") if isinstance(step.get("envelope"), dict) else step
    tool = str(step.get("tool") or envelope.get("tool") or "").strip()
    params = step.get("params") if isinstance(step.get("params"), dict) else {}
    before = envelope.get("before") or envelope.get("beforePageCard")
    after = envelope.get("after") or envelope.get("afterPageCard")
    if isinstance(after, dict) and isinstance(after.get("pageCard"), dict):
        after_card = after.get("pageCard")
    else:
        after_card = after
    compiled = {
        "id": str(step.get("id") or f"step-{index + 1}"),
        "name": str(step.get("name") or tool or f"Step {index + 1}"),
        "tool": tool,
        "verified": True,
        "when": _location(before),
        "then": {"tool": tool, "params": _durable_params(params)},
        "check": _location(after_card),
        "errorClass": envelope.get("errorClass"),
        "generation": envelope.get("generation"),
    }
    selector = step.get("selector") if isinstance(step.get("selector"), dict) else None
    if selector:
        compiled["then"]["selector"] = strip_secret_slots(selector)
    return compiled


def build_save_payload(args: dict[str, Any]) -> dict[str, Any]:
    """Return either a denial envelope or the compile payload for AutomationStore."""
    goal = str(args.get("goal") or "").strip()
    if not goal:
        raise ValueError("goal is required")
    steps = args.get("steps")
    if not isinstance(steps, list):
        raise ValueError("steps must be an array")
    if len(steps) < 2 or not steps_are_verified(steps):
        return {
            "kind": "phone_skill_save",
            "ok": False,
            "written": False,
            "status": None,
            "storeClass": STORE_CLASS,
            "compiler": COMPILER,
            "errorClass": "UNVERIFIED_STEPS",
            "error": {
                "code": "UNVERIFIED_STEPS",
                "layer": "protocol",
                "message": "phone_skill_save writes only when 2+ steps are verified. No durable write was made.",
            },
        }
    slots = args.get("params") if isinstance(args.get("params"), dict) else {}
    payload = {
        "goal": goal,
        "app": str(args.get("app") or args.get("package") or "").strip(),
        "pageKey": str(args.get("pageKey") or args.get("page_key") or "").strip(),
        "status": "draft",
        "enabled": False,
        "storeClass": STORE_CLASS,
        "compiler": COMPILER,
        "source": "PC_CODEX",
        "steps": [_compile_step(step, index) for index, step in enumerate(steps) if isinstance(step, dict)],
        "params": strip_secret_slots(slots) if isinstance(slots, dict) else {},
    }
    return {"_compile": payload}


def save_success(gateway_result: Any, payload: dict[str, Any]) -> dict[str, Any]:
    body = gateway_result if isinstance(gateway_result, dict) else {"raw": gateway_result}
    if android_skill_ops_missing(body) or android_skill_ops_missing(gateway_result):
        return missing_android_skill_ops("phone_skill_save")
    if body.get("ok") is False:
        return {
            "kind": "phone_skill_save",
            "ok": False,
            "written": False,
            "status": None,
            "enabled": False,
            "storeClass": str(body.get("storeClass") or STORE_CLASS),
            "compiler": COMPILER,
            "errorClass": str(body.get("errorClass") or (body.get("error") or {}).get("code") or "SKILL_SAVE_FAILED"),
            "error": body.get("error") or {"code": body.get("errorClass") or "SKILL_SAVE_FAILED", "layer": "execution"},
        }
    skill = body.get("skill") if isinstance(body.get("skill"), dict) else body
    return redact({
        "kind": "phone_skill_save",
        "ok": True,
        "written": True,
        "status": "draft",
        "enabled": False,
        "storeClass": str(skill.get("storeClass") or body.get("storeClass") or STORE_CLASS),
        "compiler": COMPILER,
        "skillId": skill.get("id") or skill.get("skillId") or body.get("skillId"),
        "skill": skill,
    })


def matched_verified_skill(match_raw: Any, goal: str, page_key: str) -> dict[str, Any] | None:
    if not isinstance(match_raw, dict):
        return None
    skill = match_raw.get("skill") if isinstance(match_raw.get("skill"), dict) else None
    if skill is None:
        skills = match_raw.get("skills") if isinstance(match_raw.get("skills"), list) else []
        for item in skills:
            if isinstance(item, dict) and str(item.get("status") or "").lower() == "verified":
                skill = item
                break
    if not isinstance(skill, dict):
        return None
    status = str(skill.get("status") or "").lower()
    if status != "verified":
        return None
    skill_goal = str(skill.get("goal") or "").strip().lower()
    skill_page = str(skill.get("pageKey") or skill.get("page_key") or "").strip()
    if skill_goal and skill_goal != goal.strip().lower():
        if goal.strip().lower() not in skill_goal and skill_goal not in goal.strip().lower():
            return None
    if page_key and skill_page and skill_page != page_key:
        return None
    return redact({
        "id": skill.get("id") or skill.get("skillId"),
        "status": "verified",
        "goal": skill.get("goal") or goal,
        "pageKey": skill_page or page_key,
        "storeClass": skill.get("storeClass") or STORE_CLASS,
        "skipModel": True,
        "next": "A verified skill matches this goal and pageKey. Call phone_skill_run and skip the model.",
    })


def normalize_run(result: Any, *, skill_id: str, dry_run: bool) -> dict[str, Any]:
    body = result if isinstance(result, dict) else {"raw": result}
    if android_skill_ops_missing(body) or android_skill_ops_missing(result):
        return missing_android_skill_ops("phone_skill_run", {"skillId": skill_id, "dryRun": dry_run, "steps": []})
    status = str(body.get("status") or (body.get("skill") or {}).get("status") or "").lower()
    if body.get("errorClass") == "DRAFT_RUN_DENIED" or body.get("denied") is True:
        return _draft_denied(skill_id, status or "draft")
    if status == "draft" and not dry_run:
        return _draft_denied(skill_id, status)
    steps = body.get("steps") or body.get("envelopes") or body.get("results") or []
    if not isinstance(steps, list):
        steps = []
    envelopes = [_as_act_envelope(item, index) for index, item in enumerate(steps)]
    ok = bool(envelopes) and all(item.get("ok") is True for item in envelopes) and not body.get("error")
    if body.get("ok") is False:
        ok = False
    return redact({
        "kind": "phone_skill_run",
        "skillId": skill_id,
        "status": status or body.get("status"),
        "dryRun": dry_run,
        "ok": ok,
        "storeClass": body.get("storeClass") or STORE_CLASS,
        "steps": envelopes,
        "errorClass": None if ok else (body.get("errorClass") or (body.get("error") or {}).get("code")),
        "error": None if ok else (body.get("error") or {"code": body.get("errorClass") or "SKILL_RUN_FAILED", "layer": "execution"}),
    })


def _draft_denied(skill_id: str, status: str) -> dict[str, Any]:
    return {
        "kind": "phone_skill_run",
        "skillId": skill_id,
        "status": status or "draft",
        "dryRun": False,
        "ok": False,
        "written": False,
        "storeClass": STORE_CLASS,
        "steps": [],
        "errorClass": "DRAFT_RUN_DENIED",
        "error": {
            "code": "DRAFT_RUN_DENIED",
            "layer": "policy",
            "message": "Draft skills cannot run live. Pass dryRun=true or wait until the skill is verified on the phone.",
        },
    }


def _as_act_envelope(item: Any, index: int) -> dict[str, Any]:
    if not isinstance(item, dict):
        return {
            "kind": "phone_action_result",
            "ok": False,
            "pageChanged": None,
            "before": None,
            "after": None,
            "delta": None,
            "errorClass": "PROTOCOL_MISMATCH",
            "generation": None,
            "stepIndex": index,
        }
    before_card = item.get("beforePageCard") if isinstance(item.get("beforePageCard"), dict) else None
    after_card = item.get("afterPageCard") if isinstance(item.get("afterPageCard"), dict) else None
    before = item.get("before") if isinstance(item.get("before"), dict) else _location(before_card)
    after = item.get("after") if isinstance(item.get("after"), dict) else None
    if after is None and after_card is not None:
        after = {**_location(after_card), "pageCard": after_card}
    elif isinstance(after, dict) and "pageCard" not in after and after_card is not None:
        after = {**after, "pageCard": after_card}
    return {
        "kind": item.get("kind") or "phone_action_result",
        "ok": item.get("ok") is True,
        "pageChanged": item.get("pageChanged"),
        "before": before,
        "after": after,
        "beforePageCard": before_card,
        "afterPageCard": after_card,
        "delta": item.get("delta"),
        "errorClass": item.get("errorClass"),
        "generation": item.get("generation"),
        "tool": item.get("tool"),
        "stepIndex": item.get("stepIndex", index),
        "actionStatus": item.get("actionStatus"),
    }
