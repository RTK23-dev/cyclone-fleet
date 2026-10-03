import unittest

from cyclone_phone_mcp.acceptance import AcceptanceHarness, MockGateway, _action_failed


class FailedOpenGateway(MockGateway):
    def action(self, tool, params, goal, **kwargs):
        if tool == "phone.open_app":
            return {
                "success": False,
                "error_class": "APP_NOT_FOUND",
                "verification": "android_action_failed",
            }
        return super().action(tool, params, goal)


class AcceptanceTests(unittest.TestCase):
    def test_mock_acceptance_runs_twice_and_learns_signal(self):
        report = AcceptanceHarness(MockGateway()).run(execute=True)
        self.assertTrue(report.passed)
        self.assertEqual(len(report.runs), 2)
        self.assertGreaterEqual(report.runs[1]["knownRouteHints"], report.runs[0]["knownRouteHints"])
        self.assertGreaterEqual(report.runs[1]["brainHints"], report.runs[0]["brainHints"])

    def test_acceptance_marks_returned_action_failure(self):
        report = AcceptanceHarness(FailedOpenGateway()).run(execute=True)
        self.assertFalse(report.passed)
        self.assertFalse(report.runs[0]["passed"])
        self.assertEqual(report.runs[0]["failedActions"], 1)
        self.assertIn("Opening Android Settings failed", report.runs[0]["failureReason"])

    def test_acceptance_uses_typed_execution_and_verification_classifier(self):
        base = {
            "protocol_version": "cyclone.gateway.capability.v1",
            "capability_id": "phone.click",
            "transport": {"ok": True},
            "error": None,
        }
        self.assertTrue(_action_failed({**base, "ok": True, "execution": {"ok": False}, "verification": {"ok": True, "status": "verified"}}))
        self.assertTrue(_action_failed({**base, "ok": True, "execution": {"ok": True}, "verification": {"ok": False, "status": "failed"}}))
        self.assertFalse(_action_failed({**base, "ok": True, "execution": {"ok": True}, "verification": {"ok": True, "status": "verified"}}))


if __name__ == "__main__": unittest.main()
