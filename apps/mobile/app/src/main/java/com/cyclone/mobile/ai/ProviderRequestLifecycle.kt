package com.cyclone.mobile.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProviderRequestPurpose { PHONE_TASK, CHAT, QUALIFICATION, MISSION }
class ProviderLifecycleException(val reason: String) : IOException(ProviderRequests.message(reason))
class ProviderCancellation {
    @Volatile var cancelled = false
        private set
    private var call: Call? = null
    @Synchronized fun attach(value: Call) { call = value; if (cancelled) value.cancel() }
    @Synchronized fun detach(value: Call) { if (call === value) call = null }
    @Synchronized fun cancel() { cancelled = true; call?.cancel() }
}
data class ProviderRequestContext(
    val taskId: String,
    val accountFingerprint: String,
    val modelId: String,
    val purpose: ProviderRequestPurpose,
    val deadlineMs: Long,
    val cancellation: ProviderCancellation = ProviderCancellation(),
    val retryBudget: Int = 1,
    val requestId: String = UUID.randomUUID().toString(),
    val externallyCancelled: () -> Boolean = { false },
    val onPhase: (String, Long) -> Unit = { _, _ -> },
) {
    init { require(accountFingerprint.isNotBlank()); require(modelId.isNotBlank()); require(retryBudget in 0..1) }
}
data class ProviderReply(val status: Int, val body: String, val requestId: String?)

/** Account/model/endpoint/purpose isolation; one request can probe an expired cooldown. */
class ProviderPacing(private val now: () -> Long = ProviderRequests::now) {
    private data class Entry(var busy: Boolean = false, var until: Long = 0)
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun enter(key: String) {
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.busy) throw ProviderLifecycleException("provider.request_in_progress")
        if (entry.until > now()) throw ProviderLifecycleException("provider.cooldown")
        entry.busy = true
    }
    @Synchronized fun leave(key: String, cooldownMs: Long) {
        entries[key]?.apply { busy = false; until = now() + cooldownMs.coerceIn(0, 5_000) }
        if (entries.size > 128) entries.entries.removeAll { !it.value.busy && it.value.until <= now() }
    }
    @Synchronized fun invalidateAccount(fingerprint: String) { entries.keys.removeAll { it.startsWith("$fingerprint|") } }
}

