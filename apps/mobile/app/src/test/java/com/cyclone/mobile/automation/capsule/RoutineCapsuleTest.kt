package com.cyclone.mobile.automation.capsule

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.automation.VariableDefinition
import com.cyclone.mobile.platform.capability.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineCapsuleTest {
    @Test
    fun snapshotFreezesMutableInputsAndVersionChangesHash() {
        val inputs = mutableListOf(RoutineInput("query", RoutineInputType.TEXT, description = "Search query"))
        val capabilities = mutableSetOf(CapabilityId("phone.click"))
        val capsule = fixture(inputs = inputs, capabilities = capabilities)
        val snapshot = CapsuleSnapshot.capture(capsule)

        inputs += RoutineInput("later", RoutineInputType.TEXT, description = "Added later")
        capabilities += CapabilityId("phone.scroll")

        assertEquals(listOf("query"), snapshot.capsule.inputs.map { it.name })
        assertEquals(setOf(CapabilityId("phone.click")), snapshot.capsule.requiredCapabilities)
        assertEquals(snapshot, CapsuleSnapshot.restore(snapshot.canonicalJson, snapshot.sha256))
        assertNotEquals(snapshot.sha256, CapsuleSnapshot.capture(capsule.copy(routineVersion = RoutineVersion(2, 0, 0))).sha256)
    }

    @Test
    fun canonicalEncodingIsStableAcrossCollectionOrder() {
        val first = fixture(
            inputs = listOf(
                RoutineInput("query", RoutineInputType.TEXT, description = "Query"),
                RoutineInput("account", RoutineInputType.TEXT, description = "Account"),
            ),
            capabilities = linkedSetOf(CapabilityId("phone.scroll"), CapabilityId("phone.click")),
        )
        val second = first.copy(
            inputs = first.inputs.reversed(),
            requiredCapabilities = first.requiredCapabilities.reversed().toSet(),
            graph = first.graph.copy(steps = first.graph.steps.reversed()),
            policyRequirements = first.policyRequirements.reversed(),
        )

        assertEquals(RoutineCapsuleCodec.encode(first), RoutineCapsuleCodec.encode(second))
        assertEquals(CapsuleSnapshot.capture(first).sha256, CapsuleSnapshot.capture(second).sha256)
        assertEquals(first.normalized(), RoutineCapsuleCodec.decode(RoutineCapsuleCodec.encode(first)).normalized())
    }

    @Test
    fun legacyMigrationPreservesVersionButNeverSecretDefault() {
        val source = legacy(
            version = 7,
            variables = listOf(VariableDefinition("message", "do-not-copy", secret = true)),
            steps = listOf(
                StepDefinition(
                    id = "type",
                    name = "Type message",
                    type = StepType.PHONE_TOOL,
                    parameters = mapOf("tool" to "phone.type", "text" to "\${message}"),
                    recovery = RecoveryPolicy(maxRetries = 1),
                ),
            ),
        )
        val result = LegacyAutomationCapsuleAdapter.migrate(source, 100, "test")
        assertTrue(result is RoutineMigrationResult.Ready)
        val capsule = (result as RoutineMigrationResult.Ready).capsule

        assertEquals(RoutineVersion(7, 0, 0), capsule.routineVersion)
        assertEquals(RoutineInputType.SECRET_REFERENCE, capsule.inputs.single().type)
        assertFalse(RoutineCapsuleCodec.encode(capsule).contains("do-not-copy"))
        assertTrue(capsule.graph.steps.single().action!!.arguments["text"] is RoutineArgument.SecretReference)
    }

    @Test
    fun legacyMigrationBlocksLiteralTypingAndExecutableOrAmbiguousSteps() {
        val literalType = legacy(steps = listOf(phoneStep("phone.type", mapOf("text" to "private text"))))
        val network = legacy(steps = listOf(StepDefinition(name = "Call", type = StepType.HTTP_REQUEST)))
        val partialReference = legacy(steps = listOf(phoneStep("phone.click", mapOf("label" to "Hi \${name}"))))

        assertTrue(LegacyAutomationCapsuleAdapter.migrate(literalType, 1, "test") is RoutineMigrationResult.Blocked)
        assertTrue(LegacyAutomationCapsuleAdapter.migrate(network, 1, "test") is RoutineMigrationResult.Blocked)
        assertTrue(LegacyAutomationCapsuleAdapter.migrate(partialReference, 1, "test") is RoutineMigrationResult.Blocked)
    }

    private fun legacy(
        version: Int = 1,
        variables: List<VariableDefinition> = emptyList(),
        steps: List<StepDefinition>,
    ) = AutomationDefinition(
        id = "routine-source",
        name = "Legacy routine",
        version = version,
        trigger = TriggerDefinition(TriggerType.MANUAL),
        variables = variables,
        steps = steps,
    )

    private fun phoneStep(tool: String, parameters: Map<String, String> = emptyMap()) = StepDefinition(
        name = tool,
        type = StepType.PHONE_TOOL,
        parameters = parameters + ("tool" to tool),
    )

    companion object {
        fun fixture(
            inputs: List<RoutineInput> = listOf(RoutineInput("query", RoutineInputType.TEXT, description = "Query")),
            capabilities: Set<CapabilityId> = setOf(CapabilityId("phone.click"), CapabilityId("phone.scroll")),
        ): CycloneRoutineCapsule {
            val click = RoutineStep(
                id = RoutineStepId("click"),
                name = "Click result",
                kind = RoutineStepKind.ACTION_PROPOSAL,
                action = RoutineActionProposal(
                    CapabilityId("phone.click"),
                    "click",
                    mapOf("query" to RoutineArgument.InputReference("query")),
                ),
                nextStepIds = listOf(RoutineStepId("observe")),
                verificationIds = listOf("clicked"),
                recovery = RoutineRecoveryPlan(2, listOf(RecoveryPrimitive.REOBSERVE, RecoveryPrimitive.RETRY_SELECTOR)),
            )
            val observe = RoutineStep(
                id = RoutineStepId("observe"),
                name = "Observe result",
                kind = RoutineStepKind.OBSERVE,
            )
            return CycloneRoutineCapsule(
                schemaVersion = 1,
                routineId = RoutineId("test.search"),
                routineVersion = RoutineVersion(1, 0, 0),
                intent = "Search and select a result",
                inputs = inputs,
                requiredCapabilities = capabilities,
                requiredPackages = setOf("com.example.app"),
                graph = RoutineGraph(click.id, listOf(click, observe), 20),
                verification = listOf(RoutineVerification("clicked", VerificationKind.ACTION_RESULT_OK, "typed action result")),
                policyRequirements = listOf(
                    RoutinePolicyRequirement(CapabilityId("phone.click"), "routine", RoutineConfirmation.WHEN_POLICY_REQUIRES),
                ),
                provenance = RoutineProvenance("test.fixture", "fixture", 1, "test"),
                compatibility = RoutineCompatibility(1, 2),
            )
        }
    }
}
