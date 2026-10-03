import unittest

from cyclone_phone_mcp.mcp_server import TOOLS


class V5ReadonlyContractTest(unittest.TestCase):
    def test_mapping_start_is_not_exposed(self):
        names = {tool["name"] for tool in TOOLS}
        self.assertNotIn("mapping.start", names)
        self.assertNotIn("phone_mapping_start", names)

    def test_ask_start_is_not_exposed(self):
        """Starting a phone run from the PC is a local Glass operator act, never an MCP tool."""
        names = {tool["name"] for tool in TOOLS}
        self.assertNotIn("ask.start", names)
        self.assertNotIn("phone_ask_start", names)


if __name__ == "__main__":
    unittest.main()
