import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from cyclone_phone_mcp.live_phone import LivePhone, validate_request, COMMANDS
from cyclone_phone_mcp.live_phone_ipc import LiveGateway, safe_result


class LivePhoneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.tools = Mock()
        self.engine = LivePhone(self.tools, Path(self.temp.name) / 'live-phone')
        self.args = {'operation': 'tap', 'device': 'pixel', 'element': 'e1', 'goal': 'Open settings', 'observation_id': 'o1'}
        self.engine.observations['pixel'] = ('o1', time.monotonic(), True)

    def test_only_typed_surface_and_foreground(self):
        for op in ('shell', 'exec', 'adb', 'powershell', 'workspace', 'session.start'):
            with self.assertRaises(ValueError): validate_request({'operation': op})
        for key in ('token', 'url', 'session_id', 'display_id', 'workspaceId', 'command'):
            with self.assertRaises(ValueError): validate_request({'operation': 'tap', key: 'x'})
        self.assertEqual(15, len(COMMANDS))

    def test_paused_does_not_mutate(self):
        self.assertEqual('LIVE_PHONE_PAUSED', self.engine.execute(self.args)['error'])
        self.tools.phone_act.assert_not_called()

    def test_stale_does_not_mutate(self):
        self.engine.paused = False
        self.args['observation_id'] = 'old'
        self.assertEqual('STALE_OBSERVATION', self.engine.execute(self.args)['error'])
        self.tools.phone_act.assert_not_called()

    def test_expired_observation_does_not_mutate(self):
        self.engine.paused = False
        self.engine.observations['pixel'] = ('o1', time.monotonic() - 31, True)
        self.assertEqual('STALE_OBSERVATION', self.engine.execute(self.args)['error'])

    def test_action_verifies_then_observes_without_second_injector(self):
        self.engine.paused = False
        self.tools.phone_act.return_value = {'verified': False, 'error': 'GATE'}
        self.engine.observe = Mock(return_value={'observation_id': 'o2'})
        result = self.engine.execute(self.args)
        call = self.tools.phone_act.call_args.args[0]
        self.assertEqual('default-foreground', call['session_id'])
        self.assertEqual(0, call['display_id'])
        self.assertEqual('phone.click', call['tool'])
        self.assertEqual({'elementId': 'e1'}, call['params'])
        self.assertFalse(result['action']['verified'])
        self.assertFalse(result['ok'])
        self.engine.observe.assert_called_once()
        self.assertNotIn('pixel', self.engine.observations)

    def test_transport_failure_is_not_replayed(self):
        self.engine.paused = False
        self.tools.phone_act.side_effect = OSError('private runtime')
        with self.assertRaises(OSError): self.engine.execute(self.args)
        self.assertEqual('STALE_OBSERVATION', self.engine.execute(self.args)['error'])
        self.assertEqual(1, self.tools.phone_act.call_count)

    def test_open_app_and_clear_use_canonical_contract(self):
        self.engine.paused = False
        self.engine.observe = Mock(return_value={})
        self.engine.execute({**self.args, 'operation': 'open-app', 'package': 'com.android.chrome'})
        self.assertEqual({'package': 'com.android.chrome'}, self.tools.phone_act.call_args.args[0]['params'])
        self.engine.observations['pixel'] = ('o1', time.monotonic(), True)
        self.engine.execute({**self.args, 'operation': 'clear-text', 'user_authorized': True})
        call = self.tools.phone_act.call_args.args[0]
        self.assertTrue(call['user_authorized'])
        self.assertEqual('', call['params']['text'])

    def test_screenshot_is_bounded_and_requires_trusted_artifact(self):
        folder = Path(self.temp.name) / 'runtime' / 'fleet-screenshots'
        folder.mkdir(parents=True)
        image = folder / 'pixel.png'
        from PIL import Image
        Image.new('RGB', (16, 24), 'blue').save(image)
        raw = {'screenshot': {'available': True, 'artifact': {'reference': str(image)}}}
        result = self.engine._image(raw)
        self.assertTrue(result['ready'])
        self.assertEqual(image.read_bytes(), Path(result['path']).read_bytes())
        raw['screenshot']['artifact']['reference'] = '/etc/passwd'
        self.assertFalse(self.engine._image(raw)['ready'])
        self.assertFalse(self.engine._image({})['ready'])

    def test_truncated_image_is_not_vision_ready(self):
        folder = Path(self.temp.name) / 'runtime' / 'fleet-screenshots'
        folder.mkdir(parents=True)
        image = folder / 'broken.png'
        image.write_bytes(b'\x89PNG\r\n\x1a\n' + b'broken')
        (self.engine.root / 'latest.jpg').write_bytes(b'old image')
        result = self.engine._image({'screenshot': {'available': True, 'artifact': {'reference': str(image)}}})
        self.assertFalse(result['ready'])
        self.assertFalse((self.engine.root / 'latest.jpg').exists())

    def test_observation_has_ui_and_current_image_same_response(self):
        self.tools.gateway.device_observe.return_value = {'observation': {}}
        self.tools._remember_page_card.return_value = {'observationScope': {'id': 'new'}}
        result = self.engine.observe(self.args)
        self.assertEqual('new', result['observation_id'])
        self.assertIn('ui', result)
        self.assertFalse(result['vision']['ready'])
        self.tools.gateway.device_observe.assert_called_once_with('pixel', include_screenshot=True, mode='compact', session_id='default-foreground', display_id=0)

    def test_secrets_and_dynamic_connection_are_not_exposed(self):
        result = safe_result({'token': 'secret', 'data': {'bearer': 'secret', 'message': 'http://127.0.0.1:23456/private'}, 'ui': 'Settings'})
        self.assertNotIn('secret', json.dumps(result))
        self.assertNotIn('23456', json.dumps(result))
        self.assertEqual('Settings', result['ui'])

    def test_typed_password_otp_and_key_never_return_in_response(self):
        secrets = ('Password-123!', '654321', 'api-key-example')
        response = {'after': {'ui': {'text': 'Password-123! api-key-example', 'otp': 654321}}, 'status': {'control': True}}
        encoded = json.dumps(safe_result(response, secrets))
        for secret in secrets:
            self.assertNotIn(secret, encoded)
        self.assertTrue(safe_result(response, secrets)['status']['control'])

    def test_live_marker_does_not_change_native_client(self):
        with patch('cyclone_phone_mcp.live_phone_ipc.GatewayClient._request', return_value={}) as call:
            gateway = LiveGateway(base_url='http://127.0.0.1:1234', token='test')
            gateway._request('POST', '/v1/devices/pixel/agent/observe', {'sessionId': 'default-foreground', 'displayId': 0})
            self.assertTrue(call.call_args.args[2]['livePhone'])

