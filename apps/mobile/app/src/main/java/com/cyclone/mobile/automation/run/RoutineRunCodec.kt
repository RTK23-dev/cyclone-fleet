package com.cyclone.mobile.automation.run

import com.cyclone.mobile.automation.capsule.CanonicalJson
import com.cyclone.mobile.automation.capsule.CapsuleSnapshot
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.capsule.RoutineArgument
import com.cyclone.mobile.automation.capsule.RoutineStepId
import com.cyclone.mobile.platform.capability.CapabilityId
import org.json.JSONArray
import org.json.JSONObject

object RoutineRunCodec {
    fun encode(run: RoutineRunRecord): String = CanonicalJson.write(
        mapOf(
            "runId" to run.runId.value,
            "capsuleCanonicalJson" to run.capsuleSnapshot.canonicalJson,
            "capsuleSha256" to run.capsuleSnapshot.sha256,
            "startedAtEpochMillis" to run.startedAtEpochMillis,
            "updatedAtEpochMillis" to run.updatedAtEpochMillis,
            "endedAtEpochMillis" to run.endedAtEpochMillis,
            "status" to run.status.name,
            "steps" to run.steps.sortedBy { it.stepId }.map(::stepToMap),
            "recoveryAttempts" to run.recoveryAttempts.map(::recoveryToMap),
            "artifacts" to run.artifacts.sortedBy { it.artifactId }.map { artifact ->
                mapOf(
                    "artifactId" to artifact.artifactId,
                    "mediaType" to artifact.mediaType,
                    "sha256" to artifact.sha256,
                    "createdAtEpochMillis" to artifact.createdAtEpochMillis,
                    "redacted" to artifact.redacted,
                )
            },
            "completionEvidenceIds" to run.completionEvidenceIds.sorted(),
            "failureCode" to run.failureCode,
        ),
    )

    fun decode(raw: String): RoutineRunRecord {
        val json = JSONObject(raw)
        return RoutineRunRecord(
            runId = RoutineRunId(json.getString("runId")),
            capsuleSnapshot = CapsuleSnapshot.restore(
                json.getString("capsuleCanonicalJson"),
                json.getString("capsuleSha256"),
            ),
            startedAtEpochMillis = json.getLong("startedAtEpochMillis"),
            updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
            endedAtEpochMillis = json.optLongOrNull("endedAtEpochMillis"),
            status = enumValueOf(json.getString("status")),
            steps = json.getJSONArray("steps").objects().map(::stepFromJson),
            recoveryAttempts = json.getJSONArray("recoveryAttempts").objects().map(::recoveryFromJson),
            artifacts = json.getJSONArray("artifacts").objects().map { artifact ->
                RoutineArtifactReference(
                    artifact.getString("artifactId"),
                    artifact.getString("mediaType"),
                    artifact.getString("sha256"),
                    artifact.getLong("createdAtEpochMillis"),
                    artifact.getBoolean("redacted"),
                )
            },
            completionEvidenceIds = json.getJSONArray("completionEvidenceIds").strings(),
            failureCode = json.optStringOrNull("failureCode"),
        )
    }

    private fun stepToMap(step: RoutineStepProgress): Map<String, Any?> = mapOf(
        "stepId" to step.stepId.value,
        "status" to step.status.name,
        "attempts" to step.attempts,
        "startedAtEpochMillis" to step.startedAtEpochMillis,
        "endedAtEpochMillis" to step.endedAtEpochMillis,
        "observations" to step.observations.map { observation ->
            mapOf(
                "evidenceId" to observation.evidenceId,
                "observedAtEpochMillis" to observation.observedAtEpochMillis,
                "redacted" to observation.redacted,
            )
        },
        "actions" to step.actions.map(::actionToMap),
        "verifications" to step.verifications.map { verification ->
            mapOf(
                "verificationId" to verification.verificationId,
                "passed" to verification.passed,
                "evidenceId" to verification.evidenceId,
                "verifiedAtEpochMillis" to verification.verifiedAtEpochMillis,
            )
        },
        "recoveryAttempts" to step.recoveryAttempts.map(::recoveryToMap),
    )

