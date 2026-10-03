package com.cyclone.mobile.agent.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionAgenticWiringContractTest {
    private fun source(vararg candidates: String): String {
        val file = candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Could not find production source: ${candidates.joinToString()}")
        return file.readText()
    }

    @Test
    fun productionOverlayUsesPersistentAgentAndPcParityBridge() {
        val adaptive = source(
            "src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
        )
        val overlay = source(
            "src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt",
        )
        assertTrue(adaptive.contains("CycloneLocalAgent"))
        assertTrue(adaptive.contains("CyclonePcParityBridge"))
        assertTrue(overlay.contains("OpenRouterAdaptiveAgent"))
        assertTrue(overlay.contains("resumeSuspendedTask"))
        assertFalse(adaptive.contains("while (providerRequests < config.maxDecisions)"))
    }

    @Test
    fun pcParityBridgeConsumesAgent2AndAgent3Contracts() {
        val bridge = source(
            "src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt",
            "app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt",
        )
        assertTrue(bridge.contains("CycloneAgentEnvironment"))
        assertTrue(bridge.contains("AgenticRecoveryRuntimePort"))
        assertTrue(bridge.contains("classifyProgress"))
        assertTrue(bridge.contains("selectRecovery"))
    }

    @Test
    fun productionActionPathDoesNotEquateExecutorAcceptanceWithVerification() {
        val adaptive = source(
            "src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
            "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt",
        )
        assertTrue(adaptive.contains("envelope.verification.passed"))
        assertTrue(adaptive.contains("ANDROID_EXECUTION"))
        assertTrue(adaptive.contains("AFTER_OBSERVATION"))
        assertTrue(adaptive.contains("VERIFICATION"))
        assertFalse(adaptive.contains("val verified = result.ok"))
    }
}
