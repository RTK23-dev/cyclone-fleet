package com.cyclone.mobile.skills

import android.content.Context
import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.fastpath.FastPathTree
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Stage 3 facade: record verified Fast Path runs, compile stable playbooks, replay
 * compiled skills through PhoneToolExecutor before spending an LLM turn.
 */
object SkillRuntime {
    @Volatile
    private var initialized = false
    lateinit var store: PlaybookHintStore
        private set
    private val routes = LinkedHashMap<String, CompiledSkillRoute>()
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = PlaybookHintStore.files(File(context.applicationContext.filesDir, "cyclone-playbooks"))
        refreshCompiled()
        initialized = true
    }

    @Synchronized
    fun initializeForTests(testStore: PlaybookHintStore) {
        store = testStore
        appContext = null
        refreshCompiled()
        initialized = true
    }

    fun recordSuccessfulRun(
        packageName: String,
        goal: String,
        startPageKey: String,
        sessionId: String,
        displayId: Int,
        steps: List<PlaybookHintStep>,
        nlPlaybook: String = PlaybookGoal.nlPlaybook(steps),
        nowMs: Long = System.currentTimeMillis(),
        userOverride: Boolean = false,
    ): PlaybookHint? {
        if (!initialized) return null
        if (steps.size < 2) return null
        val hint = runCatching {
            PlaybookHint(
                packageName = packageName,
                goal = goal,
                goalSignature = PlaybookGoal.signature(goal),
                startPageKey = startPageKey,
                sessionId = sessionId,
                displayId = displayId,
                steps = steps,
                nlPlaybook = nlPlaybook.ifBlank { PlaybookGoal.nlPlaybook(steps) },
                successCount = 1,
                source = if (userOverride) PlaybookSource.USER_OVERRIDE else PlaybookSource.FAST_PATH,
                lastSuccessAtMs = nowMs,
                userOverride = userOverride,
            )
        }.getOrNull() ?: return null
        val stored = if (userOverride) store.mergeUserOverride(hint, nowMs) else store.recordSuccess(hint, nowMs)
        stored?.let { SkillRouteCompiler.compile(it, nowMs).route?.let { route -> routes[route.id] = route } }
        return stored
    }

    fun mergeUserOverride(
        packageName: String,
        goal: String,
        startPageKey: String,
        sessionId: String,
        displayId: Int,
        steps: List<PlaybookHintStep>,
        nlPlaybook: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PlaybookHint? = recordSuccessfulRun(
        packageName, goal, startPageKey, sessionId, displayId, steps, nlPlaybook, nowMs, userOverride = true,
    )

    fun compiledRoutes(): List<CompiledSkillRoute> = synchronized(this) { routes.values.toList() }

    fun match(
        packageName: String,
        goal: String,
        startPageKey: String,
        sessionId: String,
        displayId: Int,
    ): CompiledSkillRoute? {
        if (!initialized) return null
        return SkillRouteCompiler.match(compiledRoutes(), packageName, goal, startPageKey, sessionId, displayId)
    }

    fun tryReplay(
        packageName: String,
        goal: String,
        page: ReplayPage,
        sessionId: String,
        displayId: Int,
        act: PhoneToolPort,
        observe: PageObservePort? = null,
    ): SkillReplayResult {
        if (!initialized) {
            return SkillReplayResult.Miss(
                SkillMissReason.NO_MATCH,
                SkillEscalateTo.FAST_PATH_LLM,
                "SkillRuntime is not initialized",
            )
        }
        return CompiledSkillReplay.tryReplay(compiledRoutes(), packageName, goal, page, sessionId, displayId, act, observe)
    }

    fun executorPort(): PhoneToolPort? = appContext?.let { ExecutorPhoneToolPort(it) }

    fun pageFrom(page: PageContext, card: AgentPageCard? = null): ReplayPage {
        val indexed = card?.controls?.size ?: page.controls.size
        val raw = card?.let { if (it.treeUseful) indexed.coerceAtLeast(1) else 0 } ?: page.controls.size
        val mode = card?.perceptionMode ?: FastPathTree.perceptionMode(raw, indexed)
        return ReplayPage(
            packageName = card?.packageName ?: page.packageName,
            pageKey = card?.pageKey ?: page.pageKey,
            perceptionMode = mode,
            treeUseful = card?.treeUseful ?: FastPathTree.treeUseful(raw, indexed),
            fingerprint = card?.accessibilityFingerprint,
            controls = (card?.controls?.mapNotNull { SemanticSelector.fromJson(it.evidence) }
                ?: page.controls.mapNotNull { SemanticSelector.fromJson(it.selector) }),
        )
    }

    @Synchronized
    private fun refreshCompiled() {
        routes.clear()
        SkillRouteCompiler.compileReady(store).forEach { routes[it.id] = it }
    }
}

