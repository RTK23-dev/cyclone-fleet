from cyclone_device_gateway.background.keep_on import HELPER_PACKAGE, PERMISSION, keep_background_on


class FakeAdb:
    def __init__(self, installed=True, granted=False, grant_works=True):
        self.installed, self.granted, self.grant_works = installed, granted, grant_works
        self.calls = []

    def shell(self, *args, timeout=15):
        self.calls.append(args)
        if args[:2] == ("pm", "path"):
            return f"package:/data/app/{HELPER_PACKAGE}/base.apk" if self.installed else ""
        if args[:2] == ("dumpsys", "package"):
            return f"  {PERMISSION}: granted={'true' if self.granted else 'false'}"
        if args[:2] == ("pm", "grant"):
            if self.grant_works:
                self.granted = True
            return ""
        raise AssertionError(f"unexpected command {args}")


def test_grants_exactly_one_permission_to_the_helper_and_proves_it():
    adb = FakeAdb()
    result = keep_background_on(adb)
    assert result.ok and result.state == "on"
    assert ("pm", "grant", HELPER_PACKAGE, PERMISSION) in adb.calls
    assert all(call[0] in {"pm", "dumpsys"} for call in adb.calls)


def test_idempotent_and_honest_failures():
    assert keep_background_on(FakeAdb(granted=True)).state == "already_on"
    assert keep_background_on(FakeAdb(installed=False)).state == "helper_missing"
    refused = keep_background_on(FakeAdb(grant_works=False))
    assert not refused.ok and refused.state == "not_granted"
