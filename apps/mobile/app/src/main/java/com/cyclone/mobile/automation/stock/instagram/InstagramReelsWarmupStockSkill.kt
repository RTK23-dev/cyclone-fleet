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
import com.cyclone.mobile.automation.StockSkillGateway
import com.cyclone.mobile.automation.StockSkillRequest
import com.cyclone.mobile.automation.StockSkillResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Clean Android port of Kevs-IOS-Agents `doomscroll` at source commit
 * b909752df7af7a595714ed660af7cc971ec408d5.
 *
 * The source's timing/profile behavior is retained, but all iOS/Appium coordinates are deliberately
 * discarded. Every Android mutation goes through PhoneToolExecutor; selectors are semantic and the
 * only gesture fallback derives coordinates from the current observed screen dimensions.
 */
object InstagramStockSkillIds {
    const val REELS_WARMUP = "stock.instagram.reels_warmup"
}

internal enum class ReelsPersonality {
    SKIMMER, CASUAL, ENGAGED, DIALED;

    companion object {
        fun parse(raw: String): ReelsPersonality? = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

internal data class ReelsProfile(
    val watchMinMs: Long,
    val watchMaxMs: Long,
    val likeChance: Double,
    val sourceSaveChance: Double,
    val commentChance: Double,
    val lingerChance: Double,
    val lingerMinMs: Long,
    val lingerMaxMs: Long,
)

/** Exact profile constants from the pinned source. `sourceSaveChance` is retained for parity tests only.
 * The source doomscroll loop never invokes save, so this Android port never performs a save action. */
internal object ReelsProfiles {
    val values = mapOf(
        ReelsPersonality.SKIMMER to ReelsProfile(1_500, 4_000, 0.28, 0.10, 0.15, 0.05, 4_000, 8_000),
        ReelsPersonality.CASUAL to ReelsProfile(4_000, 9_000, 0.50, 0.22, 0.28, 0.10, 8_000, 15_000),
        ReelsPersonality.ENGAGED to ReelsProfile(8_000, 18_000, 0.75, 0.40, 0.41, 0.20, 15_000, 30_000),
        ReelsPersonality.DIALED to ReelsProfile(1_200, 2_200, 1.0, 1.0, 1.0, 0.0, 0, 0),
    )

    fun get(personality: ReelsPersonality): ReelsProfile = values.getValue(personality)
}

internal data class ReelsWarmupConfig(
    val durationMinutes: Int,
    val personality: ReelsPersonality,
    val likeEnabled: Boolean,
    val commentEnabled: Boolean,
    val commentText: String,
    val switchAccount: String?,
) {
    companion object {
        fun parse(arguments: Map<String, String>): Result<ReelsWarmupConfig> = runCatching {
            val duration = (arguments["durationMinutes"] ?: "5").toIntOrNull()
                ?: error("durationMinutes must be an integer")
            require(duration in 1..180) { "durationMinutes must be between 1 and 180" }
            val personality = ReelsPersonality.parse(arguments["personality"] ?: "casual")
                ?: error("personality must be one of skimmer, casual, engaged, dialed")
            val likeEnabled = strictBoolean(arguments["likeEnabled"], true, "likeEnabled")
            val commentEnabled = strictBoolean(arguments["commentEnabled"], false, "commentEnabled")
            val commentText = arguments["commentText"].orEmpty().trim()
            require(!commentEnabled || commentText.isNotBlank()) {
                "commentText is required when commentEnabled=true"
            }
            ReelsWarmupConfig(
                durationMinutes = duration,
                personality = personality,
                likeEnabled = likeEnabled,
                commentEnabled = commentEnabled,
                commentText = commentText,
                switchAccount = arguments["switchAccount"]?.trim()?.takeIf(String::isNotBlank),
            )
        }

        private fun strictBoolean(raw: String?, fallback: Boolean, name: String): Boolean = when (raw?.lowercase()) {
            null, "" -> fallback
            "true" -> true
            "false" -> false
            else -> error("$name must be true or false")
        }
    }
}

internal data class ReelsWarmupCheckpoint(
    val remainingMs: Long,
    val videosViewed: Int,
    val swipes: Int,
    val likes: Int,
    val comments: Int,
    val recoveries: Int,
)

internal class InstagramNativeCheckpointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cyclone_stock_skill_checkpoints", Context.MODE_PRIVATE)

    fun read(key: String): ReelsWarmupCheckpoint? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ReelsWarmupCheckpoint(
                remainingMs = json.getLong("remainingMs").coerceAtLeast(0),
                videosViewed = json.optInt("videosViewed"),
                swipes = json.optInt("swipes"),
                likes = json.optInt("likes"),
                comments = json.optInt("comments"),
                recoveries = json.optInt("recoveries"),
            )
        }.getOrNull()
    }

    fun write(key: String, value: ReelsWarmupCheckpoint) {
        prefs.edit().putString(key, JSONObject()
            .put("remainingMs", value.remainingMs)
            .put("videosViewed", value.videosViewed)
            .put("swipes", value.swipes)
            .put("likes", value.likes)
            .put("comments", value.comments)
            .put("recoveries", value.recoveries)
            .toString()).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }
}

