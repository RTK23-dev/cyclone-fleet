package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.mapping.session.MappingBudget
import com.cyclone.mobile.mapping.session.MappingJob
import com.cyclone.mobile.mapping.session.MappingPlaneRequest
import com.cyclone.mobile.mapping.session.MappingProgress
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionRuntime
import com.cyclone.mobile.mapping.session.MappingStartRequest
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlane
import org.json.JSONObject

/**
 * Run-2 mapping control-plane gateway.
 *
 * Android owns all job state and Atlas diff history. This adapter only validates wire shapes and
 * commands the phone-local MappingSessionRuntime.
 */
internal object GatewayV5MappingAdapter {
    private val jobId = Regex("^[A-Za-z0-9_-]{8,120}$")
    private val secretField = Regex(
        "(?i)^(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(text|value))$",
    )

    /** Replaceable for JVM tests; production launches the phone mapping driver. */
    @Volatile
    internal var driverLauncher: (Context, MappingJob, Boolean) -> Unit = { context, job, freshStart ->
        com.cyclone.mobile.mapping.run.MappingDriverRuntime.launch(context, job, freshStart)
    }

    fun dispatch(context: Context, op: String, args: JSONObject): JSONObject = try {
        when (op) {
            "atlas.diff" -> atlasDiff(context, args)
            "mapping.start" -> mappingStart(context, args)
            "mapping.pause" -> mappingPause(context, args)
            "mapping.stop" -> mappingStop(context, args)
            "mapping.status" -> mappingStatus(context, args)
            else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported Run-2 mapping operation: " + op)
        }
    } catch (error: MappingSessionException) {
        throw GatewayProtocolException(error.code, error.message)
    } catch (error: SessionIdentityException) {
        throw GatewayProtocolException(error.errorClass, error.message ?: "Invalid mapping session identity.")
    } catch (error: IllegalArgumentException) {
        throw GatewayProtocolException("INVALID_REQUEST", error.message ?: "Invalid mapping request.")
    }

    private fun atlasDiff(context: Context, args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona", "since"))
        val placeId = requiredString(args, "placeId", 512)
        val persona = requiredPersona(args)
        val since = when (val raw = args.opt("since")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: throw MappingSessionException("INVALID_REQUEST", "since must be a bounded phone-issued cursor.")
            else -> throw MappingSessionException("INVALID_REQUEST", "since must be a phone-issued cursor or null.")
        }
        return MappingSessionRuntime.controller(context).atlasDiff(placeId, persona, since).toJson()
    }

    private fun mappingStart(context: Context, args: JSONObject): JSONObject {
        val allowed = setOf(
            "placeId", "persona", "sessionId", "displayId",
            "workspaceId", "workspaceGeneration", "executionGeneration",
            "budget", "resumeJobId", "identity", "describer", "deeper",
        )
        requireOnly(args, allowed)
        val describer = describer(args)
        val wantsDeeper = deeper(args)
        val plane = planeRequest(args)
        val controller = MappingSessionRuntime.controller(context)
        val resumeJobId = optionalJobId(args, "resumeJobId")
        val job = if (resumeJobId != null) {
            if (args.has("placeId") || args.has("persona") || args.has("budget") || args.has("identity") || args.has("describer") || args.has("deeper")) {
                throw MappingSessionException(
                    "INVALID_REQUEST",
                    "mapping.start resume accepts resumeJobId plus phone-plane identity only.",
                )
            }
            controller.resume(resumeJobId, plane)
        } else {
            val request = MappingStartRequest(
                placeId = requiredString(args, "placeId", 512),
                persona = requiredPersona(args),
                plane = plane,
                budget = args.optJSONObject("budget")?.let(::budget) ?: MappingBudget.DEFAULT,
                identity = when (val raw = args.opt("identity")) {
                    null, JSONObject.NULL -> com.cyclone.mobile.mapping.crawl.MappingIdentity.OWN
                    is String -> com.cyclone.mobile.mapping.crawl.MappingIdentity.fromWire(raw)
                        ?: throw MappingSessionException("INVALID_REQUEST", "identity must be own or test.")
                    else -> throw MappingSessionException("INVALID_REQUEST", "identity must be own or test.")
                },
            )
            controller.start(request)
        }
        if (resumeJobId == null) {
            // Map deeper (plan 36 §5.5): the words of the goals the manual could not answer, from the phone's own quiz.
            val focus = if (!wantsDeeper) emptyList() else job.placeId.removePrefix("package:").let { pkg ->
                com.cyclone.mobile.manual.SelfQuiz.focusWords(com.cyclone.mobile.manual.ManualRuntime.dictionary(context, pkg).quiz)
            }
            com.cyclone.mobile.manual.ManualRuntime.configure(job.mappingJobId, describer, focus)
        }
        // mapping.start owns the job; the phone driver walks it. Without this the job would idle.
        driverLauncher(context, job, resumeJobId == null)
        return jobJson(controller.status(job.mappingJobId) ?: job)
    }

