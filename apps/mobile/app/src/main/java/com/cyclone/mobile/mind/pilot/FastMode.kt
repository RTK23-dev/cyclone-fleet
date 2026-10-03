package com.cyclone.mobile.mind.pilot

import android.content.Context
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** How a fast decision travels (plan 41 §7). */
enum class FastRoute(val wire: String, val label: String) {
    /** An ordinary fast model with a strict output schema. Sees screenshots. */
    MODEL("model", "Fast model"),
    /** The decision provider (alpha.78: JEV, text only; OpenAI Decisions replaces it when live). See `mind/decide`. */
    DECISIONS("decisions", "Decision model");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: MODEL }
}

/** How sure the fast model must be before the Pilot acts. */
enum class FastSureness(val wire: String, val label: String, val bar: Double) {
    CAREFUL("careful", "Careful", 0.9), BALANCED("balanced", "Balanced", 0.8), QUICK("quick", "Quick", 0.7);
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: CAREFUL }
}

data class FastModeSettings(
    val enabled: Boolean = false,
    val route: FastRoute = FastRoute.MODEL,
    val model: String = FastMode.DEFAULT_MODEL,
    val decisionModel: String = FastMode.DEFAULT_DECISION_MODEL,
    val images: Boolean = true,
    val sureness: FastSureness = FastSureness.CAREFUL,
    /** The smart model reviews the rest of the plan in parallel while the rapid model works. */
    val lookahead: Boolean = true,
) {
    /** The decision route's model is the provider's, not a free choice (alpha.78: frozen to JEV). */
    val activeModel: String get() = if (route == FastRoute.DECISIONS) com.cyclone.mobile.mind.decide.Decisions.active().model else model
    fun pilot() = PilotSettings(sureness.bar, images && (route == FastRoute.MODEL || com.cyclone.mobile.mind.decide.Decisions.active().vision), lookahead)
}

/**
 * Fast mode (plan 41): Settings → Model & intelligence. Off by default. When on, the Mind gets the `pilot` tool and a
 * fast model carries out the steps it hands over. Stored on this phone only; never carried to other profiles.
 */
object FastMode {
    private const val PREFS = "cyclone_fast"
    const val DEFAULT_MODEL = "google/gemini-3.1-flash-lite"
    const val DEFAULT_DECISION_MODEL = "~typesafe/jev-latest"

    fun settings(context: Context): FastModeSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FastModeSettings(
            enabled = p.getBoolean("enabled", false),
            route = FastRoute.of(p.getString("route", null)),
            model = p.getString("model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
            decisionModel = p.getString("decision_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DECISION_MODEL,
            images = p.getBoolean("images", true),
            sureness = FastSureness.of(p.getString("sureness", null)),
            lookahead = p.getBoolean("lookahead", true),
        )
    }

    fun save(context: Context, settings: FastModeSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", settings.enabled).putString("route", settings.route.wire)
            .putString("model", settings.model.trim()).putString("decision_model", settings.decisionModel.trim())
            .putBoolean("images", settings.images).putString("sureness", settings.sureness.wire)
            .putBoolean("lookahead", settings.lookahead).apply()
    }

    /** The decider for a mission, or null when Fast mode is off or there is no OpenRouter key. */
    fun decider(context: Context, settings: FastModeSettings = settings(context)): PilotDecider? {
        if (!settings.enabled) return null
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        return OpenRouterPilotDecider(key, settings.route, settings.activeModel)
    }
}

/**
 * The smart model's short side channel (plan 41): the mission's own model, asked one small question with no tools and a
 * JSON verdict back. Used for bumps and for the parallel look-ahead; the Mind's conversation is not touched.
 */
class MindPilotAdvisor(private val model: com.cyclone.mobile.mind.MindModel) : PilotAdvisor {
    override fun review(review: PilotReview): PilotVerdict? = runCatching {
        val reply = model.complete(com.cyclone.mobile.mind.MindModelRequest(PilotWire.advisorMessages(review), emptyList(), false, ADVISOR_MS))
        PilotWire.parseVerdict(reply.text)
    }.getOrNull()

    companion object { const val ADVISOR_MS = 45_000L }
}

/**
 * One fast decision over OpenRouter, with a short deadline: a slow answer is no answer, and the Pilot hands back.
 * Nothing is logged; the key is sent only as the Authorization header.
 */
class OpenRouterPilotDecider(private val key: String, private val route: FastRoute, private val model: String) : PilotDecider {
    override fun decide(question: PilotQuestion): PilotAnswer? {
        val started = SystemClock.elapsedRealtime()
        // Alpha.78: the decision route goes to the one decision provider (JEV now, OpenAI Decisions when live).
        val text = when (route) {
            FastRoute.MODEL -> post(CHAT, PilotWire.choiceBody(model, question))
            FastRoute.DECISIONS -> com.cyclone.mobile.mind.decide.Decisions.active().let { provider ->
                com.cyclone.mobile.mind.decide.Decisions.post(key, provider, PilotWire.decisionsBody(provider.model, question), "Cyclone Pilot")
            }
        } ?: return null
        val answer = if (route == FastRoute.MODEL) PilotWire.parseChoice(text) else PilotWire.parseDecisions(text)
        return answer?.copy(ms = SystemClock.elapsedRealtime() - started)
    }

    private fun post(url: String, body: JSONObject): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Pilot")
            .post(body.toString().toRequestBody(JSON))
            .build()
        CLIENT.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }.getOrNull()

    companion object {
        private const val CHAT = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON = "application/json".toMediaType()
        private val CLIENT = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }
}
