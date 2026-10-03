package com.cyclone.mobile.automation.capsule

import com.cyclone.mobile.platform.capability.CapabilityId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class CapsuleSnapshot private constructor(
    val capsule: CycloneRoutineCapsule,
    val canonicalJson: String,
    val sha256: String,
) {
    override fun equals(other: Any?): Boolean = other is CapsuleSnapshot &&
        capsule == other.capsule && canonicalJson == other.canonicalJson && sha256 == other.sha256

    override fun hashCode(): Int = 31 * (31 * capsule.hashCode() + canonicalJson.hashCode()) + sha256.hashCode()

    override fun toString(): String =
        "CapsuleSnapshot(routineId=${capsule.routineId}, version=${capsule.routineVersion}, sha256=$sha256)"

    companion object {
        fun capture(capsule: CycloneRoutineCapsule): CapsuleSnapshot {
            val frozen = capsule.normalized()
            val canonical = RoutineCapsuleCodec.encode(frozen)
            return CapsuleSnapshot(frozen, canonical, sha256(canonical))
        }

        fun restore(canonicalJson: String, expectedSha256: String): CapsuleSnapshot {
            require(SHA_256.matches(expectedSha256))
            require(sha256(canonicalJson) == expectedSha256) { "Capsule snapshot hash mismatch" }
            val capsule = RoutineCapsuleCodec.decode(canonicalJson).normalized()
            val canonical = RoutineCapsuleCodec.encode(capsule)
            require(canonical == canonicalJson) { "Capsule snapshot is not canonical" }
            return CapsuleSnapshot(capsule, canonical, expectedSha256)
        }

        private val SHA_256 = Regex("[a-f0-9]{64}")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

object RoutineCapsuleCodec {
    fun encode(capsule: CycloneRoutineCapsule): String = CanonicalJson.write(toMap(capsule.normalized()))

    fun decode(raw: String): CycloneRoutineCapsule {
        val json = JSONObject(raw)
        return CycloneRoutineCapsule(
            schemaVersion = json.getInt("schemaVersion"),
            routineId = RoutineId(json.getString("routineId")),
            routineVersion = json.getJSONObject("routineVersion").let {
                RoutineVersion(it.getInt("major"), it.getInt("minor"), it.getInt("patch"))
            },
            intent = json.getString("intent"),
            inputs = json.getJSONArray("inputs").objects().map { input ->
                RoutineInput(
                    input.getString("name"),
                    enumValueOf(input.getString("type")),
                    input.getBoolean("required"),
                    input.getBoolean("sensitive"),
                    input.getString("description"),
                )
            },
            requiredCapabilities = json.getJSONArray("requiredCapabilities").strings().map(::CapabilityId).toSet(),
            requiredPackages = json.getJSONArray("requiredPackages").strings().toSet(),
            graph = graphFromJson(json.getJSONObject("graph")),
            verification = json.getJSONArray("verification").objects().map { verification ->
                RoutineVerification(
                    verification.getString("id"),
                    enumValueOf(verification.getString("kind")),
                    verification.getString("expectedReference"),
                    verification.getBoolean("required"),
                )
            },
            policyRequirements = json.getJSONArray("policyRequirements").objects().map { policy ->
                RoutinePolicyRequirement(
                    CapabilityId(policy.getString("capabilityId")),
                    policy.getString("policyCategory"),
                    enumValueOf(policy.getString("confirmation")),
                )
            },
            provenance = json.getJSONObject("provenance").let { provenance ->
                RoutineProvenance(
                    provenance.getString("sourceType"),
                    provenance.getString("sourceId"),
                    provenance.getLong("createdAtEpochMillis"),
                    provenance.getString("author"),
                )
            },
            compatibility = json.getJSONObject("compatibility").let { compatibility ->
                RoutineCompatibility(
                    compatibility.getInt("minimumCapsuleApi"),
                    compatibility.getInt("maximumCapsuleApiExclusive"),
                    compatibility.getInt("minimumAndroidApi"),
                )
            },
        )
    }

    private fun toMap(capsule: CycloneRoutineCapsule): Map<String, Any?> = mapOf(
        "schemaVersion" to capsule.schemaVersion,
        "routineId" to capsule.routineId.value,
        "routineVersion" to mapOf(
            "major" to capsule.routineVersion.major,
            "minor" to capsule.routineVersion.minor,
            "patch" to capsule.routineVersion.patch,
        ),
        "intent" to capsule.intent,
        "inputs" to capsule.inputs.map { input ->
            mapOf(
                "name" to input.name,
                "type" to input.type.name,
                "required" to input.required,
                "sensitive" to input.sensitive,
                "description" to input.description,
            )
        },
        "requiredCapabilities" to capsule.requiredCapabilities.map { it.value },
        "requiredPackages" to capsule.requiredPackages.toList(),
        "graph" to mapOf(
            "entryStepId" to capsule.graph.entryStepId.value,
            "maximumTransitions" to capsule.graph.maximumTransitions,
            "steps" to capsule.graph.steps.map(::stepToMap),
        ),
        "verification" to capsule.verification.map { verification ->
            mapOf(
                "id" to verification.id,
                "kind" to verification.kind.name,
                "expectedReference" to verification.expectedReference,
                "required" to verification.required,
            )
        },
        "policyRequirements" to capsule.policyRequirements.map { policy ->
            mapOf(
                "capabilityId" to policy.capabilityId.value,
                "policyCategory" to policy.policyCategory,
                "confirmation" to policy.confirmation.name,
            )
        },
        "provenance" to mapOf(
            "sourceType" to capsule.provenance.sourceType,
            "sourceId" to capsule.provenance.sourceId,
            "createdAtEpochMillis" to capsule.provenance.createdAtEpochMillis,
            "author" to capsule.provenance.author,
        ),
        "compatibility" to mapOf(
            "minimumCapsuleApi" to capsule.compatibility.minimumCapsuleApi,
            "maximumCapsuleApiExclusive" to capsule.compatibility.maximumCapsuleApiExclusive,
            "minimumAndroidApi" to capsule.compatibility.minimumAndroidApi,
        ),
    )

    private fun stepToMap(step: RoutineStep): Map<String, Any?> = mapOf(
        "id" to step.id.value,
        "name" to step.name,
        "kind" to step.kind.name,
        "action" to step.action?.let { action ->
            mapOf(
                "capabilityId" to action.capabilityId.value,
                "operation" to action.operation,
                "arguments" to action.arguments.mapValues { (_, argument) -> argumentToMap(argument) },
            )
        },
        "nextStepIds" to step.nextStepIds.map { it.value },
        "verificationIds" to step.verificationIds,
        "recovery" to mapOf(
            "maximumAttempts" to step.recovery.maximumAttempts,
            "sequence" to step.recovery.sequence.map { it.name },
        ),
    )

    private fun argumentToMap(argument: RoutineArgument): Map<String, Any?> = when (argument) {
        is RoutineArgument.InputReference -> mapOf("kind" to "INPUT", "value" to argument.inputName)
        is RoutineArgument.SecretReference -> mapOf("kind" to "SECRET", "value" to argument.inputName)
        is RoutineArgument.NonSensitiveLiteral -> mapOf("kind" to "LITERAL", "value" to argument.value)
    }

    private fun graphFromJson(json: JSONObject): RoutineGraph = RoutineGraph(
        entryStepId = RoutineStepId(json.getString("entryStepId")),
        steps = json.getJSONArray("steps").objects().map(::stepFromJson),
        maximumTransitions = json.getInt("maximumTransitions"),
    )

    private fun stepFromJson(json: JSONObject): RoutineStep {
        val recovery = json.getJSONObject("recovery")
        return RoutineStep(
            id = RoutineStepId(json.getString("id")),
            name = json.getString("name"),
            kind = enumValueOf(json.getString("kind")),
            action = json.optJSONObject("action")?.let { action ->
                val arguments = action.getJSONObject("arguments")
                RoutineActionProposal(
                    CapabilityId(action.getString("capabilityId")),
                    action.getString("operation"),
                    arguments.keys().asSequence().associateWith { key -> argumentFromJson(arguments.getJSONObject(key)) },
                )
            },
            nextStepIds = json.getJSONArray("nextStepIds").strings().map(::RoutineStepId),
            verificationIds = json.getJSONArray("verificationIds").strings(),
            recovery = RoutineRecoveryPlan(
                recovery.getInt("maximumAttempts"),
                recovery.getJSONArray("sequence").strings().map { enumValueOf(it) },
            ),
        )
    }

    private fun argumentFromJson(json: JSONObject): RoutineArgument = when (json.getString("kind")) {
        "INPUT" -> RoutineArgument.InputReference(json.getString("value"))
        "SECRET" -> RoutineArgument.SecretReference(json.getString("value"))
        "LITERAL" -> RoutineArgument.NonSensitiveLiteral(json.getString("value"))
        else -> error("Unknown routine argument kind")
    }
}

internal object CanonicalJson {
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> -> value.entries
            .map { (key, entryValue) -> require(key is String); key to entryValue }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
                "${JSONObject.quote(key)}:${write(entryValue)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { write(it) }
        else -> error("Unsupported canonical JSON value: ${value::class.simpleName}")
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
