package com.cyclone.mobile.gateway

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class GatewayRequest(
    val id: String,
    val op: String,
    val args: JSONObject,
    val auth: String,
)

internal class GatewayProtocolException(
    val code: String,
    override val message: String,
    val requestId: String = "",
    val details: Any? = null,
) : IllegalArgumentException(message)

internal object GatewayProtocol {
    // Session operations are an additive Cyclone One extension. Keep the V3.3 transport version so
    // existing trusted PC companions remain wire-compatible and discover the new operations through
    // bridge.status instead of negotiating a second trust protocol.
    const val VERSION = "3.3"
    const val SOCKET_NAME = "cyclone_gateway"
    const val DEFAULT_FORWARD_PORT = 8766
    const val MAX_LINE_BYTES = 1024 * 1024

    /**
     * These operations either negotiate capabilities or carry their own signed challenge proof.
     * They never authorize a phone action by themselves.
     */
    val unauthenticatedOperations = setOf(
        "trust.negotiate",
        "trust.begin",
        "trust.complete",
        "trust.session.begin",
        "trust.session.complete",
        // One transition release keeps the old four-letter pairing bootstrap as an explicit
        // non-default compatibility path. Credentials from it are read-only at dispatch.
        "pair.begin",
        "pair.complete",
        "pair.qr.complete",
    )

    /** Legacy pairing credentials can inspect state but can never mutate under V3.3 rules. */
    val legacyReadOnlyOperations = setOf(
        "bridge.status",
        "session.list",
        "observe.semantic",
        "observe.page_debug",
        "capture.screenshot",
        "ui.search",
        "ui.element",
        "app_graph.get",
        "atlas.places",
        "atlas.get",
        "atlas.diff",
        "mapping.status",
        "secrets.slots",
        "ask.status",
        "apps.list",
        "runs.list",
        "runs.get",
        "atlas.versions",
        "scenarios.list",
        "knowledge.get",
        "atlas.here",
        "share.status",
        "brain.recall",
        "teach.status",
        "debug.snapshot",
        "dictionary.get",
        "models.list",
        "manual.get",
        "signup.maps",
        "profiles.list",
        "profiles.apps",
        "health.report",
    )

    val operations = linkedSetOf(
        "trust.negotiate",
        "trust.begin",
        "trust.complete",
        "trust.session.begin",
        "trust.session.complete",
        "trust.rotate",
        "trust.revoke",
        "bridge.status",
        "session.list",
        "session.start",
        "session.status",
        "session.pause",
        // PC gateway sends continue: ALLOWED_OPS rejects ops whose names contain "su" (resume).
        "session.continue",
        "session.resume",
        "session.handoff",
        "session.stop",
        "session.snapshot",
        "observe.semantic",
        "observe.page_debug",
        "capture.screenshot",
        "ui.search",
        "ui.element",
        "app_graph.get",
        "brain.recall",
        "action.execute",
        "teach.start",
        "teach.status",
        "teach.stop",
        "debug.snapshot",
        "pair.begin",
        "pair.complete",
        "pair.qr.complete",
        "pair.revoke",
        "manual.execute",
        "clipboard.get",
        "clipboard.set",
        "skill.compile",
        "skill.run",
        "skill.match",
        "atlas.places",
        "atlas.get",
        "atlas.diff",
        "mapping.start",
        "mapping.pause",
        "mapping.stop",
        "mapping.status",
        "secrets.slots",
        "secrets.request",
        "ask.start",
        "ask.status",
        "ask.cancel",
        "apps.list",
        "runs.list",
        "runs.get",
        "runs.mark",
        "atlas.versions",
        "scenarios.list",
        "knowledge.get",
        "atlas.here",
        "share.status",
        "share.request",
        // Cyclone Lab: the PC starts, watches, answers and reads back Mind missions for measurement.
        "lab.start",
        "lab.status",
        "lab.answer",
        "lab.record",
        // Cyclone Marketplace: the phone's store of recipes and connections, read and changed from Glass.
        "market.catalog",
        "market.install",
        "market.remove",
        "market.run",
        // Learn: one press per run turns what it saw and did into app knowledge.
        "learn.run",
        // Grounded skills: the owner's saved skills and where each lives on the map.
        "skills.list",
        // Command Center (plan 33): the PC assigns a task, follows it and answers its Owner Moments.
        "cc.start",
        "cc.status",
        "cc.answer",
        "cc.key",
        "cc.media",
        // Cyclone Ports (plan 48 run 4): the PC's Port Hub collects what runs send and answers their waits.
        "ports.poll",
        "ports.blob",
        "ports.answer",
        "ports.file",
        // The app dictionary (plan 36 §7): read it, the owner's edits from Glass, and the phone's models for the picker.
        "dictionary.get",
        "dictionary.edit",
        "models.list",
        // The App Manual (plan 36 §8): abilities, the self-quiz and the manual as text. Read only.
        "manual.get",
        // Plan 43 T6: the sign-up maps this phone learned (schemas only), and forgetting one.
        "signup.maps",
        "signup.forget",
        // Plan 49 (alpha.102): this phone's numbers (each SIM's and the owner's confirmed ones) for Glass → Numbers.
        "numbers.list",
        // Plan 43 T4: the phone's profiles, switching between them, and each Cyclone profile's apps.
        "profiles.list",
        "profiles.apps",
        "profiles.switch",
        "profiles.app",
        // Alpha 87: why Cyclone stopped last time and the freezes it caught (read only).
        "health.report",
    )

