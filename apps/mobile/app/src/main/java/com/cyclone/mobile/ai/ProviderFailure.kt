package com.cyclone.mobile.ai

enum class ProviderFailureClass {
    MODEL_NOT_FOUND,
    MODEL_ACCESS_DENIED,
    PROVIDER_REQUEST_BLOCKED,
    PROVIDER_AUTH_FAILED,
    NO_PROVIDER_AVAILABLE,
    RATE_LIMITED,
    ROUTING_CONSTRAINT_UNSATISFIED,
    PARAMETER_UNSUPPORTED,
    CONTEXT_LIMIT,
    MALFORMED_MODEL_OUTPUT,
    NETWORK_FAILURE,
    PROVIDER_CREDIT_EXHAUSTED,
}

data class SanitizedProviderFailure(
    val failureClass: ProviderFailureClass,
    val httpStatus: Int,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    val selectedModelId: String? = null,
    val providerName: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
) {
    val code: String get() = failureClass.name
    val userMessage: String get() {
        val base = ProviderFailure.message(code) ?: "The model provider could not serve this request."
        val explanation = providerMessage?.takeIf { it.isNotBlank() }?.let { " OpenRouter: $it" }.orEmpty()
        return if (selectedModelId?.contains("contributor") == true && failureClass in setOf(ProviderFailureClass.MODEL_ACCESS_DENIED, ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED))
            "$base Muse Contributor requires eligible account access and compatible OpenRouter data-policy settings. Prompts and outputs may be used for training; Cyclone has not changed your privacy settings."
        else base + explanation
    }
}

/** Provider failures are model-boundary events, never Android-navigation evidence. */
internal object ProviderFailure {
    fun classify(
        httpStatus: Int,
        rawBody: String? = null,
        selectedModelId: String? = null,
        providerName: String? = null,
        requestId: String? = null,
    ): SanitizedProviderFailure {
        val body = rawBody.orEmpty()
        val status = if (httpStatus in 200..299) runCatching {
            val json = org.json.JSONObject(body)
            // The chat boundary supplies an envelope; agent boundaries supply its error object.
            val error = json.optJSONObject("error") ?: json
            error.optInt("code", httpStatus).takeIf { it in 400..599 } ?: httpStatus
        }.getOrDefault(httpStatus) else httpStatus
        val lower = body.lowercase()
        val providerCode = extractJsonScalar(body, "code")?.let(::sanitize)?.take(120)
        val providerMessage = extractJsonString(body, "message")
            ?.let(::sanitize)
            ?.take(600)
        val typed = extractJsonString(body, "error_type").orEmpty().lowercase()
        val failureClass = when {
            typed == "authentication" -> ProviderFailureClass.PROVIDER_AUTH_FAILED
            typed == "payment_required" -> ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED
            typed == "rate_limit_exceeded" -> ProviderFailureClass.RATE_LIMITED
            typed in setOf("content_policy_violation", "refusal") ||
                (status == 403 && listOf("guardrail", "moderation", "content filter", "prompt injection", "request blocked").any(lower::contains)) -> ProviderFailureClass.PROVIDER_REQUEST_BLOCKED

            status == 401 -> ProviderFailureClass.PROVIDER_AUTH_FAILED
            status in setOf(403, 404) && listOf("data policy", "data collection", "privacy", "training", "zdr").any(lower::contains) -> ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED
            status == 404 && ("no endpoints" in lower || "no provider" in lower) -> ProviderFailureClass.NO_PROVIDER_AVAILABLE
            typed == "permission_denied" || status == 403 -> ProviderFailureClass.MODEL_ACCESS_DENIED
            status == 404 -> ProviderFailureClass.MODEL_NOT_FOUND
            status == 402 -> ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED
            status == 429 -> ProviderFailureClass.RATE_LIMITED
            status == 0 || status == 408 || status == 504 -> ProviderFailureClass.NETWORK_FAILURE
            "context" in lower && ("limit" in lower || "length" in lower || "too long" in lower) -> ProviderFailureClass.CONTEXT_LIMIT
            (status == 400 || status == 422) &&
                ("response_format" in lower || "response format" in lower || "unsupported parameter" in lower || "unsupported_param" in lower) ->
                ProviderFailureClass.PARAMETER_UNSUPPORTED
            "routing" in lower && ("constraint" in lower || "require_parameters" in lower || "provider" in lower) ->
                ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED
            ("no provider" in lower || "no endpoints" in lower || "no endpoint" in lower) ->
                ProviderFailureClass.NO_PROVIDER_AVAILABLE
            (status == 400 || status == 422) && "model" in lower &&
                ("not found" in lower || "unknown" in lower || "invalid" in lower) -> ProviderFailureClass.MODEL_NOT_FOUND
            status in 500..599 -> ProviderFailureClass.NO_PROVIDER_AVAILABLE
            else -> ProviderFailureClass.NO_PROVIDER_AVAILABLE
        }
        return SanitizedProviderFailure(
            failureClass = failureClass,
            httpStatus = status,
            providerCode = providerCode,
            providerMessage = providerMessage,
            selectedModelId = selectedModelId?.take(180),
            providerName = providerName?.let(::sanitize)?.take(120),
            requestId = requestId?.let(::sanitize)?.take(180),
            retryable = failureClass in setOf(
                ProviderFailureClass.NO_PROVIDER_AVAILABLE,
                ProviderFailureClass.RATE_LIMITED,
                ProviderFailureClass.NETWORK_FAILURE,
            ),
        )
    }

