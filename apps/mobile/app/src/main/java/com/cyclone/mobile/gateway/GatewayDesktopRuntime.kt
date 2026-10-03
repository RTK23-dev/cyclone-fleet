package com.cyclone.mobile.gateway

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics
import org.json.JSONObject

internal object GatewayDesktopPreferences {
    private const val PREFS = "cyclone_desktop_gateway_v1"
    private const val CLIPBOARD_ENABLED = "clipboard_enabled"

    fun clipboardEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(CLIPBOARD_ENABLED, false)

    fun setClipboardEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CLIPBOARD_ENABLED, enabled).apply()
    }
}

internal object GatewayDesktopPairingManager {
    private val engine = DesktopPairingEngine()

    fun begin(context: Context, args: JSONObject): JSONObject {
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.begin.received")
        val challenge = try {
            engine.begin(args.optString("usbSessionId"), args.optString("pcNonce"))
        } catch (_: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Pairing request is invalid")
        }
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.begin.challenge_ready")

        // Pairing begin is intentionally protocol-only. Do not post Toasts, create windows, start
        // capture, initialize Accessibility runtimes, or perform any other UI/process transition
        // from the ADB socket worker. GatewaySettingsActivity already polls this engine and displays
        // the active code in-app, so the worker can return immediately with minimal crash surface.
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.begin.returning")
        return JSONObject()
            .put("challengeId", challenge.challengeId)
            .put("expiresAtMs", challenge.expiresAtMs)
            // The PC and phone have independent wall clocks. Send the protocol-bounded relative
            // lifetime as the cross-device authority; expiresAtMs remains for older companions and
            // the phone continues enforcing its own absolute deadline locally.
            .put("expiresInMs", DesktopPairingEngine.LIFETIME_MS)
            .put("attemptsAllowed", DesktopPairingEngine.MAX_ATTEMPTS)
            .put("credentialAuthority", false)
    }

    fun complete(context: Context, args: JSONObject): JSONObject {
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.complete.received")
        val completion = engine.complete(
            challengeId = args.optString("challengeId"),
            usbSessionId = args.optString("usbSessionId"),
            pcNonce = args.optString("pcNonce"),
            code = args.optString("code"),
        )
        if (completion is DesktopPairingCompletion.Failure) {
            val code = when (completion.reason) {
                DesktopPairingFailure.EXPIRED -> "PAIRING_EXPIRED"
                DesktopPairingFailure.REPLAY -> "PAIRING_REPLAY"
                DesktopPairingFailure.CODE_REJECTED -> "PAIRING_CODE_REJECTED"
                DesktopPairingFailure.ATTEMPTS_EXCEEDED -> "PAIRING_ATTEMPTS_EXCEEDED"
                DesktopPairingFailure.SESSION_MISMATCH -> "PAIRING_SESSION_MISMATCH"
                DesktopPairingFailure.INVALID_REQUEST -> "PROTOCOL_MISMATCH"
            }
            CycloneProcessDiagnostics.markStage(context, "gateway.pair.complete.rejected.$code")
            throw GatewayProtocolException(code, "Pairing could not be completed")
        }
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.complete.code_accepted")
        return pairingSuccess(context, "gateway.pair.complete")
    }

    fun approveQr(challengeId: String, pcNonce: String): Boolean =
        engine.approveQr(challengeId.trim(), pcNonce.trim())

    fun approveQrPayload(payload: String): Boolean {
        if (payload.length !in 32..1024) return false
        val uri = runCatching { Uri.parse(payload) }.getOrNull() ?: return false
        if (uri.scheme != "cyclone" || uri.host != "pair") return false
        val challenge = uri.getQueryParameter("challenge")?.takeIf { it.length in 8..160 } ?: return false
        val nonce = uri.getQueryParameter("nonce")?.takeIf { it.length in 16..200 } ?: return false
        return approveQr(challenge, nonce)
    }

    fun completeQr(context: Context, args: JSONObject): JSONObject {
        CycloneProcessDiagnostics.markStage(context, "gateway.pair.qr.complete.received")
        return when (val completion = engine.completeQr(
            challengeId = args.optString("challengeId"),
            usbSessionId = args.optString("usbSessionId"),
            pcNonce = args.optString("pcNonce"),
        )) {
            DesktopQrPairingCompletion.Pending -> JSONObject().put("paired", false).put("pending", true)
            DesktopQrPairingCompletion.Success -> pairingSuccess(context, "gateway.pair.qr.complete")
            is DesktopQrPairingCompletion.Failure -> {
                val code = failureCode(completion.reason)
                CycloneProcessDiagnostics.markStage(context, "gateway.pair.qr.complete.rejected.$code")
                throw GatewayProtocolException(code, "QR pairing could not be completed")
            }
        }
    }

    private fun pairingSuccess(context: Context, stage: String): JSONObject {
        val credential = if (GatewaySessionStore.enabled(context)) {
            CycloneProcessDiagnostics.markStage(context, "$stage.credential_rotate")
            GatewaySessionStore.rotate(context)
        } else {
            CycloneProcessDiagnostics.markStage(context, "$stage.credential_enable")
            GatewaySessionStore.enable(context)
        }
        CycloneProcessDiagnostics.markStage(context, "$stage.credential_ready")
        val response = JSONObject()
            .put("paired", true)
            .put("credential", credential)
            .put("credentialBits", 256)
        CycloneProcessDiagnostics.markStage(context, "$stage.returning")
        return response
    }