class ExecutorPhoneToolPort(private val context: Context) : PhoneToolPort {
    override fun act(tool: String, params: JSONObject, sessionId: String, displayId: Int): SkillActOutcome {
        val attached = ExecutionIdentity.attach(params, sessionId, displayId)
        attached.put("fastPath", true)
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("compiled-skill-${UUID.randomUUID()}", tool, attached),
        )
        val payload = result.payload as? JSONObject
        val error = result.error
        val warning = payload?.optString("warning").orEmpty()
        val verified = payload?.optBoolean("verified") == true
        val screenChanged = payload?.opt("screenChanged") == true
        val afterObserve = observePage(sessionId, displayId)
        return SkillActOutcome(
            ok = result.ok,
            selectorResolved = result.ok || error?.code != PhoneToolErrorCode.INVALID_REQUEST,
            pageChanged = screenChanged || result.beforeFingerprint != result.afterFingerprint,
            fingerprintChanged = FastPathChanged.changed(result.beforeFingerprint, result.afterFingerprint) || verified,
            afterPageKey = afterObserve?.pageKey,
            afterPackage = afterObserve?.packageName,
            perceptionMode = afterObserve?.perceptionMode ?: "a11y",
            treeUseful = afterObserve?.treeUseful ?: true,
            gateRequired = error?.code == PhoneToolErrorCode.POLICY_DENIED &&
                error.message.contains("GATE", ignoreCase = true),
            policyDenied = error?.code == PhoneToolErrorCode.POLICY_DENIED,
            unchangedWarning = warning.contains("UNCHANGED") || payload?.optBoolean("verified") == false && result.ok,
        )
    }

    private fun observePage(sessionId: String, displayId: Int): ReplayPage? {
        val params = ExecutionIdentity.attach(JSONObject(), sessionId, displayId)
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("compiled-skill-observe-${UUID.randomUUID()}", "phone.observe", params),
        )
        val payload = result.payload as? JSONObject ?: return null
        val packageName = payload.optString("package").ifBlank { payload.optString("packageName") }
        val pageKey = payload.optString("pageKey").ifBlank { payload.optString("accessibilityFingerprint") }
        if (packageName.isBlank() || pageKey.isBlank()) return null
        val nodes = payload.optJSONArray("nodes")
        val controls = buildList {
            if (nodes != null) {
                for (i in 0 until nodes.length()) {
                    SemanticSelector.fromJson(nodes.optJSONObject(i))?.let(::add)
                }
            }
        }
        return ReplayPage(
            packageName = packageName,
            pageKey = pageKey,
            perceptionMode = FastPathTree.perceptionMode(nodes?.length() ?: 0, controls.size),
            treeUseful = FastPathTree.treeUseful(nodes?.length() ?: 0, controls.size),
            fingerprint = payload.optString("fingerprint").ifBlank { payload.optString("accessibilityFingerprint") },
            controls = controls,
        )
    }
}

object FastPathChanged {
    fun changed(before: String?, after: String?): Boolean =
        !before.isNullOrBlank() && !after.isNullOrBlank() && before != after
}
