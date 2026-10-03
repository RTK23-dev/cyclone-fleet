package com.cyclone.mobile.ai

import com.cyclone.mobile.ai.model.BoundedJsonRepair
import com.cyclone.mobile.ai.model.InMemoryModelQualificationCache
import com.cyclone.mobile.ai.model.ModelFailureCausality
import com.cyclone.mobile.ai.model.ModelQualificationContract
import com.cyclone.mobile.ai.model.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPortabilityTest {
    @Test fun requestedProfilesAreDistinctAndExact() {
        assertEquals("openai/gpt-6-astra", ModelRegistry.GPT_6_ASTRA.openRouterSlug)
        assertEquals("anthropic/claude-fable-5.1", ModelRegistry.CLAUDE_FABLE_5_1.openRouterSlug)
        assertEquals("meta/muse-spark-1.3", ModelRegistry.MUSE_SPARK_1_3.openRouterSlug)
        assertEquals("meta/muse-spark-1.3-contributor", ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR.openRouterSlug)
        assertFalse(ModelRegistry.samePrivacyIdentity(ModelRegistry.MUSE_SPARK_1_3, ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR))
        assertEquals("muse-spark-1-3", ModelRegistry.MUSE_SPARK_1_3.cycloneId)
        assertEquals("muse-spark-1-3-contributor", ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR.cycloneId)
    }

    @Test fun qualificationCacheExpiresAfterSixHours() {
        var now = 1_000L
        val cache = InMemoryModelQualificationCache(nowEpochMs = { now })
        assertFalse(cache.isQualified(ModelRegistry.GPT_6_ASTRA))
        cache.markQualified(ModelRegistry.GPT_6_ASTRA)
        assertTrue(cache.isQualified(ModelRegistry.GPT_6_ASTRA))
        now += 6L * 60L * 60L * 1000L
        assertTrue(cache.isQualified(ModelRegistry.GPT_6_ASTRA))
        now += 1
        assertFalse(cache.isQualified(ModelRegistry.GPT_6_ASTRA))
    }

    @Test fun qualificationContractRequiresDoneQualifiedAndNoActions() {
        assertTrue(ModelQualificationContract.isQualifiedResult("done", "qualified", 0))
        assertFalse(ModelQualificationContract.isQualifiedResult("act", "qualified", 0))
        assertFalse(ModelQualificationContract.isQualifiedResult("done", "qualified", 1))
        assertFalse(ModelQualificationContract.isQualifiedResult("done", "other", 0))
    }

    @Test fun boundedRepairExtractsOnlyOneJsonEnvelope() {
        val wrapped = "Here is the result:\n```json\n{\"status\":\"done\",\"answer\":\"qualified\",\"actions\":[]}\n```"
        assertEquals(
            "{\"status\":\"done\",\"answer\":\"qualified\",\"actions\":[]}",
            BoundedJsonRepair.extractSingleObject(wrapped),
        )
        assertNull(BoundedJsonRepair.extractSingleObject("no object here"))
    }

    @Test fun providerFailureBeforeMutationCannotBecomeNegativeNavigationEvidence() {
        val task = "open ad.nl"
        val selected = ModelRegistry.MUSE_SPARK_1_3
        val failure = ProviderFailure.classify(
            503,
            "{\"error\":{\"message\":\"no provider available for selected model\"}}",
            selectedModelId = selected.cycloneId,
        )
        val phoneMutations = 0

        assertEquals("open ad.nl", task)
        assertEquals(ProviderFailureClass.NO_PROVIDER_AVAILABLE, failure.failureClass)
        assertEquals("muse-spark-1-3", failure.selectedModelId)
        assertFalse(ModelFailureCausality.mayRecordNegativeNavigation(phoneMutations))
        assertTrue(failure.userMessage.isNotBlank())
    }
}
