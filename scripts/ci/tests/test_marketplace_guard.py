"""Marketplace guard: listings are data, runs go through the normal Ask entry, and nothing in the store can approve,
carry a secret or reach an engine around the Mind."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
MARKET = MOBILE / "market"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class MarketplaceGuards(unittest.TestCase):
    def test_recipes_run_only_through_the_ask_entry(self):
        source = read(MARKET / "Marketplace.kt")
        self.assertIn("OverlayChromeRuntime.submitRequest(goal)", source)
        for engine in ("MindMissions.start(", "WorkspaceTasks.queueRequest", "PhoneToolExecutor", "TaskCommands.send"):
            self.assertNotIn(engine, source, engine)

    def test_runs_refuse_instead_of_queueing_or_joining_a_mission(self):
        source = read(MARKET / "Marketplace.kt")
        body = source[source.index("internal fun run(store"):]
        body = body[: body.index("\n    }\n")]
        for refusal in ('"OVERLAY_UNAVAILABLE"', '"HUMAN_HAS_CONTROL"', '"ASK_BUSY"'):
            self.assertIn(refusal, body)
        self.assertLess(body.index('"ASK_BUSY"'), body.index("submit(goal)"))

    def test_the_store_never_approves_and_never_holds_keys(self):
        for path in [*MARKET.glob("*.kt"), MOBILE / "gateway/GatewayV5MarketAdapter.kt"]:
            text = read(path)
            self.assertNotRegex(text, r"TaskCommand\.(Approve|Confirm)", path.name)
            self.assertNotRegex(text, r"OpenRouterSecretStore\.read\(context\)\s*(?!\.isNotBlank)", path.name)

    def test_listings_carry_no_code(self):
        models = read(MARKET / "MarketModels.kt")
        listing = models[models.index("data class MarketListing("):models.index(") {", models.index("data class MarketListing("))]
        self.assertNotRegex(listing, r"(script|code|url|endpoint|command)\s*:", "a listing is data, never code or a remote endpoint")

    def test_pc_connections_run_only_fixed_connector_arguments(self):
        text = read(ROOT / "apps/device-gateway/cyclone_device_gateway/market/pc_connections.py")
        argv = re.findall(r"\[\*base, ([^\]]+)\]", text)
        self.assertEqual(sorted(argv), sorted(['"status", "--probe-gateway"', '"copy-config", "generic"', '"connect", host, "--verify"']))
        self.assertIn("if host not in HOSTS", text)
        self.assertNotIn("shell=True", text)


if __name__ == "__main__":
    unittest.main()