/** Shared completion transport. Request bodies/routing are passed unchanged, never logged here. */
object ProviderRequests {
    const val REQUEST_BUDGET_MS = 30_000L
    /** One Mind turn may think for minutes; the mission budget, not a 30 s cut-off, bounds the run. */
    const val MISSION_REQUEST_BUDGET_MS = 180_000L
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    val missionHttp: OkHttpClient = http.newBuilder().readTimeout(MISSION_REQUEST_BUDGET_MS, TimeUnit.MILLISECONDS)
        .callTimeout(MISSION_REQUEST_BUDGET_MS + 10_000, TimeUnit.MILLISECONDS).build()
    private val pacing = ProviderPacing()
    private val revoked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val active = java.util.concurrent.ConcurrentHashMap<ProviderCancellation, String>()
    private val watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "provider-cancel").apply { isDaemon = true } }
    private val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "provider-request").apply { isDaemon = true } }
    fun now(): Long = System.nanoTime() / 1_000_000
    fun invalidateAccount(fingerprint: String?) {
        if (fingerprint == null) return
        revoked.add(fingerprint)
        active.filterValues { it == fingerprint }.keys.forEach(ProviderCancellation::cancel)
        pacing.invalidateAccount(fingerprint)
    }
    fun activateAccount(fingerprint: String?) { if (fingerprint != null) revoked.remove(fingerprint) }
    fun message(reason: String): String = when (reason) {
        "provider.cancelled" -> "The provider request was stopped; its response cannot perform an action."
        "provider.deadline" -> "The provider did not finish within this request's remaining time budget (at most 30 seconds). No returned plan was executed."
        "provider.cooldown" -> "This account and model are cooling down after a transient provider failure. Retry after the short cooldown."
        "provider.circuit_open" -> "This model route already exhausted its bounded retry for the current task. Retry the task later or choose another model."
        "provider.request_in_progress" -> "Another request for this account, model and purpose is still running. Wait for it to finish or stop it."
        else -> if (reason.startsWith("phase.timeout.")) "Execution exceeded the ${reason.substringAfterLast('.').replace('_', ' ')} phase budget. No further action was dispatched." else reason
    }
    fun context(taskId: String, key: String, model: String, purpose: ProviderRequestPurpose,
                budgetMs: Long = REQUEST_BUDGET_MS, cancellation: ProviderCancellation = ProviderCancellation(),
                externallyCancelled: () -> Boolean = { false }, onPhase: (String, Long) -> Unit = { _, _ -> }) =
        ProviderRequestContext(taskId, OpenRouterCatalogStore.fingerprint(key) ?: "missing", model, purpose,
            now() + budgetMs.coerceIn(1, if (purpose == ProviderRequestPurpose.MISSION) MISSION_REQUEST_BUDGET_MS else REQUEST_BUDGET_MS),
            cancellation, externallyCancelled = externallyCancelled, onPhase = onPhase)

    fun execute(request: Request, context: ProviderRequestContext, client: OkHttpClient = http): ProviderReply {
        val key = "${context.accountFingerprint}|${context.modelId}|${context.purpose}|${request.url.host}${request.url.encodedPath}"
        fun check() {
            if (context.cancellation.cancelled || context.externallyCancelled() || context.accountFingerprint in revoked) throw ProviderLifecycleException("provider.cancelled")
            if (now() >= context.deadlineMs) throw ProviderLifecycleException("provider.deadline")
        }
        check()
        pacing.enter(key)
        var cooldown = 0L
        val start = now()
        try {
            active[context.cancellation] = context.accountFingerprint
            for (attempt in 0..context.retryBudget) {
                check()
                context.onPhase(if (attempt == 0) "awaiting_provider" else "retrying_provider", now() - start)
                val call = client.newCall(request)
                call.timeout().timeout((context.deadlineMs - now()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                context.cancellation.attach(call)
                var nextProgress = now() + 1_000
                val watch = watcher.scheduleAtFixedRate({
                    if (context.externallyCancelled() || context.cancellation.cancelled || now() >= context.deadlineMs) call.cancel()
                    if (now() >= nextProgress) {
                        nextProgress = now() + 1_000
                        runCatching { context.onPhase("awaiting_provider", now() - start) }
                    }
                }, 0, 50, TimeUnit.MILLISECONDS)
                var retryAfter = 1_000L
                val reply = try {
                    call.execute().use { response ->
                        retryAfter = (response.header("Retry-After")?.toLongOrNull()?.times(1_000) ?: 1_000).coerceIn(250, 5_000)
                        ProviderReply(response.code, response.body?.string().orEmpty(),
                            response.header("x-request-id") ?: response.header("x-openrouter-request-id"))
                    }
                } catch (_: IOException) {
                    check()
                    ProviderReply(0, "", context.requestId)
                } finally {
                    watch.cancel(false)
                    context.cancellation.detach(call)
                }
                check() // A returned HTTP response after Stop is not an executable result.
                val embedded = runCatching { JSONObject(reply.body).optJSONObject("error") }.getOrNull()
                val effectiveStatus = if (reply.status in 200..299 && embedded != null) embedded.optInt("code", 500) else reply.status
                val transient = effectiveStatus in setOf(0, 429, 500, 502, 503, 504) &&
                    ProviderFailure.classify(effectiveStatus, reply.body, context.modelId).retryable
                cooldown = if (transient) retryAfter else 0
                if (!transient || attempt == context.retryBudget) return reply
                context.onPhase("provider_backoff", now() - start)
                val until = now() + retryAfter
                while (now() < until) { check(); Thread.sleep(minOf(50, until - now()).coerceAtLeast(1)) }
            }
            error("unreachable")
        } finally {
            active.remove(context.cancellation)
            pacing.leave(key, cooldown)
            context.onPhase(when {
                context.cancellation.cancelled || context.externallyCancelled() -> "provider_cancelled"
                now() >= context.deadlineMs -> "provider_deadline"
                else -> "provider_closed"
            }, now() - start)
        }
    }

    suspend fun executeAsync(request: Request, context: ProviderRequestContext, client: OkHttpClient = http): ProviderReply =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { context.cancellation.cancel() }
            workers.execute {
                val result = runCatching { execute(request, context, client) }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        }
}
