package com.cyclone.mobile.mind.learn

import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.PageSignatureEngine
import com.cyclone.mobile.applearner.ScreenRecognition
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Where learned knowledge goes. Production: the phone's app knowledge store, projected into the Atlas for Glass. */
interface LearnSink {
    fun app(packageName: String): LearnedApp?
    fun screenIdFor(packageName: String, pageKey: String): String?
    fun putApp(app: LearnedApp)
    fun putScreen(screen: LearnedScreen)
    /** Stores (or merges into an equivalent) action and returns the id it is stored under. */
    fun putAction(action: LearnedAction): String
    fun putTransition(transition: LearnedTransition)
}

data class AppLearned(val packageName: String, val label: String, val screens: Int, val newScreens: Int, val controls: Int, val transitions: Int)

data class LearnReport(val apps: List<AppLearned>, val failedSteps: Int, val atMs: Long) {
    val screens get() = apps.sumOf { it.screens }
    val newScreens get() = apps.sumOf { it.newScreens }
    val controls get() = apps.sumOf { it.controls }
    val transitions get() = apps.sumOf { it.transitions }

    /** "Learned 7 screens, 64 controls and 9 moves in Calculator." */
    fun sentence(): String = if (apps.isEmpty()) "There was nothing in this run to learn from." else
        "Learned ${plural(screens, "screen")}, ${plural(controls, "control")} and ${plural(transitions, "move")} in " +
            apps.joinToString(", ") { it.label } + "."

    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("failedSteps", failedSteps).put("sentence", sentence())
        .put("apps", JSONArray().also { out ->
            apps.forEach {
                out.put(JSONObject().put("package", it.packageName).put("label", it.label).put("screens", it.screens)
                    .put("newScreens", it.newScreens).put("controls", it.controls).put("transitions", it.transitions))
            }
        })

    companion object {
        private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

        fun fromJson(json: JSONObject?): LearnReport? = json?.let {
            runCatching {
                val apps = it.optJSONArray("apps") ?: JSONArray()
                LearnReport((0 until apps.length()).map { i ->
                    val a = apps.getJSONObject(i)
                    AppLearned(a.optString("package"), a.optString("label"), a.optInt("screens"), a.optInt("newScreens"), a.optInt("controls"), a.optInt("transitions"))
                }, it.optInt("failedSteps"), it.optLong("at"))
            }.getOrNull()
        }
    }
}

/**
 * Turns one run's trail into lasting app knowledge: every screen it saw (so Cyclone recognises it next time), every
 * control on those screens (so it knows what is there without reading the whole screen), and every move that worked
 * (screen → control → screen). Controls it pressed successfully are marked understood; controls it only saw are marked
 * discovered. A move that failed is never recorded as a route.
 */
