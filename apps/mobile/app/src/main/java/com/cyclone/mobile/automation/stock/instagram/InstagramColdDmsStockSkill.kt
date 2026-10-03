package com.cyclone.mobile.automation.stock.instagram

import android.content.Context
import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.StockSkillRequest
import com.cyclone.mobile.automation.StockSkillResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

object InstagramColdDmsStockSkill {
    const val ID = "stock.instagram.cold_dms"
    const val MAX_HANDLES = 25
    const val MAX_MESSAGE_LENGTH = 1000

    val definition = SkillDefinition(
        id = ID,
        name = "Instagram · Cold DMs",
        description = "Stock Android skill: send a reviewed message to explicit Instagram handles or the next uncontacted batch from a persisted lead list. Verifies the recipient before typing, routes Send through GATE, verifies delivery state, and resets app state between recipients.",
        inputs = listOf(
            "handles", "message", "cycles", "betweenMs", "jitterMs", "verifyEnabled", "switchAccount",
            "leadListName", "leadListJson", "leadBatch", "skipPrivate", "retryFailed", "excludeHandles",
        ),
        outputs = listOf("sent", "failed", "total", "recoveries", "reason", "leadList"),
        steps = listOf(
            StepDefinition(
                id = "run-cold-dms",
                name = "Run Cold DMs",
                type = StepType.STOCK_SKILL,
                parameters = mapOf(
                    "skillId" to ID,
                    "handles" to "${'$'}{handles}",
                    "message" to "${'$'}{message}",
                    "cycles" to "${'$'}{cycles}",
                    "betweenMs" to "${'$'}{betweenMs}",
                    "jitterMs" to "${'$'}{jitterMs}",
                    "verifyEnabled" to "${'$'}{verifyEnabled}",
                    "switchAccount" to "${'$'}{switchAccount}",
                    "leadListName" to "${'$'}{leadListName}",
                    "leadListJson" to "${'$'}{leadListJson}",
                    "leadBatch" to "${'$'}{leadBatch}",
                    "skipPrivate" to "${'$'}{skipPrivate}",
                    "retryFailed" to "${'$'}{retryFailed}",
                    "excludeHandles" to "${'$'}{excludeHandles}",
                ),
                recovery = RecoveryPolicy(maxRetries = 0),
            )
        ),
        enabled = true,
        version = 1,
    )
}

