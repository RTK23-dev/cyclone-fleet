package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V39ModelSelectionTest {
    @Test fun liveCatalogSlugSurvivesStorageAndAllRequestModes() {
        val slug = "new-provider/new-model:free"
        val model = V39AiChatContract.modelForStored(slug)
        assertEquals(slug, V39AiChatContract.storageId(model))
        val config = V39AiChatContract.config(slug, CycloneAiAccessProfile.BALANCED)
        assertEquals(slug, config.model.id)
        assertEquals(slug, config.visionModel.id)
    }

    @Test fun contributorPersistsAsDistinctCycloneIdentity() {
        val contributor = OpenRouterModelPresets.MUSE_SPARK_1_3_CONTRIBUTOR
        val normal = OpenRouterModelPresets.MUSE_SPARK_1_3
        assertEquals("muse-spark-1-3-contributor", V39AiChatContract.storageId(contributor))
        assertEquals("muse-spark-1-3", V39AiChatContract.storageId(normal))
        assertNotEquals(V39AiChatContract.storageId(normal), V39AiChatContract.storageId(contributor))
        assertEquals(
            "meta/muse-spark-1.3-contributor",
            V39AiChatContract.modelForStored("muse-spark-1-3-contributor").id,
        )
    }

    @Test fun contributorVisionNeverAliasesTheNormalVisionModel() {
        val config = V39AiChatContract.config("muse-spark-1-3-contributor", CycloneAiAccessProfile.BALANCED)
        assertEquals("meta/muse-spark-1.3-contributor", config.model.id)
        assertEquals(config.model.id, config.visionModel.id)
        assertTrue(config.model.label.contains("Contributor"))
    }
}
