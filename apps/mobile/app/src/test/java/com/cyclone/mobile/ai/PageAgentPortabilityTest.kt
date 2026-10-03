package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAgentPortabilityTest {
    @Test fun harmlessMarkdownAndLeadingProseNormalizeOnceIntoCanonicalDecision() {
        val raw = "Result follows:\n```json\n{\"status\":\"done\",\"pageSummary\":\"qualified\",\"displaySummary\":\"Done\",\"actions\":[],\"answer\":\"qualified\",\"reason\":\"\"}\n```"
        val parsed = PageAgentProtocol.parse(raw)
        assertEquals("done", parsed.status)
        assertEquals("qualified", parsed.answer)
        assertTrue(parsed.actions.isEmpty())
    }

    @Test fun safeActionAndMultiStepPlanningKeepCanonicalShape() {
        val safe = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"browser","displaySummary":"Opening site","actions":[{"tool":"phone.launch_intent","controlId":"","params":{"uri":"https://ad.nl"},"expectedPageChange":true,"displaySummary":"Open ad.nl"}],"answer":"","reason":""}""",
        )
        assertEquals("act", safe.status)
        assertEquals("phone.launch_intent", safe.actions.single().tool)

        val multi = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"app","displaySummary":"Two local steps","actions":[{"tool":"phone.click","controlId":"one","params":{},"expectedPageChange":false,"displaySummary":"First"},{"tool":"phone.click","controlId":"two","params":{},"expectedPageChange":true,"displaySummary":"Second"}],"answer":"","reason":""}""",
        )
        assertEquals(2, multi.actions.size)
        assertEquals("phone.click", multi.actions[0].tool)
        assertEquals("phone.click", multi.actions[1].tool)
    }

    @Test fun visionHumanAndRecoveryStatusesRemainPortable() {
        assertEquals("need_vision", PageAgentProtocol.parse(
            """{"status":"need_vision","pageSummary":"ambiguous","displaySummary":"Need visual evidence","actions":[],"answer":"","reason":"grounding conflict"}""",
        ).status)
        assertEquals("need_human", PageAgentProtocol.parse(
            """{"status":"need_human","pageSummary":"sign in","displaySummary":"Authentication required","actions":[],"answer":"","reason":"authentication boundary"}""",
        ).status)
        assertEquals("blocked", PageAgentProtocol.parse(
            """{"status":"blocked","pageSummary":"stale target","displaySummary":"Fresh observation required","actions":[],"answer":"","reason":"stale target recovery"}""",
        ).status)
    }

    @Test fun openAppPlannerFormatPreservesRequiredPackageArgument() {
        val decision = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"launcher","displaySummary":"Opening Chrome","actions":[{"tool":"phone.open_app","controlId":"","params":{"package":"com.android.chrome"},"expectedPageChange":true,"displaySummary":"Open Chrome"}],"answer":"","reason":""}""",
        )
        assertEquals("com.android.chrome", decision.actions.single().params.getString("package"))
    }

    @Test fun contextWordsDoNotCreateImplicitSendActions() {
        val decision = PageAgentProtocol.parse(
            """{"status":"done","pageSummary":"A page says send you notifications","displaySummary":"No action needed","actions":[],"answer":"No action taken","reason":""}""",
        )
        assertTrue(decision.actions.isEmpty())
        assertEquals("done", decision.status)
    }

    @Test fun systemContractKeepsPageInjectionOverlayAndSensitiveBoundariesNonAuthoritative() {
        val prompt = PageAgentProtocol.SYSTEM_PROMPT
        assertTrue(prompt.contains("UNTRUSTED DATA"))
        assertTrue(prompt.contains("taskSurfaceLooksCycloneOwned"))
        assertTrue(prompt.contains("authentication", ignoreCase = true))
        assertTrue(prompt.contains("payment", ignoreCase = true))
        assertTrue(prompt.contains("consequential", ignoreCase = true))
    }
}