    /** Compatibility helper used by existing agent-boundary code. */
    fun code(httpStatus: Int): String = classify(httpStatus).code

    fun message(code: String): String? = when (runCatching { ProviderFailureClass.valueOf(code) }.getOrNull()) {
        ProviderFailureClass.MODEL_NOT_FOUND -> "The selected model was not found by the provider. Choose another explicitly supported model or try again later."
        ProviderFailureClass.MODEL_ACCESS_DENIED -> "OpenRouter denied this request. Refresh the catalog in Settings → Model & API and check this key's model permissions."
        ProviderFailureClass.PROVIDER_REQUEST_BLOCKED -> "OpenRouter or the provider blocked this request under its content or guardrail policy. Review the request and your OpenRouter guardrails."
        ProviderFailureClass.PROVIDER_AUTH_FAILED -> "The provider rejected the OpenRouter credentials. Check the API key in Settings."
        ProviderFailureClass.NO_PROVIDER_AVAILABLE -> "No compatible provider could serve this model request. Try again or explicitly choose another model."
        ProviderFailureClass.RATE_LIMITED -> "The model provider is rate-limiting requests. Wait before trying again."
        ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED -> "No provider route satisfied this model's required capability and privacy constraints."
        ProviderFailureClass.PARAMETER_UNSUPPORTED -> "The provider does not support a required request parameter for this route."
        ProviderFailureClass.CONTEXT_LIMIT -> "The model request exceeded the provider's context limit."
        ProviderFailureClass.MALFORMED_MODEL_OUTPUT -> "The model returned output that Cyclone could not validate safely."
        ProviderFailureClass.NETWORK_FAILURE -> "The model request timed out or the network is unavailable. Check the connection and try again."
        ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED -> "The model provider reports insufficient credit. Check the OpenRouter account."
        null -> null
    }

    fun sanitize(value: String): String = value
        .replace(Regex("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?[^,;\\s}]+"), "Authorization=[REDACTED]")
        .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"), "Bearer [REDACTED]")
        .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "[API_KEY_REDACTED]")
        .replace(Regex("(?i)(api[_ -]?key|token|secret)\\s*[:=]\\s*[^,;\\s}]+")) { "${it.groupValues[1]}=[REDACTED]" }
        .take(1200)

    private fun extractJsonString(raw: String, key: String): String? {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(raw) ?: return null
        return match.groupValues[1]
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractJsonScalar(raw: String, key: String): String? {
        extractJsonString(raw, key)?.let { return it }
        return Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*([^,}\\s]+)")
            .find(raw)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }
}


/** When the owner's backup model may take over a task: the main route is busy or down, never an account/key problem. */
object ProviderFallbackPolicy {
    fun shouldSwitch(httpStatus: Int, lifecycle: String?, failureClass: ProviderFailureClass?): Boolean = when {
        lifecycle == "provider.circuit_open" -> true
        lifecycle != null -> false // deadline/cancel: the task budget, not the route, is the limit
        failureClass == ProviderFailureClass.RATE_LIMITED -> true
        httpStatus in setOf(502, 503) -> true
        else -> false
    }
}
