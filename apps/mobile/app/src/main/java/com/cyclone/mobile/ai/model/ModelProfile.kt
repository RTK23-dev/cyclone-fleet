package com.cyclone.mobile.ai.model

enum class ProviderFamily { OPENAI, ANTHROPIC, META, GOOGLE, ZHIPU, OTHER }
enum class StructuredOutputMode { SCHEMA_CONSTRAINED, PORTABLE_JSON }
enum class PrivacyClass { STANDARD, CONTRIBUTOR }
enum class QualificationState { UNQUALIFIED, QUALIFIED, FAILED }
enum class ContextCapability { STANDARD, LONG }

data class LivePricingHint(
    val currency: String = "USD",
    val promptHint: String? = null,
    val completionHint: String? = null,
    val source: String = "live-optional",
)

data class ModelProfile(
    val cycloneId: String,
    val displayName: String,
    val openRouterSlug: String,
    val providerFamily: ProviderFamily,
    val contextCapability: ContextCapability = ContextCapability.STANDARD,
    val imageInput: Boolean = false,
    val structuredOutputMode: StructuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
    val reasoningCapable: Boolean = true,
    val visionFallbackEligible: Boolean = false,
    val privacyClass: PrivacyClass = PrivacyClass.STANDARD,
    val qualificationState: QualificationState = QualificationState.UNQUALIFIED,
    val lastSuccessfulPreflightEpochMs: Long? = null,
    val livePricing: LivePricingHint? = null,
    val description: String = "",
    val reasoningEffort: String = "medium",
    val allowProviderFallbacks: Boolean = true,
    val providerPreferences: List<String> = emptyList(),
) {
    val isContributor: Boolean get() = privacyClass == PrivacyClass.CONTRIBUTOR
}
