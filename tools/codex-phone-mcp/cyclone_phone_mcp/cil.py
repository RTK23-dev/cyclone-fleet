"""Cyclone Input Language (CIL): human/test shorthand that compiles to CIP.

CIL never executes Android input. Its only output is a validated-shaped CIP
operation/arguments pair for the canonical engine.
"""
from __future__ import annotations

import re
import shlex
from typing import Any

_POINT = re.compile(r"^@([01](?:\.\d+)?|0?\.\d+),([01](?:\.\d+)?|0?\.\d+)$")
_ELEMENT = re.compile(r"^#([A-Za-z0-9_.:-]{1,240})$")
_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")


class CilError(ValueError): pass


def _point(token: str) -> dict[str, Any]:
    match = _POINT.fullmatch(token)
    if not match: raise CilError("Expected normalized @x,y point")
    x, y = float(match.group(1)), float(match.group(2))
    if not (0 <= x <= 1 and 0 <= y <= 1): raise CilError("Point must be within 0..1")
    return {"x": x, "y": y, "space": "display_norm"}


def _target(token: str) -> dict[str, Any]:
    element = _ELEMENT.fullmatch(token)
    if element: return {"element_id": element.group(1)}
    return {"point": _point(token)}


def compile_cil(text: str, *, observation_id: str | None = None, request_id: str | None = None,
                device: str | None = None) -> dict[str, Any]:
    if not isinstance(text, str) or not text.strip() or len(text) > 5000: raise CilError("Invalid CIL input")
    try: tokens = shlex.split(text, posix=True)
    except ValueError as exc: raise CilError("Malformed quoted CIL input") from exc
    if not tokens: raise CilError("Empty CIL input")
    head = tokens[0].upper(); args: dict[str, Any] = {}
    if device: args["device"] = device
    if head == "SEE":
        if len(tokens) > 2: raise CilError("SEE accepts at most one quoted goal")
        if len(tokens) == 2: args["goal"] = tokens[1]
        return {"protocol": "cyclone.live.v1", "operation": "see", "arguments": args}
    if head == "FIND":
        if len(tokens) != 2 or not observation_id: raise CilError("FIND requires one query and current observation_id")
        args.update({"observation_id": observation_id, "query": tokens[1]})
        return {"protocol": "cyclone.live.v1", "operation": "find", "arguments": args}
    if head == "OPEN":
        if len(tokens) != 2 or not _PACKAGE.fullmatch(tokens[1]): raise CilError("OPEN requires an Android package id")
        action = {"kind": "open_app", "package": tokens[1]}
    elif head == "DO":
        if len(tokens) < 2: raise CilError("DO requires an action")
        kind = tokens[1].lower()
        if kind in {"back", "home"}:
            if len(tokens) != 2: raise CilError(f"DO {kind} takes no target")
            action = {"kind": kind}
        elif kind in {"tap", "long"}:
            if len(tokens) != 3: raise CilError(f"DO {kind} requires one target")
            action = {"kind": "long_press" if kind == "long" else "tap", "target": _target(tokens[2])}
        elif kind == "type":
            if len(tokens) != 4: raise CilError("DO type requires #element and quoted text")
            target = _target(tokens[2])
            if "element_id" not in target: raise CilError("Typing requires #element")
            action = {"kind": "type", "target": target, "text": tokens[3]}
        elif kind == "clear":
            if len(tokens) != 3: raise CilError("DO clear requires #element")
            target = _target(tokens[2])
            if "element_id" not in target: raise CilError("Clearing requires #element")
            action = {"kind": "clear", "target": target}
        elif kind == "scroll":
            if len(tokens) != 3 or tokens[2].lower() not in {"up", "down", "forward", "backward"}: raise CilError("DO scroll requires up/down")
            action = {"kind": "scroll", "direction": tokens[2].lower()}
        elif kind == "swipe":
            if len(tokens) not in {5, 6} or tokens[3] != "->": raise CilError("DO swipe @x,y -> @x,y [350ms]")
            action = {"kind": "swipe", "from": _point(tokens[2]), "to": _point(tokens[4])}
            if len(tokens) == 6:
                if not tokens[5].endswith("ms") or not tokens[5][:-2].isdigit(): raise CilError("Swipe duration must be milliseconds")
                action["duration_ms"] = int(tokens[5][:-2])
        else: raise CilError("Unsupported CIL action")
    else: raise CilError("CIL starts with SEE, FIND, OPEN, or DO")
    if not observation_id or not request_id: raise CilError("Mutation requires current observation_id and unique request_id")
    args.update({"observation_id": observation_id, "request_id": request_id, "action": action})
    return {"protocol": "cyclone.live.v1", "operation": "act", "arguments": args}
