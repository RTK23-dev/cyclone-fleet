package com.cyclone.mobile.ai.model

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderFailure
import com.cyclone.mobile.ai.ProviderFailureClass
import com.cyclone.mobile.ai.SanitizedProviderFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ModelQualificationOutcome {
    data class Passed(
        val profile: ModelProfile,
        val cached: Boolean,
        val repaired: Boolean = false,
        val providerName: String? = null,
        val requestId: String? = null,
    ) : ModelQualificationOutcome

    data class Failed(
        val profile: ModelProfile,
        val failure: SanitizedProviderFailure,
    ) : ModelQualificationOutcome
}

object ModelQualificationRuntime {
    val cache = InMemoryModelQualificationCache()
}

/**
 * Provider-only qualification. No phone APIs, observations, screenshots, task history or user goal
 * are accepted by this class, so failure cannot mutate the phone or become navigation evidence.
 */
class ModelQualificationRunner(
    private val context: Context,
    private val http: OkHttpClient = com.cyclone.mobile.ai.ProviderRequests.http,
) {
    suspend fun qualify(model: OpenRouterModelPreset): ModelQualificationOutcome = withContext(Dispatchers.IO) {
        val profile = ModelRegistry.profileForPreset(model) ?: ModelProfile(
            cycloneId = model.id,
            displayName = model.label,
            openRouterSlug = model.id,
            providerFamily = ProviderFamily.OTHER,
            imageInput = model.vision,
            structuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
            reasoningEffort = model.reasoningEffort,
        )
        val apiKey = OpenRouterSecretStore.read(context)
        ModelQualificationRuntime.cache.bindAccount(apiKey)
        if (apiKey.isNotBlank() && ModelQualificationRuntime.cache.isQualified(profile)) {
            return@withContext ModelQualificationOutcome.Passed(profile, cached = true)
        }

        if (apiKey.isBlank() || model.id.isBlank()) {
            return@withContext ModelQualificationOutcome.Failed(
                profile,
                SanitizedProviderFailure(
                    failureClass = ProviderFailureClass.PROVIDER_AUTH_FAILED,
                    httpStatus = 0,
                    providerMessage = "Add an OpenRouter API key and choose a model in Settings → Model & API.",
                    selectedModelId = profile.openRouterSlug,
                    retryable = false,
                ),
            )
        }

        val response = request(profile, apiKey)

        if (response is ModelQualificationOutcome.Passed) {
            ModelQualificationRuntime.cache.markQualified(profile)
        }
        response
    }

    private suspend fun request(
        profile: ModelProfile,
        apiKey: String,
    ): ModelQualificationOutcome {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", ModelQualificationContract.SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", ModelQualificationContract.USER_PROMPT))
        val body = try {
            PortableModelRequest.body(profile.openRouterSlug, messages,
                emptyList(), outputTokens = 512)
        } catch (_: IOException) {
            return ModelQualificationOutcome.Failed(profile, SanitizedProviderFailure(
                ProviderFailureClass.NO_PROVIDER_AVAILABLE, 0, selectedModelId = profile.openRouterSlug,
                providerMessage = "Could not verify an available endpoint. Retry when connected.", retryable = true))
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile Model Qualification")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = com.cyclone.mobile.ai.ProviderRequests.executeAsync(request,
                com.cyclone.mobile.ai.ProviderRequests.context("qualification-${java.util.UUID.randomUUID()}", apiKey,
                    profile.openRouterSlug, com.cyclone.mobile.ai.ProviderRequestPurpose.QUALIFICATION), http)
            run {
                val text = response.body
                val json = runCatching { JSONObject(text) }.getOrNull()
                val requestId = response.requestId
                val providerName = json?.optString("provider")?.takeIf { it.isNotBlank() }
                if (response.status !in 200..299 || json?.has("error") == true) {
                    return ModelQualificationOutcome.Failed(
                        profile,
                        ProviderFailure.classify(
                            httpStatus = if ((response.status in 200..299)) json?.optJSONObject("error")?.optInt("code", 500) ?: 500 else response.status,
                            rawBody = text,
                            selectedModelId = profile.openRouterSlug,
                            providerName = providerName,
                            requestId = requestId,
                        ),
                    )
                }
                val raw = json?.optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                parseQualified(profile, raw, providerName, requestId)
            }
        } catch (io: IOException) {
            ModelQualificationOutcome.Failed(
                profile,
                SanitizedProviderFailure(
                    failureClass = ProviderFailureClass.NETWORK_FAILURE,
                    httpStatus = 0,
                    providerMessage = ProviderFailure.sanitize(io.message.orEmpty()).takeIf { it.isNotBlank() },
                    selectedModelId = profile.openRouterSlug,
                    retryable = true,
                ),
            )
        }
    }

    private fun parseQualified(
        profile: ModelProfile,
        raw: String,
        providerName: String?,
        requestId: String?,
    ): ModelQualificationOutcome {
        val direct = runCatching { JSONObject(raw.trim()) }.getOrNull()
        val extracted = if (direct == null) BoundedJsonRepair.extractSingleObject(raw) else null
        val parsed = direct ?: extracted?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (parsed == null) return malformed(profile, providerName, requestId)
        val actions = parsed.optJSONArray("actions") ?: JSONArray()
        val valid = ModelQualificationContract.isQualifiedResult(
            status = parsed.optString("status"),
            answer = parsed.optString("answer").takeIf { it.isNotBlank() },
            actionCount = actions.length(),
        ) && parsed.optString("reason").isBlank()
        if (!valid) return malformed(profile, providerName, requestId)
        return ModelQualificationOutcome.Passed(
            profile = profile,
            cached = false,
            repaired = direct == null && extracted != null,
            providerName = providerName,
            requestId = requestId,
        )
    }

    private fun malformed(
        profile: ModelProfile,
        providerName: String?,
        requestId: String?,
    ): ModelQualificationOutcome.Failed = ModelQualificationOutcome.Failed(
        profile,
        SanitizedProviderFailure(
            failureClass = ProviderFailureClass.MALFORMED_MODEL_OUTPUT,
            httpStatus = 200,
            selectedModelId = profile.openRouterSlug,
            providerName = providerName,
            requestId = requestId,
            retryable = false,
        ),
    )
}
