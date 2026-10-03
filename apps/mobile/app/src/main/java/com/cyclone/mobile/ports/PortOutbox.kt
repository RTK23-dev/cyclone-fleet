package com.cyclone.mobile.ports

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 48, run 4: the phone's side of Cyclone Ports.
 *
 * The phone never calls the PC. A run's messages wait here until the PC's Port Hub picks them up:
 * - `ports.poll` collects them and acknowledges the ones it handled;
 * - `ports.blob` fetches a screenshot in chunks;
 * - `ports.answer` brings back what a plugin delivered for a wait.
 *
 * Out ports (run.event, log.line, screen.shot, page.text, account.fields) are fire and forget. An in-port wait (code.in,
 * value.in, link.in, file.in) blocks the run until the PC answers, the time runs out or the run stops.
 *
 * A PC that polled in the last [CONNECTED_MS] counts as connected. Without one, a run is told so at once instead of
 * waiting for nothing.
 *
 * Nothing secret passes here:
 * - field names that look secret are dropped before they are queued;
 * - text lines that look like `password: …` are hidden;
 * - a code arrives sealed to this phone's key and is opened by [openCode]; the wait's answer carries only its length.
 */
class PortOutbox(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { "pt_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20) },
) {
    /** What a plugin answered for a wait; never a code. */
    data class Answer(
        val state: String,
        val reason: String = "",
        val value: Any? = null,
        val url: String? = null,
        val file: JSONObject? = null,
        val codeLength: Int = 0,
        val plugin: String = "",
    )

    /** Who a run is: its id (the mission), the app it works in and the routine or task it belongs to. */
    data class Run(val runId: String, val app: String? = null, val routine: String? = null, val taskId: String? = null, val plugin: String? = null)

    /**
     * Opens a sealed code for a wait and holds it on the phone. Returns the code's length; throws when it does not open.
     * Production: [com.cyclone.mobile.secrets.SealedDelivery.openCode].
     */
    var openCode: ((run: Run, place: String, sealed: JSONObject) -> Int)? = null

    private class Item(val id: String, val json: JSONObject, val blob: ByteArray?, val queuedAt: Long) {
        /** Handed to the PC by a poll at least once (it may not have acknowledged it yet). */
        var sent = false
    }

    private class Waiting(val run: Run, val port: String, val place: String?) {
        val latch = CountDownLatch(1)
        @Volatile var answer: Answer? = null
    }

    private val lock = Any()
    private val queue = ArrayList<Item>()
    private val waits = HashMap<String, Waiting>()
    @Volatile private var lastPollMs = Long.MIN_VALUE / 2
    @Volatile private var approvedSkills: List<JSONObject> = emptyList()

    fun connected(): Boolean = clock() - lastPollMs <= CONNECTED_MS
    fun skills(app: String?, routine: String?): List<JSONObject> = if (!connected()) emptyList() else
        approvedSkills.filter { PortSkills.matches(it, app, routine) }.map { JSONObject(it.toString()) }
    private fun permitted(run: Run, port: String): Boolean = run.plugin != null &&
        skills(run.app, run.routine).any { it.optString("name") == run.plugin && PortSkills.has(it, port) }

    // ---- the run's side --------------------------------------------------------------------------------------------

    /**
     * Queues an out-port message. Returns null when it is queued, or why not (no PC, a port the phone does not send,
     * nothing left to send after hiding secrets).
     */
    fun emit(run: Run, port: String, data: JSONObject, image: ByteArray? = null, mime: String = "image/png"): String? {
        if (!connected()) return NO_PC
        if (run.plugin != null && !permitted(run, port)) return "this plugin is not allowed here on $port"
        if (port !in OUT_PORTS && !(run.plugin != null && (port == "file.out" || port.startsWith("x.${run.plugin}.")) && permitted(run, port))) return "$port is not a port the phone sends on"
        val clean = PortScrub.data(port, data) ?: return "nothing was left to send once private fields were taken out"
        if (port in setOf("screen.shot", "file.out") && (image == null || image.isEmpty())) return "no image to send"
        if (image != null && mime !in setOf("image/png", "image/jpeg", "image/webp")) return "unsupported image format"
        if (image != null && image.size > MAX_BLOB) return "the screenshot is too large"
        val json = JSONObject().put("kind", "emit").put("port", port).put("data", clean)
        if (image != null) json.put("blob", JSONObject().put("bytes", image.size).put("mime", mime))
        enqueue(run, json, image)
        return null
    }

    /**
     * Waits on an in port until a plugin answers, [timeoutS] passes or [cancelled] says the run stopped. [place] binds a
     * code to the app or site it is for. Blocks the calling thread.
     */
    fun await(run: Run, port: String, ask: String, timeoutS: Int, place: String? = null, cancelled: () -> Boolean = { false }): Answer =
        awaitMatched(run, port, ask, timeoutS, place, null, cancelled)

    fun awaitMatched(run: Run, port: String, ask: String, timeoutS: Int, place: String? = null, match: JSONObject? = null, cancelled: () -> Boolean = { false }): Answer {
        if (!connected()) return Answer("no_pc", NO_PC)
        if (port !in IN_PORTS) return Answer("refused", "$port is not a port the phone waits on")
        if (run.plugin != null && !permitted(run, port)) return Answer("refused", "this plugin is not allowed here")
        if (match != null && (run.plugin == null || port !in setOf("value.in", "file.in") ||
            match.keys().asSequence().any { it !in setOf("ask", "requestId", "output") } ||
            match.keys().asSequence().any { k -> match.opt(k) !is String || match.getString(k).length > if (k == "ask") 200 else 80 }))
            return Answer("refused", "invalid plugin request match")
        if (port == "code.in" && place == null) return Answer("refused", "a code needs the app or site it is for; open it first")
        val timeout = timeoutS.coerceIn(MIN_WAIT_S, MAX_WAIT_S)
        val waiting = Waiting(run, port, place)
        val json = JSONObject().put("kind", "await").put("port", port).put("timeoutS", timeout)
            .put("match", match?.let { JSONObject(it.toString()) } ?: JSONObject().put("ask", PortScrub.text(ask).take(200)))
        if (place != null) json.put("place", place)
        val id = synchronized(lock) { enqueue(run, json, null).also { waits[it] = waiting } }
        // The PC times the wait out itself; the phone's own limit only covers a PC that went away.
        val deadline = clock() + (timeout + GRACE_S) * 1000L
        try {
            while (true) {
                if (waiting.latch.await(STEP_MS, TimeUnit.MILLISECONDS)) return waiting.answer!!
                if (cancelled()) return giveUp(run, id, Answer("cancelled", "the run stopped"))
                if (clock() >= deadline) {
                    return giveUp(run, id, Answer("timed_out", if (connected()) "nothing came in time" else "the PC stopped answering"))
                }
            }
        } finally {
            synchronized(lock) { waits.remove(id) }
        }
    }

    private fun giveUp(run: Run, awaitItem: String, answer: Answer): Answer {
        synchronized(lock) {
            // Never handed to the PC: take it back. Handed over: tell the PC to cancel it.
            if (queue.removeAll { it.id == awaitItem && !it.sent }) return answer
        }
        enqueue(run, JSONObject().put("kind", "cancel").put("item", awaitItem).put("reason", answer.state), null)
        return answer
    }

    private fun enqueue(run: Run, json: JSONObject, blob: ByteArray?): String {
        val id = ids()
        json.put("id", id).put("runId", run.runId).put("at", clock())
        run.app?.let { json.put("app", it) }
        run.routine?.let { json.put("routine", it) }
        run.taskId?.let { json.put("taskId", it) }
        run.plugin?.let { json.put("plugin", it) }
        synchronized(lock) {
            queue.add(Item(id, json, blob, clock()))
            // A PC that stops polling must not grow the queue: the oldest out messages go first, waits never.
            while (queue.size > MAX_QUEUE) {
                val drop = queue.firstOrNull { it.json.optString("kind") == "emit" } ?: break
                queue.remove(drop)
            }
            var blobs = queue.count { it.blob != null }
            while (blobs > MAX_BLOBS) {
                queue.remove(queue.first { it.blob != null })
                blobs--
            }
        }
        return id
    }

    // ---- the PC's side (gateway ops) ---------------------------------------------------------------------------------

    /**
     * `ports.poll {ack?, max?, drop?}` → `{items}`. [ack] removes items the PC handled; [drop] removes the first item
     * when the PC could not take it (it looked secret to the PC's own check), failing its wait.
     */
    fun poll(args: JSONObject): JSONObject {
        if (args.keys().asSequence().any { it !in setOf("ack", "max", "drop", "skills") }) throw IllegalArgumentException("ports.poll takes ack, max, drop and skills.")
        val newSkills = args.optJSONArray("skills")?.let(PortSkills::parse).orEmpty()
        val ack = args.optJSONArray("ack")?.let { a -> (0 until minOf(a.length(), 100)).map { a.optString(it) } }.orEmpty().toSet()
        val max = args.optInt("max", MAX_ITEMS).coerceIn(1, MAX_ITEMS)
        lastPollMs = clock()
        approvedSkills = newSkills
        val dropped: Item?
        val out = JSONArray()
        synchronized(lock) {
            queue.removeAll { it.id in ack }
            dropped = if (args.optBoolean("drop")) queue.removeFirstOrNull() else null
            val stale = clock() - STALE_MS
            queue.removeAll { it.json.optString("kind") == "emit" && it.queuedAt < stale }
            queue.take(max).forEach { it.sent = true; out.put(it.json) }
        }
        dropped?.let { item -> synchronized(lock) { waits[item.id] }?.let { finish(it, Answer("refused", "the PC refused it: it looked like a secret")) } }
        return JSONObject().put("items", out)
    }

    /** `ports.blob {id, offset}` → `{data, bytes, done}`: a screenshot in chunks. */
    fun blob(args: JSONObject): JSONObject {
        val id = args.optString("id")
        val offset = args.optInt("offset", -1)
        val blob = synchronized(lock) { queue.firstOrNull { it.id == id }?.blob } ?: throw NoSuchElementException("No such file.")
        if (offset !in 0 until blob.size) throw IllegalArgumentException("Bad offset.")
        val end = minOf(blob.size, offset + BLOB_CHUNK)
        return JSONObject().put("data", Base64.getEncoder().encodeToString(blob.copyOfRange(offset, end)))
            .put("bytes", blob.size).put("done", end == blob.size)
    }

    /**
     * `ports.answer {id, state, reason?, plugin?, value?, url?, file?, sealed?}` → `{handled}`. A delivered code comes
     * as `sealed` and is opened here; the run only learns that it is ready.
     */
    fun answer(args: JSONObject): JSONObject {
        if (args.keys().asSequence().any { it !in ANSWER_KEYS }) throw IllegalArgumentException("Unexpected answer field.")
        val id = args.optString("id")
        val state = args.optString("state")
        if (state !in ANSWER_STATES) throw IllegalArgumentException("Unknown answer state.")
        val waiting = synchronized(lock) { waits[id] } ?: return JSONObject().put("handled", false)
        val reason = args.optString("reason").take(200)
        val plugin = args.optString("plugin").take(80)
        val answer = if (state != "delivered") Answer(state, reason, plugin = plugin) else when (waiting.port) {
            "code.in" -> {
                val sealed = args.optJSONObject("sealed")
                val opener = openCode
                val length = if (sealed == null || opener == null || waiting.place == null) null
                    else runCatching { opener(waiting.run, waiting.place, sealed) }.getOrNull()
                if (length == null) Answer("failed", "the code did not open on this phone", plugin = plugin)
                else Answer("delivered", codeLength = length, plugin = plugin)
            }
            "link.in" -> args.optString("url").takeIf { it.startsWith("https://") && it.length <= 2_000 }
                ?.let { Answer("delivered", url = it, plugin = plugin) } ?: Answer("failed", "the link was not https", plugin = plugin)
            "file.in" -> args.optJSONObject("file")?.let { Answer("delivered", file = it, plugin = plugin) }
                ?: Answer("failed", "the file did not arrive", plugin = plugin)
            else -> if (args.has("value")) Answer("delivered", value = args.get("value"), plugin = plugin)
                else Answer("failed", "no value came", plugin = plugin)
        }
        finish(waiting, answer)
        return JSONObject().put("handled", true)
    }

    private fun finish(waiting: Waiting, answer: Answer) {
        if (waiting.answer == null) {
            waiting.answer = answer
            waiting.latch.countDown()
        }
    }

    internal fun pending(): List<JSONObject> = synchronized(lock) { queue.map { it.json } }

    companion object {
        const val CONNECTED_MS = 20_000L
        const val NO_PC = "no PC is connected to send it through (Cyclone Ports run on the owner's PC)"
        val OUT_PORTS = setOf("run.event", "log.line", "screen.shot", "page.text", "account.fields")
        val IN_PORTS = setOf("code.in", "value.in", "link.in", "file.in")
        val ANSWER_STATES = setOf("delivered", "timed_out", "cancelled", "failed", "empty", "conflict", "off", "unavailable", "refused")
        private val ANSWER_KEYS = setOf("id", "state", "reason", "plugin", "value", "url", "file", "sealed")
        const val MIN_WAIT_S = 5
        const val MAX_WAIT_S = 600
        private const val GRACE_S = 20
        private const val STEP_MS = 250L
        private const val MAX_QUEUE = 60
        private const val MAX_BLOBS = 3
        private const val MAX_ITEMS = 20
        const val MAX_BLOB = 4 * 1024 * 1024
        const val BLOB_CHUNK = 384 * 1024
        /** Out messages the PC has not picked up in this long are dropped. */
        private const val STALE_MS = 5 * 60_000L

        /** The phone's one outbox; the gateway adapter and every mission share it. */
        val shared = PortOutbox()
    }
}

/**
 * What may leave the phone on a port. It mirrors the PC's own check (`reject_secret_payload`), so the PC never has to
 * refuse a message: secret-looking field names are dropped, secret-looking text lines hidden.
 */
object PortScrub {
    private val SECRET_WORDS = setOf("password", "passcode", "passwd", "pin", "otp", "token", "secret", "apikey", "api", "authorization",
        "cookie", "cvv", "cvc", "credential", "credentials", "typedtext", "typedvalue", "card")
    private val INLINE = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))\\s*[:=]")
    private val STAGES = setOf("started", "page", "step", "needs_you", "created", "done", "failed", "cancelled")

    fun secretName(key: String): Boolean {
        val normalized = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().replace('-', '_').replace(' ', '_')
        return normalized.replace("_", "") in SECRET_WORDS || normalized.split('_').any { it in SECRET_WORDS }
    }

    /** Text with every secret-looking line replaced. */
    fun text(value: String): String = value.lines().joinToString("\n") { if (INLINE.containsMatchIn(it)) "[hidden]" else it }

    /** A port's data, cleaned; null when nothing sendable is left. */
    fun data(port: String, data: JSONObject): JSONObject? = when (port) {
        "run.event" -> data.optString("stage").takeIf { it in STAGES }?.let { stage ->
            JSONObject().put("stage", stage).apply { data.optString("note").takeIf { it.isNotBlank() }?.let { put("note", text(it).take(300)) } }
        }
        "log.line" -> text(data.optString("text")).trim().take(500).takeIf { it.isNotBlank() }?.let { JSONObject().put("text", it) }
        "page.text" -> text(data.optString("text")).take(20_000).takeIf { it.isNotBlank() }?.let { JSONObject().put("text", it) }
        "account.fields" -> {
            val fields = data.optJSONObject("fields") ?: JSONObject()
            val clean = JSONObject()
            fields.keys().asSequence().take(24).filter { !secretName(it) && it.length <= 60 }.forEach { key ->
                val value = fields.opt(key)
                if (value is String && value.length <= 300 && !INLINE.containsMatchIn(value)) clean.put(key, value)
            }
            if (clean.length() == 0) null else JSONObject().put("fields", clean)
        }
        "screen.shot" -> JSONObject().apply { data.optString("pageKey").takeIf { it.isNotBlank() }?.let { put("pageKey", it.take(160)) } }
        else -> if (port == "file.out" || port.startsWith("x.")) runCatching {
            require(data.toString().length <= 16_000)
            checkData(data, 0)
            JSONObject(data.toString())
        }.getOrNull() else null
    }

    private fun checkData(value: Any?, depth: Int) {
        require(depth <= 8)
        when (value) {
            is JSONObject -> { require(value.length() <= 60); value.keys().asSequence().forEach { key -> require(!secretName(key)); checkData(value.opt(key), depth + 1) } }
            is JSONArray -> { require(value.length() <= 60); for (i in 0 until value.length()) checkData(value.opt(i), depth + 1) }
            is String -> require(value.length <= 4000 && !INLINE.containsMatchIn(value))
        }
    }
}
