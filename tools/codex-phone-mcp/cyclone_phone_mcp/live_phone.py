"""Cloud connector facade. Native Codex MCP is deliberately unchanged."""
from __future__ import annotations

SESSION = "default-foreground"
DISPLAY = 0
READS = {"status", "devices", "observe", "screenshot", "locate", "inspect"}
ACTIONS = {"tap", "long-press", "type", "clear-text", "swipe", "scroll", "back", "home", "open-app"}
COMMANDS = READS | ACTIONS


def validate_request(request):
    if not isinstance(request, dict) or request.get("operation") not in COMMANDS:
        raise ValueError("Unsupported Live Phone operation")
    if set(request) - {"operation", "device", "goal", "element", "text", "direction", "package", "observation_id", "user_authorized"}:
        raise ValueError("Unexpected Live Phone parameter")
    for key, value in request.items():
        if key == "user_authorized":
            if type(value) is not bool:
                raise ValueError("Authorization must be boolean")
        elif not isinstance(value, str) or len(value) > (4000 if key == "text" else 240):
            raise ValueError("Invalid bounded parameter")
    return dict(request)

import os
from io import BytesIO
from PIL import Image
import threading
import time
from pathlib import Path
from .tools import PhoneTools
from .protocol import classify_failure


class LivePhone:
    def __init__(self, tools=None, root=None):
        self.tools = tools or PhoneTools()
        self.root = Path(root or Path(os.getenv("LOCALAPPDATA", str(Path.home()))) / "Cyclone One" / "live-phone")
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.observations = {}
        self.paused = True  # One's user must explicitly enable cloud control.
        self.last_seen = 0.0

    def _args(self, request):
        if not request.get("device"):
            raise ValueError("Choose a device from devices first")
        return {"device_id": request["device"], "session_id": SESSION, "display_id": DISPLAY}

    def _image(self, raw):
        for name in ("latest.png", "latest.jpg"):
            (self.root / name).unlink(missing_ok=True)
        screenshot = raw.get("screenshot") or {}
        artifact = screenshot.get("artifact") or {}
        reference = artifact.get("reference")
        if screenshot.get("available") is not True or not reference:
            return {"ready": False, "reason": "CURRENT_SCREENSHOT_UNAVAILABLE"}
        source = Path(reference).resolve()
        runtime = Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", str(self.root.parent / "runtime"))).resolve()
        if not source.is_relative_to(runtime / "fleet-screenshots") or not source.is_file() or source.stat().st_size > 8 * 1024 * 1024:
            return {"ready": False, "reason": "INVALID_SCREENSHOT_ARTIFACT"}
        with source.open("rb") as stream:
            data = stream.read(8 * 1024 * 1024 + 1)
        if len(data) > 8 * 1024 * 1024:
            return {"ready": False, "reason": "INVALID_SCREENSHOT_ARTIFACT"}
        suffix = ".png" if data.startswith(b"\x89PNG\r\n\x1a\n") else ".jpg" if data.startswith(b"\xff\xd8\xff") else None
        if not suffix:
            return {"ready": False, "reason": "INVALID_IMAGE"}
        try:
            with Image.open(BytesIO(data)) as decoded:
                if decoded.format not in {"PNG", "JPEG"} or decoded.width * decoded.height > 20_000_000:
                    raise ValueError("Unsupported image")
                decoded.load()
                width, height = decoded.size
        except (OSError, ValueError, Image.DecompressionBombError):
            return {"ready": False, "reason": "INVALID_IMAGE"}
        target = self.root / ("latest" + suffix)
        temp = target.with_suffix(".tmp")
        temp.write_bytes(data)
        temp.replace(target)
        return {"ready": True, "path": str(target), "captured_at": artifact.get("timestampMs"), "width": width, "height": height, "mime": "image/png" if suffix == ".png" else "image/jpeg"}

    def observe(self, request):
        args = self._args(request)
        raw = self.tools.gateway.device_observe(args["device_id"], include_screenshot=True, mode="compact", session_id=SESSION, display_id=DISPLAY)
        failure = classify_failure(raw)
        if failure:
            self.observations.pop(args["device_id"], None)
            return {"ok": False, "error": failure.code}
        card = self.tools._remember_page_card(args["device_id"], raw, session_id=SESSION)
        observation_id = card.get("observationScope", {}).get("id")
        image = self._image(raw)
        if observation_id:
            self.observations[args["device_id"]] = (observation_id, time.monotonic(), image["ready"])
        return {"ok": bool(observation_id), "mode": "LIVE PHONE", "session_id": SESSION, "display_id": DISPLAY, "observation_id": observation_id, "device": args["device_id"], "package": card.get("package", card.get("appPackage")), "screenshot": {"available": image["ready"], "mime": image.get("mime"), "width": image.get("width"), "height": image.get("height"), "reference": image.get("path")}, "ui": card, "vision": image, "screenshot_path": image.get("path")}

    def execute(self, request):
        request = validate_request(request)
        with self.lock:
            self.last_seen = time.monotonic()
            op = request["operation"]
            if op == "devices":
                raw = self.tools.phone_devices({})
                return {"devices": [{k: d[k] for k in ("device_id", "deviceId", "name", "model", "state") if k in d} for d in raw.get("devices", []) if d.get("source") in {"USB", "LAN", "CLOUD"}]}
            if op == "status":
                return {"mode": "LIVE PHONE", "control": "Paused" if self.paused else "Enabled — observe the selected phone first", "session_id": SESSION, "display_id": DISPLAY}
            if op in {"observe", "screenshot"}:
                return self.observe(request)
            args = self._args(request)
            if op == "locate":
                result = self.tools.phone_locate({**args, "goal": request.get("goal", "")})
                card = result.get("pageCard", {})
                ident = card.get("observationScope", {}).get("id")
                self.observations[args["device_id"]] = (ident, time.monotonic(), False)
                return {**result, "observation_id": ident}
            if op == "inspect":
                return self.tools.phone_inspect_element({**args, "element_id": request.get("element", "")})
            return self.act(request)

    def act(self, request):
        if self.paused:
            return {"ok": False, "error": "LIVE_PHONE_PAUSED", "next": "Enable Live Phone in Cyclone One"}
        device = request["device"]
        current = self.observations.get(device)
        if not current or not current[0] or request.get("observation_id") != current[0] or time.monotonic() - current[1] > 30:
            return {"ok": False, "error": "STALE_OBSERVATION", "next": "Observe and locate again"}
        op = request["operation"]
        mapping = {"tap": "phone.click", "long-press": "phone.long_press", "type": "phone.type", "clear-text": "phone.type", "swipe": "phone.scroll", "scroll": "phone.scroll", "back": "phone.back", "home": "phone.home", "open-app": "phone.open_app"}
        params = {}
        if op in {"tap", "long-press", "type", "clear-text"}:
            if not request.get("element"):
                raise ValueError("Use a current element from locate")
            params["elementId"] = request["element"]
        if op in {"type", "clear-text"}:
            params["text"] = "" if op == "clear-text" else request.get("text", "")
        if op in {"scroll", "swipe"}:
            params["direction"] = request.get("direction", "forward")
        if op == "open-app":
            params["package"] = request.get("package", "")
        goal = request.get("goal")
        if not goal:
            raise ValueError("Describe the intended result with goal")
        self.observations.pop(device, None)  # Never retry an uncertain mutation.
        result = self.tools.phone_act({**self._args(request), "tool": mapping[op], "params": params, "goal": goal, "request_ai_control": True, "user_authorized": request.get("user_authorized", False)})
        after = self.observe(request)
        return {"ok": result.get("ok") is True and after.get("ok") is True, "action": result, "after": after, "note": "Swipe uses the existing semantic scroll route" if op == "swipe" else "Inspect verification; a transport receipt is not task success"}