internal data class ColdDmConfig(
    val explicitHandles: List<String>,
    val message: String,
    val cycles: Int,
    val betweenMs: Long,
    val jitterMs: Long,
    val verifyEnabled: Boolean,
    val switchAccount: String?,
    val leadListName: String?,
    val leadListJson: String?,
    val leadBatch: Int,
    val skipPrivate: Boolean,
    val retryFailed: Boolean,
    val excludeHandles: Set<String>,
) {
    companion object {
        private val handlePattern = Regex("^@[A-Za-z0-9._]{1,64}$")
        private val leadListNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")

        fun parse(arguments: Map<String, String>): Result<ColdDmConfig> = runCatching {
            val message = arguments["message"].orEmpty().trim()
            require(message.isNotBlank()) { "Message text is required" }
            require(message.length <= InstagramColdDmsStockSkill.MAX_MESSAGE_LENGTH) {
                "Message must be ${InstagramColdDmsStockSkill.MAX_MESSAGE_LENGTH} characters or fewer"
            }
            val leadListName = arguments["leadListName"]?.trim()?.takeIf(String::isNotBlank)
            if (leadListName != null) require(leadListNamePattern.matches(leadListName)) {
                "Invalid lead list name — use letters, digits, dot, dash, underscore"
            }
            val explicit = if (leadListName == null) parseHandles(arguments["handles"].orEmpty()) else emptyList()
            if (leadListName == null) validateHandles(explicit)
            val cycles = positiveInt(arguments["cycles"], 1, "cycles")
            val betweenMs = positiveLong(arguments["betweenMs"], 2_500, "betweenMs").coerceAtMost(300_000)
            val jitterMs = nonNegativeLong(arguments["jitterMs"], 1_500, "jitterMs").coerceAtMost(300_000)
            val leadBatch = positiveInt(arguments["leadBatch"], 10, "leadBatch")
            require(leadBatch <= InstagramColdDmsStockSkill.MAX_HANDLES) {
                "leadBatch must be at most ${InstagramColdDmsStockSkill.MAX_HANDLES}"
            }
            ColdDmConfig(
                explicitHandles = explicit,
                message = message,
                cycles = cycles,
                betweenMs = betweenMs,
                jitterMs = jitterMs,
                verifyEnabled = strictBoolean(arguments["verifyEnabled"], true, "verifyEnabled"),
                switchAccount = arguments["switchAccount"]?.trim()?.takeIf(String::isNotBlank)?.let(::normalizeAtHandle),
                leadListName = leadListName,
                leadListJson = arguments["leadListJson"]?.trim()?.takeIf(String::isNotBlank),
                leadBatch = leadBatch,
                skipPrivate = strictBoolean(arguments["skipPrivate"], true, "skipPrivate"),
                retryFailed = strictBoolean(arguments["retryFailed"], false, "retryFailed"),
                excludeHandles = parseHandles(arguments["excludeHandles"].orEmpty()).map(::normalizeBareHandle).toSet(),
            )
        }

        fun parseHandles(raw: String): List<String> {
            val seen = linkedSetOf<String>()
            raw.split(Regex("[\\n,]+"))
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(::normalizeAtHandle)
                .forEach { handle ->
                    val key = handle.lowercase()
                    if (key !in seen) seen += key
                }
            return seen.map { key -> normalizeAtHandle(key) }
        }

        fun validateHandles(handles: List<String>): List<String> {
            require(handles.isNotEmpty()) { "Add at least one Instagram handle" }
            require(handles.size <= InstagramColdDmsStockSkill.MAX_HANDLES) {
                "Choose at most ${InstagramColdDmsStockSkill.MAX_HANDLES} handles per run"
            }
            handles.forEach { require(handlePattern.matches(it)) { "Invalid Instagram handle $it" } }
            return handles
        }

        private fun strictBoolean(raw: String?, fallback: Boolean, name: String): Boolean = when (raw?.trim()?.lowercase()) {
            null, "" -> fallback
            "true" -> true
            "false" -> false
            else -> error("$name must be true or false")
        }

        private fun positiveInt(raw: String?, fallback: Int, name: String): Int {
            val value = raw?.takeIf(String::isNotBlank)?.toIntOrNull() ?: fallback
            require(value > 0) { "$name must be a positive integer" }
            return value
        }

        private fun positiveLong(raw: String?, fallback: Long, name: String): Long {
            val value = raw?.takeIf(String::isNotBlank)?.toLongOrNull() ?: fallback
            require(value > 0) { "$name must be positive" }
            return value
        }

        private fun nonNegativeLong(raw: String?, fallback: Long, name: String): Long {
            val value = raw?.takeIf(String::isNotBlank)?.toLongOrNull() ?: fallback
            require(value >= 0) { "$name must be zero or positive" }
            return value
        }
    }
}

internal fun normalizeBareHandle(raw: String): String = raw.trim().lowercase().removePrefix("@")
internal fun normalizeAtHandle(raw: String): String = "@${normalizeBareHandle(raw)}"

internal data class ColdDmLead(
    val username: String,
    val fullName: String,
    val isPrivate: Boolean,
    val isVerified: Boolean,
)

internal data class ColdDmLeadList(val name: String, val leads: List<ColdDmLead>)
internal data class ColdDmLeadContact(val status: String, val at: String, val account: String? = null, val error: String? = null)

internal object ColdDmLeadCodec {
    private val handlePattern = Regex("^[A-Za-z0-9._]{1,64}$")

