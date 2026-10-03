package com.cyclone.mobile.automation.stock.instagram

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Android semantic port of Kevs-IOS-Agents `post` at source commit
 * b909752df7af7a595714ed660af7cc971ec408d5.
 *
 * Fidelity boundary: the pinned source stops once Instagram's share/caption review screen is
 * reached. It does not tap Draft or Share, and its executable path does not type the manifest
 * caption. This stock skill intentionally preserves that exact boundary.
 *
 * iOS WDA media import is translated to Android MediaStore staging from granted content:// URIs.
 * UI actions remain PhoneToolExecutor-authoritative. Media-grid fallbacks use only live observed
 * node bounds, never fixed coordinates or density assumptions.
 */
object InstagramPreparePostStockSkill {
    const val ID = "stock.instagram.prepare_post"
    const val MAX_MEDIA_BYTES = 350L * 1024L * 1024L
    const val MAX_MEDIA_ITEMS = 20

    val definition = SkillDefinition(
        id = ID,
        name = "Instagram · Prepare Post",
        description = "Stage media, open Instagram Post composer, select the newest staged media in order, and stop at final share/caption review. Never taps Draft or Share.",
        inputs = listOf("mediaUris", "mediaCount", "musicUrl", "caption", "switchAccount", "destination"),
        outputs = listOf("reviewReached", "selectedMedia", "stagedMedia", "destination", "captionPending", "accountSwitchSkipped", "reason"),
        steps = listOf(
            StepDefinition(
                id = "prepare-instagram-post",
                name = "Prepare Instagram Post",
                type = StepType.STOCK_SKILL,
                parameters = mapOf(
                    "skillId" to ID,
                    "mediaUris" to "${'$'}{mediaUris}",
                    "mediaCount" to "${'$'}{mediaCount}",
                    "musicUrl" to "${'$'}{musicUrl}",
                    "caption" to "${'$'}{caption}",
                    "switchAccount" to "${'$'}{switchAccount}",
                    "destination" to "${'$'}{destination}",
                ),
                recovery = RecoveryPolicy(maxRetries = 0),
            )
        ),
        enabled = true,
        version = 1,
    )
}