    fun parse(line: String): GatewayRequest {
        val json = try {
            JSONObject(line)
        } catch (error: Exception) {
            throw GatewayProtocolException("INVALID_JSON", "Request must be one UTF-8 JSON object per line")
        }
        val id = json.optString("id").trim()
        if (id.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "id is required")
        val op = json.optString("op").trim()
        if (op.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "op is required", id)
        val auth = json.optString("auth")
        if (auth.isBlank() && op !in unauthenticatedOperations) {
            throw GatewayProtocolException("AUTH_REQUIRED", "auth is required", id)
        }
        val argsValue = json.opt("args")
        if (argsValue != null && argsValue !== JSONObject.NULL && argsValue !is JSONObject) {
            throw GatewayProtocolException("INVALID_REQUEST", "args must be a JSON object", id)
        }
        return GatewayRequest(id, op, argsValue as? JSONObject ?: JSONObject(), auth)
    }

    fun requireKnownOperation(op: String, id: String = "") {
        if (op !in operations) throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported gateway operation: $op", id)
    }

    fun success(id: String, result: Any?): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", true)
        .put("result", result ?: JSONObject.NULL)
        .put("error", JSONObject.NULL)

    fun error(id: String, code: String, message: String, details: Any? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", false)
        .put("result", JSONObject.NULL)
        .put("error", JSONObject()
            .put("code", code)
            .put("message", message.take(600))
            .put("details", details ?: JSONObject.NULL))
}

internal object GatewayAuth {
    fun matches(expected: String?, supplied: String?): Boolean {
        if (expected.isNullOrBlank() || supplied.isNullOrBlank()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
    }
}

/** Bounded line reader so a forwarded client cannot grow the phone process without limit. */
internal object GatewayLineReader {
    fun readUtf8Line(input: InputStream, maxBytes: Int = GatewayProtocol.MAX_LINE_BYTES): String? {
        require(maxBytes > 0)
        val out = ByteArrayOutputStream(minOf(4096, maxBytes))
        while (true) {
            val next = input.read()
            if (next == -1) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (out.size() >= maxBytes) throw GatewayProtocolException("REQUEST_TOO_LARGE", "Gateway request exceeds $maxBytes bytes")
            out.write(next)
        }
    }
}