    /**
     * Which model decides for this pass (plan 36 §9): `{"model": "phone"}` is the phone's current model, resolved when
     * the pass starts; any other value must be a model id. The key never leaves the phone.
     */
    private fun deeper(args: JSONObject): Boolean = when (val raw = args.opt("deeper")) {
        null, JSONObject.NULL -> false
        is Boolean -> raw
        else -> throw MappingSessionException("INVALID_REQUEST", "deeper must be true or false.")
    }

    private fun describer(args: JSONObject): String? {
        if (!args.has("describer") || args.isNull("describer")) return null
        val value = args.optJSONObject("describer") ?: throw MappingSessionException("INVALID_REQUEST", "describer must be an object.")
        val extra = value.keys().asSequence().filter { it != "model" }.toList()
        if (extra.isNotEmpty()) throw MappingSessionException("INVALID_REQUEST", "describer accepts only model.")
        val model = value.optString("model").trim()
        if (model.isEmpty() || model.length > 200 || !Regex("^[A-Za-z0-9._:/~-]+$").matches(model)) {
            throw MappingSessionException("INVALID_REQUEST", "describer.model must be \"phone\" or a model id.")
        }
        return model
    }

    private fun mappingPause(context: Context, args: JSONObject): JSONObject {
        requireOnly(
            args,
            setOf("mappingJobId", "sessionId", "displayId", "workspaceId", "workspaceGeneration"),
        )
        val controller = MappingSessionRuntime.controller(context)
        val id = requiredJobId(args)
        val plane = planeRequest(args, allowExecutionGeneration = false)
        val current = controller.status(id)
            ?: throw MappingSessionException("MAPPING_JOB_NOT_FOUND", "Unknown mapping job.")
        requireSamePlane(current, classify(plane))
        return jobJson(controller.pause(id))
    }

    private fun mappingStop(context: Context, args: JSONObject): JSONObject {
        requireOnly(
            args,
            setOf("mappingJobId", "sessionId", "displayId", "workspaceId", "workspaceGeneration"),
        )
        val controller = MappingSessionRuntime.controller(context)
        val id = requiredJobId(args)
        val plane = planeRequest(args, allowExecutionGeneration = false)
        val current = controller.status(id)
            ?: throw MappingSessionException("MAPPING_JOB_NOT_FOUND", "Unknown mapping job.")
        requireSamePlane(current, classify(plane))
        return jobJson(controller.stop(id))
    }

    private fun mappingStatus(context: Context, args: JSONObject): JSONObject {
        requireOnly(
            args,
            setOf("mappingJobId", "sessionId", "displayId", "workspaceId", "workspaceGeneration"),
        )
        val planeRequest = planeRequest(args, allowExecutionGeneration = false)
        val plane = classify(planeRequest)
        val controller = MappingSessionRuntime.controller(context)
        val requestedId = optionalJobId(args, "mappingJobId")
        val job = if (requestedId != null) {
            controller.status(requestedId)?.also { requireSamePlane(it, plane) }
                ?: throw MappingSessionException("MAPPING_JOB_NOT_FOUND", "Unknown mapping job.")
        } else {
            controller.statusForPlane(planeRequest)
        }
        return job?.let(::jobJson) ?: idleJson(plane)
    }

