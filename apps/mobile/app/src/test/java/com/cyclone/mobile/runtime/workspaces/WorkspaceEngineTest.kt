package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceEngineTest {
    private val a = Workspace("a", "Profile A", "example.alpha")
    private val b = Workspace("b", "Profile B", "example.beta", 10)
    private fun target(w: Workspace) = WorkspaceTarget(w.appPackage, w.androidUserId)
    private fun engine() = WorkspaceEngine().apply { register(a); register(b) }
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun registryPersistsAndRestoresWithoutLiveLeases() {
        var saved = emptyList<Workspace>()
        val original = WorkspaceEngine { saved = it }
        original.register(a); original.register(b)
        original.switch("a", { false }, {}, ::target)
        val restored = WorkspaceEngine().apply { restore(saved) }
        assertEquals(listOf("a", "b"), restored.snapshot().map { it.id })
        assertTrue(restored.snapshot().all { it.state == WorkspaceState.paused })
        assertNull(restored.holder()); assertTrue(restored.queue().isEmpty())
    }
    @Test fun failedPersistenceDoesNotRegister() {
        val e = WorkspaceEngine { error("disk full") }
        fails { e.register(a) }; assertTrue(e.snapshot().isEmpty())
    }
    @Test fun rejectsNonzeroDisplayAndMalformedPackages() {
        fails { a.copy(displayId = 2) }; fails { a.copy(appPackage = "x; reboot") }
    }
    @Test fun switchReleasesBeforeLaunchAndVerifiesBeforeAcquisition() {
        val e = engine(); val old = e.switch("a", { false }, {}, ::target)
        e.switch("b", { false }, { assertNull(e.holder()) }, { assertNull(e.holder()); target(it) })
        assertEquals("b", e.holder()?.workspaceId)
        fails { e.requireMutation("a", old.generation, { false }, ::target) }
    }
    @Test fun mismatchInPackageUserOrDisplayFailsClosed() {
        listOf(WorkspaceTarget("wrong.package", 0), WorkspaceTarget(a.appPackage, 10), WorkspaceTarget(a.appPackage, 0, 4)).forEach { seen ->
            val e = engine()
            fails { e.switch("a", { false }, {}, { seen }) }
            assertNull(e.holder()); assertEquals(WorkspaceState.paused, e.snapshot().first().state)
            fails { e.requireMutation(null, -1, { false }, ::target) }
        }
    }
    @Test fun launchFailureAndUnknownIdRevokeOldLease() {
        val e = engine(); e.switch("a", { false }, {}, ::target)
        fails { e.switch("missing", { false }, {}, ::target) }; assertNull(e.holder())
        fails { e.switch("b", { false }, { error("no profile") }, ::target) }; assertNull(e.holder())
    }
    @Test fun driftAndStaleGenerationCannotMutate() {
        val e = engine(); val first = e.switch("a", { false }, {}, ::target)
        e.pause(); val second = e.switch("a", { false }, {}, ::target)
        fails { e.requireMutation("a", first.generation, { false }, ::target) }
        fails { e.requireMutation("a", second.generation, { false }, { target(b) }) }
        assertNull(e.holder())
    }
    @Test fun leftoverSelectionCannotBlockAForegroundTask() {
        val e = engine()
        e.arm("a"); e.arm("b")
        e.switch("a", { false }, {}, ::target)
        assertEquals("a", e.selectedId())
        e.releaseForForegroundTask()
        assertNull(e.selectedId())
        assertNull(e.holder())
        assertEquals(listOf("a", "b"), e.queue())
        e.requireMutation(null, -1, { false }, ::target)
    }
    @Test fun gateBeforeAndDuringSwitchAndQueueCannotBeBypassed() {
        val e = engine(); var launches = 0
        fails { e.switch("a", { true }, { launches++ }, ::target) }
        assertEquals(0, launches)
        var gate = false
        fails { e.switch("a", { gate }, { gate = true }, ::target) }
        assertNull(e.holder())
        e.arm("a"); e.arm("b")
        fails { e.next({ true }, {}, ::target) { fail("Must not mutate") } }
        assertEquals(listOf("a", "b"), e.queue())
    }
    @Test fun queueRotatesAndSerializesEntireSlices() {
        val e = engine(); e.arm("a"); e.arm("b"); e.arm("a")
        val running = AtomicInteger(); val maxRunning = AtomicInteger(); val done = CountDownLatch(2)
        val order = mutableListOf<String>()
        repeat(2) {
            Thread {
                try { e.next({ false }, {}, ::target) { lease ->
                    maxRunning.updateAndGet { maxOf(it, running.incrementAndGet()) }
                    order += lease.workspaceId
                    Thread.sleep(30)
                    running.decrementAndGet()
                } } finally { done.countDown() }
            }.start()
        }
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, maxRunning.get())
        assertEquals(listOf("a", "b"), order); assertNull(e.holder())
        assertEquals(listOf("a", "b"), e.queue())
    }
    @Test fun gateInSliceStopsWithoutRotatingJob() {
        val e = engine(); e.arm("a"); e.arm("b"); var gate = false
        fails { e.next({ gate }, {}, ::target) { gate = true } }
        assertNull(e.holder()); assertEquals(listOf("b"), e.queue())
        fails { e.next({ gate }, {}, ::target) { fail("GATE bypass") } }
    }
    @Test fun rootMappingNeverInfersRootFromRefusalOrTimeout() {
        assertEquals(RootStatus.ROOTED, RootStatusMapping.from(0, "uid=0(root) gid=0(root)"))
        assertEquals(RootStatus.UNKNOWN, RootStatusMapping.from(0, "uid=1000(shell)"))
        assertEquals(RootStatus.UNKNOWN, RootStatusMapping.from(null, ""))
        assertEquals(RootStatus.UNKNOWN, RootStatusMapping.from(1, "denied"))
        assertEquals(RootStatus.NOT_ROOTED, RootStatusMapping.from(-127, "", true))
    }
    @Test fun rootTargetParserRejectsUnknownAndWrongDisplays() {
        assertNull(RootTargetParser.parse("mResumedActivity: ActivityRecord{x u10 example.alpha/.Main}"))
        assertNull(RootTargetParser.parse("Display #2\nmResumedActivity: ActivityRecord{x u10 example.alpha/.Main}"))
        assertEquals(WorkspaceTarget("example.alpha", 10), RootTargetParser.parse("Display #0\nmResumedActivity: ActivityRecord{x u10 example.alpha/.Main}"))
        assertNull(RootTargetParser.parse("Display #0\nmResumedActivity: ActivityRecord{x u10 example.alpha/.Main}\nmResumedActivity: ActivityRecord{x u0 example.beta/.Main}"))
    }
    @Test fun wizardHasFiveReachableStepsAndTerminalDone() {
        var step = RootWizardStep.ROOT
        val visited = mutableListOf(step)
        repeat(4) { step = step.next(); visited += step }
        assertEquals(RootWizardStep.entries, visited)
        assertEquals("Done", step.action); assertEquals(step, step.next())
    }
}