internal data class PreparePostConfig(
    val mediaUris: List<String>,
    val mediaCount: Int,
    val musicUrl: String?,
    val caption: String?,
    val switchAccount: String?,
    val destination: String,
) {
    companion object {
        fun parse(arguments: Map<String, String>): Result<PreparePostConfig> = runCatching {
            val uris = parseUris(arguments["mediaUris"].orEmpty())
            val requestedCount = arguments["mediaCount"]?.trim()?.takeIf(String::isNotBlank)?.toIntOrNull()
            val count = if (uris.isNotEmpty()) uris.size else requestedCount ?: 0
            require(count in 1..InstagramPreparePostStockSkill.MAX_MEDIA_ITEMS) {
                "mediaCount must be between 1 and ${InstagramPreparePostStockSkill.MAX_MEDIA_ITEMS}"
            }
            val destination = arguments["destination"]?.trim()?.lowercase().takeUnless { it.isNullOrBlank() } ?: "publish"
            require(destination == "draft" || destination == "publish") { "destination must be draft or publish" }
            val musicUrl = arguments["musicUrl"]?.trim()?.takeIf(String::isNotBlank)
            if (musicUrl != null) require(musicUrl.startsWith("https://") || musicUrl.startsWith("http://")) {
                "musicUrl must be http or https"
            }
            PreparePostConfig(
                mediaUris = uris,
                mediaCount = count,
                musicUrl = musicUrl,
                caption = arguments["caption"]?.takeIf(String::isNotBlank),
                switchAccount = arguments["switchAccount"]?.trim()?.takeIf(String::isNotBlank),
                destination = destination,
            )
        }

        fun parseUris(raw: String): List<String> = raw
            .split(Regex("[\\n,]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }
}

internal data class StagedMedia(val source: String, val staged: Uri, val mimeType: String, val name: String)

/** Android equivalent of WDA import-media: copy granted content URIs into Recents newest-first. */
internal class InstagramMediaStager(private val context: Context) {
    fun stage(sourceUris: List<String>): Result<List<StagedMedia>> = runCatching {
        if (sourceUris.isEmpty()) return@runCatching emptyList()
        val resolver = context.contentResolver
        val baseTaken = System.currentTimeMillis()
        val out = mutableListOf<StagedMedia>()

        // Same ordering rule as source: reverse imports so input[0] is newest cell 0.
        sourceUris.asReversed().forEachIndexed { reversedIndex, raw ->
            val source = Uri.parse(raw)
            require(source.scheme == "content") { "Post media must be granted content:// URIs" }
            val meta = queryMeta(source)
            require(meta.size == null || meta.size <= InstagramPreparePostStockSkill.MAX_MEDIA_BYTES) {
                "${meta.name} exceeds the 350 MB Instagram media import limit"
            }
            val mime = resolver.getType(source) ?: meta.mimeType ?: error("Could not determine media type for ${meta.name}")
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> error("Unsupported Instagram media type $mime")
            }
            val relativePath = if (mime.startsWith("video/")) "Movies/Cyclone" else "Pictures/Cyclone"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, meta.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, baseTaken + reversedIndex)
            }
            val target = resolver.insert(collection, values) ?: error("Could not stage ${meta.name} in MediaStore")
            try {
                resolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Could not open ${meta.name}" }
                    resolver.openOutputStream(target).use { output ->
                        requireNotNull(output) { "Could not write ${meta.name}" }
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= InstagramPreparePostStockSkill.MAX_MEDIA_BYTES) {
                                "${meta.name} exceeds the 350 MB Instagram media import limit"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                out += StagedMedia(raw, target, mime, meta.name)
            } catch (error: Throwable) {
                resolver.delete(target, null, null)
                throw error
            }
        }
        out.reversed()
    }

    private data class Meta(val name: String, val size: Long?, val mimeType: String?)

    private fun queryMeta(uri: Uri): Meta {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "cyclone-media-${System.nanoTime()}"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    cursor.getString(index)?.takeIf(String::isNotBlank)?.let { name = it }
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) size = cursor.getLong(index)
                }
            }
        }
        return Meta(name = name, size = size, mimeType = context.contentResolver.getType(uri))
    }
}

internal data class PostBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

internal data class PostNode(
    val id: String,
    val path: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val role: String,
    val bounds: PostBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val checkable: Boolean,
) {
    val label: String get() = "$text $contentDescription".trim()
}

internal data class PostScreen(
    val packageName: String,
    val width: Int,
    val height: Int,
    val nodes: List<PostNode>,
) {
    fun has(fragment: String): Boolean = nodes.any { it.label.contains(fragment, ignoreCase = true) }
}

internal sealed interface PostAction {
    data object Ok : PostAction
    data class Failed(val message: String) : PostAction
    data class HumanReview(val message: String) : PostAction
}

internal class InstagramPostPhonePort(private val context: Context) {
    private val pkg = "com.instagram.android"

    fun observe(): Result<PostScreen> {
        val result = execute("phone.observe", JSONObject())
        if (!result.ok) return Result.failure(IllegalStateException(result.error?.message ?: "phone.observe failed"))
        val payload = result.payload as? JSONObject ?: return Result.failure(IllegalStateException("phone.observe returned no snapshot"))
        val screen = payload.optJSONObject("screen")
        val nodesJson = payload.optJSONArray("nodes") ?: JSONArray()
        return Result.success(PostScreen(
            packageName = payload.optString("package"),
            width = screen?.optInt("width") ?: 0,
            height = screen?.optInt("height") ?: 0,
            nodes = buildList {
                for (i in 0 until nodesJson.length()) {
                    val node = nodesJson.optJSONObject(i) ?: continue
                    val b = node.optJSONObject("bounds")
                    add(PostNode(
                        id = node.optString("id"),
                        path = node.optString("path"),
                        text = node.optString("text"),
                        contentDescription = node.optString("contentDescription"),
                        resourceId = node.optString("resourceId"),
                        className = node.optString("class"),
                        role = node.optString("role"),
                        bounds = PostBounds(b?.optInt("left") ?: 0, b?.optInt("top") ?: 0, b?.optInt("right") ?: 0, b?.optInt("bottom") ?: 0),
                        clickable = node.optBoolean("clickable"),
                        editable = node.optBoolean("editable"),
                        selected = node.optBoolean("selected"),
                        checked = node.optBoolean("checked"),
                        checkable = node.optBoolean("checkable"),
                    ))
                }
            },
        ))
    }

