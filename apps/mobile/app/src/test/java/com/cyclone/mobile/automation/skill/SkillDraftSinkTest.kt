package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GatePolicy
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PcGateEnvelope
import com.cyclone.mobile.policy.PolicyClock
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import com.cyclone.mobile.ui.v32.v32IsDraftSkillOnAutomations
import com.cyclone.mobile.ui.v32.v32RoutinesAutomationsListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDraftSinkTest {
    private val agent = PolicyPrincipal(PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT))

    @Test
    fun overlayDoneWritesOneDisabledDraftListedOnAutomations() {
        val store = AutomationStore.inMemory()
        val sink = sink(store)
        val result = sink.consumeOverlayState(
            SkillDraftSink.OVERLAY_DONE,
            settingsPath(verified = true, includeAfterState = true, now = 1_000L),
        )
        assertTrue(result is SkillDraftSinkResult.DraftWritten)
        val written = result as SkillDraftSinkResult.DraftWritten
        assertEquals(SkillSaveSource.OVERLAY_DONE, written.source)
        assertEquals(SkillCapsuleStatus.DRAFT, written.capsule.status)
        assertFalse(written.capsule.enabled)

        val listed = v32RoutinesAutomationsListing(store.listAutomations())
        assertEquals(1, listed.size)
        assertTrue(v32IsDraftSkillOnAutomations(listed.single()))
        assertFalse(listed.single().enabled)
    }

    @Test
    fun mcpSkillSaveLandsInTheSameStore() {
        val store = AutomationStore.inMemory()
        val sink = sink(store)
        val first = sink.saveFromOverlayDone(settingsPath(verified = true, includeAfterState = true, now = 1_000L))
        val second = sink.saveFromMcp(settingsPath(verified = true, includeAfterState = true, now = 2_000L))
        assertTrue(first is SkillDraftSinkResult.DraftWritten)
        assertTrue(second is SkillDraftSinkResult.DraftWritten)
        assertEquals(1, store.listAutomations().size)
        assertEquals(SkillSaveSource.MCP_SKILL_SAVE, (second as SkillDraftSinkResult.DraftWritten).source)
        assertEquals(2, store.listAutomations().single().version)
    }

    @Test
    fun gateDenyOnPayLikeActionWritesNoVerifiedCapsule() {
        val store = AutomationStore.inMemory()
        val sink = sink(store)
        val payPath = settingsPath(verified = true, includeAfterState = true, now = 1_000L).copy(
            goal = "Place food order",
            steps = settingsPath(verified = true, includeAfterState = true, now = 1_000L).steps + SkillStepDraft(
                whenClause = "When on checkout",
                thenClause = "Then Pay now",
                checkClause = "Check order placed",
                action = "phone.click",
                selectors = listOf(RankedSelector("text", "Pay now", 0.99)),
                verifiers = listOf(SkillVerifier(afterPageKey = "shop.receipt", text = "Thanks")),
                beforePageKey = "shop.checkout",
                afterPageKey = "shop.receipt",
                verified = true,
                evidenceTrace = "trace-pay",
            ),
        )
        val result = sink.saveFromMcp(payPath, PcGateEnvelope(autoApprove = true, requestedCapsuleStatus = "verified"))
        assertTrue(result is SkillDraftSinkResult.GateDenied)
        val denied = result as SkillDraftSinkResult.GateDenied
        assertEquals(GateClass.PAY, denied.gateClass)
        assertFalse(denied.mutationAllowed)
        assertFalse(denied.writesVerified)
        assertTrue(denied.ignoredPcAutoApprove)
        assertTrue(store.listAutomations().isEmpty())
        assertTrue(store.listAutomations().none { it.description.contains("status=verified") })
    }

    @Test
    fun workersAndPcCannotFlipDraftToVerified() {
        val store = AutomationStore.inMemory()
        val compiler = SkillCompiler(store)
        val written = compiler.compile(settingsPath(verified = true, includeAfterState = true, now = 1_000L))
        assertTrue(written is SkillCompileResult.DraftWritten)
        val id = (written as SkillCompileResult.DraftWritten).capsule.id

        val worker = SkillPromotion.requestStatus(store, id, SkillActor.FLEET_WORKER, SkillCapsuleStatus.VERIFIED)
        val pc = SkillPromotion.requestStatus(
            store,
            id,
            SkillActor.PC_COMPANION,
            SkillCapsuleStatus.VERIFIED,
            PcGateEnvelope(autoApprove = true, requestedCapsuleStatus = "verified"),
        )
        assertTrue(worker is SkillPromotionResult.Rejected)
        assertTrue(pc is SkillPromotionResult.Rejected)
        assertFalse(store.getAutomation(id)!!.enabled)
        assertTrue(store.getAutomation(id)!!.description.contains("status=draft"))
    }

    @Test
    fun overlayAnalysisIsIgnoredAndDoesNotWrite() {
        val store = AutomationStore.inMemory()
        val sink = sink(store)
        val result = sink.consumeOverlayState(
            SkillDraftSink.OVERLAY_ANALYSIS,
            settingsPath(verified = true, includeAfterState = true, now = 1_000L),
        )
        assertEquals(SkillDraftSinkResult.Ignored, result)
        assertTrue(store.listAutomations().isEmpty())
    }

    private fun sink(store: AutomationStore) = SkillDraftSink(
        compiler = SkillCompiler(store),
        gate = GatePolicy(InMemoryPolicyGovernor(PolicyClock { 100L })),
        principal = agent,
    )
}
