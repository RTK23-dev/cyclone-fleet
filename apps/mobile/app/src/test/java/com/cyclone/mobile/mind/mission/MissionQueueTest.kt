package com.cyclone.mobile.mind.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MissionQueueTest {
    @Test fun separateTasksQueueAndCorrectionsSteer() {
        assertTrue(MissionQueue.isNewTask("After this, set a timer for 10 minutes"))
        assertTrue(MissionQueue.isNewTask("Also reply to Louella in the background"))
        assertTrue(MissionQueue.isNewTask("Daarna een wekker zetten"))
        assertFalse(MissionQueue.isNewTask("No, the other chat"))
        assertFalse(MissionQueue.isNewTask("Use the blue one"))
        assertFalse(MissionQueue.isNewTask("Stop, do it later"))
    }

    @Test fun firstInFirstOutPersistedAndBounded() {
        val file = Files.createTempDirectory("queue").resolve("queue.json").toFile()
        val queue = MissionQueue(file)
        val a = queue.add("first", now = 1)!!
        queue.add("second", now = 2)
        assertEquals(listOf("first", "second"), MissionQueue(file).all().map { it.goal })
        assertEquals(a.id, queue.take()!!.id)
        repeat(10) { queue.add("more $it", now = 10L + it) }
        assertEquals(MissionQueue.CAPACITY, queue.all().size)
        assertNull(queue.add("one too many"))
        assertTrue(queue.remove(queue.all().first().id))
    }
}
