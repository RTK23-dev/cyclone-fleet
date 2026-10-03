package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import org.json.JSONArray
import org.json.JSONObject

object RecoveryStateCodec {
    fun encode(state: RecoveryPersistentState): String = RecoveryCanonicalJson.write(stateToMap(state.normalized()))

    fun decode(raw: String): RecoveryPersistentState {
        val json = JSONObject(raw)
        return RecoveryPersistentState(
            schemaVersion = json.getInt("schemaVersion"),
            lastKnownGood = json.optObject("lastKnownGood")?.let(::snapshotFromJson),
            activeRuntime = json.optObject("activeRuntime")?.let(::runtimeFromJson),
            candidate = json.optObject("candidate")?.let(::candidateFromJson),
            pendingCommand = json.optObject("pendingCommand")?.let(::commandFromJson),
            quarantinedModules = json.getJSONArray("quarantinedModules").strings().map(::ModuleId).toSet(),
            safeModePlan = json.optObject("safeModePlan")?.let(::safeModeFromJson),
            consecutiveActiveCrashes = json.getInt("consecutiveActiveCrashes"),
            lastCrashAttribution = json.optObject("lastCrashAttribution")?.let(::attributionFromJson),
            journal = json.getJSONArray("journal").objects().map(::journalFromJson),
            nextSequence = json.getLong("nextSequence"),
        ).normalized()
    }

    private fun stateToMap(state: RecoveryPersistentState): Map<String, Any?> = mapOf(
        "schemaVersion" to state.schemaVersion,
        "lastKnownGood" to state.lastKnownGood?.let(::snapshotToMap),
        "activeRuntime" to state.activeRuntime?.let(::runtimeToMap),
        "candidate" to state.candidate?.let(::candidateToMap),
        "pendingCommand" to state.pendingCommand?.let(::commandToMap),
        "quarantinedModules" to state.quarantinedModules.sorted().map { it.value },
        "safeModePlan" to state.safeModePlan?.let(::safeModeToMap),
        "consecutiveActiveCrashes" to state.consecutiveActiveCrashes,
        "lastCrashAttribution" to state.lastCrashAttribution?.let(::attributionToMap),
        "journal" to state.journal.sortedBy { it.sequence }.map(::journalToMap),
        "nextSequence" to state.nextSequence,
    )

    private fun runtimeToMap(runtime: RuntimeIdentity): Map<String, Any?> = mapOf(
        "runtimeId" to runtime.runtimeId,
        "runtimeApiVersion" to runtime.runtimeApiVersion,
        "manifestSha256" to runtime.manifestSha256,
    )

    private fun runtimeFromJson(json: JSONObject) = RuntimeIdentity(
        json.getString("runtimeId"),
        json.getString("runtimeApiVersion"),
        json.getString("manifestSha256"),
    )

    private fun moduleToMap(module: RecoveryModuleSnapshot): Map<String, Any?> = mapOf(
        "moduleId" to module.moduleId.value,
        "version" to versionToMap(module.version),
        "enabled" to module.enabled,
        "essential" to module.essential,
    )

    private fun moduleFromJson(json: JSONObject) = RecoveryModuleSnapshot(
        ModuleId(json.getString("moduleId")),
        versionFromJson(json.getJSONObject("version")),
        json.getBoolean("enabled"),
        json.getBoolean("essential"),
    )

    private fun versionToMap(version: ModuleVersion) = mapOf(
        "major" to version.major,
        "minor" to version.minor,
        "patch" to version.patch,
    )

    private fun versionFromJson(json: JSONObject) = ModuleVersion(
        json.getInt("major"),
        json.getInt("minor"),
        json.getInt("patch"),
    )

    private fun schemaToMap(schema: RecoverySchemaVersion): Map<String, Any?> = mapOf(
        "schemaId" to schema.schemaId,
        "version" to schema.version,
    )

    private fun schemaFromJson(json: JSONObject) = RecoverySchemaVersion(
        json.getString("schemaId"),
        json.getInt("version"),
    )

    private fun snapshotToMap(snapshot: RecoverySnapshot): Map<String, Any?> = mapOf(
        "snapshotId" to snapshot.snapshotId,
        "capturedAtEpochMillis" to snapshot.capturedAtEpochMillis,
        "runtime" to runtimeToMap(snapshot.runtime),
        "configurationSha256" to snapshot.configurationSha256,
        "modules" to snapshot.modules.sortedBy { it.moduleId }.map(::moduleToMap),
        "schemas" to snapshot.schemas.sortedBy { it.schemaId }.map(::schemaToMap),
        "lastUpdateId" to snapshot.lastUpdateId,
    )

