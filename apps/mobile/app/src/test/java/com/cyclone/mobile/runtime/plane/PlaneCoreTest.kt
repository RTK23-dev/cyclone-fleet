package com.cyclone.mobile.runtime.plane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Planes (plan 25): the switch transaction, the policy, the compatibility memory and the health ladder. */
class PlaneCoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val bg = TaskPlane.Background("workspace-00000000-0000-0000-0000-000000000001", 7)

    /** A scripted phone: records what happened and fails where told. */
    private class FakePort(
        var pauseOk: Boolean = true,
        var moveTo: ((TaskPlane, PlaneKind) -> TaskPlane)? = null,
        var verifyResult: String? = null,
        var restoreOk: Boolean = true,
        var location: TaskPlane? = null,
    ) : PlanePort {
        val calls = mutableListOf<String>()
        var owner: TaskPlane? = null
        override fun pause(from: TaskPlane): Boolean { calls += "pause:${from.kind.wire}"; owner = null; return pauseOk }
        override fun move(from: TaskPlane, to: PlaneKind): TaskPlane { calls += "move:${to.wire}"; return moveTo!!(from, to) }
        override fun verify(plane: TaskPlane): String? { calls += "verify:${plane.kind.wire}"; return verifyResult }
        override fun restore(original: TaskPlane, attempted: TaskPlane?): Boolean { calls += "restore:${original.kind.wire}"; return restoreOk }
        override fun grant(plane: TaskPlane) { calls += "grant:${plane.kind.wire}"; owner = plane }
        override fun locate(missionId: String): TaskPlane? = location
    }

    private class MemoryJournal : PlaneJournal {
        val records = mutableListOf<SwitchRecord>()
        override fun write(record: SwitchRecord) { records += record }
        override fun open(): SwitchRecord? = records.groupBy { it.id }.values.map { it.last() }.lastOrNull { !it.phase.terminal }
        override fun recent(limit: Int) = records.takeLast(limit)
    }

    @Test fun aSwitchPausesMovesVerifiesAndCommitsWithCycloneOwningTheNewPlaneOnly() {
        val port = FakePort(moveTo = { _, _ -> bg })
        val journal = MemoryJournal()
        var now = 0L
        val outcome = PlaneSwitcher(port, journal, clock = { now += 100; now }).switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "pill"))
        assertTrue(outcome is SwitchOutcome.Committed)
        assertEquals(bg, (outcome as SwitchOutcome.Committed).plane)
        assertEquals(listOf("pause:screen", "move:background", "verify:background", "grant:background"), port.calls)
        assertEquals(bg, port.owner)
        assertEquals(listOf(SwitchPhase.REQUESTED, SwitchPhase.PAUSED, SwitchPhase.MOVED, SwitchPhase.COMMITTED), journal.records.map { it.phase })
        assertNull(journal.open())
    }

    @Test fun anUnhealthyLandingRollsBackAndCycloneKeepsWorkingWhereItWas() {
        val port = FakePort(moveTo = { _, _ -> bg }, verifyResult = "This screen is protected.")
        val outcome = PlaneSwitcher(port, MemoryJournal()).switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "auto"))
        outcome as SwitchOutcome.RolledBack
        assertEquals("This screen is protected.", outcome.reason)
        assertEquals(TaskPlane.Screen, outcome.plane)
        assertEquals(listOf("pause:screen", "move:background", "verify:background", "restore:screen", "grant:screen"), port.calls)
        assertEquals(TaskPlane.Screen, port.owner)
    }

    @Test fun aFailedMoveOrAFailedRestoreEndsWhereTheTaskReallyIs() {
        val moveFails = FakePort(moveTo = { _, _ -> error("FOREGROUND_REQUIRED: Android did not preserve and surface the task") })
        val a = PlaneSwitcher(moveFails, MemoryJournal()).switch(SwitchRequest("m1", bg, PlaneKind.SCREEN, "pill")) as SwitchOutcome.RolledBack
        assertEquals("Android did not preserve and surface the task", a.reason)
        assertEquals(bg, a.plane)

        val lost = FakePort(moveTo = { _, _ -> bg }, verifyResult = "no frames", restoreOk = false, location = bg)
        val b = PlaneSwitcher(lost, MemoryJournal()).switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "auto")) as SwitchOutcome.RolledBack
        assertEquals("restore failed: Cyclone continues where the task actually is", bg, b.plane)
        assertEquals(bg, lost.owner)
    }

    @Test fun cycloneNeverSwitchesMidActionAndNeverTwiceAtOnce() {
        val stuck = FakePort(pauseOk = false, moveTo = { _, _ -> bg })
        val a = PlaneSwitcher(stuck, MemoryJournal()).switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "pill"))
        assertTrue(a is SwitchOutcome.RolledBack)
        assertFalse("nothing moved", stuck.calls.any { it.startsWith("move") })
        assertEquals(TaskPlane.Screen, stuck.owner)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = FakePort(moveTo = { _, _ -> entered.countDown(); release.await(5, TimeUnit.SECONDS); bg })
        val switcher = PlaneSwitcher(slow, MemoryJournal())
        val worker = Thread { switcher.switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "pill")) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = switcher.switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "pill again"))
        assertEquals(SwitchOutcome.Refused("A switch is already in progress."), second)
        release.countDown(); worker.join(5_000)
        assertEquals(SwitchOutcome.Refused("The task is already on screen."),
            switcher.switch(SwitchRequest("m1", TaskPlane.Screen, PlaneKind.SCREEN, "pill")))
    }

    @Test fun anInterruptedSwitchIsClosedAfterARestartAgainstWhereTheTaskIs() {
        val journal = FilePlaneJournal(folder.root.resolve("planes.jsonl"))
        journal.write(SwitchRecord("s1", "m1", TaskPlane.Screen, PlaneKind.BACKGROUND, "pill", SwitchPhase.PAUSED, startedAtMs = 1, updatedAtMs = 2))
        val port = FakePort(location = bg)
        val outcome = PlaneSwitcher(port, journal, clock = { 10 }).recover()
        assertEquals(SwitchOutcome.Committed(bg, 9), outcome)
        assertEquals(bg, port.owner)
        assertNull(journal.open())
        assertEquals(SwitchPhase.COMMITTED, journal.recent(1).single().phase)

        journal.write(SwitchRecord("s2", "m1", bg, PlaneKind.SCREEN, "pill", SwitchPhase.REQUESTED, startedAtMs = 20, updatedAtMs = 20))
        val stayed = PlaneSwitcher(FakePort(location = bg), journal, clock = { 30 }).recover()
        assertTrue(stayed is SwitchOutcome.RolledBack)
        assertNull(PlaneSwitcher(FakePort(), journal).recover())
    }

    @Test fun planesRoundTripAndABackgroundPlaneNeverUsesTheMainScreen() {
        assertEquals(bg, TaskPlane.fromJson(bg.toJson()))
        assertEquals(TaskPlane.Screen, TaskPlane.fromJson(TaskPlane.Screen.toJson()))
        assertTrue(runCatching { TaskPlane.Background("default-foreground", 3) }.isFailure)
        assertTrue(runCatching { TaskPlane.Background("workspace-x", 0) }.isFailure)
    }

    private fun facts(
        mode: PlaneMode = PlaneMode.AUTOMATIC, ready: Boolean = true, busy: Boolean = false, driver: Boolean = false,
        long: Boolean = false, compat: BackgroundCompat = BackgroundCompat.UNKNOWN, hands: Boolean = false,
    ) = PlaneFacts(mode, ready, if (ready) null else "Shizuku is not running.", busy, driver, long, compat, hands)

    @Test fun automaticPicksTheBackgroundOnlyWhenItHelpsAndIsKnownToWork() {
        assertEquals(PlaneKind.SCREEN, PlanePolicy.start(facts()).plane)
        assertEquals(PlaneKind.BACKGROUND, PlanePolicy.start(facts(busy = true)).plane)
        assertEquals(PlaneKind.BACKGROUND, PlanePolicy.start(facts(driver = true)).plane)
        assertEquals(PlaneKind.BACKGROUND, PlanePolicy.start(facts(long = true)).plane)
        assertEquals("Shizuku is not running.", PlanePolicy.start(facts(busy = true, ready = false)).reason)
        assertEquals(PlaneKind.SCREEN, PlanePolicy.start(facts(busy = true, compat = BackgroundCompat.SECURE)).plane)
        assertEquals(PlaneKind.SCREEN, PlanePolicy.start(facts(busy = true, hands = true)).plane)
        assertEquals(PlaneKind.SCREEN, PlanePolicy.start(facts(mode = PlaneMode.SCREEN, busy = true)).plane)
        assertEquals(PlaneKind.BACKGROUND, PlanePolicy.start(facts(mode = PlaneMode.BACKGROUND)).plane)
        assertEquals("the screen is the fallback even when background was chosen", PlaneKind.SCREEN,
            PlanePolicy.start(facts(mode = PlaneMode.BACKGROUND, ready = false)).plane)
    }

    @Test fun signalsBringTheTaskToTheScreenAndItGoesBackWhenTheOwnerIsDone() {
        assertEquals(PlaneKind.SCREEN, PlanePolicy.escalate(PlaneSignal.SECURE_CONTENT)!!.plane)
        assertEquals(PlaneKind.SCREEN, PlanePolicy.escalate(PlaneSignal.SEE_TO_APPROVE)!!.plane)
        assertNull("one blip is not a pattern", PlanePolicy.escalate(PlaneSignal.NO_EFFECT, 2))
        assertEquals(PlaneKind.SCREEN, PlanePolicy.escalate(PlaneSignal.NO_EFFECT, 3)!!.plane)
        assertNull(PlanePolicy.escalate(PlaneSignal.LEFT_DISPLAY, 1))
        assertTrue(PlanePolicy.returnToBackground(PlaneKind.BACKGROUND, PlaneMode.AUTOMATIC, false, 6_000, true))
        assertFalse(PlanePolicy.returnToBackground(PlaneKind.BACKGROUND, PlaneMode.AUTOMATIC, true, 6_000, true))
        assertFalse(PlanePolicy.returnToBackground(PlaneKind.SCREEN, PlaneMode.AUTOMATIC, false, 6_000, true))
        assertFalse(PlanePolicy.returnToBackground(PlaneKind.BACKGROUND, PlaneMode.AUTOMATIC, false, 1_000, true))
    }

    @Test fun compatibilityIsAOneTimeDiscoveryPerAppAndTheOwnerCanOverrideIt() {
        val compat = AppPlaneCompat(folder.root.resolve("compat.json")) { 5 }
        val bank = "nl.example.bank"
        assertEquals(BackgroundCompat.UNKNOWN, compat.status(bank, "1.0"))
        compat.record(bank, "1.0", PlaneOutcome.SECURE_CONTENT, "black frames")
        assertEquals(BackgroundCompat.SECURE, AppPlaneCompat(folder.root.resolve("compat.json")).status(bank, "1.0"))
        assertEquals("a new version starts from what the last one showed", BackgroundCompat.SECURE, compat.status(bank, "1.1"))
        compat.record(bank, "1.1", PlaneOutcome.WORKED)
        assertEquals(BackgroundCompat.OK, compat.status(bank, "1.1"))

        val flaky = "com.example.flaky"
        compat.record(flaky, "2", PlaneOutcome.SWITCH_FAILED)
        assertEquals(BackgroundCompat.UNKNOWN, compat.status(flaky, "2"))
        compat.record(flaky, "2", PlaneOutcome.SWITCH_FAILED)
        assertEquals(BackgroundCompat.UNSTABLE, compat.status(flaky, "2"))
        compat.allow(flaky, "2")
        assertEquals(BackgroundCompat.OK, compat.status(flaky, "2"))
        assertEquals(BackgroundCompat.REFUSES, compat.status("com.google.android.GoogleCamera", null))
    }

    @Test fun healthIsJudgedFromFactsAndTheLadderClimbsOnce() {
        val fine = HealthFacts(true, true, true, 300, true, false)
        assertEquals(HealthVerdict.Healthy, BackgroundHealth.judge(fine))
        assertEquals(HealthVerdict.Locked, BackgroundHealth.judge(fine.copy(deviceLocked = true)))
        assertEquals(HealthVerdict.Broken(HealthProblem.SHIZUKU_STOPPED), BackgroundHealth.judge(fine.copy(binderAlive = false)))
        assertEquals(HealthVerdict.Broken(HealthProblem.FRAMES_STALLED), BackgroundHealth.judge(fine.copy(lastFrameAgeMs = 2_500)))
        assertEquals(HealthVerdict.Healthy, BackgroundHealth.judge(fine.copy(lastFrameAgeMs = null), sinceStartMs = 500))
        assertEquals(HealthVerdict.Broken(HealthProblem.TASK_GONE), BackgroundHealth.judge(fine.copy(taskPresent = false)))

        val ladder = RecoveryLadder(PlaneMode.AUTOMATIC)
        assertEquals(RecoveryStep.REBIND, ladder.next(HealthProblem.SERVICE_LOST))
        assertEquals(RecoveryStep.RECREATE_AND_RETURN, ladder.next(HealthProblem.SERVICE_LOST))
        assertEquals(RecoveryStep.MOVE_TO_SCREEN, ladder.next(HealthProblem.FRAMES_STALLED))
        assertEquals(RecoveryStep.MOVE_TO_SCREEN, ladder.next(HealthProblem.TASK_GONE))
        assertEquals(RecoveryStep.PAUSE, RecoveryLadder(PlaneMode.BACKGROUND).next(HealthProblem.SHIZUKU_STOPPED))
        assertEquals(RecoveryStep.MOVE_TO_SCREEN, RecoveryLadder(PlaneMode.AUTOMATIC).next(HealthProblem.SHIZUKU_STOPPED))
    }
}