class MissionLearner(
    private val sink: LearnSink,
    /**
     * False for what a mapping pass on a test account walked: those moves are learned as less trusted (confidence
     * 0.5, discovered) so the map prefers moves confirmed on the owner's own account until a walk confirms them.
     */
    private val confirmed: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun learn(trail: MissionTrail, appLabel: (String) -> String): LearnReport {
        val now = clock()
        val used = trail.steps.filter { it.controlKey != null }.groupBy { it.fromPageKey to it.controlKey }
        val screenIds = mutableMapOf<String, String>()
        val actionIds = mutableMapOf<Pair<String, String>, String>()
        val perApp = linkedMapOf<String, IntArray>() // screens, newScreens, controls, transitions

        trail.screens.groupBy { it.packageName }.forEach { (pkg, screens) ->
            val existing = sink.app(pkg)
            sink.putApp(LearnedApp(pkg, existing?.label ?: appLabel(pkg), existing?.versionName, existing?.versionCode,
                if (existing?.knowledgeState == KnowledgeState.VERIFIED) KnowledgeState.VERIFIED else KnowledgeState.UNDERSTOOD,
                maxOf(existing?.confidence ?: 0.0, 0.6), now, existing?.lastVerifiedAt, existing?.instructionSummary.orEmpty()))
            val counts = perApp.getOrPut(pkg) { IntArray(4) }
            screens.forEach { screen ->
                val knownId = sink.screenIdFor(pkg, screen.pageKey)
                val screenId = knownId ?: "mind-" + digest(pkg + "|" + screen.pageKey)
                screenIds[screen.pageKey] = screenId
                val acted = trail.steps.any { it.fromPageKey == screen.pageKey && it.ok }
                sink.putScreen(LearnedScreen(
                    id = screenId,
                    packageName = pkg,
                    identity = PageSignatureEngine.semanticName(screen.title, "page"),
                    title = screen.title,
                    purpose = "Seen in a Cyclone run.",
                    recognition = ScreenRecognition(
                        semanticFingerprint = screen.pageKey,
                        structuralFingerprint = screen.structuralKey,
                        stableAnchors = screen.controls.map { PageSignatureEngine.normalizeLabel(it.label) }.filter(String::isNotBlank).distinct().take(24),
                        className = screen.className,
                        titleHints = listOf(screen.title).filter { it.isNotBlank() && it != "Screen" },
                    ),
                    knowledgeState = if (acted) KnowledgeState.UNDERSTOOD else KnowledgeState.DISCOVERED,
                    confidence = (0.55 + 0.05 * screen.sightings + if (acted) 0.15 else 0.0).coerceAtMost(0.9),
                    lastSeenAt = now,
                    lastVerifiedAt = if (acted) now else null,
                ))
                counts[0]++
                if (knownId == null) counts[1]++
                screen.controls.forEach { control ->
                    val steps = used[screen.pageKey to control.key].orEmpty()
                    val worked = steps.any { it.ok }
                    val failed = steps.count { !it.ok }
                    val id = sink.putAction(LearnedAction(
                        id = "mind-" + digest(screenId + "|" + control.key),
                        packageName = pkg,
                        screenId = screenId,
                        semanticName = control.semanticName.ifBlank { PageSignatureEngine.semanticName(control.label, control.role) },
                        label = control.label,
                        androidActions = control.androidActions,
                        selectorJson = control.selector.toString(),
                        risk = control.risk,
                        knowledgeState = if (worked) KnowledgeState.UNDERSTOOD else KnowledgeState.DISCOVERED,
                        confidence = (control.confidence * if (worked) 1.0 else 0.8).coerceIn(0.0, 0.95),
                        lastSuccessAt = if (worked) now else null,
                        lastFailureAt = if (failed > 0) now else null,
                        failureCount = failed,
                    ))
                    actionIds[screen.pageKey to control.key] = id
                    counts[2]++
                }
            }
        }

        trail.steps.filter { it.ok && it.fromPageKey != null && it.toPageKey != null && it.fromPageKey != it.toPageKey && it.controlKey != null }
            .distinctBy { Triple(it.fromPageKey, it.controlKey, it.toPageKey) }
            .forEach { step ->
                val from = trail.screens.firstOrNull { it.pageKey == step.fromPageKey } ?: return@forEach
                val to = trail.screens.firstOrNull { it.pageKey == step.toPageKey } ?: return@forEach
                if (from.packageName != to.packageName) return@forEach // a move into another app is not part of this app's map
                val action = actionIds[step.fromPageKey to step.controlKey] ?: return@forEach
                sink.putTransition(LearnedTransition(
                    id = "mind-" + digest(step.fromPageKey + "|" + step.controlKey + "|" + step.toPageKey),
                    packageName = from.packageName,
                    fromScreenId = screenIds.getValue(from.pageKey),
                    actionId = action,
                    toScreenId = screenIds.getValue(to.pageKey),
                    knowledgeState = if (confirmed) KnowledgeState.UNDERSTOOD else KnowledgeState.DISCOVERED,
                    confidence = if (confirmed) 0.7 else UNCONFIRMED_CONFIDENCE,
                    observedCount = 1,
                    successfulCount = 1,
                    lastObservedAt = now,
                ))
                perApp.getValue(from.packageName)[3]++
            }

        return LearnReport(
            perApp.map { (pkg, c) -> AppLearned(pkg, sink.app(pkg)?.label ?: appLabel(pkg), c[0], c[1], c[2], c[3]) },
            trail.steps.count { !it.ok },
            now,
        )
    }

    companion object {
        const val UNCONFIRMED_CONFIDENCE = 0.5
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
