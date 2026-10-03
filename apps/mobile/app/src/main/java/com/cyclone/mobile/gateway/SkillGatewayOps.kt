package com.cyclone.mobile.gateway

import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.skill.RankedSelector
import com.cyclone.mobile.automation.skill.SkillCompileInput
import com.cyclone.mobile.automation.skill.SkillCompiler
import com.cyclone.mobile.automation.skill.SkillDraftSink
import com.cyclone.mobile.automation.skill.SkillDraftSinkResult
import com.cyclone.mobile.automation.skill.SkillSecrets
import com.cyclone.mobile.automation.skill.SkillStatusMarker
import com.cyclone.mobile.automation.skill.SkillStepDraft
import com.cyclone.mobile.automation.skill.SkillVerifier
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.policy.GateDecision
import com.cyclone.mobile.policy.GatePolicy
import com.cyclone.mobile.policy.PcGateEnvelope
import com.cyclone.mobile.policy.PolicyPrincipal
import org.json.JSONArray
import org.json.JSONObject

fun interface SkillStepExecutor {
    fun execute(commandId: String, tool: String, params: JSONObject): SkillStepOutcome
}

data class SkillStepOutcome(
    val ok: Boolean,
    val pageChanged: Boolean? = null,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val errorClass: String? = null,
    val generation: String? = null,
    val before: JSONObject? = null,
    val after: JSONObject? = null,
    val delta: JSONObject? = null,
)

/**
 * Phone-side gateway ops for `skill.compile` / `skill.run` / `skill.match`.
 *
 * Compile lands through [SkillDraftSink.saveFromMcp] → [SkillCompiler.compile] into the existing
 * [AutomationStore]. Live run uses a single [SkillStepExecutor] bound to PhoneToolExecutor.
 * GATE pay/send/delete/grant stays phone-side; PC autoApprove is ignored.
 */