internal data class AndroidNode(
    val text: String,
    val contentDescription: String,
    val role: String,
    val selected: Boolean,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
) {
    val label: String get() = "$text $contentDescription".trim()
}

internal data class AndroidScreen(
    val packageName: String,
    val width: Int,
    val height: Int,
    val nodes: List<AndroidNode>,
) {
    fun hasLabel(fragment: String): Boolean = nodes.any { it.label.contains(fragment, ignoreCase = true) }
    fun hasSelectedLabel(fragment: String): Boolean = nodes.any { it.selected && it.label.contains(fragment, ignoreCase = true) }
}

internal sealed interface AndroidActionResult {
    data object Ok : AndroidActionResult
    data class Failed(val message: String) : AndroidActionResult
    data class HumanReview(val message: String) : AndroidActionResult
}

/** Semantic Android control surface. No direct AccessibilityService calls are allowed here. */
internal class InstagramPhonePort(private val context: Context) {
    private val packageName = "com.instagram.android"

    fun observe(): Result<AndroidScreen> {
        val result = execute("phone.observe", JSONObject())
        if (!result.ok) return Result.failure(IllegalStateException(result.error?.message ?: "phone.observe failed"))
        val payload = result.payload as? JSONObject ?: return Result.failure(IllegalStateException("phone.observe returned no snapshot"))
        val screen = payload.optJSONObject("screen")
        val nodesJson = payload.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (i in 0 until nodesJson.length()) {
                val node = nodesJson.optJSONObject(i) ?: continue
                add(AndroidNode(
                    text = node.optString("text"),
                    contentDescription = node.optString("contentDescription"),
                    role = node.optString("role"),
                    selected = node.optBoolean("selected"),
                    clickable = node.optBoolean("clickable"),
                    editable = node.optBoolean("editable"),
                    scrollable = node.optBoolean("scrollable"),
                ))
            }
        }
        return Result.success(AndroidScreen(
            packageName = payload.optString("package"),
            width = screen?.optInt("width") ?: 0,
            height = screen?.optInt("height") ?: 0,
            nodes = nodes,
        ))
    }

    fun openInstagram(): AndroidActionResult = mutate("phone.open_app", JSONObject().put("package", packageName))

    fun back(): AndroidActionResult = mutate("phone.back", JSONObject())

    fun clickReels(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("contentDescription", "Reels").put("clickable", true),
        JSONObject().put("text", "Reels").put("clickable", true),
        JSONObject().put("fuzzyText", "Reels").put("minFuzzyScore", 0.88).put("clickable", true),
    ))

    fun clickProfile(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("contentDescription", "Profile").put("clickable", true),
        JSONObject().put("text", "Profile").put("clickable", true),
        JSONObject().put("fuzzyText", "Profile").put("minFuzzyScore", 0.88).put("clickable", true),
    ))

    fun clickAccountSwitcher(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("contentDescriptionContains", "Switch account").put("clickable", true),
        JSONObject().put("textContains", "Switch account").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Accounts").put("clickable", true),
    ))

    fun clickAccount(handle: String): AndroidActionResult {
        val normalized = handle.removePrefix("@").trim()
        return clickFirst(listOf(
            JSONObject().put("text", normalized).put("clickable", true),
            JSONObject().put("text", "@$normalized").put("clickable", true),
            JSONObject().put("contentDescriptionContains", normalized).put("clickable", true),
            JSONObject().put("fuzzyText", normalized).put("minFuzzyScore", 0.92).put("clickable", true),
        ))
    }

    fun clickLike(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("contentDescription", "Like").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Like").put("clickable", true),
        JSONObject().put("text", "Like").put("clickable", true),
    ))

    fun openComments(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("contentDescription", "Comment").put("clickable", true),
        JSONObject().put("contentDescriptionContains", "Comment").put("clickable", true),
        JSONObject().put("text", "Comment").put("clickable", true),
    ))

    fun typeComment(text: String): AndroidActionResult {
        val selectors = listOf(
            JSONObject().put("editable", true).put("contentDescriptionContains", "comment"),
            JSONObject().put("editable", true).put("textContains", "comment"),
            JSONObject().put("editable", true),
        )
        for (selector in selectors) {
            val fresh = observe().getOrNull() ?: continue
            if (fresh.packageName != packageName) return AndroidActionResult.Failed("Instagram lost foreground while entering comment")
            val params = JSONObject().put("value", text).put("selector", selector)
            when (val result = resultOf(execute("phone.type", params))) {
                AndroidActionResult.Ok -> return result
                is AndroidActionResult.HumanReview -> return result
                is AndroidActionResult.Failed -> Unit
            }
        }
        return AndroidActionResult.Failed("Could not find the Instagram comment composer")
    }

    fun sendComment(): AndroidActionResult = clickFirst(listOf(
        JSONObject().put("text", "Post").put("clickable", true),
        JSONObject().put("contentDescription", "Post").put("clickable", true),
        JSONObject().put("text", "Send").put("clickable", true),
        JSONObject().put("contentDescription", "Send").put("clickable", true),
    ))

    fun dismissComments(): AndroidActionResult {
        val close = clickFirst(listOf(
            JSONObject().put("contentDescription", "Close").put("clickable", true),
            JSONObject().put("contentDescriptionContains", "close").put("clickable", true),
            JSONObject().put("text", "Close").put("clickable", true),
        ))
        return if (close is AndroidActionResult.Failed) back() else close
    }

    fun nextReel(screen: AndroidScreen): AndroidActionResult {
        // Prefer Android's semantic scroll action. Reels versions that do not expose a scrollable
        // node fall back to a vertical swipe derived from this device's live screen dimensions.
        when (val scrolled = mutate("phone.scroll", JSONObject().put("direction", "forward"))) {
            AndroidActionResult.Ok -> return scrolled
            is AndroidActionResult.HumanReview -> return scrolled
            is AndroidActionResult.Failed -> Unit
        }
        if (screen.width <= 0 || screen.height <= 0) return AndroidActionResult.Failed("No live screen dimensions for Reels gesture")
        return mutate("phone.swipe", JSONObject()
            .put("x1", (screen.width * 0.38).roundToLong())
            .put("y1", (screen.height * 0.72).roundToLong())
            .put("x2", (screen.width * 0.38).roundToLong())
            .put("y2", (screen.height * 0.22).roundToLong())
            .put("durationMs", 350)
            .put("humanize", "off"))
    }

    private fun clickFirst(selectors: List<JSONObject>): AndroidActionResult {
        var last = "No selector matched"
        for (selector in selectors) {
            val fresh = observe().getOrElse { return AndroidActionResult.Failed(it.message ?: "Could not refresh observation") }
            if (fresh.packageName != packageName) return AndroidActionResult.Failed("Instagram is not foreground")
            when (val result = resultOf(execute("phone.click", JSONObject().put("selector", selector)))) {
                AndroidActionResult.Ok -> return result
                is AndroidActionResult.HumanReview -> return result
                is AndroidActionResult.Failed -> last = result.message
            }
        }
        return AndroidActionResult.Failed(last)
    }

    private fun mutate(tool: String, params: JSONObject): AndroidActionResult {
        // A fresh observation is intentional before every mutation. This preserves 4.4.8's
        // observation -> act -> verify contract and clears return-from-human stale state.
        val fresh = observe().getOrElse { return AndroidActionResult.Failed(it.message ?: "Could not refresh observation") }
        if (tool != "phone.open_app" && fresh.packageName != packageName) {
            return AndroidActionResult.Failed("Instagram is not foreground")
        }
        return resultOf(execute(tool, params))
    }

    private fun resultOf(result: PhoneToolResult): AndroidActionResult {
        if (result.ok) return AndroidActionResult.Ok
        val message = result.error?.message ?: "${result.tool} failed"
        return if (result.error?.code == PhoneToolErrorCode.POLICY_DENIED) {
            AndroidActionResult.HumanReview(message)
        } else AndroidActionResult.Failed(message)
    }

    private fun execute(tool: String, params: JSONObject): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest("stock-instagram-${UUID.randomUUID()}", tool, params.put("fastPath", true)),
    )
}

