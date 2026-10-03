"""The Lab tools on the MCP surface CycloneAgentMCP.exe actually serves (it runs cyclone_phone_mcp, not the generic
cyclone_agent_mcp server): an agent on the PC can list missions, start an experiment, read the report and stop it."""
import json
import tempfile
import unittest

from cyclone_phone_mcp.mcp_server import TOOLS, McpServer
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools

LAB = {"phone_lab_missions", "phone_lab_start", "phone_lab_report", "phone_lab_stop"}
EXP = "exp-20260930-160000-ab12"


class LabGateway:
    def __init__(self):
        self.calls = []

    def lab_missions(self):
        self.calls.append(("missions",))
        return {"missions": [{"id": "settings.wifi", "goal": "Open Wi-Fi settings"}]}

    def lab_start(self, device_id, name, missions, variants, repetitions):
        self.calls.append(("start", device_id, name, missions, variants, repetitions))
        return {"id": EXP, "state": "running"}

    def lab_experiments(self):
        self.calls.append(("list",))
        return {"experiments": [{"id": EXP}]}

    def lab_experiment(self, experiment_id):
        self.calls.append(("get", experiment_id))
        return {"id": experiment_id, "trials": [
            {"missionId": "settings.wifi", "verdict": "pass"},
            {"missionId": "settings.wifi", "verdict": "fail", "cause": "timeout", "phone": {"summary": "stuck", "metrics": {"toolCalls": 9}}},
        ]}

    def lab_stop(self, experiment_id):
        self.calls.append(("stop", experiment_id))
        return {"id": experiment_id, "state": "stopping"}


class LabToolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.gateway = LabGateway()
        self.tools = PhoneTools(self.gateway, SessionRecorder(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def call(self, name, args):
        content = self.tools.call(name, args)
        return json.loads(content[0]["text"])

    def test_the_served_surface_lists_the_lab_tools(self):
        names = {tool["name"] for tool in TOOLS}
        self.assertTrue(LAB <= names)
        listed = McpServer(self.tools).handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertTrue(LAB <= {tool["name"] for tool in listed["result"]["tools"]})

    def test_start_report_stop(self):
        self.assertEqual(self.call("phone_lab_missions", {})["missions"][0]["id"], "settings.wifi")
        started = self.call("phone_lab_start", {"device_id": "dev_a", "name": " smoke ", "missions": ["settings.wifi"], "repetitions": 2})
        self.assertEqual(started["id"], EXP)
        self.assertEqual(self.gateway.calls[-1], ("start", "dev_a", "smoke", ["settings.wifi"], [{"name": "A"}], 2))
        report = self.call("phone_lab_report", {"experiment_id": EXP})
        self.assertNotIn("trials", report)
        self.assertEqual([f["cause"] for f in report["failures"]], ["timeout"])
        self.assertEqual(self.call("phone_lab_report", {})["experiments"][0]["id"], EXP)
        self.assertEqual(self.call("phone_lab_stop", {"experiment_id": EXP})["state"], "stopping")

    def test_bad_arguments_never_reach_the_gateway(self):
        for args in (
            {"device_id": "dev_a", "name": "x", "missions": ["BAD ID"]},
            {"device_id": "dev_a", "name": "x", "missions": ["settings.wifi"], "variants": [{"name": "A", "shell": "rm"}]},
            {"device_id": "dev_a", "name": "x", "missions": ["settings.wifi"], "repetitions": 99},
            {"device_id": "dev_a", "name": "", "missions": ["settings.wifi"]},
            {"device_id": "dev_a", "name": "x", "missions": ["settings.wifi"], "extra": 1},
        ):
            self.assertIn("error", self.call("phone_lab_start", args))
        self.assertIn("error", self.call("phone_lab_stop", {"experiment_id": "../../v1/pc"}))
        self.assertEqual(self.gateway.calls, [])


if __name__ == "__main__":
    unittest.main()
