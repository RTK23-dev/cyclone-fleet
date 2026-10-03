package com.cyclone.mobile.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class RunReliabilitySummaryTest {
    private fun e(kind: String, code: String?, ok: Boolean?, detail: String?, text: String = kind) =
        AiTraceEvent("id-$kind-$code", "s", 1, kind, text, code, ok, detail)

    @Test fun summaryNamesWaitsProofsLatencyBackupAndBasis() {
        val summary = AgentRunDiagnosticV39.reliabilitySummary(listOf(
            e("WAIT", "settle.ready", true, """{"state":"ready","waitedMs":2400,"extended":true}"""),
            e("VERIFICATION", "verify.deferred", true, "{}"),
            e("PROVIDER_PHASE", "provider_closed", true, "decision=2 phase=provider_closed elapsedMs=14545 request=x"),
            e("PROVIDER_FALLBACK", "provider.backup_route", true, "", "GPT-6 Luna is busy; continuing with your backup"),
            e("NAV_CLAUSE", "nav.clause", true, """{"proof":"Enabled alarm at 23:09 observed"}"""),
            e("VERIFY", "completion.claim_is_navigation", false, null),
        ))
        listOf("Screen waits: 1 (2400 ms total, 1 extended", "Deferred proofs: 1 proven / 0 not proven",
            "Model request latency ms: 14545", "Backup model:", "Completion basis: Enabled alarm at 23:09 observed",
            "Rejected claim").forEach { assertTrue(it, summary.contains(it)) }
    }

    @Test fun alpha22AlarmRunWouldShowNoRealBasis() {
        val summary = AgentRunDiagnosticV39.reliabilitySummary(listOf(e("VERIFY", "completion.claim_is_navigation", false, null)))
        assertTrue(summary.contains("Completion basis: none"))
    }
}
