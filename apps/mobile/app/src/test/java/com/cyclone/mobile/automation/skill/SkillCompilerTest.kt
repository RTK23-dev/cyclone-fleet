package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.brain.graphv2.GraphEdgeKey
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphStaleness
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.SkillGraphProjector
import com.cyclone.mobile.ui.v32.v32RoutinesAutomationsListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCompilerTest {
    @Test
    fun threeVerifiedSettingsStepsProduceOneDisabledCapsuleInAutomationStore() {
        val store = AutomationStore.inMemory()
        assertEquals("AutomationStore", store.javaClass.simpleName)

        val compiler = SkillCompiler(store)
        val result = compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 1_000L))

        assertTrue(result is SkillCompileResult.DraftWritten)
        val capsule = (result as SkillCompileResult.DraftWritten).capsule
        assertEquals(SkillCapsuleStatus.DRAFT, capsule.status)
        assertFalse(capsule.enabled)
        assertEquals("com.android.settings", capsule.app)
        assertEquals("Open battery settings", capsule.goal)
        assertEquals("settings.home", capsule.whenPage.pageKey)
        assertEquals(3, capsule.steps.size)
        assertEquals("When on Settings home", capsule.steps[0].whenClause)
        assertEquals("Then open Apps", capsule.steps[0].thenClause)
        assertEquals("Check Apps page", capsule.steps[0].checkClause)

        val saved = store.listAutomations()
        assertEquals(1, saved.size)
        assertEquals(capsule.id, saved.single().id)
        assertFalse(saved.single().enabled)
        assertTrue(saved.single().description.contains(SkillCompiler.DESCRIPTION_MARKER))
        assertEquals(store.javaClass.name, AutomationStore::class.java.name)

        val listed = v32RoutinesAutomationsListing(saved)
        assertEquals(1, listed.size)
        assertTrue(SkillDraftListing.isDraftSkill(listed.single()))
        assertFalse(listed.single().enabled)
    }

    @Test
    fun existingCompileStillWritesDisabledDrafts() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val result = compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 1_000L))
        assertTrue(result is SkillCompileResult.DraftWritten)
        val saved = store.getAutomation((result as SkillCompileResult.DraftWritten).capsule.id)!!
        assertFalse(saved.enabled)
        assertEquals(SkillCapsuleStatus.DRAFT.name.lowercase(), "draft")
        assertTrue(saved.description.contains("status=draft"))
        store.saveAutomation(saved.copy(enabled = true))
        assertFalse(store.getAutomation(saved.id)!!.enabled)
    }

    @Test
    fun missingAfterStateBlocksPromotionAndWritesNothing() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val result = compiler.compile(settingsPath(verified = true, includeAfterState = false, now = 1_000L))

        assertTrue(result is SkillCompileResult.Rejected)
        assertTrue((result as SkillCompileResult.Rejected).reason.contains("after-state"))
        assertTrue(store.listAutomations().isEmpty())
        assertTrue(compiler.listDrafts().isEmpty())
        assertTrue(v32RoutinesAutomationsListing(store.listAutomations()).isEmpty())
    }

    @Test
    fun unverifiedPathDoesNotPromote() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val result = compiler.compile(settingsPath(verified = false, includeAfterState = true, now = 1_000L))

        assertTrue(result is SkillCompileResult.Rejected)
        assertTrue((result as SkillCompileResult.Rejected).reason.contains("unverified"))
        assertTrue(store.listAutomations().isEmpty())
    }

    @Test
    fun passwordSlotIsStrippedFromParams() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val input = settingsPath(verified = true, includeAfterState = true, now = 1_000L).copy(
            params = mapOf(
                "account" to "work",
                "password" to "hunter2",
                "wifiNetwork" to "HomeNet",
            ),
            steps = settingsPath(verified = true, includeAfterState = true, now = 1_000L).steps.mapIndexed { index, step ->
                if (index != 2) step else step.copy(params = mapOf("password" to "hunter2", "ssid" to "HomeNet"))
            },
        )

        val capsule = (compiler.compile(input) as SkillCompileResult.DraftWritten).capsule
        assertFalse(capsule.params.containsKey("password"))
        assertEquals("work", capsule.params["account"])
        assertFalse(capsule.steps.flatMap { it.params.entries }.any { it.key.equals("password", ignoreCase = true) })
        assertFalse(capsule.steps.flatMap { it.params.values }.any { it.contains("hunter2") })
        val persisted = store.getAutomation(capsule.id)!!
        assertFalse(persisted.steps.any { it.parameters["password"] != null })
        assertFalse(persisted.steps.any { it.parameters.values.any { value -> value.contains("hunter2") } })
    }

    @Test
    fun secondCompileOfSamePathDoesNotDuplicateAppGraphNode() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val first = compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 1_000L))
        val second = compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 2_000L))

        assertTrue(first is SkillCompileResult.DraftWritten)
        assertTrue(second is SkillCompileResult.DraftWritten)
        assertEquals(1, store.listAutomations().size)
        assertEquals(2, store.listAutomations().single().version)
        assertEquals(1, SkillGraphProjector.appCount(compiler.graph, "com.android.settings"))
        assertEquals(
            store.listAutomations().single().id,
            (first as SkillCompileResult.DraftWritten).capsule.id,
        )

        val pageIds = compiler.graph.nodes().filterIsInstance<PageNode>().map { it.id.value }.sorted()
        assertEquals(pageIds.distinct(), pageIds)
    }

    @Test
    fun failureLowersOnlyThatEdge() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 1_000L))

        val home = SkillGraphProjector.pageNodeId("com.android.settings", "settings.home")
        val apps = SkillGraphProjector.pageNodeId("com.android.settings", "settings.apps")
        val appsAll = SkillGraphProjector.pageNodeId("com.android.settings", "settings.apps.all")
        val firstHop = GraphEdgeKey(home, GraphEdgeType.NAVIGATES_TO, apps)
        val secondHop = GraphEdgeKey(apps, GraphEdgeType.NAVIGATES_TO, appsAll)

        compiler.lowerFailedEdge("com.android.settings", "settings.home", "settings.apps", "fail-home-apps", 3_000L)

        val firstCurrent = compiler.graph.history(firstHop).last()
        val secondCurrent = compiler.graph.history(secondHop).last()
        assertEquals(GraphStaleness.SUSPECT, firstCurrent.evidence.staleness)
        assertEquals(1, firstCurrent.evidence.failureCount)
        assertEquals(GraphStaleness.CURRENT, secondCurrent.evidence.staleness)
        assertEquals(0, secondCurrent.evidence.failureCount)
    }
}

