package com.cyclone.mobile.automation.stock.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramPreparePostStockSkillTest {
    @Test
    fun definitionIsFirstClassPreparePostSkill() {
        val definition = InstagramPreparePostStockSkill.definition
        assertEquals("stock.instagram.prepare_post", definition.id)
        assertEquals("Instagram · Prepare Post", definition.name)
        assertEquals("STOCK_SKILL", definition.steps.single().type.name)
        assertEquals(definition.id, definition.steps.single().parameters["skillId"])
        assertTrue(definition.outputs.contains("reviewReached"))
        assertTrue(definition.outputs.contains("captionPending"))
    }

    @Test
    fun mediaInputsPreserveOrderAndSourceBoundary() {
        val config = PreparePostConfig.parse(mapOf(
            "mediaUris" to "content://media/a\ncontent://media/b\ncontent://media/a",
            "caption" to "caption stays pending",
            "destination" to "draft",
        )).getOrThrow()
        assertEquals(listOf("content://media/a", "content://media/b"), config.mediaUris)
        assertEquals(2, config.mediaCount)
        assertEquals("draft", config.destination)
        assertEquals("caption stays pending", config.caption)

        // The executable source stops on share/caption review: destination remains metadata only.
        assertFalse(InstagramPreparePostStockSkill.definition.steps.single().parameters.values.any {
            it.equals("Share", ignoreCase = true) || it.equals("Draft", ignoreCase = true)
        })
    }

    @Test
    fun sourceCompatiblePostValidationIsStrict() {
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "1")).isSuccess)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "20")).isSuccess)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "0")).isFailure)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "21")).isFailure)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "1", "destination" to "publish")).isSuccess)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "1", "destination" to "draft")).isSuccess)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "1", "destination" to "auto-share")).isFailure)
        assertTrue(PreparePostConfig.parse(mapOf("mediaCount" to "1", "musicUrl" to "instagram://sound/1")).isFailure)
        assertEquals(350L * 1024L * 1024L, InstagramPreparePostStockSkill.MAX_MEDIA_BYTES)
    }

    @Test
    fun stockCollectionContainsFullPinnedInstagramSetInSourceOrder() {
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
