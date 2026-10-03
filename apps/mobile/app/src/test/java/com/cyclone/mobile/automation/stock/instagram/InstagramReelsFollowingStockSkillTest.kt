package com.cyclone.mobile.automation.stock.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReelsFollowingStockSkillTest {
    @Test
    fun definitionIsFirstClassFollowingStockSkill() {
        val definition = InstagramReelsFollowingStockSkill.definition
        assertEquals("stock.instagram.reels_following", definition.id)
        assertEquals("Instagram · Reels Following", definition.name)
        assertEquals("STOCK_SKILL", definition.steps.single().type.name)
        assertEquals(definition.id, definition.steps.single().parameters["skillId"])
        assertTrue(definition.outputs.contains("feed"))
    }

    @Test
    fun followingUsesSamePinnedProfileContractWithoutInventingSave() {
        assertEquals(0.28, ReelsProfiles.get(ReelsPersonality.SKIMMER).likeChance, 0.0)
        assertEquals(0.41, ReelsProfiles.get(ReelsPersonality.ENGAGED).commentChance, 0.0)
        assertEquals(1.0, ReelsProfiles.get(ReelsPersonality.DIALED).sourceSaveChance, 0.0)
        assertFalse(InstagramReelsFollowingStockSkill.definition.inputs.any { it.contains("save", ignoreCase = true) })
        assertFalse(InstagramReelsFollowingStockSkill.definition.toString().contains("phone.save", ignoreCase = true))
    }

    @Test
    fun sourceDurationAndCommentValidationRemainShared() {
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "1")).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "180")).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "181")).isFailure)
        assertTrue(ReelsWarmupConfig.parse(mapOf("commentEnabled" to "true", "commentText" to "hello")).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("commentEnabled" to "true", "commentText" to "")).isFailure)
    }

    @Test
    fun stockCollectionKeepsFollowingAfterWarmupInFullReleaseOrder() {
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
