package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class WorkspaceRequestQueueTest {
    @Test fun newTasksNeverOverwritePreviousRequests() {
        val queue = WorkspaceRequestQueue()
        val first = queue.add("first")
        val second = queue.add("second")
        assertEquals(listOf("first", "second"), queue.state.value.map { it.goal })
        queue.remove(first.id)
        assertEquals(second, queue.find(second.id))
        assertNull(queue.find(first.id))
    }
    @Test fun fullQueueDoesNotConsumeTheNewDraftAttachment() {
        val queue = WorkspaceRequestQueue(1)
        queue.add("first")
        var consumed = false
        try { queue.add("second") { consumed = true; null }; fail("must refuse full queue") } catch (_: IllegalStateException) { }
        assertFalse(consumed)
        assertEquals("first", queue.state.value.single().goal)
    }
    @Test fun blankRequestDoesNotEnterQueue() {
        val queue = WorkspaceRequestQueue()
        try { queue.add("  "); fail("must reject blank") } catch (_: IllegalArgumentException) { }
        assertTrue(queue.state.value.isEmpty())
    }
}
