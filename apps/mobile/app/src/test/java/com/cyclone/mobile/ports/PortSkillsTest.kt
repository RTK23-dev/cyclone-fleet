package com.cyclone.mobile.ports

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortSkillsTest {
    private val skill = JSONObject().put("name", "id-generator").put("title", "ID Generator")
        .put("description", "Company IDs").put("instructions", "Use an owner portrait; wait for complete.")
        .put("ports", JSONArray(listOf("file.out", "x.id-generator.generate", "value.in", "file.in")))
        .put("apps", JSONArray(listOf("com.example.company"))).put("routines", JSONArray())
    @Test fun consentScopesWithdrawalAndPrivatePayloadAreEnforced() {
        val now = AtomicLong(1_000_000)
        val box = PortOutbox(clock = now::get)
        box.poll(JSONObject().put("skills", JSONArray().put(skill)))
        val run = PortOutbox.Run("run_employee", "com.example.company", plugin = "id-generator")
        assertNull(box.emit(run, "file.out", JSONObject().put("assetId", "photo1"), byteArrayOf(1, 2), "image/jpeg"))
        assertEquals("id-generator", box.pending().single().getString("plugin"))
        assertNotNull(box.emit(run.copy(app = "com.example.other"), "file.out", JSONObject(), byteArrayOf(1)))
        assertNotNull(box.emit(run, "x.id-generator.generate", JSONObject().put("employee", JSONObject().put("password", "bad"))))
        box.poll(JSONObject().put("skills", JSONArray()))
        assertTrue(box.skills(run.app, null).isEmpty())
        assertNotNull(box.emit(run, "x.id-generator.generate", JSONObject().put("requestId", "employee1")))
        box.poll(JSONObject().put("skills", JSONArray().put(skill)))
        now.addAndGet(21_000)
        assertTrue(box.skills(run.app, null).isEmpty())
    }
    @Test fun malformedAndHugeAdvertisementsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PortSkills.parse(JSONArray().put(JSONObject(skill.toString()).put("endpoint", "http://evil"))) }
        assertThrows(IllegalArgumentException::class.java) { PortSkills.parse(JSONArray().put(JSONObject(skill.toString()).put("instructions", "x".repeat(8001)))) }
    }
}