if __name__ == '__main__': unittest.main()

@unittest.skipUnless(__import__('os').name == 'nt', 'Windows named-pipe/DPAPI integration')
class WindowsLivePhoneIpcTests(unittest.TestCase):
    def test_broker_restart_preserves_private_address_but_revokes_observations(self):
        import multiprocessing
        import os
        from cyclone_phone_mcp.live_phone_ipc import serve, request_one, root
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {'LOCALAPPDATA': folder, 'CYCLONE_DEVICE_GATEWAY_TOKEN': 'test-only', 'CYCLONE_DEVICE_GATEWAY_URL': 'http://127.0.0.1:1'}):
            root().mkdir(parents=True)
            (root() / 'control.json').write_text('{"enabled":true,"stopped":false}')
            def launch():
                process = multiprocessing.get_context('spawn').Process(target=serve, daemon=True)
                process.start()
                for _ in range(100):
                    try:
                        result = request_one({'operation': 'status'})
                        self.assertEqual('LIVE PHONE', result['mode'])
                        return process
                    except (OSError, FileNotFoundError):
                        time.sleep(.1)
                process.terminate()
                process.join(5)
                self.fail('Private IPC did not start')
            first = launch()
            first.terminate()
            first.join(5)
            second = launch()
            try:
                result = request_one({'operation': 'back', 'device': 'pixel', 'goal': 'Back', 'observation_id': 'before-restart'})
                self.assertEqual('STALE_OBSERVATION', result['error'])
                (root() / 'control.json').write_text('{"enabled":false,"stopped":true,"generation":"stop"}')
                result = request_one({'operation': 'home', 'device': 'pixel', 'goal': 'Home', 'observation_id': 'old'})
                self.assertEqual('LIVE_PHONE_STOPPED', result['error'])
                (root() / 'control.json').write_text('{"enabled":true,"stopped":false,"generation":"resume"}')
                result = request_one({'operation': 'home', 'device': 'pixel', 'goal': 'Home', 'observation_id': 'old'})
                self.assertEqual('STALE_OBSERVATION', result['error'])
            finally:
                second.terminate()
                second.join(5)
