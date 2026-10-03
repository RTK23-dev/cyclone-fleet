package com.cyclone.mobile.automation.stock.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramColdDmsStockSkillTest {
    @Test
    fun definitionIsFirstClassStockSkill() {
        val definition = InstagramColdDmsStockSkill.definition
        assertEquals("stock.instagram.cold_dms", definition.id)
        assertEquals("Instagram · Cold DMs", definition.name)
        assertEquals("STOCK_SKILL", definition.steps.single().type.name)
        assertEquals(definition.id, definition.steps.single().parameters["skillId"])
        assertTrue(definition.inputs.contains("leadListName"))
        assertTrue(definition.inputs.contains("verifyEnabled"))
    }

    @Test
    fun sourcePayloadLimitsAndDeduplicationArePreserved() {
        val config = ColdDmConfig.parse(mapOf(
            "handles" to "alice, @BOB\nalice",
            "message" to " hello ",
        )).getOrThrow()
        assertEquals(listOf("@alice", "@bob"), config.explicitHandles)
        assertEquals("hello", config.message)

        val twentyFive = (1..25).joinToString(",") { "user$it" }
        assertTrue(ColdDmConfig.parse(mapOf("handles" to twentyFive, "message" to "ok")).isSuccess)
        val twentySix = (1..26).joinToString(",") { "user$it" }
        assertTrue(ColdDmConfig.parse(mapOf("handles" to twentySix, "message" to "ok")).isFailure)
        assertTrue(ColdDmConfig.parse(mapOf("handles" to "alice", "message" to "x".repeat(1000))).isSuccess)
        assertTrue(ColdDmConfig.parse(mapOf("handles" to "alice", "message" to "x".repeat(1001))).isFailure)
    }

    @Test
    fun sourceDefaultsAndStrictBooleansRemainIntact() {
        val config = ColdDmConfig.parse(mapOf("handles" to "alice", "message" to "hi")).getOrThrow()
        assertEquals(1, config.cycles)
        assertEquals(2_500L, config.betweenMs)
        assertEquals(1_500L, config.jitterMs)
        assertTrue(config.verifyEnabled)
        assertTrue(config.skipPrivate)
        assertFalse(config.retryFailed)
        assertTrue(ColdDmConfig.parse(mapOf("handles" to "alice", "message" to "hi", "verifyEnabled" to "maybe")).isFailure)
    }

    @Test
    fun leadListCodecAcceptsCompactAndRawScraperShapes() {
        val compact = ColdDmLeadCodec.parse("launch", """
            {"leads":[
              {"username":"Alice","fullName":"Alice A","isPrivate":false,"isVerified":true},
              {"username":"alice","fullName":"duplicate"},
              {"username":"private.user","isPrivate":true}
            ]}
        """.trimIndent())
        assertEquals(listOf("alice", "private.user"), compact.leads.map { it.username })
        assertTrue(compact.leads.first().isVerified)
        assertTrue(compact.leads.last().isPrivate)

        val raw = ColdDmLeadCodec.parse("raw", """
            [
              {"username":"bob","full_name":"Bob B","is_private":false,"is_verified":false},
              {"ownerUsername":"creator.2","full_name":"Creator Two"}
            ]
        """.trimIndent())
        assertEquals(listOf("bob", "creator.2"), raw.leads.map { it.username })
        assertEquals("Bob B", raw.leads.first().fullName)
    }

    @Test
    fun stockCollectionKeepsColdDmsAfterReelsInFullReleaseOrder() {
        assertEquals(
            listOf(
                InstagramStockSkillIds.REELS_WARMUP,
                InstagramReelsFollowingStockSkill.ID,
                InstagramColdDmsStockSkill.ID,
                InstagramPreparePostStockSkill.ID,
            ),
            InstagramStockSkillCollectionGateway.definitions.map { it.id },
        )
    }
}