    private fun snapshotFromJson(json: JSONObject) = RecoverySnapshot(
        json.getString("snapshotId"),
        json.getLong("capturedAtEpochMillis"),
        runtimeFromJson(json.getJSONObject("runtime")),
        json.getString("configurationSha256"),
        json.getJSONArray("modules").objects().map(::moduleFromJson),
        json.getJSONArray("schemas").objects().map(::schemaFromJson),
        json.optStringOrNull("lastUpdateId"),
    ).normalized()

    private fun candidateToMap(candidate: CandidateRecoveryState): Map<String, Any?> = mapOf(
        "requestId" to candidate.requestId,
        "updateId" to candidate.updateId,
        "previousKnownGood" to snapshotToMap(candidate.previousKnownGood),
        "candidate" to snapshotToMap(candidate.candidate),
        "requestedAtEpochMillis" to candidate.requestedAtEpochMillis,
        "bootAttempts" to candidate.bootAttempts,
        "consecutiveHealthyObservations" to candidate.consecutiveHealthyObservations,
        "firstHealthyAtEpochMillis" to candidate.firstHealthyAtEpochMillis,
        "lastObservationId" to candidate.lastObservationId,
        "lastObservationAtEpochMillis" to candidate.lastObservationAtEpochMillis,
    )

    private fun candidateFromJson(json: JSONObject) = CandidateRecoveryState(
        requestId = json.getString("requestId"),
        updateId = json.getString("updateId"),
        previousKnownGood = snapshotFromJson(json.getJSONObject("previousKnownGood")),
        candidate = snapshotFromJson(json.getJSONObject("candidate")),
        requestedAtEpochMillis = json.getLong("requestedAtEpochMillis"),
        bootAttempts = json.getInt("bootAttempts"),
        consecutiveHealthyObservations = json.getInt("consecutiveHealthyObservations"),
        firstHealthyAtEpochMillis = json.optLongOrNull("firstHealthyAtEpochMillis"),
        lastObservationId = json.optStringOrNull("lastObservationId"),
        lastObservationAtEpochMillis = json.optLongOrNull("lastObservationAtEpochMillis"),
    )

    private fun safeModeToMap(plan: SafeModePlan): Map<String, Any?> = mapOf(
        "launcherComponents" to plan.launcherComponents,
        "trustedCore" to plan.trustedCore.sorted().map { it.name },
        "disabledOptionalModules" to plan.disabledOptionalModules.sorted().map { it.value },
        "preserveUserData" to plan.preserveUserData,
        "allowsAutomaticDataErase" to plan.allowsAutomaticDataErase,
    )

    private fun safeModeFromJson(json: JSONObject) = SafeModePlan(
        launcherComponents = json.getJSONArray("launcherComponents").strings(),
        trustedCore = json.getJSONArray("trustedCore").strings().map { enumValueOf<TrustedCoreService>(it) }.toSet(),
        disabledOptionalModules = json.getJSONArray("disabledOptionalModules").strings().map(::ModuleId).toSet(),
        preserveUserData = json.getBoolean("preserveUserData"),
        allowsAutomaticDataErase = json.getBoolean("allowsAutomaticDataErase"),
    ).normalized()

    private fun commandToMap(command: RecoveryCommand): Map<String, Any?> = when (command) {
        is RecoveryCommand.PromoteCandidate -> mapOf(
            "kind" to "PROMOTE_CANDIDATE",
            "commandId" to command.commandId,
            "issuedAtEpochMillis" to command.issuedAtEpochMillis,
            "preservesUserData" to command.preservesUserData,
            "updateId" to command.updateId,
            "candidateRuntime" to runtimeToMap(command.candidateRuntime),
        )
        is RecoveryCommand.RollbackRuntime -> mapOf(
            "kind" to "ROLLBACK_RUNTIME",
            "commandId" to command.commandId,
            "issuedAtEpochMillis" to command.issuedAtEpochMillis,
            "preservesUserData" to command.preservesUserData,
            "failedRuntime" to runtimeToMap(command.failedRuntime),
            "targetKnownGood" to runtimeToMap(command.targetKnownGood),
            "reason" to command.reason.name,
        )
        is RecoveryCommand.QuarantineOptionalModule -> mapOf(
            "kind" to "QUARANTINE_OPTIONAL_MODULE",
            "commandId" to command.commandId,
            "issuedAtEpochMillis" to command.issuedAtEpochMillis,
            "preservesUserData" to command.preservesUserData,
            "moduleId" to command.moduleId.value,
            "reason" to command.reason.name,
        )
        is RecoveryCommand.EnterSafeMode -> mapOf(
            "kind" to "ENTER_SAFE_MODE",
            "commandId" to command.commandId,
            "issuedAtEpochMillis" to command.issuedAtEpochMillis,
            "preservesUserData" to command.preservesUserData,
            "plan" to safeModeToMap(command.plan),
            "reason" to command.reason.name,
        )
    }