    private fun planeRequest(
        args: JSONObject,
        allowExecutionGeneration: Boolean = true,
    ): MappingPlaneRequest {
        val sessionId = requiredString(args, "sessionId", 160)
        val display = args.opt("displayId")
        if (display !is Number || display.toDouble() != display.toInt().toDouble()) {
            throw MappingSessionException("SESSION_DISPLAY_MISMATCH", "displayId is required and must be an integer.")
        }
        val workspaceId = optionalString(args, "workspaceId", 80)
        val workspaceGeneration = optionalLong(args, "workspaceGeneration")
        val executionGeneration = if (allowExecutionGeneration) {
            optionalLong(args, "executionGeneration")
        } else {
            if (args.has("executionGeneration")) {
                throw MappingSessionException("INVALID_REQUEST", "executionGeneration is not accepted by this operation.")
            }
            null
        }
        return MappingPlaneRequest(
            sessionId = sessionId,
            displayId = display.toInt(),
            workspaceId = workspaceId,
            workspaceGeneration = workspaceGeneration,
            executionGeneration = executionGeneration,
        )
    }

    private fun classify(request: MappingPlaneRequest): SessionPlane {
        val json = JSONObject()
            .put("sessionId", request.sessionId)
            .put("displayId", request.displayId)
        request.workspaceId?.let { json.put("workspaceId", it) }
        request.workspaceGeneration?.let { json.put("workspaceGeneration", it) }
        return SessionContract.requireUi(json)
    }

    private fun requireSamePlane(job: MappingJob, requested: SessionPlane) {
        val bound = job.lease.plane
        if (bound.kind != requested.kind ||
            bound.sessionId != requested.sessionId ||
            bound.displayId != requested.displayId ||
            bound.workspaceId != requested.workspaceId
        ) {
            throw MappingSessionException(
                "SESSION_DISPLAY_MISMATCH",
                "Mapping command does not target the job's bound phone plane.",
            )
        }
    }

    private fun budget(json: JSONObject): MappingBudget {
        val expected = setOf(
            "maxNewScreens",
            "maxElapsedMs",
            "maxConsecutiveNonProgress",
            "maxAttemptsPerDoor",
        )
        if (json.keys().asSequence().toSet() != expected) {
            throw MappingSessionException("INVALID_REQUEST", "Mapping budget must contain the four exact bounded fields.")
        }
        return MappingBudget(
            maxNewScreens = exactInt(json, "maxNewScreens"),
            maxElapsedMs = exactLong(json, "maxElapsedMs"),
            maxConsecutiveNonProgress = exactInt(json, "maxConsecutiveNonProgress"),
            maxAttemptsPerDoor = exactInt(json, "maxAttemptsPerDoor"),
        )
    }

    private fun jobJson(job: MappingJob): JSONObject = JSONObject()
        .put("mappingJobId", job.mappingJobId)
        .put("placeId", job.placeId)
        .put("persona", job.persona)
        .put("state", job.state.wireValue)
        .put("sessionId", job.lease.plane.sessionId)
        .put("displayId", job.lease.plane.displayId)
        .put("plane", job.lease.plane.toJson())
        .put("controlRevision", job.lease.controlRevision)
        .put("executionGeneration", job.lease.executionGeneration ?: JSONObject.NULL)
        .put("budget", budgetJson(job.budget))
        .put("currentAtlasNodeId", job.currentAtlasNodeId ?: JSONObject.NULL)
        .put("progress", progressJson(job.progress))
        .put("atlasStatus", job.atlasStatus?.wireValue ?: JSONObject.NULL)
        .put("danger", job.danger?.wireValue ?: JSONObject.NULL)
        .put("boundary", job.boundary?.wireValue ?: JSONObject.NULL)
        .put("startedAtEpochMs", job.startedAtEpochMs)
        .put("updatedAtEpochMs", job.updatedAtEpochMs)
        .put("failureCode", job.failureCode ?: JSONObject.NULL)
        .put("identity", job.identity.wire)