    fun parse(name: String, raw: String): ColdDmLeadList {
        val root: Any = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
        val rows = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("leads") ?: error("Lead list $name has no leads array")
            else -> error("Invalid lead list")
        }
        val seen = linkedSetOf<String>()
        val leads = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val username = normalizeBareHandle(row.optString("username").ifBlank { row.optString("ownerUsername") })
                if (username.isBlank() || !handlePattern.matches(username) || !seen.add(username)) continue
                add(ColdDmLead(
                    username = username,
                    fullName = row.optString("fullName").ifBlank { row.optString("full_name") }.trim(),
                    isPrivate = if (row.has("isPrivate")) row.optBoolean("isPrivate") else row.optBoolean("is_private"),
                    isVerified = if (row.has("isVerified")) row.optBoolean("isVerified") else row.optBoolean("is_verified"),
                ))
            }
        }
        return ColdDmLeadList(name, leads)
    }

    fun toJson(list: ColdDmLeadList): String = JSONObject().put("leads", JSONArray().apply {
        list.leads.forEach { lead ->
            put(JSONObject()
                .put("username", lead.username)
                .put("fullName", lead.fullName)
                .put("isPrivate", lead.isPrivate)
                .put("isVerified", lead.isVerified))
        }
    }).toString()
}

internal class ColdDmLeadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cyclone_instagram_cold_dm_leads", Context.MODE_PRIVATE)

    fun import(name: String, raw: String): ColdDmLeadList = ColdDmLeadCodec.parse(name, raw).also {
        prefs.edit().putString("list:$name", ColdDmLeadCodec.toJson(it)).apply()
    }

    fun load(name: String): ColdDmLeadList? = prefs.getString("list:$name", null)?.let { ColdDmLeadCodec.parse(name, it) }

    fun state(name: String): Map<String, ColdDmLeadContact> {
        val root = runCatching { JSONObject(prefs.getString("state:$name", "{}") ?: "{}") }.getOrElse { JSONObject() }
        return buildMap {
            root.keys().forEach { key ->
                val value = root.optJSONObject(key) ?: return@forEach
                put(key, ColdDmLeadContact(
                    status = value.optString("status"),
                    at = value.optString("at"),
                    account = value.optString("account").takeIf(String::isNotBlank),
                    error = value.optString("error").takeIf(String::isNotBlank),
                ))
            }
        }
    }

    fun mark(name: String, handle: String, status: String, account: String?, error: String? = null) {
        val root = runCatching { JSONObject(prefs.getString("state:$name", "{}") ?: "{}") }.getOrElse { JSONObject() }
        root.put(normalizeBareHandle(handle), JSONObject()
            .put("status", status)
            .put("at", Instant.now().toString())
            .put("account", account ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL))
        prefs.edit().putString("state:$name", root.toString()).apply()
    }

    fun pick(
        list: ColdDmLeadList,
        state: Map<String, ColdDmLeadContact>,
        size: Int,
        skipPrivate: Boolean,
        retryFailed: Boolean,
        exclude: Set<String>,
    ): List<ColdDmLead> = buildList {
        for (lead in list.leads) {
            if (this.size >= size) break
            if (lead.username in exclude) continue
            if (skipPrivate && lead.isPrivate) continue
            when (state[lead.username]?.status) {
                "sent" -> continue
                "failed" -> if (!retryFailed) continue
            }
            add(lead)
        }
    }
}

internal data class ColdDmRunCheckpoint(
    val nextIndex: Int,
    val sent: Int,
    val failed: Int,
    val recoveries: Int,
    val pendingSendHandle: String?,
)