    private fun commandFromJson(json: JSONObject): RecoveryCommand {
        val commonId = json.getString("commandId")
        val issuedAt = json.getLong("issuedAtEpochMillis")
        val preserves = json.getBoolean("preservesUserData")
        return when (json.getString("kind")) {
            "PROMOTE_CANDIDATE" -> RecoveryCommand.PromoteCandidate(
                commonId,
                issuedAt,
                json.getString("updateId"),
                runtimeFromJson(json.getJSONObject("candidateRuntime")),
                preserves,
            )
            "ROLLBACK_RUNTIME" -> RecoveryCommand.RollbackRuntime(
                commonId,
                issuedAt,
                runtimeFromJson(json.getJSONObject("failedRuntime")),
                runtimeFromJson(json.getJSONObject("targetKnownGood")),
                enumValueOf(json.getString("reason")),
                preserves,
            )
            "QUARANTINE_OPTIONAL_MODULE" -> RecoveryCommand.QuarantineOptionalModule(
                commonId,
                issuedAt,
                ModuleId(json.getString("moduleId")),
                enumValueOf(json.getString("reason")),
                preserves,
            )
            "ENTER_SAFE_MODE" -> RecoveryCommand.EnterSafeMode(
                commonId,
                issuedAt,
                safeModeFromJson(json.getJSONObject("plan")),
                enumValueOf(json.getString("reason")),
                preserves,
            )
            else -> error("Unknown recovery command kind")
        }.also { it.validate() }
    }

    private fun attributionToMap(attribution: CrashAttribution): Map<String, Any?> = mapOf(
        "previousActiveRuntime" to runtimeToMap(attribution.previousActiveRuntime),
        "moduleSet" to attribution.moduleSet.sortedBy { it.moduleId }.map(::moduleToMap),
        "schemas" to attribution.schemas.sortedBy { it.schemaId }.map(::schemaToMap),
        "lastUpdateId" to attribution.lastUpdateId,
        "bootAttempts" to attribution.bootAttempts,
        "safeFailureReason" to attribution.safeFailureReason.name,
        "recordedAtEpochMillis" to attribution.recordedAtEpochMillis,
    )

    private fun attributionFromJson(json: JSONObject) = CrashAttribution(
        previousActiveRuntime = runtimeFromJson(json.getJSONObject("previousActiveRuntime")),
        moduleSet = json.getJSONArray("moduleSet").objects().map(::moduleFromJson),
        schemas = json.getJSONArray("schemas").objects().map(::schemaFromJson),
        lastUpdateId = json.optStringOrNull("lastUpdateId"),
        bootAttempts = json.getInt("bootAttempts"),
        safeFailureReason = enumValueOf(json.getString("safeFailureReason")),
        recordedAtEpochMillis = json.getLong("recordedAtEpochMillis"),
    ).normalized()

    private fun journalToMap(entry: RecoveryJournalEntry): Map<String, Any?> = mapOf(
        "sequence" to entry.sequence,
        "event" to entry.event.name,
        "occurredAtEpochMillis" to entry.occurredAtEpochMillis,
        "updateId" to entry.updateId,
        "runtimeId" to entry.runtimeId,
        "moduleId" to entry.moduleId?.value,
        "commandId" to entry.commandId,
        "reason" to entry.reason?.name,
    )

    private fun journalFromJson(json: JSONObject) = RecoveryJournalEntry(
        sequence = json.getLong("sequence"),
        event = enumValueOf(json.getString("event")),
        occurredAtEpochMillis = json.getLong("occurredAtEpochMillis"),
        updateId = json.optStringOrNull("updateId"),
        runtimeId = json.optStringOrNull("runtimeId"),
        moduleId = json.optStringOrNull("moduleId")?.let(::ModuleId),
        commandId = json.optStringOrNull("commandId"),
        reason = json.optStringOrNull("reason")?.let { enumValueOf<RecoveryFailureReason>(it) },
    )
}

internal object RecoveryCanonicalJson {
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
        else -> error("Unsupported recovery JSON value: ${value::class.simpleName}")
    }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONObject.optObject(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
