package com.cyclone.mobile.gateway

import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.policy.PublishGate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class CommandMediaTest {
    @get:Rule val folder = TemporaryFolder()
    private val published = mutableListOf<Triple<ByteArray, String, String>>()
    private val video = ByteArray(700_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(video).joinToString("") { "%02x".format(it) }

    @Before fun install() {
        CommandMedia.dir = { folder.root }
        CommandMedia.publish = { file: File, name: String, mime: String -> published += Triple(file.readBytes(), name, mime); "Movies/Cyclone" }
    }

    @After fun reset() {
        PublishGate.missionId = null
        PublishGate.liveMission = { null }
    }

    private fun chunk(offset: Int, size: Int, hash: String = sha, task: String = "tsk_video0001") = JSONObject()
        .put("taskId", task).put("name", "job-1.mp4").put("mime", "video/mp4").put("size", video.size).put("sha256", hash)
        .put("offset", offset).put("data", Base64.getEncoder().encodeToString(video.copyOfRange(offset, offset + size)))

    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code

    @Test fun aFileArrivesInChunksAndReachesTheGalleryOnlyWhenItsHashMatches() {
        val first = CommandMedia.receive(chunk(0, 300_000))
        assertFalse(first.getBoolean("done"))
        assertEquals(300_000L, first.getLong("received"))
        assertEquals("MEDIA_OFFSET", code { CommandMedia.receive(chunk(400_000, 100_000)) })
        CommandMedia.receive(chunk(300_000, 300_000))
        val last = CommandMedia.receive(chunk(600_000, 100_000))
        assertTrue(last.getBoolean("done"))
        assertEquals("Movies/Cyclone", last.getString("folder"))
        assertTrue(published.single().first.contentEquals(video))
        assertTrue(folder.root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun aCorruptFileIsRefusedAndNeverPublished() {
        val wrong = "0".repeat(64)
        CommandMedia.receive(chunk(0, 500_000, wrong))
        assertEquals("MEDIA_CORRUPT", code { CommandMedia.receive(chunk(500_000, 200_000, wrong)) })
        assertTrue(published.isEmpty())
    }

    @Test fun onlyMediaOfBoundedSizeIsTaken() {
        assertEquals("INVALID_REQUEST", code { CommandMedia.receive(chunk(0, 10).put("mime", "application/x-sh")) })
        assertEquals("INVALID_REQUEST", code { CommandMedia.receive(chunk(0, 10).put("name", "../evil.mp4")) })
        assertEquals("INVALID_REQUEST", code { CommandMedia.receive(chunk(0, 10).put("size", 600L * 1024 * 1024)) })
        assertEquals("INVALID_REQUEST", code { CommandMedia.receive(chunk(0, 10).put("shell", "id")) })
    }

    @Test fun shareIsASendOnlyWhileAPostingMissionRuns() {
        assertNull(GateClassifier.classify("phone.click", listOf("Share")))
        PublishGate.missionId = "m1post0001"
        PublishGate.liveMission = { "m1post0001" }
        assertEquals(GateClass.SEND, GateClassifier.classify("phone.click", listOf("Share")))
        assertEquals(GateClass.SEND, GateClassifier.classify("phone.click", listOf("Upload")))
        assertNull(GateClassifier.classify("phone.click", listOf("Next")))
        PublishGate.liveMission = { "m1other0001" }
        assertNull(GateClassifier.classify("phone.click", listOf("Share")))
    }
}
