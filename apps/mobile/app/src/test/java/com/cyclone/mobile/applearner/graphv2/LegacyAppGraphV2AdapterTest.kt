package com.cyclone.mobile.applearner.graphv2

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.brain.graphv2.EdgeRecordResult
import com.cyclone.mobile.brain.graphv2.GraphNodeType
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.InMemoryTemporalGraphStore
import com.cyclone.mobile.brain.graphv2.SelectorNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppGraphV2AdapterTest {
    @Test
    fun projectionIsDeterministicAndDoesNotMutateLegacySnapshot() {
        val snapshot = legacySnapshot()
        val before = snapshot.copy(
            screens = snapshot.screens.toList(),
            actions = snapshot.actions.toList(),
            transitions = snapshot.transitions.toList(),
        )

        val first = LegacyAppGraphV2Adapter.project(snapshot)
        val second = LegacyAppGraphV2Adapter.project(snapshot)

        assertEquals(before, snapshot)
        assertEquals(first, second)
        assertTrue(first.nodes.map { it.type }.containsAll(GraphNodeType.entries.take(6)))
    }

    @Test
    fun legacyVerifiedNeverBecomesPhysicalVerificationAndImportsWithoutRewritingSource() {
        val snapshot = legacySnapshot()
        val projection = LegacyAppGraphV2Adapter.project(snapshot)
        val store = InMemoryTemporalGraphStore()

        val results = projection.importInto(store)

        assertTrue(results.all { it is EdgeRecordResult.Recorded })
        assertTrue(store.currentEdges(includeStale = true).all {
            it.evidence.verificationScope != GraphVerificationScope.PHYSICAL_DEVICE
        })
        assertEquals(KnowledgeState.VERIFIED, snapshot.transitions.single().knowledgeState)
    }

    @Test
    fun selectorProjectionHashesRawSelectorInsteadOfPersistingItsContent() {
        val projection = LegacyAppGraphV2Adapter.project(legacySnapshot())
        val selectors = projection.nodes.filterIsInstance<SelectorNode>()

        assertEquals(1, selectors.size)
        assertTrue(selectors.single().selectorKey.startsWith("sha256:"))
        assertFalse(selectors.single().toString().contains("sensitive-selector-value"))
    }

    private fun legacySnapshot(): AppGraphSnapshot {
        val home = LearnedScreen(
            id = "home",
            packageName = "com.shop",
            identity = "home",
            title = "Home",
            purpose = "Start",
            recognition = ScreenRecognition("semantic-home", "struct-home", listOf("home"), "com.shop.MainActivity", listOf("Home")),
            knowledgeState = KnowledgeState.VERIFIED,
            confidence = 0.9,
            appVersion = "10.0",
            lastSeenAt = 100,
            lastVerifiedAt = 100,
        )
        val orders = home.copy(
            id = "orders",
            identity = "orders",
            title = "Orders",
            recognition = ScreenRecognition("semantic-orders", "struct-orders", listOf("orders"), "com.shop.OrdersActivity", listOf("Orders")),
        )
        val action = LearnedAction(
            id = "open-orders",
            packageName = "com.shop",
            screenId = home.id,
            semanticName = "open_orders",
            label = "Orders",
            androidActions = listOf("ACTION_CLICK"),
            selectorJson = "{\"resourceId\":\"sensitive-selector-value\"}",
            risk = ActionRisk.SAFE,
            knowledgeState = KnowledgeState.VERIFIED,
            confidence = 0.9,
            lastSuccessAt = 100,
        )
        val transition = LearnedTransition(
            id = "home-orders",
            packageName = "com.shop",
            fromScreenId = home.id,
            actionId = action.id,
            toScreenId = orders.id,
            knowledgeState = KnowledgeState.VERIFIED,
            confidence = 0.9,
            observedCount = 3,
            successfulCount = 3,
            lastObservedAt = 100,
        )
        return AppGraphSnapshot(
            app = LearnedApp(
                packageName = "com.shop",
                label = "Shop",
                versionName = "10.0",
                versionCode = 10,
                knowledgeState = KnowledgeState.VERIFIED,
                confidence = 0.9,
                lastLearnedAt = 100,
                lastVerifiedAt = 100,
            ),
            screens = listOf(home, orders),
            actions = listOf(action),
            transitions = listOf(transition),
        )
    }
}
