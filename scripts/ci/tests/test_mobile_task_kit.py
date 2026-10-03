"""Task Kit guard: every task button goes through TaskCommands to the engine that owns the task.

alpha.27 shipped "I'm done does nothing": a surface talked to the wrong engine directly. These rules keep surfaces
from reaching engines around the bus again.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"


def sources():
    for path in BASE.rglob("*.kt"):
        yield path.relative_to(BASE).as_posix(), path.read_text(encoding="utf-8")


def callers(pattern: str) -> set[str]:
    regex = re.compile(pattern)
    return {name for name, text in sources() if regex.search(text)}


class TaskKitGuards(unittest.TestCase):
    def test_classic_foreground_commands_only_through_its_controller(self):
        self.assertEqual(callers(r"commandForegroundTask\("),
                         {"ui/overlay/OverlayChromeRuntime.kt", "task/TaskCommands.kt"})

    def test_workspace_service_intents_only_from_task_kit(self):
        self.assertEqual(callers(r"WorkspaceTasks\.commandIntent\("), {"task/TaskCommands.kt"})

    def test_mission_commands_only_from_task_kit(self):
        self.assertEqual(callers(r"MindMissions\.(ownerDone|ownerTakesPhone)\("), {"task/TaskCommands.kt"})

    def test_owner_answers_only_from_task_kit(self):
        self.assertEqual(callers(r"MindMissions\.answer\("), {"task/TaskCommands.kt"})

    def test_plane_moves_only_from_task_kit(self):
        # Planes (plan 25): the pill, notification actions and Glass move a task only through the bus.
        self.assertEqual(callers(r"MissionPlanes\.(request|allowCurrentApp|startNow)\("), {"task/TaskCommands.kt"})

    def test_the_plane_pill_only_speaks_task_kit(self):
        pill = (BASE / "ui/overlay/PlanePill.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.send(context, taskId", pill)
        self.assertNotRegex(pill, r"MindMissions|WorkspaceRuntime|MissionPlanes\.(request|allow)")

    def test_the_owner_card_only_speaks_task_kit(self):
        card = (BASE / "ui/v32/CycloneOwnerCard.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.send(context, moment.taskId", card)
        self.assertNotRegex(card, r"MindMissions|OverlayChromeRuntime|WorkspaceTasks|inbox\.")

    def test_drive_voice_only_speaks_task_kit(self):
        # Plan 32: voice is one more surface. Its answers and its Stop go through the bus like any button.
        session = (BASE / "voice/VoiceSession.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.send(app, taskId, command)", session)
        for path in (BASE / "voice").glob("*.kt"):
            self.assertNotRegex(path.read_text(encoding="utf-8"), r"MindMissions|WorkspaceRuntime|WorkspaceTasks\.|MissionPlanes\.", path.name)
        # The Driver buttons (Stop, Not now, the orb) only reach the voice session.
        overlay = (BASE / "ui/overlay/AiModeOverlay.kt").read_text(encoding="utf-8")
        self.assertIn("onStop = session::stop", overlay)
        self.assertNotRegex(overlay, r"TaskCommands\.send|MindMissions|dispatch\(OverlayUserAction")

    def test_every_surface_renders_the_one_owner_card(self):
        # Plan 27: the overlay renders moments on its glass card, which keeps the owner card's bodies.
        self.assertIn("OverlayOwnerCard(moment)", (BASE / "ui/overlay/OverlayChrome.kt").read_text(encoding="utf-8"))
        for surface in ("ui/overlay/OverlayGlassStack.kt", "ui/v32/CycloneAskTaskPanel.kt", "ui/v32/CycloneMissionPanel.kt"):
            self.assertIn("CycloneOwnerCard(", (BASE / surface).read_text(encoding="utf-8"), surface)

    def test_consequential_shade_buttons_need_an_unlocked_phone(self):
        notification = (BASE / "runtime/background/TaskProgressNotification.kt").read_text(encoding="utf-8")
        self.assertIn("OwnerMoments.needsUnlock(action)) setAuthenticationRequired(true)", notification)

    def test_legacy_command_entry_point_delegates_to_the_bus(self):
        state = (BASE / "runtime/background/WorkspaceTaskState.kt").read_text(encoding="utf-8")
        body = state[state.index("fun command(context: Context, task: WorkspaceTaskUi, action: String)"):]
        body = body[: body.index("\n    }") ]
        self.assertIn("TaskCommands.send", body)
        self.assertNotIn("startService", body)

    def test_notification_buttons_use_task_kit(self):
        notification = (BASE / "runtime/background/TaskProgressNotification.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.pendingIntent", notification)
        self.assertNotIn("PendingIntent.getService", notification)

    def test_foreground_commands_reaching_the_workspace_service_are_forwarded_to_the_bus(self):
        service = (BASE / "runtime/background/WorkspaceTaskService.kt").read_text(encoding="utf-8")
        self.assertIn("TaskCommands.send(applicationContext, shared.taskId", service)

    def test_receiver_is_declared_private(self):
        manifest = (ROOT / "apps/mobile/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertRegex(manifest, r'<receiver android:name="\.task\.TaskCommandReceiver" android:exported="false" />')


if __name__ == "__main__":
    unittest.main()