    private fun stepFromJson(json: JSONObject): RoutineStepProgress = RoutineStepProgress(
        stepId = RoutineStepId(json.getString("stepId")),
        status = enumValueOf(json.getString("status")),
        attempts = json.getInt("attempts"),
        startedAtEpochMillis = json.optLongOrNull("startedAtEpochMillis"),
        endedAtEpochMillis = json.optLongOrNull("endedAtEpochMillis"),
        observations = json.getJSONArray("observations").objects().map { observation ->
            RoutineObservationReference(
                observation.getString("evidenceId"),
                observation.getLong("observedAtEpochMillis"),
                observation.getBoolean("redacted"),
            )
        },
        actions = json.getJSONArray("actions").objects().map(::actionFromJson),
        verifications = json.getJSONArray("verifications").objects().map { verification ->
            RoutineVerificationRecord(
                verification.getString("verificationId"),
                verification.getBoolean("passed"),
                verification.getString("evidenceId"),
                verification.getLong("verifiedAtEpochMillis"),
            )
        },
        recoveryAttempts = json.getJSONArray("recoveryAttempts").objects().map(::recoveryFromJson),
    )

    private fun actionToMap(action: RoutineActionRecord): Map<String, Any?> = mapOf(
        "proposal" to mapOf(
            "capabilityId" to action.proposal.capabilityId.value,
            "operation" to action.proposal.operation,
            "arguments" to action.proposal.arguments.toSortedMap().mapValues { (_, value) -> argumentToMap(value) },
        ),
        "policyOutcome" to action.policyOutcome.name,
        "policyEvidenceId" to action.policyEvidenceId,
        "executionEvidenceId" to action.executionEvidenceId,
        "verificationEvidenceId" to action.verificationEvidenceId,
        "recordedAtEpochMillis" to action.recordedAtEpochMillis,
    )

    private fun actionFromJson(json: JSONObject): RoutineActionRecord {
        val proposal = json.getJSONObject("proposal")
        val arguments = proposal.getJSONObject("arguments")
        return RoutineActionRecord(
            proposal = RoutineActionProposal(
                CapabilityId(proposal.getString("capabilityId")),
                proposal.getString("operation"),
                arguments.keys().asSequence().associateWith { key -> argumentFromJson(arguments.getJSONObject(key)) },
            ),
            policyOutcome = enumValueOf(json.getString("policyOutcome")),
            policyEvidenceId = json.getString("policyEvidenceId"),
            executionEvidenceId = json.optStringOrNull("executionEvidenceId"),
            verificationEvidenceId = json.optStringOrNull("verificationEvidenceId"),
            recordedAtEpochMillis = json.getLong("recordedAtEpochMillis"),
        )
    }

    private fun argumentToMap(argument: RoutineArgument): Map<String, Any?> = when (argument) {
        is RoutineArgument.InputReference -> mapOf("kind" to "INPUT", "value" to argument.inputName)
        is RoutineArgument.SecretReference -> mapOf("kind" to "SECRET", "value" to argument.inputName)
        is RoutineArgument.NonSensitiveLiteral -> mapOf("kind" to "LITERAL", "value" to argument.value)
    }

    private fun argumentFromJson(json: JSONObject): RoutineArgument = when (json.getString("kind")) {
        "INPUT" -> RoutineArgument.InputReference(json.getString("value"))
        "SECRET" -> RoutineArgument.SecretReference(json.getString("value"))
        "LITERAL" -> RoutineArgument.NonSensitiveLiteral(json.getString("value"))
        else -> error("Unknown routine argument kind")
    }

    private fun recoveryToMap(attempt: RoutineRecoveryAttempt): Map<String, Any?> = mapOf(
        "stepId" to attempt.stepId.value,
        "ordinal" to attempt.ordinal,
        "primitive" to attempt.primitive.name,
        "outcome" to attempt.outcome.name,
        "evidenceId" to attempt.evidenceId,
        "attemptedAtEpochMillis" to attempt.attemptedAtEpochMillis,
    )

    private fun recoveryFromJson(json: JSONObject) = RoutineRecoveryAttempt(
        RoutineStepId(json.getString("stepId")),
        json.getInt("ordinal"),
        enumValueOf<RecoveryPrimitive>(json.getString("primitive")),
        enumValueOf(json.getString("outcome")),
        json.getString("evidenceId"),
        json.getLong("attemptedAtEpochMillis"),
    )
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
