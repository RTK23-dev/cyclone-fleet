package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.ai.vision.live.AccessibilityScreenshotFrameSource
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.InMemoryLiveFrameBroker
import com.cyclone.mobile.ai.vision.live.LiveFrame
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V4FoundationTest {
    @Test
    fun defaultSessionIsStableDisplayZeroAndLegacyLookupResolvesIt() {
        val store = ExecutionSessionStore()
        val direct = store.defaultForeground()
        val missing = store.lookup(null)
        val blank = store.lookup("")

        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, direct.sessionId)
        assertEquals(0, direct.displayId)
        assertEquals(direct, missing)
        assertEquals(direct, blank)
        assertTrue(direct.executable)
        assertEquals(ExecutionBackendKind.FOREGROUND_ACCESSIBILITY, direct.backend)
        assertEquals(InputOwner.HUMAN, direct.inputOwner)
        assertEquals(ExecutionContext.DEFAULT, ExecutionContext.from(direct))
    }

    @Test
    fun virtualDisplayMetadataAloneDoesNotAuthorizeExecution() {
        try {
            ExecutionSession(
                sessionId = "future",
                displayId = 12,
                backend = ExecutionBackendKind.VIRTUAL_DISPLAY,
                executable = true,
                createdAtEpochMs = 1L,
            )
            fail("unowned backend metadata must not authorize execution")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun syntheticSessionsCoexistAreImmutableSnapshotsAndAreNotExecutable() {
        val store = ExecutionSessionStore()
        val one = store.registerSynthetic("hidden-a", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val two = store.registerSynthetic("hidden-b", 13, ExecutionBackendKind.SHIZUKU)

        assertFalse(one.executable)
        assertFalse(two.executable)
        assertEquals(one, store.lookup("hidden-a"))
        assertEquals(3, store.snapshot().size)
        assertTrue(SessionKernel.API_SESSION_CAPACITY >= 2)
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(1, WorkspaceTasks.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(12, store.requireSessionDisplay("hidden-a", 12).displayId)

        try {
            store.requireSessionDisplay("hidden-a", 13)
            fail("display mismatch must be rejected")
        } catch (_: SessionIdentityException) {
        }

        try {
            store.registerSynthetic("hidden-a", 14, ExecutionBackendKind.ROOT)
            fail("duplicate identity must be rejected")
        } catch (_: SessionIdentityException) {
        }

        val owned = ExecutionSessionStore()
        owned.registerOwned("workspace-a", 8, "com.a")
        owned.registerOwned("workspace-b", 9, "com.b")
        assertEquals(3, owned.snapshot().size)
        try {
            owned.registerOwned("workspace-c", 0, "com.c")
            fail("owned sessions must not silently map to display 0")
        } catch (_: SessionIdentityException) {
        }
    }

    @Test
    fun observationsAreSessionLocalAndForeignDisplayOrObservationIsRejected() {
        val sessions = ExecutionSessionStore()
        sessions.registerSynthetic("A", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        sessions.registerSynthetic("B", 13, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val observations = SessionObservationStore(sessions)

        val a1 = observations.publish("A", 12, "obs-a1", "payload-a1", 1L)
        val b1 = observations.publish("B", 13, "obs-b1", "payload-b1", 2L)
        val a2 = observations.publish("A", 12, "obs-a2", "payload-a2", 3L)

        assertEquals(1L, a1.generation)
        assertEquals(1L, b1.generation)
        assertEquals(2L, a2.generation)
        assertEquals("obs-a2", observations.current("A")?.observationId)
        assertEquals("obs-b1", observations.current("B")?.observationId)

        try {
            observations.publish("A", 13, "bad-display", null, 4L)
            fail("foreign display must be rejected")
        } catch (_: SessionIdentityException) {
        }

        try {
            observations.associate("A", 12, "obs-b1")
            fail("foreign observation must be rejected")
        } catch (_: SessionIdentityException) {
        }

        observations.associate("A", 12, "obs-a2")
    }

    @Test
    fun legacyObservationBridgeMapsMissingSessionToDefaultForeground() {
        val observations = SessionObservationStore()
        val published = observations.publish(
            sessionId = null,
            displayId = null,
            observationId = "legacy-obs",
            payload = "legacy",
            timestampEpochMs = 1L,
        )

        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, published.sessionId)
        assertEquals(0, published.displayId)
        assertEquals("legacy-obs", observations.currentOrLegacy(null)?.observationId)
    }

    @Test
    fun framesAreSessionIsolatedBoundedMonotonicAndLatestDoesNotCapture() {
        val sessions = ExecutionSessionStore()
        sessions.registerSynthetic("A", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        sessions.registerSynthetic("B", 13, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val broker = InMemoryLiveFrameBroker(capacityPerSession = 2, sessions = sessions)
        val source = AccessibilityScreenshotFrameSource("A", 12)

        val first = source.adaptOneShot(broker, 100, 200, "h1", 10L)
        assertEquals(1L, first.frameId)
        broker.publish(frame("A", 12, 2L, 11L, "h2"))
        broker.publish(frame("A", 12, 3L, 12L, "h3"))
        broker.publish(frame("B", 13, 1L, 13L, "b1", FrameSourceType.SCRCPY))

        assertEquals("h3", broker.latest("A")?.payloadHandle)
        assertEquals(listOf(2L, 3L), broker.framesSince("A", 0L).map { it.frameId })
        assertEquals("b1", broker.latest("B")?.payloadHandle)

        val beforeLatestReads = broker.framesSince("A", 0L)
        repeat(5) { broker.latest("A") }
        assertEquals(beforeLatestReads, broker.framesSince("A", 0L))

        broker.clear("A")
        assertNull(broker.latest("A"))
        assertNotNull(broker.latest("B"))

        val afterClear = broker.publish(frame("A", 12, 0L, 14L, "h4"))
        assertEquals(4L, afterClear.frameId)
    }

    @Test
    fun firstFrameDisplayMismatchAndNonMonotonicIdsAreRejected() {
        val sessions = ExecutionSessionStore()
        sessions.registerSynthetic("A", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val broker = InMemoryLiveFrameBroker(capacityPerSession = 2, sessions = sessions)

        try {
            broker.publish(frame("A", 13, 1L, 1L, "wrong-display"))
            fail("first frame display mismatch must be rejected")
        } catch (_: SessionIdentityException) {
        }

        broker.publish(frame("A", 12, 5L, 2L, "five"))
        try {
            broker.publish(frame("A", 12, 5L, 3L, "duplicate"))
            fail("duplicate frame id must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            broker.publish(frame("A", 12, 4L, 4L, "backwards"))
            fail("backwards frame id must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun invalidBrokerCapacityIsRejected() {
        try {
            InMemoryLiveFrameBroker(capacityPerSession = 0)
            fail("zero capacity must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun sessionStoreIsSafeUnderConcurrency() {
        val store = ExecutionSessionStore()
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(40)
        repeat(40) { index ->
            pool.execute {
                try {
                    store.registerSynthetic("s-$index", index + 1, ExecutionBackendKind.VIRTUAL_DISPLAY)
                    store.lookup(null)
                    store.snapshot()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(41, store.snapshot().size)
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, store.defaultForeground().sessionId)
    }

    @Test
    fun frameBrokerAssignsIndependentIdsSafelyUnderConcurrency() {
        val sessions = ExecutionSessionStore()
        sessions.registerSynthetic("A", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        sessions.registerSynthetic("B", 13, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val broker = InMemoryLiveFrameBroker(capacityPerSession = 64, sessions = sessions)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(40)

        repeat(40) { index ->
            pool.execute {
                try {
                    val sessionId = if (index % 2 == 0) "A" else "B"
                    val displayId = if (sessionId == "A") 12 else 13
                    broker.publish(frame(sessionId, displayId, 0L, index.toLong() + 1L, "f-$index"))
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals((1L..20L).toList(), broker.framesSince("A", 0L).map { it.frameId })
        assertEquals((1L..20L).toList(), broker.framesSince("B", 0L).map { it.frameId })
    }

    private fun frame(
        sessionId: String,
        displayId: Int,
        frameId: Long,
        capturedAtMonotonicMs: Long,
        handle: String,
        source: FrameSourceType = FrameSourceType.ACCESSIBILITY_SCREENSHOT,
    ) = LiveFrame(
        sessionId = sessionId,
        displayId = displayId,
        frameId = frameId,
        capturedAtMonotonicMs = capturedAtMonotonicMs,
        width = 100,
        height = 200,
        source = source,
        payloadHandle = handle,
    )
}