    private fun failureCode(reason: DesktopPairingFailure): String = when (reason) {
        DesktopPairingFailure.EXPIRED -> "PAIRING_EXPIRED"
        DesktopPairingFailure.REPLAY -> "PAIRING_REPLAY"
        DesktopPairingFailure.CODE_REJECTED -> "PAIRING_CODE_REJECTED"
        DesktopPairingFailure.ATTEMPTS_EXCEEDED -> "PAIRING_ATTEMPTS_EXCEEDED"
        DesktopPairingFailure.SESSION_MISMATCH -> "PAIRING_SESSION_MISMATCH"
        DesktopPairingFailure.INVALID_REQUEST -> "PROTOCOL_MISMATCH"
    }

    fun revoke(context: Context): JSONObject {
        engine.clear()
        if (GatewaySessionStore.enabled(context)) GatewaySessionStore.rotate(context)
        return JSONObject().put("revoked", true)
    }

    fun codeForUser(): String? = engine.activeForUser()?.code
    fun expiresAtForUser(): Long? = engine.activeForUser()?.expiresAtMs
    fun active(): Boolean = engine.activeForUser() != null
}

internal object GatewayManualDesktopAdapter {
    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val kind = args.optString("kind")
        if (kind !in DesktopManualControlContract.allowedKinds) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        val snapshot = runCatching { CycloneAccessibilityService.instance?.observe(markFresh = true) }.getOrNull()
        val request = when (kind) {
            "tap" -> {
                val width = snapshot?.screenWidth ?: 0
                val height = snapshot?.screenHeight ?: 0
                val x = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble("x", Double.NaN), width)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "tap x is invalid", requestId)
                }
                val y = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble("y", Double.NaN), height)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "tap y is invalid", requestId)
                }
                PhoneToolRequest(requestId, "phone.tap", JSONObject().put("x", x).put("y", y).put("waitForChangeMs", 0))
            }
            "swipe" -> {
                val width = snapshot?.screenWidth ?: 0
                val height = snapshot?.screenHeight ?: 0
                fun pixel(name: String, size: Int): Int = try {
                    DesktopManualControlContract.normalizedPixel(args.optDouble(name, Double.NaN), size)
                } catch (_: IllegalArgumentException) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "$name is invalid", requestId)
                }
                PhoneToolRequest(
                    requestId,
                    "phone.swipe",
                    JSONObject()
                        .put("x1", pixel("x1", width))
                        .put("y1", pixel("y1", height))
                        .put("x2", pixel("x2", width))
                        .put("y2", pixel("y2", height))
                        .put("durationMs", args.optLong("durationMs", 350L).coerceIn(100L, 3000L))
                        .put("waitForChangeMs", 0),
                )
            }
            "back" -> PhoneToolRequest(requestId, "phone.back", JSONObject().put("waitForChangeMs", 0))
            "home" -> PhoneToolRequest(requestId, "phone.home", JSONObject().put("waitForChangeMs", 0))
            "scroll_up" -> PhoneToolRequest(requestId, "phone.scroll", JSONObject().put("direction", "backward").put("waitForChangeMs", 0))
            "scroll_down" -> PhoneToolRequest(requestId, "phone.scroll", JSONObject().put("direction", "forward").put("waitForChangeMs", 0))
            "text" -> {
                val value = args.optString("text")
                if (value.isBlank() || value.length > 4096) {
                    throw GatewayProtocolException("PROTOCOL_MISMATCH", "text batch is invalid", requestId)
                }
                PhoneToolRequest(requestId, "phone.type", JSONObject().put("value", value).put("waitForChangeMs", 0))
            }
            "wake" -> {
                val power = context.getSystemService(PowerManager::class.java)
                if (power?.isInteractive == true) {
                    return JSONObject().put("ok", true).put("kind", "wake").put("status", "ALREADY_AWAKE")
                }
                throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Safe wake is unavailable on this build", requestId)
            }
            else -> throw GatewayProtocolException("PROTOCOL_MISMATCH", "Unsupported manual control kind", requestId)
        }
        val result = PhoneToolExecutor.execute(context, request)
        return JSONObject()
            .put("ok", result.ok)
            .put("kind", kind)
            .put("error", result.error?.toJson() ?: JSONObject.NULL)
            .put("beforeFingerprint", result.beforeFingerprint ?: JSONObject.NULL)
            .put("afterFingerprint", result.afterFingerprint ?: JSONObject.NULL)
            .put("typedValueRedacted", kind == "text")
    }
}

internal object GatewayClipboardAdapter {
    fun capability(context: Context): JSONObject = JSONObject()
        .put("mode", "PC_TO_PHONE")
        .put("enabled", GatewayDesktopPreferences.clipboardEnabled(context))
        .put("reverseSync", "UNAVAILABLE")

    fun set(context: Context, requestId: String, args: JSONObject): JSONObject {
        if (!GatewayDesktopPreferences.clipboardEnabled(context)) {
            throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Clipboard sync is disabled on the phone", requestId)
        }
        val value = args.optString("text")
        if (value.isBlank() || value.length > 16_384 || DesktopClipboardPolicy.looksSensitive(value)) {
            throw GatewayProtocolException("POLICY_DENIED", "Clipboard content was rejected by the privacy filter", requestId)
        }
        runCatching { CycloneAccessibilityService.instance?.observe(markFresh = true) }
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest(requestId, "phone.set_clipboard", JSONObject().put("text", value).put("waitForChangeMs", 0)),
        )
        return JSONObject()
            .put("updated", result.ok)
            .put("mode", "PC_TO_PHONE")
            .put("contentRedacted", true)
            .put("error", result.error?.toJson() ?: JSONObject.NULL)
    }
}
