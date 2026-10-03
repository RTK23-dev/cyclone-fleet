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
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.random.Random

/** Android semantic port of Kevs-IOS-Agents `doomscroll-following` at the pinned source commit. */
object InstagramReelsFollowingStockSkill {
    const val ID = "stock.instagram.reels_following"

    val definition = SkillDefinition(
        id = ID,
        name = "Instagram · Reels Following",
        description = "Stock Android skill: browse the Instagram Reels Following feed with source-faithful watch, like, optional comment and linger behavior. Plain Reels never count as Following success.",
        inputs = listOf("durationMinutes", "personality", "likeEnabled", "commentEnabled", "commentText", "switchAccount"),
        outputs = listOf("videosViewed", "swipes", "likes", "comments", "recoveries", "reason", "personality", "feed"),
        steps = listOf(
            StepDefinition(
                id = "run-reels-following",
                name = "Run Reels Following",
                type = StepType.STOCK_SKILL,
                parameters = mapOf(
                    "skillId" to ID,
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

/** Only the Following-tab affordance is separate; all other mutations reuse the Warmup phone port. */
internal class InstagramFollowingTabPort(private val context: Context) {
    fun clickFollowing(): AndroidActionResult {
        val selectors = listOf(
            JSONObject().put("contentDescription", "Following").put("clickable", true),
            JSONObject().put("text", "Following").put("clickable", true),
            JSONObject().put("fuzzyText", "Following").put("minFuzzyScore", 0.90).put("clickable", true),
        )
        var last = "Following control was not found"
        for (selector in selectors) {
            val observed = execute("phone.observe", JSONObject())
            if (!observed.ok) return AndroidActionResult.Failed(observed.error?.message ?: "Could not observe Instagram")
            val payload = observed.payload as? JSONObject
            if (payload?.optString("package") != "com.instagram.android") {
                return AndroidActionResult.Failed("Instagram is not foreground")
            }
            val clicked = execute("phone.click", JSONObject().put("selector", selector))
            if (clicked.ok) return AndroidActionResult.Ok
            if (clicked.error?.code == PhoneToolErrorCode.POLICY_DENIED) {
                return AndroidActionResult.HumanReview(clicked.error.message)
            }
            last = clicked.error?.message ?: last
        }
        return AndroidActionResult.Failed(last)
    }

    private fun execute(tool: String, params: JSONObject): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest("stock-instagram-following-${UUID.randomUUID()}", tool, params.put("fastPath", true)),
    )
}

internal class InstagramReelsFollowingRunner(
    private val context: Context,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val phone = InstagramPhonePort(context)
    private val following = InstagramFollowingTabPort(context)
    private val checkpoints = InstagramNativeCheckpointStore(context)

    fun run(request: StockSkillRequest): StockSkillResult {
        val config = ReelsWarmupConfig.parse(request.arguments).getOrElse {
            return StockSkillResult(false, message = it.message ?: "Invalid Reels Following configuration")
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
            "feed" to "following",
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
        if (!delayCancellable(1_000, deadline)) return stopped(checkpointKey, output("stopped"))

        if (config.switchAccount != null) {
            when (val switched = ensureAccount(config.switchAccount, deadline)) {
                AndroidActionResult.Ok -> Unit
                is AndroidActionResult.HumanReview -> return saveForHuman(switched.message)
                is AndroidActionResult.Failed -> return StockSkillResult(false, output("failed"), message = switched.message)
            }
        }

        // Source startup is explicitly Reels -> Following, never Following from an arbitrary screen.
        when (val landed = openReelsThenFollowing(deadline)) {
            AndroidActionResult.Ok -> Unit
            is AndroidActionResult.HumanReview -> return saveForHuman(landed.message)
            is AndroidActionResult.Failed -> return StockSkillResult(false, output("failed"), message = landed.message)
        }

        while (hasTime(deadline)) {
            when (val ensured = ensureFollowing(deadline)) {
                AndroidActionResult.Ok -> Unit
                is AndroidActionResult.HumanReview -> return saveForHuman(ensured.message)
                is AndroidActionResult.Failed -> {
                    recoveries++
                    if (!delayCancellable(clamp(deadline, 1_200), deadline)) break
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
                if (screen == null || !isFollowing(screen)) {
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
                        is AndroidActionResult.Failed -> Unit
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
                // Source settles back to Following after engagement instead of accepting plain Reels.
                when (val settled = ensureFollowing(deadline, maxAttempts = 2)) {
                    is AndroidActionResult.HumanReview -> return saveForHuman(settled.message)
                    else -> Unit
                }
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
            if (!isFollowing(screen)) {
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
        val interrupted = Thread.currentThread().isInterrupted
        return StockSkillResult(
            success = !interrupted,
            output = output(if (interrupted) "stopped" else "completed"),
            message = if (interrupted) "Reels Following stopped" else "Reels Following completed",
        )
    }

    private fun ensureAccount(handle: String, deadline: Long): AndroidActionResult {
        val normalized = handle.removePrefix("@").trim()
        when (val profile = phone.clickProfile()) {
            AndroidActionResult.Ok -> Unit
            else -> return profile
        }
        if (!delayCancellable(clamp(deadline, 2_000), deadline)) return AndroidActionResult.Failed("Stopped while opening Instagram profile")
        if (phone.observe().getOrNull()?.hasLabel(normalized) == true) return AndroidActionResult.Ok

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
        return if (phone.observe().getOrNull()?.hasLabel(normalized) == true) AndroidActionResult.Ok
        else AndroidActionResult.Failed("Instagram account switch could not be verified for $normalized")
    }

    private fun openReelsThenFollowing(deadline: Long): AndroidActionResult {
        when (val reels = phone.clickReels()) {
            AndroidActionResult.Ok -> Unit
            else -> return reels
        }
        if (!delayCancellable(clamp(deadline, 1_400), deadline)) return AndroidActionResult.Failed("Stopped while opening Reels")
        when (val tab = following.clickFollowing()) {
            AndroidActionResult.Ok -> Unit
            else -> return tab
        }
        if (!delayCancellable(clamp(deadline, 1_500), deadline)) return AndroidActionResult.Failed("Stopped while opening Following")
        val screen = phone.observe().getOrNull() ?: return AndroidActionResult.Failed("Could not verify Following feed")
        return if (isFollowing(screen)) AndroidActionResult.Ok else AndroidActionResult.Failed("Following feed could not be verified")
    }

    private fun ensureFollowing(deadline: Long, maxAttempts: Int = 6): AndroidActionResult {
        var softRetries = 0
        repeat(maxAttempts) { attempt ->
            if (!hasTime(deadline)) return AndroidActionResult.Failed("Reels Following duration elapsed")
            val screen = phone.observe().getOrNull()
            if (screen != null) {
                if (isFollowing(screen)) return AndroidActionResult.Ok
                if (isComments(screen)) {
                    when (val dismissed = phone.dismissComments()) {
                        AndroidActionResult.Ok -> Unit
                        is AndroidActionResult.HumanReview -> return dismissed
                        is AndroidActionResult.Failed -> Unit
                    }
                    delayCancellable(clamp(deadline, 550), deadline)
                    return@repeat
                }
                if (isPlainReels(screen) || isHome(screen)) {
                    softRetries++
                    val recovered = if (softRetries >= 2) {
                        softRetries = 0
                        openReelsThenFollowing(deadline)
                    } else {
                        following.clickFollowing().also { delayCancellable(clamp(deadline, 1_100), deadline) }
                    }
                    if (recovered is AndroidActionResult.HumanReview) return recovered
                    return@repeat
                }
                if ((isSearch(screen) || isOffFeed(screen)) && softRetries < 1) {
                    softRetries++
                    delayCancellable(clamp(deadline, 900), deadline)
                    return@repeat
                }
            }
            softRetries = 0
            when (val reopened = phone.openInstagram()) {
                AndroidActionResult.Ok -> {
                    delayCancellable(clamp(deadline, 1_400), deadline)
                    when (val route = openReelsThenFollowing(deadline)) {
                        is AndroidActionResult.HumanReview -> return route
                        else -> Unit
                    }
                }
                else -> return reopened
            }
            if (attempt + 1 >= maxAttempts) return@repeat
        }
        return AndroidActionResult.Failed("Could not recover Instagram to Reels Following after $maxAttempts attempts")
    }

    internal fun isFollowing(screen: AndroidScreen): Boolean {
        if (screen.packageName != "com.instagram.android") return false
        // Critical source-fidelity rule: the unselected Following affordance exists on plain Reels,
        // so label presence alone is never enough. It must be selected in the live a11y tree.
        val selectedFollowing = screen.nodes.any {
            it.selected && it.label.contains("Following", ignoreCase = true)
        }
        if (!selectedFollowing) return false
        val engagementRail = screen.hasLabel("Comment") && (screen.hasLabel("Like") || screen.hasLabel("Unlike"))
        return engagementRail && !isComments(screen)
    }

    private fun isPlainReels(screen: AndroidScreen): Boolean =
        screen.packageName == "com.instagram.android" &&
            !isFollowing(screen) &&
            (screen.hasSelectedLabel("Reels") ||
                (screen.hasLabel("Comment") && (screen.hasLabel("Like") || screen.hasLabel("Unlike"))))

    private fun isHome(screen: AndroidScreen): Boolean = screen.hasSelectedLabel("Home")
    private fun isSearch(screen: AndroidScreen): Boolean = screen.hasSelectedLabel("Search")
    private fun isOffFeed(screen: AndroidScreen): Boolean =
        screen.packageName != "com.instagram.android" || (!isPlainReels(screen) && !isFollowing(screen) && !isHome(screen) && !isSearch(screen) && !isComments(screen))

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

    private fun stopped(checkpointKey: String, output: Map<String, String>): StockSkillResult {
        checkpoints.clear(checkpointKey)
        return StockSkillResult(false, output = output, message = "Reels Following stopped")
    }
}