internal class ColdDmCheckpointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cyclone_stock_skill_checkpoints", Context.MODE_PRIVATE)

    fun read(key: String): ColdDmRunCheckpoint? = prefs.getString(key, null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            ColdDmRunCheckpoint(
                nextIndex = json.optInt("nextIndex"),
                sent = json.optInt("sent"),
                failed = json.optInt("failed"),
                recoveries = json.optInt("recoveries"),
                pendingSendHandle = json.optString("pendingSendHandle").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun write(key: String, value: ColdDmRunCheckpoint) {
        prefs.edit().putString(key, JSONObject()
            .put("nextIndex", value.nextIndex)
            .put("sent", value.sent)
            .put("failed", value.failed)
            .put("recoveries", value.recoveries)
            .put("pendingSendHandle", value.pendingSendHandle ?: JSONObject.NULL)
            .toString()).apply()
    }

    fun clear(key: String) { prefs.edit().remove(key).apply() }
}

internal sealed interface ColdDmAction {
    data object Ok : ColdDmAction
    data class Failed(val message: String) : ColdDmAction
    data class HumanReview(val message: String) : ColdDmAction
}

internal class InstagramColdDmPhonePort(private val context: Context) {
    private val base = InstagramPhonePort(context)

    fun observe(): Result<AndroidScreen> = base.observe()
    fun openInstagram(): ColdDmAction = base.openInstagram().toColdDm()
    fun back(): ColdDmAction = base.back().toColdDm()

    fun clickHome(): ColdDmAction = clickFirst(listOf(
        JSONObject().put("contentDescription", "Home").put("clickable", true),
        JSONObject().put("text", "Home").put("clickable", true),
        JSONObject().put("fuzzyText", "Home").put("minFuzzyScore", 0.92).put("clickable", true),
    ))

    fun openInbox(): ColdDmAction = clickFirst(listOf(
        JSONObject().put("contentDescriptionContains", "Direct messages").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Messages").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Messenger").put("clickable", true),
        JSONObject().put("text", "Messages").put("clickable", true),
    ))

    fun composeNewMessage(): ColdDmAction = clickFirst(listOf(
        JSONObject().put("contentDescriptionContains", "New message").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Compose").put("clickable", true),
        JSONObject().put("text", "New message").put("clickable", true),
        JSONObject().put("text", "Compose").put("clickable", true),
    ))

    fun enterRecipientQuery(query: String): ColdDmAction = typeFirst(query, listOf(
        JSONObject().put("editable", true).put("contentDescriptionContains", "To"),
        JSONObject().put("editable", true).put("contentDescriptionContains", "Search"),
        JSONObject().put("editable", true).put("textContains", "Search"),
        JSONObject().put("editable", true),
    ))

    fun selectRecipient(handle: String): ColdDmAction {
        val bare = normalizeBareHandle(handle)
        return clickFirst(listOf(
            JSONObject().put("text", "@$bare").put("clickable", true),
            JSONObject().put("text", bare).put("clickable", true),
            JSONObject().put("contentDescriptionContains", "@$bare").put("clickable", true),
            JSONObject().put("contentDescriptionContains", bare).put("clickable", true),
            JSONObject().put("fuzzyText", bare).put("minFuzzyScore", 0.96).put("clickable", true),
        ))
    }

    fun openSelectedChat(): ColdDmAction = clickFirst(listOf(
        JSONObject().put("text", "Chat").put("clickable", true),
        JSONObject().put("contentDescription", "Chat").put("clickable", true),
        JSONObject().put("text", "Next").put("clickable", true),
        JSONObject().put("contentDescription", "Next").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "arrow").put("clickable", true),
    ))

    fun typeMessage(message: String): ColdDmAction = typeFirst(message, listOf(
        JSONObject().put("editable", true).put("contentDescriptionContains", "Message"),
        JSONObject().put("editable", true).put("textContains", "Message"),
        JSONObject().put("editable", true),
    ))

    fun send(): ColdDmAction = clickFirst(listOf(
        JSONObject().put("text", "Send").put("clickable", true),
        JSONObject().put("contentDescription", "Send").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Send").put("clickable", true),
    ))

    fun clickProfile(): ColdDmAction = base.clickProfile().toColdDm()
    fun clickAccountSwitcher(): ColdDmAction = base.clickAccountSwitcher().toColdDm()
    fun clickAccount(handle: String): ColdDmAction = base.clickAccount(handle).toColdDm()

    private fun typeFirst(value: String, selectors: List<JSONObject>): ColdDmAction {
        var last = "Editable field not found"
        for (selector in selectors) {
            val fresh = observe().getOrElse { return ColdDmAction.Failed(it.message ?: "Could not observe Instagram") }
            if (fresh.packageName != "com.instagram.android") return ColdDmAction.Failed("Instagram is not foreground")
            val result = execute("phone.type", JSONObject().put("value", value).put("selector", selector))
            if (result.ok) return ColdDmAction.Ok
            if (result.error?.code == PhoneToolErrorCode.POLICY_DENIED) return ColdDmAction.HumanReview(result.error.message)
            last = result.error?.message ?: last
        }
        return ColdDmAction.Failed(last)
    }

    private fun clickFirst(selectors: List<JSONObject>): ColdDmAction {
        var last = "Control not found"
        for (selector in selectors) {
            val fresh = observe().getOrElse { return ColdDmAction.Failed(it.message ?: "Could not observe Instagram") }
            if (fresh.packageName != "com.instagram.android") return ColdDmAction.Failed("Instagram is not foreground")
            val result = execute("phone.click", JSONObject().put("selector", selector))
            if (result.ok) return ColdDmAction.Ok
            if (result.error?.code == PhoneToolErrorCode.POLICY_DENIED) return ColdDmAction.HumanReview(result.error.message)
            last = result.error?.message ?: last
        }
        return ColdDmAction.Failed(last)
    }

    private fun execute(tool: String, params: JSONObject): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest("stock-instagram-cold-dm-${UUID.randomUUID()}", tool, params.put("fastPath", true)),
    )

    private fun AndroidActionResult.toColdDm(): ColdDmAction = when (this) {
        AndroidActionResult.Ok -> ColdDmAction.Ok
        is AndroidActionResult.Failed -> ColdDmAction.Failed(message)
        is AndroidActionResult.HumanReview -> ColdDmAction.HumanReview(message)
    }
}

