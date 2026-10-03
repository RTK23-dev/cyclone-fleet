from __future__ import annotations

import time
import xml.etree.ElementTree as ET
from typing import Any

from ..adb.client import ADBClient


def normalize_xml(xml_text: str) -> dict[str, Any]:
    root = ET.fromstring(xml_text)
    nodes = []
    for idx, elem in enumerate(root.iter("node")):
        a = elem.attrib
        nodes.append({
            "id": f"uia:{idx}", "source": "UIAUTOMATOR", "text": a.get("text", ""),
            "content_desc": a.get("content-desc", ""), "resource_id": a.get("resource-id", ""),
            "class": a.get("class", ""), "package": a.get("package", ""),
            "clickable": a.get("clickable") == "true", "enabled": a.get("enabled") == "true",
            "focusable": a.get("focusable") == "true", "scrollable": a.get("scrollable") == "true",
            "bounds": a.get("bounds", ""),
        })
    return {"source": "UIAUTOMATOR", "captured_at": time.time(), "raw_xml": xml_text, "nodes": nodes}


class UiAutomatorProvider:
    def __init__(self, adb: ADBClient):
        self.adb = adb
        self._u2 = None

    def _observe_uiautomator2(self) -> dict[str, Any] | None:
        try:
            import uiautomator2 as u2
        except ImportError:
            return None
        if self._u2 is None:
            self._u2 = u2.connect(self.adb.serial)
        xml_text = self._u2.dump_hierarchy(compressed=False, pretty=False)
        result = normalize_xml(xml_text)
        result["transport"] = "UIAUTOMATOR2"
        return result

    def observe(self) -> dict[str, Any]:
        try:
            u2_result = self._observe_uiautomator2()
            if u2_result is not None:
                return u2_result
        except Exception:
            pass
        self.adb.shell("uiautomator", "dump", "/sdcard/cyclone_uia.xml", timeout=20)
        xml_text = self.adb.exec_out("cat", "/sdcard/cyclone_uia.xml", timeout=10).decode("utf-8", "replace")
        result = normalize_xml(xml_text)
        result["transport"] = "ADB_UIAUTOMATOR_DUMP"
        return result
