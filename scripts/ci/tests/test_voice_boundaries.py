"""Drive guard (plans 24 and 32): voice is a surface, never an actor.

Voice hands work to Cyclone in two ways only: a new request through OverlayChromeRuntime.submitRequest and answers
through Task Kit. It never touches the phone's tools or an engine, never writes audio anywhere, speaks only redacted
lines, approves only a send (after its verbatim readback), and keeps the microphone service to listening alone.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
VOICE = BASE / "voice"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def voice_sources():
    files = sorted(VOICE.glob("*.kt"))
    assert files, "voice/ has no sources"
    for path in files:
        yield path.name, path.read_text(encoding="utf-8")


def code(text: str) -> str:
    """The source without comments, so a KDoc naming an engine is not a use of it."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class VoiceBoundaries(unittest.TestCase):
    def test_voice_never_reaches_tools_or_engines(self):
        forbidden = re.compile(
            r"PhoneToolExecutor|PhoneToolRegistry|CycloneAccessibilityService|MindMissions|PhoneMindToolbox|"
            r"WorkspaceTasks\b|WorkspaceRuntime|MissionPlanes|AdaptiveAgent|AgentLoop|OverlayUserAction|dispatchGesture|"
            r"performGlobalAction|performAction\(")
        for name, text in voice_sources():
            self.assertIsNone(forbidden.search(code(text)), f"voice/{name} reaches an engine: {forbidden.search(code(text))}")

    def test_the_only_ways_out_are_submit_request_and_task_kit(self):
        overlay_uses = set()
        for name, text in voice_sources():
            body = code(text)
            overlay_uses |= set(re.findall(r"OverlayChromeRuntime\.(\w+)", body))
            for imported in re.findall(r"^import (com\.cyclone\.mobile\.[\w.]+)", text, re.M):
                self.assertRegex(imported, r"^com\.cyclone\.mobile\.(voice|task\.TaskCommands?|owner\.|ai\.OpenRouterSecretStore|"
                                 r"ui\.overlay\.(OverlayChromeRuntime|glass\.VoicePress)|runtime\.background\.(TaskPhase|WorkspaceTaskUi)|"
                                 r"mind\.mission\.MindRedaction|R$)", f"voice/{name} imports {imported}")
        # Alpha.78: and voice's Stop for a quick action it started (it only cancels that action; missions stop via Task Kit).
        self.assertEqual(overlay_uses, {"submitRequest", "stopVoiceRequest"})
        runtime = code((BASE / "ui/overlay/OverlayChromeRuntime.kt").read_text(encoding="utf-8"))
        stop = runtime[runtime.index("fun stopVoiceRequest()"):]
        stop = stop[: stop.index("\n    }")]
        self.assertIn("CycloneModes.cancel()", stop)
        self.assertNotIn("MindMissions", stop)
        session = code((VOICE / "VoiceSession.kt").read_text(encoding="utf-8"))
        self.assertIn("OverlayChromeRuntime.submitRequest(effect.goal, driving = true)", session)
        self.assertIn("TaskCommands.send(app, taskId, command)", session)

    def test_no_audio_is_ever_written(self):
        writes = re.compile(r"FileOutputStream|RandomAccessFile|java\.io\.File\b|\bFile\(|writeBytes|writeText|openFileOutput|"
                            r"setOutputFile|Files\.write|cacheDir|filesDir|MediaRecorder\(|getExternal")
        for name, text in voice_sources():
            self.assertIsNone(writes.search(code(text)), f"voice/{name} writes: {writes.search(code(text))}")

    def test_only_a_send_is_approved_by_voice(self):
        turn = code((VOICE / "VoiceTurn.kt").read_text(encoding="utf-8"))
        approvals = [line.strip() for line in turn.splitlines() if "VoiceAnswer.Approve" in line and "data object" not in line]
        self.assertEqual(len(approvals), 1, approvals)
        self.assertTrue(approvals[0].startswith("VoiceMoment.Kind.SEND ->"), approvals[0])
        # A yes counts only after the readback was heard to the end (a tap can cut it short).
        self.assertIn("if (readbackHeard == open.id) sendAnswer(VoiceAnswer.Approve(open.id)", approvals[0])
        # Everything else that is consequential waits for the owner on screen.
        moments = code((VOICE / "VoiceMoments.kt").read_text(encoding="utf-8"))
        self.assertNotRegex(moments, r"Kind\.(APPROVAL|SECRET|HANDOVER)\s*->\s*(?!VoiceCopy\.NEEDS_SCREEN)")
        self.assertIn('NEEDS_SCREEN = "That needs you on screen when you\'re stopped."',
                      (VOICE / "VoiceCopy.kt").read_text(encoding="utf-8"))

    def test_secrets_are_never_spoken(self):
        source = code((VOICE / "VoiceMomentSource.kt").read_text(encoding="utf-8"))
        self.assertIn('if (kind == VoiceMoment.Kind.SECRET) ""', source)
        turn = code((VOICE / "VoiceTurn.kt").read_text(encoding="utf-8"))
        # Every line reaches the speaker through one place, and that place redacts it.
        self.assertEqual(turn.count("VoiceEffect.Say("), 1)
        say = turn[turn.index("private fun say("):]
        say = say[: say.index("\n    }")]
        self.assertIn("VoiceRedaction.spoken(line)", say)

    def test_the_microphone_opens_only_after_a_tap_and_its_service_only_while_listening(self):
        session = code((VOICE / "VoiceSession.kt").read_text(encoding="utf-8"))
        # Alpha.72: listening starts the service and waits until it holds the microphone (startAndWait), still once.
        self.assertEqual(session.count("VoiceService.start"), 1)
        listen = session[session.index("private fun listen("):]
        listen = listen[: listen.index("\n    private fun ")]
        self.assertIn("VoiceService.startAndWait(app)", listen)
        self.assertIn("VoiceService.stop(app)", listen)
        # Plan 42 (Live): the mic reopens by itself only right after a quick command the owner just gave, and closes on silence.
        turn = code((VOICE / "VoiceTurn.kt").read_text(encoding="utf-8"))
        self.assertEqual(turn.count("VoiceEffect.KeepListening(keepListeningMs)"), 1)
        quick = turn[turn.index("private fun quickDone("):]
        quick = quick[: quick.index("\n    private fun ")]
        self.assertIn("VoiceEffect.KeepListening(keepListeningMs)", quick)
        self.assertIn("if (!quickLive) return same()", quick)
        # No wake word: nothing in voice/ listens without the turn asking for it.
        for name, text in voice_sources():
            self.assertNotRegex(code(text).lower(), r"wake ?word|hotword|alwayson", name)
        manifest = ET.parse(ROOT / "apps/mobile/app/src/main/AndroidManifest.xml").getroot()
        mic = [s.get(f"{ANDROID}name") for s in manifest.iter("service") if "microphone" in (s.get(f"{ANDROID}foregroundServiceType") or "")]
        self.assertEqual(mic, [".voice.VoiceService"])

    def test_the_readback_is_the_text_that_is_sent(self):
        # Plan 32 D2: a spoken yes approves only the send that was read back, and the readback is the approval's own text.
        source = code((VOICE / "VoiceMomentSource.kt").read_text(encoding="utf-8"))
        self.assertIn('moment.gate == "send"', source)
        self.assertIn("message = send.text", source)
        self.assertIn("VoiceRedaction.spoken(line) == line", source)
        session = code((VOICE / "VoiceSession.kt").read_text(encoding="utf-8"))
        self.assertIn("open?.id != answer.momentId || open.kind != VoiceMoment.Kind.SEND", session)
        # The Mind sends exactly what it asked approval for.
        toolbox = code((BASE / "mind/PhoneMindToolbox.kt").read_text(encoding="utf-8"))
        reply = toolbox[toolbox.index("private fun replyNotification("):]
        reply = reply[: reply.index("\n    private fun ")]
        self.assertIn("MindSend(text, recipient, app)", reply)
        self.assertIn("device.replyNotification(key, text)", reply)

    def test_jev_only_watches(self):
        # alpha.51: JEV's answer is compared with the understanding model's and tallied; it never drives the turn.
        session = code((VOICE / "VoiceSession.kt").read_text(encoding="utf-8"))
        self.assertIn("JevWatch.record(event.understanding.kind, decision, ms, error)", session)
        self.assertNotRegex(session, r"dispatch\([^)]*(decision|jev)", "a JEV answer must never become a voice event")
        tally = code((VOICE / "JevShadow.kt").read_text(encoding="utf-8"))
        sample = tally[tally.index("data class Sample("):]
        sample = sample[: sample.index(")")]
        self.assertNotIn("String", sample, "the tally keeps kinds and timings, never what was said")

    def test_no_voice_cloning(self):
        for name, text in voice_sources():
            self.assertNotRegex(code(text).lower(), r"\b(voice_?)?clon(e|es|ed|ing)\b", name)

    def test_announcements_never_open_the_microphone_or_keep_messages(self):
        turn = code((VOICE / "VoiceTurn.kt").read_text(encoding="utf-8"))
        announce = turn[turn.index("private fun announceMessage("):]
        announce = announce[: announce.index("\n    }")]
        # Said and closed: only a tap opens the microphone to answer.
        self.assertIn("AfterSpeech.CLOSE", announce)
        self.assertNotIn("VoiceEffect.Listen", announce)
        self.assertNotIn("AfterSpeech.LISTEN", announce)
        # The understanding model hears who wrote, never what they wrote.
        source = code((VOICE / "VoiceAnnounce.kt").read_text(encoding="utf-8"))
        ask = source[source.index("fun openAsk("):]
        ask = ask[: ask.index("\n\n")]
        self.assertNotRegex(ask.split("=", 1)[1], r"\.(text|line)\b")
        # Codes and anything redacted are never announced.
        self.assertIn("if (looksLikeCode(text) || VoiceRedaction.spoken(text) != text) return null", source)
        bridge = code((VOICE / "DriveAnnouncements.kt").read_text(encoding="utf-8"))
        self.assertNotRegex(bridge, r"addLog|Log\.|SharedPreferences|putString", "messages are never logged or stored")

    def test_values_said_by_voice_are_never_remembered(self):
        session = code((VOICE / "VoiceSession.kt").read_text(encoding="utf-8"))
        self.assertIn("TaskCommand.Fill(answer.values, remember = false)", session)


if __name__ == "__main__":
    unittest.main()