internal class InstagramReelsWarmupRunner(
    private val context: Context,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val phone = InstagramPhonePort(context)
    private val checkpoints = InstagramNativeCheckpointStore(context)

    fun run(request: StockSkillRequest): StockSkillResult {
        val config = ReelsWarmupConfig.parse(request.arguments).getOrElse {
            return StockSkillResult(false, message = it.message ?: "Invalid Reels Warmup configuration")
        }
        val profile = ReelsProfiles.get(config.personality)
        val checkpointKey = "${request.runId}:${request.stepId}:${request.skillId}"
        val saved = checkpoints.read(checkpointKey)
        val requestedMs = config.durationMinutes * 60_000L
        val initialRemaining = saved?.remainingMs?.coerceAtMost(requestedMs) ?: requestedMs
        val deadline = now() + initialRemaining
        var videos = saved?.videosViewed ?: 0
        var swipes = saved?.swipes ?: 0
        var likes = saved?.likes ?: 0
        var comments = saved?.comments ?: 0
        var recoveries = saved?.recoveries ?: 0

        fun output(reason: String) = mapOf(
            "videosViewed" to videos.toString(),
            "swipes" to swipes.toString(),
            "likes" to likes.toString(),
            "comments" to comments.toString(),
            "recoveries" to recoveries.toString(),
            "reason" to reason,
            "personality" to config.personality.name.lowercase(),
        )

        fun saveForHuman(message: String): StockSkillResult {
            checkpoints.write(checkpointKey, ReelsWarmupCheckpoint(
                remainingMs = (deadline - now()).coerceAtLeast(0),
                videosViewed = videos,
                swipes = swipes,
                likes = likes,
                comments = comments,
                recoveries = recoveries,
            ))
            return StockSkillResult(false, output("waiting_for_human"), waitingForHuman = true, message = message)
        }

        when (val opened = phone.openInstagram()) {
            AndroidActionResult.Ok -> Unit
            is AndroidActionResult.HumanReview -> return saveForHuman(opened.message)
            is AndroidActionResult.Failed -> return StockSkillResult(false, output("failed"), message = opened.message)
        }
        if (!delayCancellable(1_000, deadline)) return interrupted(checkpointKey, output("stopped"))

        if (config.switchAccount != null) {
            when (val switched = ensureAccount(config.switchAccount, deadline)) {
                AndroidActionResult.Ok -> Unit
                is AndroidActionResult.HumanReview -> return saveForHuman(switched.message)
                is AndroidActionResult.Failed -> return StockSkillResult(false, output("failed"), message = switched.message)
            }
        }

        while (hasTime(deadline)) {
            when (val ensured = ensureReels(deadline)) {
                AndroidActionResult.Ok -> Unit
                is AndroidActionResult.HumanReview -> return saveForHuman(ensured.message)
                is AndroidActionResult.Failed -> {
                    recoveries++
                    if (!delayCancellable(1_200, deadline)) break
                    continue
                }
            }
            if (!hasTime(deadline)) break

            videos++
            if (!delayCancellable(clamp(deadline, between(profile.watchMinMs, profile.watchMaxMs)), deadline)) break
            if (!hasTime(deadline)) break

            val willLike = config.likeEnabled && chance(profile.likeChance)
            val willComment = config.commentEnabled && chance(profile.commentChance)
            if (willLike || willComment) {
                val screen = phone.observe().getOrNull()
                if (screen == null || !isReels(screen)) {
                    recoveries++
                    continue
                }
            }

            if (willLike) {
                val screen = phone.observe().getOrNull()
                val alreadyLiked = screen?.hasLabel("Unlike") == true
                if (!alreadyLiked) {
                    if (!delayCancellable(clamp(deadline, between(350, 800)), deadline)) break
                    when (val action = phone.clickLike()) {
                        AndroidActionResult.Ok -> likes++
                        is AndroidActionResult.HumanReview -> return saveForHuman(action.message)
                        is AndroidActionResult.Failed -> Unit // same soft-failure behavior as source refinement fallback
                    }
                    if (!delayCancellable(clamp(deadline, between(750, 1_600)), deadline)) break
                }
            }
            if (!hasTime(deadline)) break

            if (willComment) {
                if (!delayCancellable(clamp(deadline, between(350, 800)), deadline)) break
                when (val openedComments = phone.openComments()) {
                    AndroidActionResult.Ok -> Unit
                    is AndroidActionResult.HumanReview -> return saveForHuman(openedComments.message)
                    is AndroidActionResult.Failed -> {
                        recoveries++
                        continue
                    }
                }
                if (!delayCancellable(clamp(deadline, 800), deadline)) break
                when (val typed = phone.typeComment(config.commentText)) {
                    AndroidActionResult.Ok -> Unit
                    is AndroidActionResult.HumanReview -> return saveForHuman(typed.message)
                    is AndroidActionResult.Failed -> {
                        phone.dismissComments()
                        recoveries++
                        continue
                    }
                }
                when (val sent = phone.sendComment()) {
                    AndroidActionResult.Ok -> comments++
                    is AndroidActionResult.HumanReview -> return saveForHuman(sent.message)
                    is AndroidActionResult.Failed -> {
                        phone.dismissComments()
                        recoveries++
                        continue
                    }
                }
                if (!delayCancellable(clamp(deadline, between(750, 1_600)), deadline)) break
                phone.dismissComments()
            }
            if (!hasTime(deadline)) break

            if (chance(profile.lingerChance)) {
                if (!delayCancellable(clamp(deadline, between(profile.lingerMinMs, profile.lingerMaxMs)), deadline)) break
            }
            if (!hasTime(deadline)) break

            val screen = phone.observe().getOrNull() ?: run {
                recoveries++
                continue
            }
            when (val next = phone.nextReel(screen)) {
                AndroidActionResult.Ok -> swipes++
                is AndroidActionResult.HumanReview -> return saveForHuman(next.message)
                is AndroidActionResult.Failed -> {
                    recoveries++
                    continue
                }
            }
            if (!delayCancellable(clamp(deadline, between(550, 800)), deadline)) break
        }

        checkpoints.clear(checkpointKey)
        val stopped = Thread.currentThread().isInterrupted
        return StockSkillResult(
            success = !stopped,
            output = output(if (stopped) "stopped" else "completed"),
            message = if (stopped) "Reels Warmup stopped" else "Reels Warmup completed",
        )
    }

    private fun ensureAccount(handle: String, deadline: Long): AndroidActionResult {
        val normalized = handle.removePrefix("@").trim()
        when (val profile = phone.clickProfile()) {
            AndroidActionResult.Ok -> Unit
            else -> return profile
        }
        if (!delayCancellable(clamp(deadline, 2_000), deadline)) return AndroidActionResult.Failed("Stopped while opening Instagram profile")
        val onProfile = phone.observe().getOrNull() ?: return AndroidActionResult.Failed("Could not observe Instagram profile")
        if (onProfile.hasLabel(normalized)) return AndroidActionResult.Ok

        var opened = false
        for (attempt in 1..4) {
            when (val trigger = phone.clickAccountSwitcher()) {
                AndroidActionResult.Ok -> {
                    opened = true
                    break
                }
                is AndroidActionResult.HumanReview -> return trigger
                is AndroidActionResult.Failed -> Unit
            }
            if (attempt < 4 && !delayCancellable(clamp(deadline, 1_000), deadline)) {
                return AndroidActionResult.Failed("Stopped while opening account switcher")
            }
        }
        if (!opened) return AndroidActionResult.Failed("Could not open Instagram account switcher semantically")
        if (!delayCancellable(clamp(deadline, 800), deadline)) return AndroidActionResult.Failed("Stopped while opening account switcher")
        when (val choose = phone.clickAccount(normalized)) {
            AndroidActionResult.Ok -> Unit
            else -> return choose
        }
        if (!delayCancellable(clamp(deadline, 3_000), deadline)) return AndroidActionResult.Failed("Stopped while switching account")
        when (val profile = phone.clickProfile()) {
            AndroidActionResult.Ok -> Unit
            else -> return profile
        }
        if (!delayCancellable(clamp(deadline, 700), deadline)) return AndroidActionResult.Failed("Stopped while verifying account")
        val verified = phone.observe().getOrNull()?.hasLabel(normalized) == true
        return if (verified) AndroidActionResult.Ok else AndroidActionResult.Failed("Instagram account switch could not be verified for $normalized")
    }

    private fun ensureReels(deadline: Long): AndroidActionResult {
        repeat(6) { attempt ->
            if (!hasTime(deadline)) return AndroidActionResult.Failed("Reels Warmup duration elapsed")
            val screen = phone.observe().getOrNull()
            if (screen != null) {
                if (isReels(screen)) return AndroidActionResult.Ok
                if (isComments(screen)) {
                    when (val dismissed = phone.dismissComments()) {
                        AndroidActionResult.Ok -> Unit
                        is AndroidActionResult.HumanReview -> return dismissed
                        is AndroidActionResult.Failed -> Unit
                    }
                    delayCancellable(clamp(deadline, 550), deadline)
                    return@repeat
                }
            }
            when (val reels = phone.clickReels()) {
                AndroidActionResult.Ok -> {
                    delayCancellable(clamp(deadline, 1_100), deadline)
                    val verified = phone.observe().getOrNull()
                    if (verified != null && isReels(verified)) return AndroidActionResult.Ok
                }
                is AndroidActionResult.HumanReview -> return reels
                is AndroidActionResult.Failed -> {
                    if (attempt >= 1) {
                        when (val relaunched = phone.openInstagram()) {
                            AndroidActionResult.Ok -> delayCancellable(clamp(deadline, 1_400), deadline)
                            else -> return relaunched
                        }
                    }
                }
            }
        }
        return AndroidActionResult.Failed("Could not recover Instagram to Reels after 6 attempts")
    }

    private fun isReels(screen: AndroidScreen): Boolean {
        if (screen.packageName != "com.instagram.android") return false
        if (screen.hasSelectedLabel("Reels")) return true
        val hasEngagementRail = screen.hasLabel("Comment") && (screen.hasLabel("Like") || screen.hasLabel("Unlike"))
        val homeSelected = screen.hasSelectedLabel("Home")
        return hasEngagementRail && !homeSelected && !isComments(screen)
    }

    private fun isComments(screen: AndroidScreen): Boolean =
        screen.nodes.any { it.editable && it.label.contains("comment", ignoreCase = true) } ||
            (screen.hasLabel("Comments") && screen.nodes.any { it.editable })

    private fun chance(probability: Double): Boolean = random.nextDouble() < probability

    private fun between(min: Long, max: Long): Long {
        if (max <= min) return min
        return (min + random.nextDouble() * (max - min)).roundToLong()
    }

    private fun hasTime(deadline: Long): Boolean = !Thread.currentThread().isInterrupted && now() < deadline

    private fun clamp(deadline: Long, desiredMs: Long): Long = desiredMs.coerceAtLeast(0).coerceAtMost((deadline - now()).coerceAtLeast(0))

    private fun delayCancellable(ms: Long, deadline: Long): Boolean {
        var remaining = ms.coerceAtLeast(0)
        while (remaining > 0 && hasTime(deadline)) {
            val slice = remaining.coerceAtMost(250)
            try {
                sleeper(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !Thread.currentThread().isInterrupted
    }

    private fun interrupted(checkpointKey: String, output: Map<String, String>): StockSkillResult {
        checkpoints.clear(checkpointKey)
        return StockSkillResult(false, output = output, message = "Reels Warmup stopped")
    }
}

/** Skill definition stored in the existing AutomationStore so it appears as a first-class skill. */
object InstagramReelsWarmupStockSkill {
    val definition = SkillDefinition(
        id = InstagramStockSkillIds.REELS_WARMUP,
        name = "Instagram · Reels Warmup",
        description = "Stock Android skill: browse Instagram Reels with source-faithful watch, like, optional comment and linger behavior. Uses semantic Android controls and verified phone actions; never hard-coded device coordinates.",
        inputs = listOf("durationMinutes", "personality", "likeEnabled", "commentEnabled", "commentText", "switchAccount"),
        outputs = listOf("videosViewed", "swipes", "likes", "comments", "recoveries", "reason", "personality"),
        steps = listOf(
            StepDefinition(
                id = "run-reels-warmup",
                name = "Run Reels Warmup",
                type = StepType.STOCK_SKILL,
                parameters = mapOf(
                    "skillId" to InstagramStockSkillIds.REELS_WARMUP,
                    "durationMinutes" to "${'$'}{durationMinutes}",
                    "personality" to "${'$'}{personality}",
                    "likeEnabled" to "${'$'}{likeEnabled}",
                    "commentEnabled" to "${'$'}{commentEnabled}",
                    "commentText" to "${'$'}{commentText}",
                    "switchAccount" to "${'$'}{switchAccount}",
                ),
                recovery = RecoveryPolicy(maxRetries = 0),
            )
        ),
        enabled = true,
        version = 1,
    )
}

class InstagramStockSkillGateway(private val context: Context) : StockSkillGateway {
    override fun execute(request: StockSkillRequest): StockSkillResult = when (request.skillId) {
        InstagramStockSkillIds.REELS_WARMUP -> InstagramReelsWarmupRunner(context).run(request)
        else -> StockSkillResult(false, message = "unknown_stock_skill:${request.skillId}")
    }
}
