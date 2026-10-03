package com.cyclone.mobile.market

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Something Cyclone connects to, with its live state. Never carries a key or a token. */
data class MarketConnection(
    val id: String,
    val name: String,
    val glyph: String,
    val state: ConnectionState,
    val detail: String,
    val where: String,
)

enum class ConnectionState { CONNECTED, NEEDS_SETUP, OFF }

/** Why a run did not start, in words for the owner. Null means it started. */
data class RunRefusal(val code: String, val message: String)

/** The phone's marketplace: the one authority for what is added and what runs. Glass reaches it through market.* ops. */
object Marketplace {
    private val changes = MutableStateFlow(0)
    /** Bumps on every add, remove and run so pages refresh. */
    val revision: StateFlow<Int> = changes

    @Volatile private var installs: MarketInstalls? = null
    @Volatile private var owner: OwnerSkills? = null

    /** Seams for JVM tests of the gateway adapter and the page logic. */
    internal var busy: () -> Boolean = { MindMissions.isLive() || OverlayChromeRuntime.hasExecutingTask() || !WorkspaceTasks.canStartRequest() }
    internal var overlayReady: () -> Boolean = { OverlayChromeRuntime.isAttached() }
    internal var humanHasControl: () -> Boolean = { DeviceState.controller == DeviceState.Controller.HUMAN }
    internal var submit: (String) -> Unit = { goal -> Handler(Looper.getMainLooper()).post { OverlayChromeRuntime.submitRequest(goal) } }

    fun installs(context: Context): MarketInstalls = installs ?: synchronized(this) {
        ownerSkills(context)
        installs ?: MarketInstalls(File(context.applicationContext.filesDir, "Cyclone Brain/Marketplace/installed.json")).also { installs = it }
    }

    fun ownerSkills(context: Context): OwnerSkills = owner ?: synchronized(this) {
        owner ?: OwnerSkills(File(context.applicationContext.filesDir, "Cyclone Brain/Marketplace/owner-skills.json")).also { owner = it }
    }

    /**
     * Listings that pass every rule, first-party then the owner's saved skills. A broken listing is left out, never
     * shown half-checked.
     */
    fun catalog(): List<MarketListing> =
        MarketCatalog.LISTINGS.filter { runCatching { MarketRules.validate(it) }.isSuccess } + owner?.list().orEmpty()

    /**
     * "Save skill" on a run: the run's goal becomes the owner's own recipe, added and ready to Run. Throws
     * [MarketError] when the goal cannot be a skill.
     */
    fun saveSkill(context: Context, goal: String, apps: List<String>, anchor: SkillAnchor? = null): MarketListing {
        val listing = ownerSkills(context).save(goal, apps, anchor)
        val store = installs(context)
        if (store.get(listing.id) == null) store.add(listing, emptyMap(), "saved")
        changes.value++
        return listing
    }

    /**
     * Save skill on a finished Mind run, grounded (plan 23): the run is learned (so its screens and moves are on the
     * map) and the skill remembers where it worked: the app, the way in and the destination.
     */
    fun saveSkillFromRun(context: Context, missionId: String, goal: String): MarketListing {
        val app = context.applicationContext
        val trail = MindMissions.store(app).loadTrail(missionId)
        runCatching { com.cyclone.mobile.mind.learn.MissionLearning.learn(app, missionId) }
        val apps = trail?.screens.orEmpty().map { it.packageName }.filterNot { it.contains("launcher", ignoreCase = true) }
        return saveSkill(app, goal, apps, trail?.let { SkillAnchor.fromTrail(it, System.currentTimeMillis()) })
    }

    /**
     * A saved skill ran to the end: learn the run and move the skill's anchor to where it worked this time, so the
     * skill follows the app as it changes. Runs of goals that are not saved skills are left alone.
     */
    fun regroundAfterRun(context: Context, missionId: String, goal: String) {
        val skill = savedSkillFor(context, goal) ?: return
        val app = context.applicationContext
        val trail = MindMissions.store(app).loadTrail(missionId) ?: return
        val anchor = SkillAnchor.fromTrail(trail, System.currentTimeMillis()) ?: return
        runCatching { com.cyclone.mobile.mind.learn.MissionLearning.learn(app, missionId) }
        ownerSkills(app).save(skill.goal, skill.apps, anchor)
        changes.value++
    }

    /** A saved skill with its anchor, for the Mind's skill card; null when the goal is not a saved skill. */
    fun groundedSkillFor(context: Context, goal: String): Pair<MarketListing, SkillAnchor?>? =
        savedSkillFor(context, goal)?.let { it to ownerSkills(context).anchor(it.id) }