internal class SkillGatewayOps(
    private val store: AutomationStore,
    private val sink: SkillDraftSink,
    private val gate: GatePolicy,
    private val principal: PolicyPrincipal,
    private val stepExecutor: SkillStepExecutor? = null,
    private val observePage: (() -> JSONObject?)? = null,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun dispatch(request: GatewayRequest): JSONObject = when (request.op) {
        "skill.compile" -> compile(request.args)
        "skill.run" -> run(request.args)
        "skill.match" -> match(request.args)
        else -> throw GatewayProtocolException(
            "PROTOCOL_MISMATCH",
            "Unsupported skill operation: ${request.op}",
            request.id,
        )
    }

    fun compile(args: JSONObject): JSONObject {
        val requestedStatus = args.optString("status").trim().ifBlank { "draft" }
        val pcEnvelope = pcEnvelopeFrom(args, requestedStatus)
        val input = compileInputFrom(args)
        return when (val result = sink.saveFromMcp(input, pcEnvelope, input.nowEpochMillis)) {
            is SkillDraftSinkResult.DraftWritten -> {
                val capsule = result.capsule
                val skill = JSONObject()
                    .put("id", capsule.id)
                    .put("storeClass", STORE_CLASS)
                    .put("status", "draft")
                    .put("enabled", false)
                    .put("goal", capsule.goal)
                    .put("app", capsule.app)
                    .put("pageKey", capsule.whenPage.pageKey)
                    .put("compiler", SkillCompiler.COMPILE_FUNCTION)
                JSONObject()
                    .put("ok", true)
                    .put("written", true)
                    .put("status", "draft")
                    .put("enabled", false)
                    .put("storeClass", STORE_CLASS)
                    .put("compiler", SkillCompiler.COMPILE_FUNCTION)
                    .put("skill", skill)
                    .put("skillId", capsule.id)
                    .put("source", SOURCE)
            }
            is SkillDraftSinkResult.Rejected -> compileDenied(
                errorClass = compileErrorClass(result.reason),
                message = result.reason,
            )
            is SkillDraftSinkResult.GateDenied -> JSONObject()
                .put("ok", false)
                .put("written", false)
                .put("status", JSONObject.NULL)
                .put("enabled", false)
                .put("storeClass", STORE_CLASS)
                .put("compiler", SkillCompiler.COMPILE_FUNCTION)
                .put("skill", JSONObject.NULL)
                .put("errorClass", "GATE_DENIED")
                .put("gateClass", result.gateClass.jsonKey)
                .put("ignoredPcAutoApprove", result.ignoredPcAutoApprove)
                .put("mutationAllowed", false)
                .put("error", JSONObject()
                    .put("code", "GATE_DENIED")
                    .put("layer", "policy")
                    .put("message", "GATE ${result.gateClass.jsonKey} denied. PC autoApprove is ignored."))
            SkillDraftSinkResult.Ignored -> compileDenied("COMPILE_REJECTED", "compile produced no write")
        }
    }

    fun run(args: JSONObject): JSONObject {
        val skillId = args.optString("skillId").ifBlank { args.optString("skill_id") }.trim()
        if (skillId.isBlank()) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "skillId is required")
        }
        val dryRun = args.optBoolean("dryRun", false) || args.optBoolean("dry_run", false)
        val overlayParams = SkillSecrets.strip(args.optJSONObject("params").stringMap())
        val pcEnvelope = pcEnvelopeFrom(args, args.optString("status"))
        val automation = store.getAutomation(skillId)
            ?: return runDenied(skillId, dryRun, "SKILL_NOT_FOUND", "Skill is not in AutomationStore", "draft")
        val status = SkillStatusMarker.statusOf(automation)
        val verified = SkillStatusMarker.isVerified(automation)
        if (!verified && !dryRun) {
            return runDenied(
                skillId = skillId,
                dryRun = false,
                errorClass = "DRAFT_RUN_DENIED",
                message = "Draft skills cannot run live. Pass dryRun=true or wait until the skill is verified on the phone.",
                status = status,
            )
        }

        automation.steps.forEachIndexed { index, step ->
            val tool = step.parameters["tool"].orEmpty().ifBlank { "phone.click" }
            val labels = stepLabels(step)
            val classified = GateClassifier.classify(tool, labels) ?: return@forEachIndexed
            val decision = gate.evaluate(
                actionId = "gate:skill-run:step-$index:${classified.jsonKey}",
                action = step.name.ifBlank { tool },
                labels = labels,
                packageName = SkillStatusMarker.appOf(automation).ifBlank { null },
                principal = principal,
                requestedAtEpochMillis = nowEpochMillis(),
                pcEnvelope = pcEnvelope,
                gateClass = classified,
            )
            if (decision is GateDecision.Blocked) {
                return runResult(
                    skillId = skillId,
                    status = status,
                    dryRun = dryRun,
                    ok = false,
                    mutated = false,
                    envelopes = JSONArray().put(gateDeniedEnvelope(index, tool, decision)),
                    errorClass = "GATE_DENIED",
                    gateClass = decision.gateClass,
                    ignoredPcAutoApprove = decision.ignoredPcAutoApprove,
                    message = "GATE ${decision.gateClass.jsonKey} denied. PC autoApprove is ignored.",
                )
            }
        }

        val envelopes = JSONArray()
        var mutated = false
        automation.steps.forEachIndexed { index, step ->
            val tool = step.parameters["tool"].orEmpty().ifBlank { "phone.click" }
            if (dryRun) {
                envelopes.put(plannedEnvelope(index, step, tool))
                return@forEachIndexed
            }
            val executor = stepExecutor
                ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "PhoneToolExecutor is unavailable for skill.run")
            val beforeCard = observePage?.invoke()
            val outcome = executor.execute("skill-run:$skillId:${step.id}", tool, paramsFor(step, overlayParams))
            mutated = true
            val afterCard = observePage?.invoke()
            envelopes.put(liveEnvelope(index, tool, outcome, beforeCard, afterCard))
            if (!outcome.ok) {
                return runResult(
                    skillId = skillId,
                    status = status,
                    dryRun = false,
                    ok = false,
                    mutated = true,
                    envelopes = envelopes,
                    errorClass = outcome.errorClass ?: "SKILL_RUN_FAILED",
                    message = "Skill step $index failed",
                )
            }
        }
        return runResult(
            skillId = skillId,
            status = status,
            dryRun = dryRun,
            ok = true,
            mutated = mutated,
            envelopes = envelopes,
        )
    }

    fun match(args: JSONObject): JSONObject {
        val goal = args.optString("goal").trim()
        val pageKey = args.optString("pageKey").ifBlank { args.optString("page_key") }.trim()
        val matched = store.listAutomations().firstOrNull { SkillStatusMarker.matchesVerified(it, goal, pageKey) }
        if (matched == null) {
            return JSONObject()
                .put("ok", true)
                .put("matched", false)
                .put("skipModel", false)
                .put("storeClass", STORE_CLASS)
                .put("skill", JSONObject.NULL)
        }
        val skill = JSONObject()
            .put("id", matched.id)
            .put("skillId", matched.id)
            .put("status", "verified")
            .put("goal", SkillStatusMarker.goalOf(matched))
            .put("pageKey", SkillStatusMarker.pageKeyOf(matched).ifBlank { pageKey })
            .put("app", SkillStatusMarker.appOf(matched))
            .put("storeClass", STORE_CLASS)
            .put("enabled", false)
            .put("skipModel", true)
            .put("next", "A verified skill matches this goal and pageKey. Call phone_skill_run and skip the model.")
        return JSONObject()
            .put("ok", true)
            .put("matched", true)
            .put("skipModel", true)
            .put("storeClass", STORE_CLASS)
            .put("skill", skill)
    }

    private fun compileInputFrom(args: JSONObject): SkillCompileInput {
        val stepsJson = args.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(index) ?: continue
                add(parseStep(item, index))
            }
        }
        return SkillCompileInput(
            app = args.optString("app").ifBlank { args.optString("package") }.trim(),
            goal = args.optString("goal").trim(),
            startPageKey = args.optString("pageKey").ifBlank { args.optString("page_key") }.trim(),
            steps = steps,
            params = SkillSecrets.strip(args.optJSONObject("params").stringMap()),
            nowEpochMillis = args.optLong("nowEpochMillis").takeIf { it > 0L } ?: nowEpochMillis(),
        )
    }

    private fun parseStep(step: JSONObject, index: Int): SkillStepDraft {
        val envelope = step.optJSONObject("envelope") ?: step
        val then = step.optJSONObject("then") ?: JSONObject()
        val whenLoc = step.optJSONObject("when") ?: JSONObject()
        val check = step.optJSONObject("check") ?: JSONObject()
        val tool = step.optString("tool").ifBlank {
            step.optString("action").ifBlank { then.optString("tool") }
        }.ifBlank { "phone.click" }
        val beforePageKey = step.optString("beforePageKey").ifBlank {
            whenLoc.optString("pageKey").ifBlank { envelope.optJSONObject("before")?.optString("pageKey").orEmpty() }
        }.trim().ifBlank { null }
        val afterPageKey = step.optString("afterPageKey").ifBlank {
            check.optString("pageKey").ifBlank {
                val after = envelope.opt("after")
                when (after) {
                    is JSONObject -> after.optJSONObject("pageCard")?.optString("pageKey")
                        ?.ifBlank { after.optString("pageKey") }.orEmpty()
                    else -> ""
                }
            }
        }.trim().ifBlank { null }
        val selectorObject = step.optJSONObject("selector") ?: then.optJSONObject("selector")
        val selectors = selectorsFrom(step.optJSONArray("selectors"), selectorObject)
        val params = SkillSecrets.strip(
            (step.optJSONObject("params") ?: then.optJSONObject("params")).stringMap(),
        )
        val verified = step.optBoolean("verified", false) ||
            step.optBoolean("ok", false) ||
            (envelope.optBoolean("ok", false) && envelope.optString("errorClass").isBlank())
        val whenClause = step.optString("whenClause").ifBlank {
            beforePageKey?.let { "When on $it" }.orEmpty()
        }
        val thenClause = step.optString("thenClause").ifBlank {
            step.optString("name").ifBlank { "Then $tool" }
        }
        val checkClause = step.optString("checkClause").ifBlank {
            afterPageKey?.let { "Check $it" }.orEmpty()
        }
        return SkillStepDraft(
            whenClause = whenClause,
            thenClause = thenClause,
            checkClause = checkClause,
            action = tool,
            selectors = selectors,
            verifiers = listOfNotNull(afterPageKey?.let { SkillVerifier(afterPageKey = it) }),
            params = params,
            beforePageKey = beforePageKey,
            afterPageKey = afterPageKey,
            verified = verified,
            evidenceTrace = step.optString("evidenceTrace").ifBlank { envelope.optString("generation") }.ifBlank { "mcp-step-$index" },
        )
    }

    private fun selectorsFrom(array: JSONArray?, selector: JSONObject?): List<RankedSelector> {
        val ranked = mutableListOf<RankedSelector>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val kind = item.optString("kind").ifBlank { item.optString("type") }
                val value = item.optString("value").ifBlank { item.optString("text") }
                val confidence = item.optDouble("confidence", 0.8).coerceIn(0.0, 1.0)
                if (kind.isNotBlank() && value.isNotBlank()) {
                    ranked += RankedSelector(kind, value, confidence)
                }
            }
        }
        if (selector != null) {
            selector.optString("text").takeIf { it.isNotBlank() }?.let { ranked += RankedSelector("text", it, 0.9) }
            selector.optString("resourceId").takeIf { it.isNotBlank() }?.let { ranked += RankedSelector("resourceId", it, 0.8) }
            selector.optString("contentDescription").takeIf { it.isNotBlank() }?.let {
                ranked += RankedSelector("contentDescription", it, 0.75)
            }
            selector.optString("textContains").takeIf { it.isNotBlank() }?.let { ranked += RankedSelector("partialText", it, 0.7) }
            selector.optString("role").takeIf { it.isNotBlank() }?.let { ranked += RankedSelector("role", it, 0.6) }
        }
        return ranked
    }

    private fun stepLabels(step: StepDefinition): List<String> = buildList {
        add(step.name)
        step.parameters.forEach { (key, value) ->
            add(key)
            add(value)
        }
        step.selector?.text?.let(::add)
        step.selector?.partialText?.let(::add)
        step.selector?.contentDescription?.let(::add)
        step.selector?.contentDescriptionContains?.let(::add)
        step.selector?.role?.let(::add)
    }

    private fun paramsFor(step: StepDefinition, overlay: Map<String, String>): JSONObject {
        val merged = SkillSecrets.strip(
            step.parameters.filterKeys { it != "tool" && it != "afterPageKey" } + overlay,
        )
        val json = JSONObject()
        merged.forEach { (key, value) -> json.put(key, value) }
        step.selector?.let { json.put("selector", selectorJson(it)) }
        return json
    }

    private fun selectorJson(selector: Selector): JSONObject = JSONObject().apply {
        selector.resourceId?.let { put("resourceId", it) }
        selector.text?.let { put("text", it) }
        selector.partialText?.let { put("textContains", it) }
        selector.contentDescription?.let { put("contentDescription", it) }
        selector.contentDescriptionContains?.let { put("contentDescriptionContains", it) }
        selector.role?.let { put("role", it) }
        selector.className?.let { put("class", it) }
        selector.x?.let { put("x", it) }
        selector.y?.let { put("y", it) }
    }

    private fun plannedEnvelope(index: Int, step: StepDefinition, tool: String): JSONObject {
        val beforeKey = step.parameters["beforePageKey"]
        val afterKey = step.parameters["afterPageKey"].orEmpty()
        val after = if (afterKey.isBlank()) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("pageKey", afterKey)
                .put("pageCard", JSONObject().put("pageKey", afterKey))
        }
        return JSONObject()
            .put("kind", "phone_action_result")
            .put("ok", true)
            .put("pageChanged", afterKey.isNotBlank())
            .put("before", if (beforeKey.isNullOrBlank()) JSONObject.NULL else JSONObject().put("pageKey", beforeKey))
            .put("after", after)
            .put("delta", JSONObject().put("dryRun", true).put("mutated", false))
            .put("errorClass", JSONObject.NULL)
            .put("generation", JSONObject.NULL)
            .put("tool", tool)
            .put("stepIndex", index)
            .put("dryRun", true)
    }

    private fun liveEnvelope(
        index: Int,
        tool: String,
        outcome: SkillStepOutcome,
        beforeCard: JSONObject?,
        afterCard: JSONObject?,
    ): JSONObject {
        val before = outcome.before ?: beforeCard?.let(::locationOf)
        val after = outcome.after ?: afterCard?.let { card ->
            locationOf(card).put("pageCard", card)
        }
        val pageChanged = outcome.pageChanged
            ?: pageKeysDiffer(before, after)
        val delta = outcome.delta ?: JSONObject()
            .put("pageKeyChanged", pageChanged)
            .put("fingerprintChanged", outcome.beforeFingerprint != null && outcome.beforeFingerprint != outcome.afterFingerprint)
        return JSONObject()
            .put("kind", "phone_action_result")
            .put("ok", outcome.ok)
            .put("pageChanged", pageChanged)
            .put("before", before ?: JSONObject.NULL)
            .put("after", after ?: JSONObject.NULL)
            .put("delta", delta)
            .put("errorClass", outcome.errorClass ?: JSONObject.NULL)
            .put("generation", outcome.generation ?: afterCard?.optJSONObject("observationScope")?.opt("id") ?: JSONObject.NULL)
            .put("tool", tool)
            .put("stepIndex", index)
    }

    private fun gateDeniedEnvelope(index: Int, tool: String, decision: GateDecision.Blocked): JSONObject =
        JSONObject()
            .put("kind", "phone_action_result")
            .put("ok", false)
            .put("pageChanged", false)
            .put("before", JSONObject.NULL)
            .put("after", JSONObject.NULL)
            .put("delta", JSONObject().put("mutated", false))
            .put("errorClass", "GATE_DENIED")
            .put("generation", JSONObject.NULL)
            .put("tool", tool)
            .put("stepIndex", index)
            .put("gateClass", decision.gateClass.jsonKey)
            .put("ignoredPcAutoApprove", decision.ignoredPcAutoApprove)

    private fun locationOf(card: JSONObject): JSONObject {
        val location = card.optJSONObject("location") ?: JSONObject()
        val out = JSONObject()
        for (key in listOf("pageKey", "package", "activity")) {
            val value = card.optString(key).ifBlank { location.optString(key) }
            if (value.isNotBlank()) out.put(key, value)
        }
        return out
    }

    private fun pageKeysDiffer(before: JSONObject?, after: JSONObject?): Boolean {
        if (before == null || after == null) return false
        val left = before.optString("pageKey")
        val right = after.optString("pageKey").ifBlank {
            after.optJSONObject("pageCard")?.optString("pageKey").orEmpty()
        }
        return left.isNotBlank() && right.isNotBlank() && left != right
    }

    private fun runResult(
        skillId: String,
        status: String,
        dryRun: Boolean,
        ok: Boolean,
        mutated: Boolean,
        envelopes: JSONArray,
        errorClass: String? = null,
        gateClass: GateClass? = null,
        ignoredPcAutoApprove: Boolean? = null,
        message: String? = null,
    ): JSONObject = JSONObject()
        .put("ok", ok)
        .put("kind", "phone_skill_run")
        .put("skillId", skillId)
        .put("status", status)
        .put("dryRun", dryRun)
        .put("mutated", mutated)
        .put("storeClass", STORE_CLASS)
        .put("steps", envelopes)
        .put("envelopes", envelopes)
        .put("errorClass", errorClass ?: JSONObject.NULL)
        .put("gateClass", gateClass?.jsonKey ?: JSONObject.NULL)
        .put("ignoredPcAutoApprove", ignoredPcAutoApprove ?: JSONObject.NULL)
        .put("denied", errorClass == "DRAFT_RUN_DENIED" || errorClass == "GATE_DENIED")
        .put("error", if (errorClass == null) JSONObject.NULL else JSONObject()
            .put("code", errorClass)
            .put("layer", if (errorClass == "GATE_DENIED" || errorClass == "DRAFT_RUN_DENIED") "policy" else "execution")
            .put("message", message ?: errorClass))

    private fun runDenied(
        skillId: String,
        dryRun: Boolean,
        errorClass: String,
        message: String,
        status: String,
    ): JSONObject = runResult(
        skillId = skillId,
        status = status,
        dryRun = dryRun,
        ok = false,
        mutated = false,
        envelopes = JSONArray(),
        errorClass = errorClass,
        message = message,
    )

    private fun compileDenied(errorClass: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("written", false)
        .put("status", JSONObject.NULL)
        .put("enabled", false)
        .put("storeClass", STORE_CLASS)
        .put("compiler", SkillCompiler.COMPILE_FUNCTION)
        .put("skill", JSONObject.NULL)
        .put("errorClass", errorClass)
        .put("error", JSONObject().put("code", errorClass).put("layer", "protocol").put("message", message))

    private fun compileErrorClass(reason: String): String = when {
        reason.contains("workers/PC cannot flip", ignoreCase = true) ||
            reason.contains("cannot flip draft", ignoreCase = true) -> "PC_VERIFIED_DENIED"
        reason.contains("unverified", ignoreCase = true) -> "UNVERIFIED_STEPS"
        reason.contains("after-state", ignoreCase = true) -> "MISSING_AFTER_STATE"
        else -> "COMPILE_REJECTED"
    }

    private fun pcEnvelopeFrom(args: JSONObject, requestedStatus: String): PcGateEnvelope {
        val nested = args.optJSONObject("pcEnvelope")
        val autoApprove = args.optBoolean("autoApprove", false) || nested?.optBoolean("autoApprove") == true
        val requested = requestedStatus.takeIf { it.isNotBlank() }
            ?: nested?.optString("requestedCapsuleStatus")?.takeIf { it.isNotBlank() }
        return PcGateEnvelope(
            autoApprove = autoApprove,
            origin = nested?.optString("origin")?.ifBlank { "pc" } ?: "pc",
            requestedCapsuleStatus = requested,
        )
    }

    private fun JSONObject?.stringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val iter = keys()
        while (iter.hasNext()) {
            val key = iter.next()
            val value = opt(key) ?: continue
            if (value === JSONObject.NULL || value is JSONObject || value is JSONArray) continue
            out[key] = value.toString()
        }
        return out
    }

    companion object {
        const val STORE_CLASS = "AutomationStore"
        const val SOURCE = "PC_CODEX"
    }
}
