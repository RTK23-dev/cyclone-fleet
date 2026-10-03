from types import SimpleNamespace
import pytest
from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError

@pytest.mark.parametrize('source,identity', [
    ('VIRTUAL', {'sessionId': 'default-foreground', 'displayId': 0}),
    ('USB', {'sessionId': 'background', 'displayId': 1}),
    ('USB', None),
    ('USB', {'sessionId': 'default-foreground', 'displayId': 1}),
])
def test_live_phone_cannot_be_virtual_or_background(source, identity):
    with pytest.raises(DesktopRuntimeError):
        DesktopAgentService._check_live_phone(SimpleNamespace(source=source), {'livePhone': True}, identity)


def test_physical_foreground_and_existing_native_scopes_are_separate():
    DesktopAgentService._check_live_phone(SimpleNamespace(source='USB'), {'livePhone': True}, {'sessionId': 'default-foreground', 'displayId': 0})
    DesktopAgentService._check_live_phone(SimpleNamespace(source='VIRTUAL'), {}, {'sessionId': 'named-vd', 'displayId': 1})