    /** Health of every owner skill against the map as it is now (Glass, the phone's Your skills). */
    fun skillsWithHealth(context: Context): List<Triple<MarketListing, SkillAnchor?, SkillHealth>> {
        val app = context.applicationContext
        val maps = runCatching {
            com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(app)
            com.cyclone.mobile.mind.map.MindMaps(com.cyclone.mobile.mind.learn.AppKnowledgeReader(com.cyclone.mobile.applearner.AppLearnerRuntime.store))
        }.getOrNull()
        val owner = ownerSkills(app)
        return owner.list().map { listing ->
            val anchor = owner.anchor(listing.id)
            Triple(listing, anchor, SkillGrounding.health(anchor, anchor?.let { maps?.map(it.packageName) }))
        }
    }

    /** The owner's skill saved from this goal, if any. */
    fun savedSkillFor(context: Context, goal: String): MarketListing? = runCatching {
        val id = OwnerSkills.draft(goal, emptyList()).id
        ownerSkills(context).list().firstOrNull { it.id == id }
    }.getOrNull()

    fun listing(id: String): MarketListing? = catalog().firstOrNull { it.id == id }

    /** Launchable apps on this phone, package → label, for "Because you use …". */
    fun installedApps(context: Context): Map<String, String> = runCatching {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }.getOrDefault(emptyMap())

    fun add(context: Context, id: String, inputs: Map<String, String>, source: String): InstalledListing =
        add(installs(context), id, inputs, source)

    internal fun add(store: MarketInstalls, id: String, inputs: Map<String, String>, source: String): InstalledListing {
        val listing = listing(id) ?: throw MarketError("That listing is not in the marketplace.")
        val entry = store.add(listing, MarketRules.cleanInputs(listing, inputs), source)
        changes.value++
        return entry
    }

    /** Removing an owner's skill deletes it; removing a catalog listing only removes it from the phone. */
    fun remove(context: Context, id: String): Boolean {
        val removed = installs(context).remove(id)
        val deleted = id.startsWith("you.") && ownerSkills(context).remove(id)
        if (removed || deleted) changes.value++
        return removed || deleted
    }

    /**
     * Runs an added recipe as a Mind mission, through the same entry as a typed Ask: same GATE, approvals, Secrets Card
     * and run trace. Refuses (never queues, never joins a running mission) when the phone is not free.
     */
    fun run(context: Context, id: String, overrides: Map<String, String> = emptyMap()): RunRefusal? =
        run(installs(context), id, overrides)

    internal fun run(store: MarketInstalls, id: String, overrides: Map<String, String>): RunRefusal? {
        val listing = listing(id) ?: return RunRefusal("NOT_FOUND", "That listing is not in the marketplace.")
        val added = store.get(id) ?: return RunRefusal("NOT_ADDED", "Add ${listing.name} first.")
        if (!overlayReady()) return RunRefusal("OVERLAY_UNAVAILABLE", "Turn on Cyclone's accessibility service on the phone.")
        if (humanHasControl()) return RunRefusal("HUMAN_HAS_CONTROL", "You have control of the phone. Give it back to Cyclone first.")
        if (busy()) return RunRefusal("ASK_BUSY", "Cyclone is busy with another task. Try again when it is done.")
        val goal = try {
            MarketRules.fill(listing, added.inputs + MarketRules.cleanInputs(listing, overrides))
        } catch (error: MarketError) {
            return RunRefusal("INVALID_REQUEST", error.message ?: "Check the inputs.")
        }
        submit(goal)
        store.recordRun(id)
        changes.value++
        return null
    }

    /** The phone's own connections: the AI Cyclone thinks with and the PC link. States only, never secrets. */
    fun connections(context: Context): List<MarketConnection> {
        val keySet = runCatching { OpenRouterSecretStore.read(context).isNotBlank() }.getOrDefault(false)
        val model = runCatching { OpenRouterCatalogStore.activeId(context) }.getOrDefault("")
        val ai = MarketConnection("openrouter", "OpenRouter", "✦",
            if (keySet && model.isNotBlank()) ConnectionState.CONNECTED else ConnectionState.NEEDS_SETUP,
            when {
                !keySet -> "Add your OpenRouter API key to let Cyclone think."
                model.isBlank() -> "Choose a verified model."
                else -> "Thinking with $model"
            }, "phone")
        val gatewayOn = runCatching { com.cyclone.mobile.gateway.GatewaySessionStore.enabled(context) }.getOrDefault(false)
        val trustedPcs = runCatching { com.cyclone.mobile.gateway.GatewayV33TrustManager.status(context).optInt("trustedPcCount", 0) }.getOrDefault(0)
        val pc = MarketConnection("pc-gateway", "Cyclone One (PC)", "⌘",
            when { !gatewayOn -> ConnectionState.OFF; trustedPcs > 0 -> ConnectionState.CONNECTED; else -> ConnectionState.NEEDS_SETUP },
            when {
                !gatewayOn -> "Optional. Connect a PC for Glass, the Lab and PC agents over MCP."
                trustedPcs > 0 -> "Paired with $trustedPcs PC${if (trustedPcs == 1) "" else "s"}; PC agents reach Cyclone over MCP."
                else -> "Pair this phone in Cyclone One to use Glass and PC agents."
            }, "phone")
        return listOf(ai, pc)
    }
}