    fun openInstagram(): PostAction = mutate("phone.open_app", JSONObject().put("package", pkg), requireInstagram = false)
    fun back(): PostAction = mutate("phone.back", JSONObject())

    fun launchMusic(url: String): PostAction = mutate(
        "phone.launch_intent",
        JSONObject().put("uri", url).put("package", pkg),
        requireInstagram = false,
    )

    fun clickHome(): PostAction = clickFirst(listOf(desc("Home"), text("Home"), fuzzy("Home", 0.90)))
    fun clickCreate(): PostAction = clickFirst(listOf(
        desc("Create"), containsDesc("Create"), text("Create"), text("New post"), containsDesc("New post"),
    ))
    fun clickPostContent(): PostAction = clickFirst(listOf(
        text("Post"), desc("Post"), containsDesc("Post"),
    ))
    fun clickUseSound(): PostAction = clickFirst(listOf(
        text("Use this sound"), desc("Use this sound"), text("Use sound"), containsDesc("Use this sound"),
    ))
    fun clickGalleryUpload(): PostAction = clickFirst(listOf(
        text("Gallery"), desc("Gallery"), text("Upload"), desc("Upload"), containsDesc("Gallery"),
    ))
    fun clickNext(): PostAction = clickFirst(listOf(text("Next"), desc("Next"), containsDesc("Next")))

    fun dismissFeedTutorials() {
        val labels = listOf("Skip", "Got it", "Not now", "Try it", "Next tip", "Learn more")
        repeat(6) {
            val screen = observe().getOrNull() ?: return
            val target = screen.nodes.firstOrNull { node -> node.clickable && labels.any { label -> node.label.contains(label, ignoreCase = true) } }
                ?: return
            tapNode(target)
            Thread.sleep(350)
        }
    }

    fun ensureToggle(label: String, desired: Boolean): PostAction {
        repeat(3) {
            val screen = observe().getOrNull() ?: return PostAction.Failed("Could not observe $label toggle")
            val node = screen.nodes.firstOrNull { it.label.contains(label, ignoreCase = true) }
                ?: return PostAction.Failed("Instagram control not found: $label")
            val checked = node.checked || node.selected
            if (checked == desired) return PostAction.Ok
            when (val tapped = tapNode(node)) {
                PostAction.Ok -> Thread.sleep(350)
                else -> return tapped
            }
        }
        val state = observe().getOrNull()?.nodes?.firstOrNull { it.label.contains(label, ignoreCase = true) }
        return if (state != null && (state.checked || state.selected) == desired) PostAction.Ok
        else PostAction.Failed("Could not get $label into ${if (desired) "on" else "off"} state")
    }

    fun selectNewestMedia(count: Int): PostAction {
        var remaining = count
        var page = 0
        while (remaining > 0 && page < 8) {
            val screen = observe().getOrNull() ?: return PostAction.Failed("Could not observe Instagram media picker")
            val candidates = mediaCandidates(screen)
            if (candidates.isEmpty()) return PostAction.Failed("No selectable media cells found in Instagram picker")
            val alreadyChosen = count - remaining
            val onThisPage = if (page == 0) candidates.drop(alreadyChosen) else candidates
            for (node in onThisPage) {
                if (remaining <= 0) break
                when (val tapped = tapNode(node)) {
                    PostAction.Ok -> {
                        remaining--
                        Thread.sleep(300)
                    }
                    else -> return tapped
                }
            }
            if (remaining <= 0) return PostAction.Ok
            when (val scroll = scrollGrid(screen)) {
                PostAction.Ok -> { page++; Thread.sleep(450) }
                else -> return PostAction.Failed("Picker cannot expose all $count requested media items")
            }
        }
        return if (remaining == 0) PostAction.Ok else PostAction.Failed("Could not select all $count media items")
    }

    fun reviewReached(): Boolean {
        val screen = observe().getOrNull() ?: return false
        if (screen.packageName != pkg) return false
        val reviewSignals = listOf("Write a caption", "Caption", "Tag people", "Add location", "Share")
        return reviewSignals.count { screen.has(it) } >= 2 || (screen.has("Share") && screen.nodes.any { it.editable })
    }

