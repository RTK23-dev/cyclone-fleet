package com.cyclone.mobile.mind

import org.json.JSONObject

/** What a plugin answered for a run's wait (plan 48 run 4). A code is never here: only that it came and its length. */
data class MindPortAnswer(
    val state: String,
    val reason: String = "",
    val value: Any? = null,
    val url: String? = null,
    /** A file that reached the phone: its name and where it was saved. */
    val fileName: String? = null,
    val folder: String? = null,
    val codeLength: Int = 0,
    val plugin: String = "",
)

/**
 * Cyclone Ports for a running mission (plan 48 run 4): the Mind sends on out ports and waits on in ports through the
 * owner's PC. Present only when a PC's Port Hub is connected to this phone. Pure; the phone side is
 * `ports.PortOutboxLink`.
 */
interface MindPortsLink {
    fun skills(app: String?): List<JSONObject> = emptyList()
    fun sendTo(plugin: String, port: String, data: JSONObject, image: ByteArray?, mime: String, app: String?): String? = "This PC link does not support plugin requests."
    fun waitFor(plugin: String, port: String, match: JSONObject, timeoutS: Int, app: String?, cancelled: () -> Boolean): MindPortAnswer = MindPortAnswer("refused", "This PC link does not support plugin requests.")
    /** Queues an out-port message; null when queued, else why not. [app] is the app the run is in now. */
    fun send(port: String, data: JSONObject, image: ByteArray?, app: String?): String?

    /** Blocks until a plugin answers, the time runs out or [cancelled] says the run stopped. */
    fun wait(port: String, ask: String, timeoutS: Int, place: String?, app: String?, cancelled: () -> Boolean): MindPortAnswer

    /** The app or site a code is for ("package:…" or "chrome:https://…"), as a code fill will check it; null when unknown. */
    fun place(page: com.cyclone.mobile.agent.contract.AgentPageCard): String?
}

/** Only an image explicitly attached by the owner, never a screen capture or a model-provided path. */
data class MindPortPhoto(val bytes: ByteArray, val mime: String) {
    companion object {
        fun fromDataUrl(raw: String?): MindPortPhoto? = runCatching {
            if (raw == null || raw.length > 6_000_000) return null
            val m = Regex("^data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=]+)$").matchEntire(raw) ?: return null
            val bytes = java.util.Base64.getDecoder().decode(m.groupValues[2])
            if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
            MindPortPhoto(bytes, m.groupValues[1])
        }.getOrNull()
    }
}