internal class InstagramColdDmsRunner(
    private val context: Context,
    private val random: Random = Random.Default,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val phone = InstagramColdDmPhonePort(context)
    private val leads = ColdDmLeadStore(context)
    private val checkpoints = ColdDmCheckpointStore(context)

    fun run(request: StockSkillRequest): StockSkillResult {
        val config = ColdDmConfig.parse(request.arguments).getOrElse {
            return StockSkillResult(false, message = it.message ?: "Invalid Cold DMs configuration")
        }
        val leadList = if (config.leadListName != null) {
            val imported = config.leadListJson?.let { raw -> runCatching { leads.import(config.leadListName, raw) }.getOrElse {
                return StockSkillResult(false, message = "Lead list import failed: ${it.message}")
            } }
            imported ?: leads.load(config.leadListName)
                ?: return StockSkillResult(false, message = "Lead list ${config.leadListName} not found; provide leadListJson once to import it")
        } else null

        val leadState = leadList?.let { leads.state(it.name) }.orEmpty()
        val excluded = buildSet {
            addAll(config.excludeHandles)
            config.switchAccount?.let { add(normalizeBareHandle(it)) }
        }
        val picked = leadList?.let {
            leads.pick(it, leadState, config.leadBatch, config.skipPrivate, config.retryFailed, excluded)
        }
        val handles = if (picked != null) {
            if (picked.isEmpty()) return StockSkillResult(false, message = "Lead list ${leadList?.name} has no uncontacted leads left for this filter")
            ColdDmConfig.validateHandles(picked.map { normalizeAtHandle(it.username) })
        } else config.explicitHandles
        val leadNames = picked.orEmpty().associate { it.username to it.fullName }
        val sequence = buildList {
            handles.forEach { handle -> repeat(config.cycles) { add(handle) } }
        }
        val checkpointKey = "${request.runId}:${request.stepId}:${request.skillId}"
        val checkpoint = checkpoints.read(checkpointKey)
        var index = checkpoint?.nextIndex?.coerceIn(0, sequence.size) ?: 0
        var sent = checkpoint?.sent ?: 0
        var failed = checkpoint?.failed ?: 0
        var recoveries = checkpoint?.recoveries ?: 0
        var activeAccount: String? = null

        fun output(reason: String) = mapOf(
            "sent" to sent.toString(),
            "failed" to failed.toString(),
            "total" to sequence.size.toString(),
            "recoveries" to recoveries.toString(),
            "reason" to reason,
            "leadList" to (leadList?.name ?: ""),
        )

        fun waitForHuman(handle: String, message: String): StockSkillResult {
            checkpoints.write(checkpointKey, ColdDmRunCheckpoint(index, sent, failed, recoveries, pendingSendHandle = handle))
            return StockSkillResult(false, output("waiting_for_human"), waitingForHuman = true, message = message)
        }

        when (val opened = phone.openInstagram()) {
            ColdDmAction.Ok -> Unit
            is ColdDmAction.HumanReview -> return waitForHuman(sequence.getOrNull(index).orEmpty(), opened.message)
            is ColdDmAction.Failed -> return StockSkillResult(false, output("failed"), message = opened.message)
        }
        if (!delay(1_500)) return stopped(checkpointKey, output("stopped"))

        if (config.switchAccount != null) {
            when (val switched = switchAccount(config.switchAccount)) {
                ColdDmAction.Ok -> activeAccount = normalizeAtHandle(config.switchAccount)
                is ColdDmAction.HumanReview -> return waitForHuman(sequence.getOrNull(index).orEmpty(), switched.message)
                is ColdDmAction.Failed -> recoveries++ // source continues with current signed-in account on switch failure
            }
        }

        if (checkpoint?.pendingSendHandle != null && index < sequence.size && sequence[index].equals(checkpoint.pendingSendHandle, ignoreCase = true)) {
            val pendingHandle = sequence[index]
            when (val resumed = resumePendingSend(pendingHandle, config.message, config.verifyEnabled, leadNames[normalizeBareHandle(pendingHandle)])) {
                ColdDmAction.Ok -> {
                    sent++
                    leadList?.let { leads.mark(it.name, pendingHandle, "sent", activeAccount) }
                    index++
                    checkpoints.write(checkpointKey, ColdDmRunCheckpoint(index, sent, failed, recoveries, null))
                }
                is ColdDmAction.HumanReview -> return waitForHuman(pendingHandle, resumed.message)
                is ColdDmAction.Failed -> {
                    failed++
                    recoveries++
                    leadList?.let { leads.mark(it.name, pendingHandle, "failed", activeAccount, resumed.message) }
                    index++
                }
            }
        }

        if (index == 0 && !resetToHome()) recoveries++

        while (index < sequence.size && !Thread.currentThread().isInterrupted) {
            val handle = sequence[index]
            val displayName = leadNames[normalizeBareHandle(handle)]
            when (val result = sendToHandle(handle, displayName, config.message, config.verifyEnabled)) {
                ColdDmAction.Ok -> {
                    sent++
                    leadList?.let { leads.mark(it.name, handle, "sent", activeAccount) }
                }
                is ColdDmAction.HumanReview -> return waitForHuman(handle, result.message)
                is ColdDmAction.Failed -> {
                    failed++
                    leadList?.let { leads.mark(it.name, handle, "failed", activeAccount, result.message) }
                }
            }
            index++
            checkpoints.write(checkpointKey, ColdDmRunCheckpoint(index, sent, failed, recoveries, null))
            if (index >= sequence.size || Thread.currentThread().isInterrupted) break

            val gap = config.betweenMs + if (config.jitterMs > 0) random.nextLong(config.jitterMs + 1) else 0
            if (!delay(gap)) break
            if (!resetToHome()) recoveries++
        }

        checkpoints.clear(checkpointKey)
        if (Thread.currentThread().isInterrupted) return StockSkillResult(false, output("stopped"), message = "Cold DMs stopped")
        if (sent == 0) return StockSkillResult(false, output("zero_sent"), message = "Cold DMs sent zero messages")
        return StockSkillResult(true, output("completed"), message = "Cold DMs completed")
    }

    private fun sendToHandle(handle: String, displayName: String?, message: String, verify: Boolean): ColdDmAction {
        if (!resetToHome()) return ColdDmAction.Failed("Could not reset Instagram to Home before $handle")
        when (val inbox = phone.openInbox()) { ColdDmAction.Ok -> Unit; else -> return inbox }
        if (!delay(2_000)) return ColdDmAction.Failed("Stopped while opening DM inbox")
        when (val compose = phone.composeNewMessage()) { ColdDmAction.Ok -> Unit; else -> return compose }
        if (!delay(1_000)) return ColdDmAction.Failed("Stopped while opening new message composer")
        when (val query = phone.enterRecipientQuery(normalizeBareHandle(handle))) { ColdDmAction.Ok -> Unit; else -> return query }
        if (!delay(1_500)) return ColdDmAction.Failed("Stopped while searching recipient")
        when (val selected = phone.selectRecipient(handle)) { ColdDmAction.Ok -> Unit; else -> return selected }
        if (!delay(750)) return ColdDmAction.Failed("Stopped while selecting recipient")
        when (val chat = phone.openSelectedChat()) { ColdDmAction.Ok -> Unit; else -> return chat }
        if (!delay(1_500)) return ColdDmAction.Failed("Stopped while opening recipient thread")
        if (verify && !recipientVerified(handle, displayName)) return ColdDmAction.Failed("Recipient $handle could not be verified in the opened thread")
        when (val typed = phone.typeMessage(message)) { ColdDmAction.Ok -> Unit; else -> return typed }
        if (!delay(600)) return ColdDmAction.Failed("Stopped before send")
        // Source tolerates unreadable pre-send OCR and lets the post-send checks decide whether the tap worked.
        // Keep that tolerance on Android: a sparse accessibility tree must not become a false hard failure here.
        return sendAndVerify(handle, message, verify)
    }

    private fun resumePendingSend(handle: String, message: String, verify: Boolean, displayName: String?): ColdDmAction {
        if (verify && !recipientVerified(handle, displayName)) {
            return ColdDmAction.Failed("Pending send recipient $handle is no longer verified; message was not sent")
        }
        if (verify && !composerReady(message)) {
            return ColdDmAction.Failed("Pending send composer no longer contains the exact message; message was not sent")
        }
        return sendAndVerify(handle, message, verify)
    }

    private fun sendAndVerify(handle: String, message: String, verify: Boolean): ColdDmAction {
        for (attempt in 1..2) {
            when (val send = phone.send()) {
                ColdDmAction.Ok -> Unit
                is ColdDmAction.HumanReview -> return send // PhoneToolExecutor GATE is authoritative for SEND.
                is ColdDmAction.Failed -> if (attempt == 2) return send else continue
            }
            if (!delay(2_200)) return ColdDmAction.Failed("Stopped while verifying send to $handle")
            if (!verify || sentVerified(message)) return ColdDmAction.Ok
        }
        return ColdDmAction.Failed("Could not confirm send to $handle after 2 attempts")
    }

    private fun recipientVerified(handle: String, displayName: String?): Boolean {
        val screen = phone.observe().getOrNull() ?: return false
        val bare = normalizeBareHandle(handle)
        if (screen.nodes.any { node ->
                val label = node.label.lowercase()
                label == bare || label == "@$bare" || label.contains("@$bare")
            }) return true
        val tokens = displayName.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
        return tokens.isNotEmpty() && screen.nodes.any { node ->
            val normalized = node.label.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
            tokens.any { token -> normalized.contains(token) }
        }
    }

    private fun composerReady(message: String): Boolean {
        val screen = phone.observe().getOrNull() ?: return false
        val exact = message.trim()
        val typedVisible = screen.nodes.any { it.editable && (it.text.trim() == exact || it.label.contains(exact, ignoreCase = false)) }
        val sendVisible = screen.nodes.any { it.clickable && it.label.equals("Send", ignoreCase = true) }
        return typedVisible || sendVisible
    }

    private fun sentVerified(message: String): Boolean {
        val screen = phone.observe().getOrNull() ?: return false
        val sendStillVisible = screen.nodes.any { it.clickable && it.label.equals("Send", ignoreCase = true) }
        val placeholderBack = screen.nodes.any { it.editable && it.label.contains("Message", ignoreCase = true) && !it.label.contains(message, ignoreCase = false) }
        val bubbleVisible = screen.nodes.any { !it.editable && (it.text.trim() == message.trim() || it.label.contains(message.trim(), ignoreCase = false)) }
        return !sendStillVisible && (placeholderBack || bubbleVisible)
    }

    private fun switchAccount(handle: String): ColdDmAction {
        val bare = normalizeBareHandle(handle)
        when (val profile = phone.clickProfile()) { ColdDmAction.Ok -> Unit; else -> return profile }
        if (!delay(2_000)) return ColdDmAction.Failed("Stopped while opening profile")
        if (phone.observe().getOrNull()?.hasLabel(bare) == true) return ColdDmAction.Ok
        var opened = false
        for (attempt in 1..4) {
            when (val switcher = phone.clickAccountSwitcher()) {
                ColdDmAction.Ok -> { opened = true; break }
                is ColdDmAction.HumanReview -> return switcher
                is ColdDmAction.Failed -> Unit
            }
            if (attempt < 4 && !delay(1_000)) return ColdDmAction.Failed("Stopped while opening account switcher")
        }
        if (!opened) return ColdDmAction.Failed("Could not open account switcher")
        if (!delay(800)) return ColdDmAction.Failed("Stopped while opening account switcher")
        when (val selected = phone.clickAccount(handle)) { ColdDmAction.Ok -> Unit; else -> return selected }
        if (!delay(3_000)) return ColdDmAction.Failed("Stopped while switching account")
        when (val profile = phone.clickProfile()) { ColdDmAction.Ok -> Unit; else -> return profile }
        if (!delay(700)) return ColdDmAction.Failed("Stopped while verifying account")
        return if (phone.observe().getOrNull()?.hasLabel(bare) == true) ColdDmAction.Ok
        else ColdDmAction.Failed("Account switch to $handle could not be verified")
    }

    private fun resetToHome(): Boolean {
        repeat(4) { attempt ->
            val screen = phone.observe().getOrNull()
            if (screen?.hasSelectedLabel("Home") == true) return true
            when (phone.clickHome()) {
                ColdDmAction.Ok -> {
                    if (delay(700) && phone.observe().getOrNull()?.hasSelectedLabel("Home") == true) return true
                }
                is ColdDmAction.HumanReview -> return false
                is ColdDmAction.Failed -> Unit
            }
            if (attempt < 3) {
                phone.back()
                if (!delay(500)) return false
            }
        }
        when (phone.openInstagram()) {
            ColdDmAction.Ok -> Unit
            else -> return false
        }
        if (!delay(1_200)) return false
        repeat(3) {
            if (phone.observe().getOrNull()?.hasSelectedLabel("Home") == true) return true
            if (phone.clickHome() is ColdDmAction.Ok && delay(600) && phone.observe().getOrNull()?.hasSelectedLabel("Home") == true) return true
            phone.back()
            delay(400)
        }
        return false
    }

    private fun delay(ms: Long): Boolean {
        var remaining = ms.coerceAtLeast(0)
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            val slice = remaining.coerceAtMost(250)
            try { sleeper(slice) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            remaining -= slice
        }
        return !Thread.currentThread().isInterrupted
    }

    private fun stopped(key: String, output: Map<String, String>): StockSkillResult {
        checkpoints.clear(key)
        return StockSkillResult(false, output = output, message = "Cold DMs stopped")
    }
}
