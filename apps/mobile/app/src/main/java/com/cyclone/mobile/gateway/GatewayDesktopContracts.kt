package com.cyclone.mobile.gateway

import java.security.SecureRandom
import java.util.Base64
import kotlin.math.roundToInt

internal enum class DesktopPairingFailure {
    EXPIRED, REPLAY, CODE_REJECTED, ATTEMPTS_EXCEEDED, SESSION_MISMATCH, INVALID_REQUEST,
}

internal data class DesktopPairingChallenge(
    val challengeId: String,
    val usbSessionId: String,
    val pcNonce: String,
    val code: String,
    val expiresAtMs: Long,
    var attempts: Int = 0,
    var qrApproved: Boolean = false,
)

internal sealed class DesktopPairingCompletion {
    data object Success : DesktopPairingCompletion()
    data class Failure(val reason: DesktopPairingFailure) : DesktopPairingCompletion()
}

internal sealed class DesktopQrPairingCompletion {
    data object Pending : DesktopQrPairingCompletion()
    data object Success : DesktopQrPairingCompletion()
    data class Failure(val reason: DesktopPairingFailure) : DesktopQrPairingCompletion()
}

internal class DesktopPairingEngine(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val LIFETIME_MS = 60_000L
        const val MAX_ATTEMPTS = 5
    }

    private var active: DesktopPairingChallenge? = null
    private val consumed = LinkedHashSet<String>()

    @Synchronized
    fun begin(usbSessionId: String, pcNonce: String): DesktopPairingChallenge {
        require(usbSessionId.isNotBlank()) { "usbSessionId is required" }
        require(pcNonce.length >= 16) { "pcNonce is too short" }
        val challenge = DesktopPairingChallenge(
            challengeId = randomToken(18),
            usbSessionId = usbSessionId.take(160),
            pcNonce = pcNonce.take(200),
            code = buildString(4) { repeat(4) { append(('A'.code + random.nextInt(26)).toChar()) } },
            expiresAtMs = nowMs() + LIFETIME_MS,
        )
        active = challenge
        return challenge
    }

    @Synchronized
    fun complete(challengeId: String, usbSessionId: String, pcNonce: String, code: String): DesktopPairingCompletion {
        if (challengeId in consumed) return DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        val current = active ?: return DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        if (current.challengeId != challengeId) return DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        if (nowMs() > current.expiresAtMs) {
            active = null
            consumed(current.challengeId)
            return DesktopPairingCompletion.Failure(DesktopPairingFailure.EXPIRED)
        }
        if (current.usbSessionId != usbSessionId || current.pcNonce != pcNonce) {
            active = null
            consumed(current.challengeId)
            return DesktopPairingCompletion.Failure(DesktopPairingFailure.SESSION_MISMATCH)
        }
        if (!Regex("^[A-Z]{4}$").matches(code) || !constantTimeEquals(current.code, code)) {
            current.attempts += 1
            if (current.attempts >= MAX_ATTEMPTS) {
                active = null
                consumed(current.challengeId)
                return DesktopPairingCompletion.Failure(DesktopPairingFailure.ATTEMPTS_EXCEEDED)
            }
            return DesktopPairingCompletion.Failure(DesktopPairingFailure.CODE_REJECTED)
        }
        active = null
        consumed(current.challengeId)
        return DesktopPairingCompletion.Success
    }

    /** Approves only the currently displayed one-time challenge after the user scans it locally. */
    @Synchronized
    fun approveQr(challengeId: String, pcNonce: String): Boolean {
        if (challengeId in consumed) return false
        val current = active ?: return false
        if (nowMs() > current.expiresAtMs) {
            active = null
            consumed(current.challengeId)
            return false
        }
        if (current.challengeId != challengeId || !constantTimeEquals(current.pcNonce, pcNonce)) return false
        current.qrApproved = true
        return true
    }

    @Synchronized
    fun completeQr(challengeId: String, usbSessionId: String, pcNonce: String): DesktopQrPairingCompletion {
        if (challengeId in consumed) return DesktopQrPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        val current = active ?: return DesktopQrPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        if (current.challengeId != challengeId) return DesktopQrPairingCompletion.Failure(DesktopPairingFailure.REPLAY)
        if (nowMs() > current.expiresAtMs) {
            active = null
            consumed(current.challengeId)
            return DesktopQrPairingCompletion.Failure(DesktopPairingFailure.EXPIRED)
        }
        if (current.usbSessionId != usbSessionId || !constantTimeEquals(current.pcNonce, pcNonce)) {
            active = null
            consumed(current.challengeId)
            return DesktopQrPairingCompletion.Failure(DesktopPairingFailure.SESSION_MISMATCH)
        }
        if (!current.qrApproved) return DesktopQrPairingCompletion.Pending
        active = null
        consumed(current.challengeId)
        return DesktopQrPairingCompletion.Success
    }

    @Synchronized
    fun activeForUser(): DesktopPairingChallenge? = active?.takeIf { nowMs() <= it.expiresAtMs }

    @Synchronized
    fun clear() {
        active = null
    }

    private fun consumed(id: String) {
        consumed.add(id)
        while (consumed.size > 64) consumed.remove(consumed.first())
    }

    private fun randomToken(bytes: Int): String {
        val value = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(Charsets.US_ASCII)
        val b = right.toByteArray(Charsets.US_ASCII)
        var diff = a.size xor b.size
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            diff = diff or ((a.getOrElse(index) { 0 }).toInt() xor (b.getOrElse(index) { 0 }).toInt())
        }
        return diff == 0
    }
}

internal object DesktopManualControlContract {
    val allowedKinds = setOf("tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text", "wake")

    fun normalizedPixel(value: Double, pixels: Int): Int {
        require(value.isFinite() && value in 0.0..1.0) { "normalized coordinate must be 0.0..1.0" }
        require(pixels > 0) { "display dimension must be positive" }
        return (value * (pixels - 1)).roundToInt().coerceIn(0, pixels - 1)
    }
}

internal object DesktopClipboardPolicy {
    private val keyword = Regex("(?i)(password|passcode|otp|one.?time|verification.?code|api.?key|bearer|token|secret|cvv|pin)\\s*[:=]?")
    private val jwt = Regex("^[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}$")
    private val longSecret = Regex("^[A-Za-z0-9_+\\-/=]{32,}$")
    private val otpLike = Regex("^\\s*\\d{4,8}\\s*$")

    fun looksSensitive(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        return keyword.containsMatchIn(value) || jwt.matches(value) || longSecret.matches(value) || otpLike.matches(value)
    }
}
