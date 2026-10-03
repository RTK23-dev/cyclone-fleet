package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReliabilityPolicyTest {
    @Test fun redditSequenceDoesNotCarryRetryDebtAcrossVerifiedProgress() {
        val session = AgentReliabilitySession(AgentReliabilityConfig(maxConsecutiveFailures = 20))
        session.start()
        session.observe("launcher")
        session.requestAction("phone.open_app", "chrome")
        assertEquals(ReliabilityDirective.RETRY, session.result(false, false, ReliabilityFailureClass.ACTION))
        session.requestAction("phone.open_app", "chrome")
        session.result(true, true)
        session.requestAction("phone.launch_intent", "reddit")
        session.result(true, true)
        session.requestAction("phone.back", null)
        assertEquals(ReliabilityDirective.RETRY, session.result(false, false, ReliabilityFailureClass.ACTION))
        assertEquals(ReliabilityDirective.CONTINUE, session.requestAction("phone.click", "reject_optional_cookies"))
    }

    @Test fun observationChurnAndTransportAcceptanceDoNotEraseRetryDebt() {
        val session = AgentReliabilitySession(AgentReliabilityConfig(maxConsecutiveFailures = 20))
        session.start()
        session.observe("a")
        session.result(false, false, ReliabilityFailureClass.ACTION)
        session.observe("b")
        assertEquals(ReliabilityDirective.PAUSE, session.result(true, null, ReliabilityFailureClass.ACTION))
    }

    @Test
    fun repeatedActionWithoutStateProgressPauses() {
        val session = AgentReliabilitySession(AgentReliabilityConfig(maxRepeatedActionWithoutProgress = 2))
        session.start()
        session.observe("page-a")

        assertEquals(ReliabilityDirective.CONTINUE, session.requestAction("phone.click", "Apps"))
        assertEquals(ReliabilityDirective.CONTINUE, session.requestAction("phone.click", "Apps"))
        assertEquals(ReliabilityDirective.PAUSE, session.requestAction("phone.click", "Apps"))
        assertEquals("convergence.repeated_action", session.snapshot().stopCode)
    }

    @Test
    fun freshStateResetsRepetitionAndSuccessfulVerificationResetsFailures() {
        val session = AgentReliabilitySession()
        session.start()
        session.observe("page-a")
        session.requestAction("phone.click", "Apps")
        assertEquals(ReliabilityDirective.RETRY, session.result(ok = false, verified = false, failureClass = ReliabilityFailureClass.ACTION))
        session.observe("page-b")
        assertEquals(ReliabilityDirective.CONTINUE, session.requestAction("phone.click", "Apps"))
        assertEquals(ReliabilityDirective.CONTINUE, session.result(ok = true, verified = true))
        assertEquals(0, session.snapshot().consecutiveFailures)
    }

    @Test
    fun retryBudgetsAreDifferentByFailureClass() {
        val config = AgentReliabilityConfig(observationRetries = 2, actionRetries = 0)
        val observation = AgentReliabilitySession(config).also { it.start() }
        assertEquals(ReliabilityDirective.RETRY, observation.result(false, null, ReliabilityFailureClass.OBSERVATION))
        val action = AgentReliabilitySession(config).also { it.start() }
        assertEquals(ReliabilityDirective.PAUSE, action.result(false, null, ReliabilityFailureClass.ACTION))
        assertEquals(8_000, config.timeoutFor(ReliabilityFailureClass.VERIFICATION))
        assertEquals(15_000, config.timeoutFor(ReliabilityFailureClass.TRANSPORT))
    }

    @Test
    fun pauseResumeCancelAndBoundedHistoryAreExplicit() {
        var clock = 100L
        val session = AgentReliabilitySession(
            AgentReliabilityConfig(maxHistory = 20),
            now = { clock++ },
            sessionId = "task-1",
        )
        session.start()
        repeat(30) { session.observe("page-$it") }
        assertEquals(ReliabilityDirective.PAUSE, session.pause())
        assertEquals(ReliabilityDirective.CONTINUE, session.resume())
        assertEquals(ReliabilityDirective.FAIL, session.cancel())
        assertEquals(AgentTaskStatus.CANCELLED, session.snapshot().status)
        assertEquals(20, session.snapshot().events.size)
    }

    @Test
    fun signaturesDoNotPersistVisibleOrTypedValues() {
        val signature = AgentReliabilitySession.safeActionSignature("phone.type", "my secret value")
        assertTrue(signature.startsWith("phone.type:"))
        assertFalse(signature.contains("secret"))
        assertFalse(signature.contains("value"))
    }
}