    fun selectedHome(): Boolean = observe().getOrNull()?.nodes?.any {
        it.selected && it.label.contains("Home", ignoreCase = true)
    } == true

    private fun mediaCandidates(screen: PostScreen): List<PostNode> {
        if (screen.width <= 0 || screen.height <= 0) return emptyList()
        val excluded = listOf("Next", "Recent", "Recents", "Camera", "Select multiple", "Use layout", "Gallery", "Close", "Back")
        val minTop = (screen.height * 0.20).roundToInt()
        return screen.nodes.asSequence()
            .filter { it.clickable && it.bounds.top >= minTop && it.bounds.width > 32 && it.bounds.height > 32 }
            .filterNot { node -> excluded.any { node.label.contains(it, ignoreCase = true) } }
            .filter { node ->
                node.className.contains("ImageView", ignoreCase = true) ||
                    node.role.contains("image", ignoreCase = true) ||
                    node.resourceId.contains("media", ignoreCase = true) ||
                    node.resourceId.contains("gallery", ignoreCase = true) ||
                    node.resourceId.contains("thumbnail", ignoreCase = true) ||
                    node.label.contains("photo", ignoreCase = true) ||
                    node.label.contains("video", ignoreCase = true)
            }
            .distinctBy { "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}" }
            .sortedWith(compareBy<PostNode> { it.bounds.top }.thenBy { it.bounds.left })
            .toList()
    }

    private fun scrollGrid(screen: PostScreen): PostAction {
        val scrollable = screen.nodes.firstOrNull { it.resourceId.contains("recycler", true) || it.className.contains("Recycler", true) }
        if (scrollable != null) {
            val params = JSONObject().put("direction", "forward")
            if (scrollable.id.isNotBlank()) params.put("selector", JSONObject().put("elementId", scrollable.id))
            return map(execute("phone.scroll", params))
        }
        if (screen.width <= 0 || screen.height <= 0) return PostAction.Failed("No live screen dimensions for picker scroll")
        return map(execute("phone.swipe", JSONObject()
            .put("x1", screen.width * 0.5)
            .put("y1", screen.height * 0.78)
            .put("x2", screen.width * 0.5)
            .put("y2", screen.height * 0.38)
            .put("durationMs", 350)
            .put("humanize", "off")))
    }

    private fun tapNode(node: PostNode): PostAction {
        if (node.id.isNotBlank()) {
            val clicked = map(execute("phone.click", JSONObject().put("selector", JSONObject().put("elementId", node.id))))
            if (clicked !is PostAction.Failed) return clicked
        }
        if (node.path.isNotBlank()) {
            val clicked = map(execute("phone.click", JSONObject().put("selector", JSONObject().put("path", node.path))))
            if (clicked !is PostAction.Failed) return clicked
        }
        return map(execute("phone.tap", JSONObject().put("x", node.bounds.centerX).put("y", node.bounds.centerY)))
    }

    private fun clickFirst(selectors: List<JSONObject>): PostAction {
        var last = "Control not found"
        for (selector in selectors) {
            val fresh = observe().getOrElse { return PostAction.Failed(it.message ?: "Could not observe Instagram") }
            if (fresh.packageName != pkg) return PostAction.Failed("Instagram is not foreground")
            val result = execute("phone.click", JSONObject().put("selector", selector))
            if (result.ok) return PostAction.Ok
            if (result.error?.code == PhoneToolErrorCode.POLICY_DENIED) return PostAction.HumanReview(result.error.message)
            last = result.error?.message ?: last
        }
        return PostAction.Failed(last)
    }

    private fun mutate(tool: String, params: JSONObject, requireInstagram: Boolean = true): PostAction {
        val fresh = execute("phone.observe", JSONObject())
        if (!fresh.ok) return PostAction.Failed(fresh.error?.message ?: "Could not refresh observation")
        if (requireInstagram) {
            val pkgNow = (fresh.payload as? JSONObject)?.optString("package")
            if (pkgNow != pkg) return PostAction.Failed("Instagram is not foreground")
        }
        return map(execute(tool, params))
    }

