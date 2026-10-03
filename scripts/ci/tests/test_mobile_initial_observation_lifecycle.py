from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"

class InitialObservationLifecycleGuards(unittest.TestCase):
    def test_report_exists_before_initialization_and_capture(self):
        source = (BASE / "ai/OpenRouterAdaptiveAgent.kt").read_text()
        start = source.index("val traceId = AgentTraceRuntime.start(context, goal, config.model.id)")
        self.assertLess(start, source.index("CycloneBrainRuntime.initialize(context)"))
        self.assertLess(start, source.index("InitialObservationRecovery.capture("))
        self.assertIn("RequestOutcomeBoundary.run", source)

    def test_store_cannot_reopen_or_overwrite_a_terminal_report(self):
        source = (BASE / "ai/AgentTraceStore.kt").read_text()
        self.assertIn('"id=? AND ended_at IS NULL"', source)
        self.assertIn("if (changed == 0) return false", source)
        self.assertIn("if (!store.finishSession(sessionId, status, result, decisions)) return", source)

    def test_overlay_does_not_retry_the_whole_agent_or_lose_attachments(self):
        source = (BASE / "ui/overlay/OverlayChromeRuntime.kt").read_text()
        self.assertEqual(source.count("PendingTaskAttachment.take()"), 1)
        self.assertEqual(source.count("agent.execute(request, config, progress)"), 1)
        self.assertIn("if (expectedTaskId != foregroundTaskId) return", source)

    def test_primary_is_send_and_pause_never_a_second_voice_button(self):
        source = (BASE / "ui/overlay/OverlayAppleLiquidComposer.kt").read_text()
        self.assertNotIn("OverlayVoiceWaveform", source)
        self.assertNotIn("Start voice request", source)
        self.assertIn("SignatureGlyph.SEND", source)
        self.assertIn("SignatureGlyph.PAUSE", source)
        self.assertEqual(source.count("SignatureGlyph.MIC"), 1)
