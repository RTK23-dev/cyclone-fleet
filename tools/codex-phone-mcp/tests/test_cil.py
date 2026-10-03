from __future__ import annotations
import unittest
from cyclone_phone_mcp.cil import CilError, compile_cil


class CilTests(unittest.TestCase):
    def test_see_goal(self):
        result = compile_cil('SEE "Install Snapchat"')
        self.assertEqual(result["operation"], "see")
        self.assertEqual(result["arguments"]["goal"], "Install Snapchat")

    def test_element_tap(self):
        result = compile_cil("DO tap #e31", observation_id="obs-1", request_id="request-1")
        self.assertEqual(result["arguments"]["action"], {"kind": "tap", "target": {"element_id": "e31"}})

    def test_point_tap(self):
        result = compile_cil("DO tap @0.52,0.67", observation_id="obs-1", request_id="request-2")
        self.assertEqual(result["arguments"]["action"]["target"]["point"]["space"], "display_norm")

    def test_swipe(self):
        result = compile_cil("DO swipe @0.5,0.8 -> @0.5,0.2 350ms", observation_id="obs-1", request_id="request-3")
        action = result["arguments"]["action"]
        self.assertEqual(action["kind"], "swipe")
        self.assertEqual(action["duration_ms"], 350)

    def test_type_requires_element(self):
        with self.assertRaises(CilError):
            compile_cil('DO type @0.5,0.5 "hello"', observation_id="obs-1", request_id="request-4")

    def test_mutation_requires_ids(self):
        with self.assertRaises(CilError): compile_cil("DO back")


if __name__ == "__main__": unittest.main()