    private fun map(result: PhoneToolResult): PostAction {
        if (result.ok) return PostAction.Ok
        val message = result.error?.message ?: "${result.tool} failed"
        return if (result.error?.code == PhoneToolErrorCode.POLICY_DENIED) PostAction.HumanReview(message)
        else PostAction.Failed(message)
    }

    private fun execute(tool: String, params: JSONObject): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest("stock-instagram-post-${UUID.randomUUID()}", tool, params.put("fastPath", true)),
    )

    private fun text(value: String) = JSONObject().put("text", value).put("clickable", true)
    private fun desc(value: String) = JSONObject().put("contentDescription", value).put("clickable", true)
    private fun containsDesc(value: String) = JSONObject().put("contentDescriptionContains", value).put("clickable", true)
    private fun fuzzy(value: String, score: Double) = JSONObject().put("fuzzyText", value).put("minFuzzyScore", score).put("clickable", true)
}

internal class InstagramPreparePostRunner(
    private val context: Context,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val phone = InstagramPostPhonePort(context)
    private val stager = InstagramMediaStager(context)

    fun run(request: StockSkillRequest): StockSkillResult {
        val config = PreparePostConfig.parse(request.arguments).getOrElse {
            return StockSkillResult(false, message = it.message ?: "Invalid Prepare Post configuration")
        }
        val staged = stager.stage(config.mediaUris).getOrElse {
            return StockSkillResult(false, message = "Could not stage Instagram media: ${it.message}")
        }
        if (staged.isNotEmpty() && !delay(3_000)) return StockSkillResult(false, message = "Prepare Post stopped during media staging")
        var accountSwitchSkipped = false

        fun output(reason: String, reached: Boolean) = mapOf(
            "reviewReached" to reached.toString(),
            "selectedMedia" to config.mediaCount.toString(),
            "stagedMedia" to staged.size.toString(),
            "destination" to config.destination,
            "captionPending" to (!config.caption.isNullOrBlank()).toString(),
            "accountSwitchSkipped" to accountSwitchSkipped.toString(),
            "reason" to reason,
        )

        when (val opened = phone.openInstagram()) {
            PostAction.Ok -> Unit
            is PostAction.HumanReview -> return StockSkillResult(false, output("waiting_for_human", false), waitingForHuman = true, message = opened.message)
            is PostAction.Failed -> return StockSkillResult(false, output("failed", false), message = opened.message)
        }
        if (!delay(1_500)) return StockSkillResult(false, output("stopped", false), message = "Prepare Post stopped")

        if (config.switchAccount != null) {
            when (val switched = switchAccount(config.switchAccount)) {
                PostAction.Ok -> Unit
                is PostAction.HumanReview -> return StockSkillResult(false, output("waiting_for_human", false), waitingForHuman = true, message = switched.message)
                is PostAction.Failed -> accountSwitchSkipped = true // source continues with current account
            }
        }

        var lastError = "Could not reach Instagram share/caption review"
        for (attempt in 1..3) {
            when (val result = reachReview(config)) {
                PostAction.Ok -> return StockSkillResult(true, output("review_ready", true), message = "Instagram share/caption review is ready; Draft/Share was not tapped")
                is PostAction.HumanReview -> return StockSkillResult(false, output("waiting_for_human", false), waitingForHuman = true, message = result.message)
                is PostAction.Failed -> lastError = result.message
            }
            if (attempt < 3) {
                recoverHome()
                if (!delay(1_000)) return StockSkillResult(false, output("stopped", false), message = "Prepare Post stopped")
            }
        }
        return StockSkillResult(false, output("failed", false), message = lastError)
    }

    private fun reachReview(config: PreparePostConfig): PostAction {
        if (config.musicUrl != null) {
            when (val launched = phone.launchMusic(config.musicUrl)) { PostAction.Ok -> Unit; else -> return launched }
            if (!delay(2_500)) return PostAction.Failed("Stopped while opening Instagram music")
            when (val sound = phone.clickUseSound()) { PostAction.Ok -> Unit; else -> return sound }
            if (!delay(1_500)) return PostAction.Failed("Stopped while opening sound composer")
            when (val gallery = phone.clickGalleryUpload()) { PostAction.Ok -> Unit; else -> return gallery }
        } else {
            if (!recoverHome()) return PostAction.Failed("Could not recover Instagram Home before Create")
            phone.dismissFeedTutorials()
            when (val create = phone.clickCreate()) { PostAction.Ok -> Unit; else -> return create }
            if (!delay(800)) return PostAction.Failed("Stopped while opening Create")
            when (val post = phone.clickPostContent()) { PostAction.Ok -> Unit; else -> return post }
        }
        if (!delay(1_500)) return PostAction.Failed("Stopped while opening Instagram media picker")

        if (config.mediaCount > 1) {
            when (val multi = phone.ensureToggle("Select multiple", true)) { PostAction.Ok -> Unit; else -> return multi }
        }
        when (val select = phone.selectNewestMedia(config.mediaCount)) { PostAction.Ok -> Unit; else -> return select }
        if (config.mediaCount > 1) {
            // Source explicitly forces Layout off after media selection.
            val layout = phone.ensureToggle("Use layout", false)
            if (layout is PostAction.HumanReview) return layout
        }
        when (val next = phone.clickNext()) { PostAction.Ok -> Unit; else -> return next }
        if (!delay(1_500)) return PostAction.Failed("Stopped after picker Next")
        if (phone.reviewReached()) return PostAction.Ok
        when (val editorNext = phone.clickNext()) { PostAction.Ok -> Unit; else -> return editorNext }
        if (!delay(1_500)) return PostAction.Failed("Stopped after editor Next")
        return if (phone.reviewReached()) PostAction.Ok else PostAction.Failed("Instagram share/caption review could not be verified")
    }

    private fun switchAccount(handle: String): PostAction {
        val base = InstagramPhonePort(context)
        val bare = handle.removePrefix("@").trim()
        when (val profile = base.clickProfile()) {
            AndroidActionResult.Ok -> Unit
            is AndroidActionResult.HumanReview -> return PostAction.HumanReview(profile.message)
            is AndroidActionResult.Failed -> return PostAction.Failed(profile.message)
        }
        if (!delay(1_500)) return PostAction.Failed("Stopped while opening Instagram profile")
        if (base.observe().getOrNull()?.hasLabel(bare) == true) return PostAction.Ok
        var opened = false
        for (attempt in 1..4) {
            when (val switcher = base.clickAccountSwitcher()) {
                AndroidActionResult.Ok -> { opened = true; break }
                is AndroidActionResult.HumanReview -> return PostAction.HumanReview(switcher.message)
                is AndroidActionResult.Failed -> Unit
            }
            if (attempt < 4 && !delay(700)) return PostAction.Failed("Stopped while opening account switcher")
        }
        if (!opened) return PostAction.Failed("Could not open Instagram account switcher")
        when (val account = base.clickAccount(bare)) {
            AndroidActionResult.Ok -> Unit
            is AndroidActionResult.HumanReview -> return PostAction.HumanReview(account.message)
            is AndroidActionResult.Failed -> return PostAction.Failed(account.message)
        }
        if (!delay(2_500)) return PostAction.Failed("Stopped while switching account")
        when (val profile = base.clickProfile()) {
            AndroidActionResult.Ok -> Unit
            is AndroidActionResult.HumanReview -> return PostAction.HumanReview(profile.message)
            is AndroidActionResult.Failed -> return PostAction.Failed(profile.message)
        }
        return if (delay(500) && base.observe().getOrNull()?.hasLabel(bare) == true) PostAction.Ok
        else PostAction.Failed("Instagram account switch could not be verified for $bare")
    }

    private fun recoverHome(): Boolean {
        repeat(4) { attempt ->
            if (phone.selectedHome()) return true
            when (phone.clickHome()) {
                PostAction.Ok -> if (delay(500) && phone.selectedHome()) return true
                is PostAction.HumanReview -> return false
                is PostAction.Failed -> Unit
            }
            if (attempt < 3) {
                phone.back()
                if (!delay(350)) return false
            }
        }
        when (phone.openInstagram()) { PostAction.Ok -> Unit; else -> return false }
        if (!delay(900)) return false
        return phone.selectedHome() || (phone.clickHome() is PostAction.Ok && delay(500) && phone.selectedHome())
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
}
