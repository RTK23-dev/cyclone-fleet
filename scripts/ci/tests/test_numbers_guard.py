"""Alpha.102 Numbers: every number Cyclone can receive codes on, in one place.

- Numbers only: no text, sender or code is stored, sent by the phone or shown in Glass.
- The owner manages numbers: nothing buys, rents or releases a number, and agents only read which number an account uses.
- One number, one account.
- The phone op exists on the phone, the bridge and the PC contract together.
"""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway"
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
GLASS = ROOT / "apps/glass/src"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class NumbersGuard(unittest.TestCase):
    def test_the_registry_has_no_place_for_a_text_or_a_code(self):
        from importlib.util import module_from_spec, spec_from_file_location
        spec = spec_from_file_location("numbers_service", GATEWAY / "numbers/service.py")
        module = module_from_spec(spec)
        spec.loader.exec_module(module)
        db = sqlite3.connect(":memory:")
        db.executescript(module.SCHEMA)
        columns = {row[1] for row in db.execute("PRAGMA table_info(number)")}
        self.assertFalse(columns & {"body", "text", "message", "code", "sender", "otp"}, columns)
        self.assertIn("account_id TEXT UNIQUE", module.SCHEMA, "one number per account")

    def test_nothing_rents_buys_or_releases_a_number(self):
        api = read(GATEWAY / "numbers/api.py")
        service = read(GATEWAY / "numbers/service.py")
        for text in (api, service):
            self.assertNotRegex(text, r"(?i)def (rent|buy|purchase|release|order)_|/(rent|buy|purchase|order)\b")
        routes = re.findall(r'@router\.(get|post)\("([^"]+)"', api)
        self.assertEqual({path for _, path in routes},
                         {"/v1/numbers", "/v1/numbers/{number_id}", "/v1/numbers/{number_id}/delete", "/v1/accounts/{account_id}/number"})
        self.assertIn("dependencies=[Depends(auth)]", api)
        self.assertEqual(api.count("@router."), api.count("dependencies=[Depends(auth)]"))

    def test_the_phone_reports_numbers_only(self):
        report = read(MOBILE / "codes/NumbersReport.kt")
        self.assertNotRegex(report, r"\b(SmsLine|SmsInbox|CodeExtractor|CodeCatcher)\b|\.body\b|inbox\(")
        contract = read(GATEWAY / "desktop_runtime/v5_contract.py")
        self.assertIn('set(row) != {"number", "source", "slot"}', contract)
        self.assertIn('set(value) != {"enabled", "canRead", "numbers"}', contract)

    def test_the_op_is_in_every_list(self):
        self.assertIn('"numbers.list"', read(MOBILE / "gateway/GatewayProtocol.kt"))
        self.assertIn('"numbers.list" ->', read(MOBILE / "gateway/GatewayRuntime.kt"))
        self.assertIn('"numbers.list"', read(GATEWAY / "cyclone_bridge/protocol.py"))
        self.assertIn('"numbers.list"', read(GATEWAY / "desktop_runtime/v5_contract.py"))

    def test_glass_shows_numbers_never_codes(self):
        view = read(GLASS / "pages/numbersView.ts") + read(GLASS / "services/numbers.ts")
        self.assertNotRegex(view, r"(?i)\b(otp|codeValue|message body|sms body)\b")
        self.assertNotIn("innerHTML", view)
        router = read(GLASS / "core/router.ts")
        self.assertIn('"numbers"', router)


if __name__ == "__main__":
    unittest.main()