    private fun idleJson(plane: SessionPlane): JSONObject = JSONObject()
        .put("mappingJobId", JSONObject.NULL)
        .put("placeId", JSONObject.NULL)
        .put("persona", JSONObject.NULL)
        .put("state", "idle")
        .put("sessionId", plane.sessionId)
        .put("displayId", plane.displayId)
        .put("plane", plane.toJson())
        .put("controlRevision", JSONObject.NULL)
        .put("executionGeneration", JSONObject.NULL)
        .put("budget", JSONObject.NULL)
        .put("currentAtlasNodeId", JSONObject.NULL)
        .put("progress", progressJson(MappingProgress()))
        .put("atlasStatus", JSONObject.NULL)
        .put("danger", JSONObject.NULL)
        .put("boundary", JSONObject.NULL)
        .put("startedAtEpochMs", JSONObject.NULL)
        .put("updatedAtEpochMs", JSONObject.NULL)
        .put("failureCode", JSONObject.NULL)
        .put("identity", JSONObject.NULL)

    private fun budgetJson(value: MappingBudget): JSONObject = JSONObject()
        .put("maxNewScreens", value.maxNewScreens)
        .put("maxElapsedMs", value.maxElapsedMs)
        .put("maxConsecutiveNonProgress", value.maxConsecutiveNonProgress)
        .put("maxAttemptsPerDoor", value.maxAttemptsPerDoor)

    private fun progressJson(value: MappingProgress): JSONObject = JSONObject()
        .put("newScreens", value.newScreens)
        .put("verifiedMutations", value.verifiedMutations)
        .put("consecutiveNonProgress", value.consecutiveNonProgress)
        .put("attemptedDoors", value.attemptedDoors)
        .put("remainingDarkRegions", value.remainingDarkRegions)

    private fun requiredPersona(args: JSONObject): String {
        val value = requiredString(args, "persona", 16)
        if (value != "live" && value != "mapping") {
            throw MappingSessionException("INVALID_REQUEST", "persona must be live or mapping.")
        }
        return value
    }

    private fun requiredJobId(args: JSONObject): String =
        optionalJobId(args, "mappingJobId")
            ?: throw MappingSessionException("INVALID_REQUEST", "mappingJobId is required.")

    private fun optionalJobId(args: JSONObject, key: String): String? {
        val value = optionalString(args, key, 120) ?: return null
        if (!jobId.matches(value)) {
            throw MappingSessionException("INVALID_REQUEST", key + " is invalid.")
        }
        return value
    }

    private fun requiredString(args: JSONObject, key: String, max: Int): String =
        optionalString(args, key, max)
            ?: throw MappingSessionException("SESSION_REQUIRED".takeIf { key == "sessionId" } ?: "INVALID_REQUEST", key + " is required.")

    private fun optionalString(args: JSONObject, key: String, max: Int): String? {
        if (!args.has(key) || args.opt(key) === JSONObject.NULL) return null
        val value = args.opt(key)
        if (value !is String || value.isBlank() || value.length > max) {
            throw MappingSessionException("INVALID_REQUEST", key + " must be a bounded string.")
        }
        return value
    }

    private fun optionalLong(args: JSONObject, key: String): Long? {
        if (!args.has(key) || args.opt(key) === JSONObject.NULL) return null
        val value = args.opt(key)
        if (value !is Number || value.toDouble() != value.toLong().toDouble() || value.toLong() < 0L) {
            throw MappingSessionException("INVALID_REQUEST", key + " must be a non-negative integer.")
        }
        return value.toLong()
    }

    private fun exactInt(json: JSONObject, key: String): Int {
        val value = json.opt(key)
        if (value !is Number || value.toDouble() != value.toInt().toDouble()) {
            throw MappingSessionException("INVALID_REQUEST", key + " must be an integer.")
        }
        return value.toInt()
    }

    private fun exactLong(json: JSONObject, key: String): Long {
        val value = json.opt(key)
        if (value !is Number || value.toDouble() != value.toLong().toDouble()) {
            throw MappingSessionException("INVALID_REQUEST", key + " must be an integer.")
        }
        return value.toLong()
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extras = args.keys().asSequence().filter { it !in allowed }.toList()
        val secret = extras.firstOrNull { secretField.matches(it) }
        if (secret != null) {
            throw GatewayProtocolException("SECRET_PAYLOAD_REJECTED", "Secret-bearing mapping payload rejected.")
        }
        if (extras.isNotEmpty()) {
            throw MappingSessionException("INVALID_REQUEST", "Unexpected Run-2 mapping field.")
        }
    }
}
