from pathlib import Path

from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.server import Gateway


class FakeADB:
    serial = "PIXEL8"


class FakeUIA:
    pass


class FakeRoot:
    pass


class KnowledgeBridge:
    def __init__(self):
        self.calls = []

    def request(self, op, args=None):
        args = args or {}
        self.calls.append((op, args))
        if op == "observe.semantic":
            return {
                "observationId": "obs-knowledge",
                "pageKey": "settings-home",
                "package": "com.android.settings",
                "activity": "Settings",
                "semanticControls": [],
                "rawAccessibility": {"nodes": []},
            }
        if op == "app_graph.get":
            return {
                "relevance": {"packageMatched": True, "pageMatched": True},
                "retrieval": {
                    "route": ["Settings", "Apps"],
                    "confidence": 0.94,
                },
            }
        if op == "brain.recall":
            return {
                "recall": {
                    "microSkills": [
                        {"tool": "phone.click", "selector": {"text": "Apps"}}
                    ],
                    "confidence": 0.91,
                }
            }
        raise AssertionError(f"Unexpected bridge op: {op}")


def test_observation_can_surface_canonical_android_knowledge(tmp_path: Path):
    bridge = KnowledgeBridge()
    gateway = Gateway(
        Settings("http", None, "adb", tmp_path, bridge_token="android"),
        adb=FakeADB(),
        bridge=bridge,
        uia=FakeUIA(),
        root=FakeRoot(),
    )

    gateway.observe(screenshot=False, uiautomator=False, diagnostics=False)
    knowledge = gateway.knowledge_context("Open Apps")

    assert knowledge["knowledgeProvenance"] == "ANDROID_CANONICAL"
    assert knowledge["knownRouteHints"] == [
        {"route": ["Settings", "Apps"], "confidence": 0.94}
    ]
    assert knowledge["brainRecall"]["microSkills"][0]["tool"] == "phone.click"

    graph_call = next(call for call in bridge.calls if call[0] == "app_graph.get")
    brain_call = next(call for call in bridge.calls if call[0] == "brain.recall")
    for _, args in (graph_call, brain_call):
        assert args["package"] == "com.android.settings"
        assert args["pageKey"] == "settings-home"
        assert args["goal"] == "Open Apps"
