"""Plan 48 run 4: Cyclone Ports reach the phone and the Mind.

- The phone never calls the PC: the gateway's PhoneBridge polls `ports.poll` and answers with `ports.answer`.
- A code is sealed to the owner-trusted device key and opened only on the phone; the Mind learns only its length.
- The Mind's port tools exist only while a PC is connected, and their briefs never carry a value.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class PortsRun4Guard(unittest.TestCase):
    def test_port_tools_only_with_a_link(self):
        box = read(MOBILE / "mind/PhoneMindToolbox.kt")
        self.assertIn('private val PORT_TOOLS = setOf("port_send", "port_wait")', box)
        self.assertIn("in PORT_TOOLS -> ports != null", box)
        missions = read(MOBILE / "mind/mission/MindMissions.kt")
        self.assertIn("PortOutbox.shared.connected()", missions)
        self.assertIn("ports = portsLink(run.id, run.mission.lab == null)", missions)

    def test_the_mind_never_sees_a_code(self):
        box = read(MOBILE / "mind/PhoneMindToolbox.kt")
        body = box[box.index("private fun portWait("):box.index("// ---- tracking and finishing")]
        # The answer type has no code field at all; only its length.
        link = read(MOBILE / "mind/MindPortsLink.kt")
        self.assertIn("val codeLength: Int = 0", link)
        self.assertNotRegex(link, r"val code\s*:")
        self.assertIn("vault_fill what=one_time_code", body)
        # Every brief is fixed text: no value, url, file name or ask in it.
        for brief in re.findall(r'MindToolResult\("[^\n]*?",\s*"(port_wait[^"]*)"', body):
            # The answer's state is a fixed word; nothing else from the answer may appear.
            self.assertNotRegex(brief.replace("${answer.state}", ""), r"\$\{?(ask|shown|url|answer|host)", brief)

    def test_private_screens_never_leave(self):
        box = read(MOBILE / "mind/PhoneMindToolbox.kt")
        body = box[box.index("private fun portSend("):box.index("private fun portWait(")]
        self.assertIn("privateScreen(page, bound)?.let", body)
        private = box[box.index("private fun privateScreen("):box.index("private fun portSend(")]
        self.assertIn("it.password", private)
        self.assertIn("Pilot.keepOff", private)

    def test_codes_are_sealed_only_to_a_trusted_key(self):
        phone = read(GATEWAY / "ports/phone.py")
        self.assertIn("seal.code_envelope(", phone)
        self.assertIn("self._trusted_key(device)", phone)
        self.assertNotRegex(phone, r"ports_answer\([^)]*\bcode=")
        api = read(GATEWAY / "desktop_runtime/api.py")
        self.assertIn("self.command.delivery.trusted_key(", api)
        self.assertIn("PhoneBridge(", api)

    def test_the_phone_never_calls_the_pc(self):
        for path in (MOBILE / "ports").glob("*.kt"):
            text = read(path)
            self.assertNotRegex(text, r"HttpURLConnection|OkHttp|java\.net\.URL\(", path.name)


if __name__ == "__main__":
    unittest.main()
