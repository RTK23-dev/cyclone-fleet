package com.cyclone.mobile.gateway

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.secrets.SecretPersona
import com.cyclone.mobile.secrets.SecretSlotKey
import com.cyclone.mobile.secrets.SecretSlotMetadata
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5KnowledgeSummaryAdapterTest {
    @After
    fun reset() = GatewayV5KnowledgeSummaryAdapter.resetForTests()

    @Test
    fun summaryNamesSlotsAndSkillsButNeverValues() {
        GatewayV5KnowledgeSummaryAdapter.slots = {
            listOf(
                SecretSlotMetadata(SecretSlotKey.of("package:com.facebook.katana", SecretPersona.LIVE, "password"), true, 1, 2),
                SecretSlotMetadata(SecretSlotKey.of("package:com.facebook.katana", SecretPersona.LIVE, "email"), false, 1, 0),
            )
        }
        GatewayV5KnowledgeSummaryAdapter.skills = {
            listOf(SkillDefinition(id = "skill-1", name = "Morning brief token=abc123", steps = emptyList()))
        }
        GatewayV5KnowledgeSummaryAdapter.automations = {
            listOf(AutomationDefinition(id = "auto-1", name = "Plug in", trigger = TriggerDefinition(TriggerType.entries.first()), steps = emptyList(), enabled = false))
        }
        val result = GatewayV5KnowledgeSummaryAdapter.summary(JSONObject())
        val vault = result.getJSONObject("vault")
        assertEquals(2, vault.getInt("slotCount"))
        assertEquals(1, vault.getInt("setCount"))
        val email = vault.getJSONArray("slots").getJSONObject(0)
        assertEquals("email", email.getString("slot"))
        assertFalse(email.getBoolean("set"))
        assertTrue(email.isNull("updatedAt"))
        assertEquals(setOf("placeId", "persona", "slot", "set", "updatedAt"), email.keys().asSequence().toSet())
        val skill = result.getJSONArray("skills").getJSONObject(0)
        assertFalse(skill.getString("name"), skill.getString("name").contains("abc123"))
        assertFalse(result.getJSONArray("automations").getJSONObject(0).getBoolean("enabled"))
        assertEquals(0, result.getJSONObject("atlas").getInt("places"))
        val bad = runCatching { GatewayV5KnowledgeSummaryAdapter.dispatch("knowledge.get", JSONObject().put("all", true)) }.exceptionOrNull()
        assertEquals("INVALID_REQUEST", (bad as GatewayProtocolException).code)
        assertTrue("knowledge.get" in GatewayProtocol.legacyReadOnlyOperations)
    }

    @Test
    fun guardedDoorsAreCountedPerAppAndDangerWithoutLabels() {
        val dir = java.nio.file.Files.createTempDirectory("guarded").toFile()
        try {
            val store = com.cyclone.mobile.brain.graphv2.AtlasStore(java.io.File(dir, "atlas.json"))
            val shop = "package:com.example.shop"
            val port = com.cyclone.mobile.mapping.run.AtlasStoreMappingPort(store, shop, "Shop", clock = { 1_000 })
            port.recordVerified(shop, "mapping", com.cyclone.mobile.mapping.crawl.VerifiedStructure(
                "screen:home:aaaaaaaaaaaaaaaa", "screen:list:bbbbbbbbbbbbbbbb", "door:list:1111111111111111",
                com.cyclone.mobile.mapping.crawl.MappingDoorKind.MENU, "fp-a", "fp-b",
            ))
            port.markDanger(shop, "mapping", "screen:list:bbbbbbbbbbbbbbbb", "door:buy:2222222222222222", com.cyclone.mobile.mapping.crawl.MappingDanger.PAY)
            val summary = store.places().single()
            val snapshot = store.snapshot(com.cyclone.mobile.brain.graphv2.AtlasPlaceKey(summary.place.id, summary.place.persona))!!
            val rows = GatewayV5KnowledgeSummaryAdapter.guardedCounts(summary.place, snapshot)
            assertEquals(listOf("payment"), rows.map { it.danger.wireValue })
            assertTrue(rows.single().doors + rows.single().rooms >= 1)

            GatewayV5KnowledgeSummaryAdapter.guarded = { rows }
            val guarded = GatewayV5KnowledgeSummaryAdapter.summary(JSONObject()).getJSONArray("guarded").getJSONObject(0)
            assertEquals(shop, guarded.getString("placeId"))
            assertEquals("Shop", guarded.getString("label"))
            assertEquals("payment", guarded.getString("danger"))
            assertEquals(setOf("placeId", "label", "persona", "danger", "doors", "rooms", "roomIds"), guarded.keys().asSequence().toSet())
            assertEquals("screen:list:bbbbbbbbbbbbbbbb", guarded.getJSONArray("roomIds").getString(0))
        } finally {
            dir.deleteRecursively()
        }
    }
}