internal fun settingsPath(verified: Boolean, includeAfterState: Boolean, now: Long) = SkillCompileInput(
    app = "com.android.settings",
    goal = "Open battery settings",
    startPageKey = "settings.home",
    preconditions = listOf("package=com.android.settings"),
    nowEpochMillis = now,
    steps = listOf(
        SkillStepDraft(
            whenClause = "When on Settings home",
            thenClause = "Then open Apps",
            checkClause = "Check Apps page",
            action = "phone.click",
            selectors = listOf(
                RankedSelector("text", "Apps", 0.92),
                RankedSelector("resourceId", "com.android.settings:id/apps", 0.71),
            ),
            verifiers = listOf(SkillVerifier(afterPageKey = "settings.apps", text = "Apps")),
            beforePageKey = "settings.home",
            afterPageKey = "settings.apps".takeIf { includeAfterState },
            verified = verified,
            evidenceTrace = "trace-open-apps",
        ),
        SkillStepDraft(
            whenClause = "When on Apps",
            thenClause = "Then open See all",
            checkClause = "Check app list visible",
            action = "phone.click",
            selectors = listOf(RankedSelector("text", "See all apps", 0.88)),
            verifiers = listOf(SkillVerifier(afterPageKey = "settings.apps.all", text = "See all")),
            beforePageKey = "settings.apps",
            afterPageKey = "settings.apps.all".takeIf { includeAfterState },
            verified = verified,
            evidenceTrace = "trace-see-all",
        ),
        SkillStepDraft(
            whenClause = "When on app list",
            thenClause = "Then open Battery",
            checkClause = "Check Battery page",
            action = "phone.click",
            selectors = listOf(RankedSelector("text", "Battery", 0.95)),
            verifiers = listOf(SkillVerifier(afterPageKey = "settings.battery", goneControl = "See all apps")),
            beforePageKey = "settings.apps.all",
            afterPageKey = "settings.battery".takeIf { includeAfterState },
            verified = verified,
            evidenceTrace = "trace-battery",
        ),
    ),
)
