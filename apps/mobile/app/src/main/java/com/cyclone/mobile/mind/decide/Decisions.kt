package com.cyclone.mobile.mind.decide

import android.content.Context
import android.os.SystemClock
import com.cyclone.mobile.mind.modes.BoxReply
import com.cyclone.mobile.mind.modes.BoxRequest
import com.cyclone.mobile.mind.modes.BoxWire
import com.cyclone.mobile.mind.modes.DecisionBox
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Who answers Cyclone's typed decisions (plans 41 and 42): the modes router's decision box and the Pilot's decision
 * route. One port, one switch, so the provider changes in one place.
 *
 * - [JEV] is live: TypeSafe's decision model over OpenRouter's decisions endpoint. **Text only:** it never gets a
 *   screenshot, so no screenshot is even taken for it.
 * - [OPENAI_DECISIONS] is frozen (alpha.78): OpenAI's Decisions API is announced but not on OpenRouter and has no
 *   public contract Cyclone can build against. The adapter is shaped (same questions, same tolerant reader) and waits.
 *
 * **To switch when OpenAI Decisions is available** (plan 41 §12): fill its [model] and [endpoint] from the official
 * docs, set [live], set [Decisions.ACTIVE], run `DecisionsTest` and the Lab's watch comparison. Nothing else changes.
 */
enum class DecisionProvider(
    val label: String,
    val model: String,
    val endpoint: String?,
    /** Can it read a screenshot? A provider without vision never receives one. */
    val vision: Boolean,
    /** False while frozen: [Decisions.active] never picks it. */
    val live: Boolean,
) {
    JEV("JEV (TypeSafe)", "~typesafe/jev-latest", "https://openrouter.ai/api/alpha/decisions", vision = false, live = true),
    OPENAI_DECISIONS("OpenAI Decisions", model = "", endpoint = null, vision = true, live = false),
}

object Decisions {
    /** The provider Cyclone's decisions use. JEV until OpenAI Decisions is live. */
    val ACTIVE: DecisionProvider = DecisionProvider.JEV

    /** The active provider, or JEV when the chosen one is frozen or not set up. */
    fun active(): DecisionProvider = ACTIVE.takeIf { it.live && it.endpoint != null && it.model.isNotBlank() } ?: DecisionProvider.JEV

    /** The decision box for this phone, or null without an OpenRouter key (the router then uses only grammar and rules). */
    fun box(context: Context): DecisionBox? {
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        return ProviderDecisionBox(key, active(), ROUTING_DEADLINE_MS)
    }

    /**
     * One decision call: a short deadline (a slow answer is no answer and the caller goes one mode up), nothing logged,
     * the key only in the Authorization header. Null on any failure.
     */
    fun post(key: String, provider: DecisionProvider, body: JSONObject, title: String, deadlineMs: Long = 6_000): String? = runCatching {
        val url = provider.endpoint ?: return null
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", title)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val client = if (deadlineMs == 6_000L) CLIENT else CLIENT.newBuilder().callTimeout(deadlineMs, TimeUnit.MILLISECONDS).build()
        client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }.getOrNull()

    /**
     * Alpha 89: how long routing waits for the decision. A slower answer is no answer: the request goes one mode up
     * (Flash), so a slow network never stalls a command.
     */
    const val ROUTING_DEADLINE_MS = 2_500L

    private val JSON = "application/json".toMediaType()
    private val CLIENT = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
}

/** The decision box over a [DecisionProvider]. A screenshot is dropped for a provider without vision. */
class ProviderDecisionBox(private val key: String, private val provider: DecisionProvider, private val deadlineMs: Long = 6_000) : DecisionBox {
    override val sees: Boolean get() = provider.vision

    override fun ask(request: BoxRequest): BoxReply? {
        val started = SystemClock.elapsedRealtime()
        val text = Decisions.post(key, provider, body(provider, request), "Cyclone Modes", deadlineMs) ?: return null
        return BoxWire.parseDecisions(text, request)?.copy(ms = SystemClock.elapsedRealtime() - started)
    }

    companion object {
        /** The request as [provider] gets it: the decisions shape, and never an image it can't read. Pure. */
        fun body(provider: DecisionProvider, request: BoxRequest): JSONObject =
            BoxWire.decisionsBody(provider.model, if (provider.vision) request else request.copy(image = null))
    }
}
