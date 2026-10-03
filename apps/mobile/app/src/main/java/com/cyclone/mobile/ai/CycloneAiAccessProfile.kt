package com.cyclone.mobile.ai

import android.content.Context
import org.json.JSONObject

/**
 * The user's standing boundary for AI-originated phone actions.
 *
 * This is deliberately separate from Android permissions. Android decides what Cyclone can do;
 * this profile decides which of those already-granted capabilities an AI may use without a new
 * local confirmation. No profile can approve authentication, financial, destructive, security-
 * critical or final external-communication actions.
 */
enum class CycloneAiAccessProfile(
    val storageValue: String,
    val displayName: String,
    val summary: String,
) {
    GUIDED(
        storageValue = "guided",
        displayName = "Guided",
        summary = "Navigate and inspect. Cyclone cannot type, long-press or open sharing flows.",
    ),
    BALANCED(
        storageValue = "balanced",
        displayName = "Balanced",
        summary = "Recommended. Routine taps and ordinary typing are allowed; consequential boundaries stop.",
    ),
    FULL(
        storageValue = "full",
        displayName = "Full control",
        summary = "All supported routine controls, including compose and share screens. Sensitive final actions still ask.",
    );

    companion object {
        fun fromStorage(value: String?): CycloneAiAccessProfile = entries.firstOrNull {
            it.storageValue == value?.trim()?.lowercase()
        } ?: BALANCED
    }
}

object CycloneAiAccessProfileStore {
    private const val PREFS = "cyclone_ai"
    private const val PROFILE_KEY = "ai_access_profile"
    private const val LEGACY_SAFE_MODE_KEY = "safe_mode"

    fun read(context: Context): CycloneAiAccessProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(PROFILE_KEY)) {
            return CycloneAiAccessProfile.fromStorage(prefs.getString(PROFILE_KEY, null))
        }
        // Preserve the meaning of existing installs while moving to one explicit source of truth.
        return if (prefs.getBoolean(LEGACY_SAFE_MODE_KEY, true)) {
            CycloneAiAccessProfile.BALANCED
        } else {
            CycloneAiAccessProfile.FULL
        }
    }

    fun write(context: Context, profile: CycloneAiAccessProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PROFILE_KEY, profile.storageValue)
            // Older dormant UI versions still read this key. Keep it as a compatibility mirror,
            // never as an independent authority.
            .putBoolean(LEGACY_SAFE_MODE_KEY, profile != CycloneAiAccessProfile.FULL)
            .apply()
    }
}

data class CycloneAiAccessDecision(
    val allowed: Boolean,
    val reasonCode: String,
    val safeMessage: String,
)

/** Pure, deterministic guard shared by on-phone AI and the PC/Codex policy adapter. */
object CycloneAiAccessPolicy {
    private val guidedBlockedTools = setOf(
        "phone.long_press",
        "phone.tap",
        "phone.type",
        "phone.replace_text",
        "phone.set_clipboard",
        "phone.share",
        "phone.launch_intent",
        "phone.submit_text",
        "phone.tap_point",
    )

    private val balancedBlockedTools = setOf("phone.share")

    private val consequentialTargetWords = setOf(
        "pay",
        "purchase",
        "buy",
        "place order",
        "confirm order",
        "transfer",
        "send",
        "submit",
        "delete",
        "remove",
        "uninstall",
        "confirm payment",
        "book now",
        "sign in",
        "log in",
        "verify",
    )

    private val sensitiveFieldWords = setOf(
        "password",
        "passcode",
        "otp",
        "one time",
        "verification code",
        "pin",
        "token",
        "secret",
        "cvv",
        "card number",
    )

    private val searchFieldWords = setOf("search", "zoek", "query", "url", "address", "omnibox", "location_bar", "find")

    fun evaluate(
        profile: CycloneAiAccessProfile,
        tool: String,
        params: JSONObject,
    ): CycloneAiAccessDecision {
        val target = semanticTarget(params)
        if (tool in setOf("phone.type", "phone.replace_text") && sensitiveFieldWords.any(target::contains)) {
            return denied(
                "LOCAL_CONFIRMATION_REQUIRED",
                "Cyclone stopped at a sensitive text field. Enter credentials or verification codes yourself.",
            )
        }
        if (tool in setOf("phone.click", "phone.long_press", "phone.tap") && consequentialTargetWords.any(target::contains)) {
            if (params.optBoolean("autofill_authorized") && LoginAutofillPolicy.isLoginSubmitLabel(target)) {
                return CycloneAiAccessDecision(true, "LOGIN_AUTOFILL_AUTHORIZED", "User authorized login autofill.")
            }
            return denied(
                "LOCAL_CONFIRMATION_REQUIRED",
                "Cyclone stopped before a consequential action that needs your confirmation.",
            )
        }
        // The keyboard action key can send a message or submit a form. Only search and address fields submit freely.
        if (tool == "phone.submit_text" && searchFieldWords.none(target::contains)) {
            return denied(
                "LOCAL_CONFIRMATION_REQUIRED",
                "Cyclone stopped before pressing Enter in a field that may send or submit something.",
            )
        }
        if (profile == CycloneAiAccessProfile.GUIDED && tool in guidedBlockedTools) {
            return denied(
                "PROFILE_GUIDED_BOUNDARY",
                "Guided access does not allow this interaction. Change the AI access profile in Settings if you want Cyclone to continue.",
            )
        }
        if (profile == CycloneAiAccessProfile.BALANCED && tool in balancedBlockedTools) {
            return denied(
                "PROFILE_BALANCED_BOUNDARY",
                "Balanced access stops before opening a sharing flow.",
            )
        }
        if (profile != CycloneAiAccessProfile.FULL && tool == "phone.launch_intent") {
            val uri = params.optString("uri").trim().lowercase()
            if (uri.startsWith("tel:") || uri.startsWith("sms:") || uri.startsWith("mailto:")) {
                return denied(
                    "PROFILE_COMMUNICATION_BOUNDARY",
                    "This profile does not allow Cyclone to open a call or message composer.",
                )
            }
        }
        return CycloneAiAccessDecision(true, "PROFILE_ALLOWED", "Allowed by ${profile.displayName} access.")
    }

    private fun semanticTarget(params: JSONObject): String {
        val selector = params.optJSONObject("selector") ?: params
        return listOf(
            selector.optString("text"),
            selector.optString("textContains"),
            selector.optString("contentDescription"),
            selector.optString("fuzzyText"),
            selector.optString("resourceId"),
        ).joinToString(" ").lowercase()
    }

    private fun denied(reasonCode: String, message: String) = CycloneAiAccessDecision(
        allowed = false,
        reasonCode = reasonCode,
        safeMessage = message,
    )
}
