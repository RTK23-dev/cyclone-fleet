package com.cyclone.mobile.ai.model

import com.cyclone.mobile.ai.OpenRouterModelPreset

object ModelRegistry {
    val GPT_6_ASTRA = ModelProfile(
        cycloneId = "gpt-6-astra",
        displayName = "GPT-6 Astra",
        openRouterSlug = "openai/gpt-6-astra",
        providerFamily = ProviderFamily.OPENAI,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.SCHEMA_CONSTRAINED,
        visionFallbackEligible = true,
        reasoningEffort = "high",
        description = "OpenAI reasoning profile for Cyclone phone tasks.",
    )
    val CLAUDE_FABLE_5_1 = ModelProfile(
        cycloneId = "claude-fable-5-1",
        displayName = "Claude Fable 5.1",
        openRouterSlug = "anthropic/claude-fable-5.1",
        providerFamily = ProviderFamily.ANTHROPIC,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.SCHEMA_CONSTRAINED,
        visionFallbackEligible = true,
        reasoningEffort = "high",
        description = "Anthropic reasoning profile for Cyclone phone tasks.",
    )
    val MUSE_SPARK_1_3 = ModelProfile(
        cycloneId = "muse-spark-1-3",
        displayName = "Muse Spark 1.3",
        openRouterSlug = "meta/muse-spark-1.3",
        providerFamily = ProviderFamily.META,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
        visionFallbackEligible = true,
        reasoningEffort = "max",
        description = "Standard Muse Spark profile.",
    )
    val MUSE_SPARK_1_3_CONTRIBUTOR = ModelProfile(
        cycloneId = "muse-spark-1-3-contributor",
        displayName = "Muse Spark 1.3 Contributor",
        openRouterSlug = "meta/muse-spark-1.3-contributor",
        providerFamily = ProviderFamily.META,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
        visionFallbackEligible = false,
        privacyClass = PrivacyClass.CONTRIBUTOR,
        reasoningEffort = "max",
        allowProviderFallbacks = false,
        description = "Data-contributing / lower-cost tier. Selection is explicit and never used as a fallback for standard Muse.",
    )

    val GPT_5_6_LUNA = ModelProfile(
        cycloneId = "gpt-5-6-luna",
        displayName = "GPT-5.6 Luna",
        openRouterSlug = "openai/gpt-5.6-luna",
        providerFamily = ProviderFamily.OPENAI,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.SCHEMA_CONSTRAINED,
        visionFallbackEligible = true,
    )
    val GEMINI_3_8_FLASH = ModelProfile(
        cycloneId = "gemini-3-8-flash",
        displayName = "Gemini 3.8 Flash",
        openRouterSlug = "google/gemini-3.8-flash",
        providerFamily = ProviderFamily.GOOGLE,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.SCHEMA_CONSTRAINED,
        visionFallbackEligible = true,
    )
    val GLM_5_3_FLASH = ModelProfile(
        cycloneId = "glm-5-3-flash",
        displayName = "GLM 5.3 Flash",
        openRouterSlug = "z-ai/glm-5.3-flash",
        providerFamily = ProviderFamily.ZHIPU,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.PORTABLE_JSON,
        visionFallbackEligible = true,
    )
    val GPT_5_6_SOL = ModelProfile(
        cycloneId = "gpt-5-6-sol",
        displayName = "GPT-5.6 Sol",
        openRouterSlug = "openai/gpt-5.6-sol",
        providerFamily = ProviderFamily.OPENAI,
        imageInput = true,
        structuredOutputMode = StructuredOutputMode.SCHEMA_CONSTRAINED,
        visionFallbackEligible = true,
        reasoningEffort = "high",
    )

    val all: List<ModelProfile> = listOf(
        GEMINI_3_8_FLASH,
        GPT_5_6_LUNA,
        GLM_5_3_FLASH,
        MUSE_SPARK_1_3,
        GPT_5_6_SOL,
        GPT_6_ASTRA,
        CLAUDE_FABLE_5_1,
        MUSE_SPARK_1_3_CONTRIBUTOR,
    )

    private val byCycloneId = all.associateBy { it.cycloneId }
    private val bySlug = all.associateBy { it.openRouterSlug }

    fun resolve(idOrSlug: String?): ModelProfile? {
        val clean = idOrSlug?.trim().orEmpty()
        return byCycloneId[clean] ?: bySlug[clean]
    }

    fun require(idOrSlug: String): ModelProfile =
        resolve(idOrSlug) ?: error("Unknown Cyclone model profile: $idOrSlug")

    fun preset(profile: ModelProfile): OpenRouterModelPreset = OpenRouterModelPreset(
        id = profile.openRouterSlug,
        label = profile.displayName,
        vision = profile.imageInput,
        reasoningEffort = profile.reasoningEffort,
    )

    fun profileForPreset(preset: OpenRouterModelPreset): ModelProfile? = resolve(preset.id)

    fun samePrivacyIdentity(left: ModelProfile, right: ModelProfile): Boolean =
        left.privacyClass == right.privacyClass &&
            (!left.isContributor || left.openRouterSlug == right.openRouterSlug)
}
