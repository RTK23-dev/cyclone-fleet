package com.cyclone.mobile.manual

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictionaryJson
import com.cyclone.mobile.manual.dictionary.ManualScreens
import com.cyclone.mobile.manual.dictionary.Organizer
import com.cyclone.mobile.manual.dictionary.OrganizerDecision
import com.cyclone.mobile.manual.dictionary.OrganizerJudge
import com.cyclone.mobile.manual.dictionary.OrganizerPrompt
import com.cyclone.mobile.manual.dictionary.OrganizerQuestion
import com.cyclone.mobile.manual.dictionary.PassInfo
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.OpenRouterMindModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The phone side of the app dictionary (plan 36 §7). A mapping pass feeds every screen it reads through the
 * [StructureReader] (only the app's own words survive) into the organizer's record; when the pass ends the organizer
 * runs its gates and asks its one question with the model chosen for the pass. JEV watches that question and never
 * decides. Dictionaries are stored per app on the phone, with structure only.
 */
object ManualRuntime {
    /** The model the owner picked for a pass: the phone's current model, or one from its list. */
    const val PHONE_MODEL = "phone"

    private class Pass(val packageName: String, val versionName: String?, val modelChoice: String, val rooms: MutableSet<String> = ConcurrentHashMap.newKeySet()) {
        val memory = PassMemory()
    }

    private val passes = ConcurrentHashMap<String, Pass>()
    private val choices = ConcurrentHashMap<String, String>()
    private val cache = ConcurrentHashMap<String, AppDictionary>()
    private val lexicons = ConcurrentHashMap<String, Pair<Long, AppLexicon>>()
    /** Per app, in memory only: downloaded names waiting for the owner's "app word or yours?". */
    private val reviews = ConcurrentHashMap<String, ReviewQueue>()
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cyclone-manual").apply { isDaemon = true } }

    /** Seam for tests and the Lab: who answers the organizer's question. */
    internal var judgeFactory: (Context, String, String) -> OrganizerJudge? = { context, appLabel, model -> modelJudge(context, appLabel, model) }

    // ---- pass lifecycle ----

    /**
     * mapping.start: remembers which model decides for this pass ("phone" = the phone's current model) and, for "Map
     * deeper", the words of the goals the manual could not answer: doors with those words are tried first.
     */
    fun configure(jobId: String, model: String?, focus: List<String> = emptyList()) {
        choices[jobId] = model?.takeIf { it.isNotBlank() } ?: PHONE_MODEL
        val words = AbilityIndex.terms(focus).filter { it.length > 2 }.distinct().take(24)
        if (words.isEmpty()) focusWords.remove(jobId) else focusWords[jobId] = words.toSet()
    }

    private val focusWords = ConcurrentHashMap<String, Set<String>>()

    /** The pass's Map deeper words, when it has any. */
    fun focus(jobId: String): Set<String> = focusWords[jobId].orEmpty()

    /**
     * Map deeper: the doors of [observation] whose words (the app's own) share a word with the pass's focus. They still
     * pass every safety check; they are only tried first.
     */
    internal fun focusDoors(
        jobId: String,
        captured: com.cyclone.mobile.gateway.GatewayObservation,
        observation: com.cyclone.mobile.mapping.crawl.MappingObservation,
    ): Set<String> {
        val focus = focusWords[jobId] ?: return emptySet()
        return observation.doors.filter { door ->
            val element = captured.elements[door.elementId] ?: return@filter false
            val words = AbilityIndex.terms(listOfNotNull(element.label, element.evidence.optString("contentDescription")))
            words.any { it in focus }
        }.map { it.key }.toSet()
    }

    /** Resolves "phone" to the phone's current model id, when the pass starts. */
    fun resolveModel(context: Context, choice: String): String =
        if (choice == PHONE_MODEL) OpenRouterCatalogStore.activeId(context) else OpenRouterCatalogStore.canonicalId(choice)

    /** One screen of a mapping pass. Never throws into the mapper. */
    internal fun observe(
        context: Context,
        jobId: String,
        placeId: String,
        roomKey: String,
        captured: com.cyclone.mobile.gateway.GatewayObservation,
        observation: com.cyclone.mobile.mapping.crawl.MappingObservation? = null,
    ) {
        runCatching {
            val packageName = placeId.removePrefix("package:").takeIf { placeId.startsWith("package:") && PACKAGE.matches(it) } ?: return
            if (captured.page.packageName != packageName) return
            val app = context.applicationContext
            val pass = passes.getOrPut(jobId) {
                val version = runCatching { app.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                Pass(packageName, version, resolveModel(app, choices[jobId] ?: PHONE_MODEL))
            }
            pass.rooms += roomKey
            val lexicon = lexicon(app, packageName)
            val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
            // Downloaded names are read so a probe can prove them, but only app strings reach the dictionary.
            val found = StructureReader(lexicon, label, allowVocabulary = true).read(roomKey, nodes(captured))
            val split = pass.memory.split(found)
            observation?.doors?.forEach { door ->
                val element = captured.elements[door.elementId]
                val word = listOfNotNull(element?.label, element?.evidence?.optString("contentDescription"))
                    .firstNotNullOfOrNull { lexicon.chrome(it)?.takeIf { w -> w.proof == ChromeProof.LEXICON } }
                pass.memory.rememberDoor(door.key, word?.text, door.kind.name.lowercase())
            }
            val now = System.currentTimeMillis()
            synchronized(lock) {
                var dict = load(app, packageName)
                if (split.appStrings.isNotEmpty()) dict = Organizer.record(dict, split.appStrings, PassInfo(now, pass.versionName))
                dict = ManualScreens.observed(dict, roomKey, found, now, captured.page.pageKey)
                save(app, dict)
                if (split.downloaded.isNotEmpty()) reviews.getOrPut(packageName) { ReviewQueue() }.offer(dict, split.downloaded, now)
            }
        }
    }

    /** The walker verified a door: the destination is named from the door's words, and a reveal makes it a panel. */
    internal fun verified(context: Context, jobId: String, structure: com.cyclone.mobile.mapping.crawl.VerifiedStructure) {
        runCatching {
            val pass = passes[jobId] ?: return
            if (structure.fromNodeKey == structure.toNodeKey) return
            val (label, kind) = pass.memory.door(structure.doorKey) ?: (null to structure.doorKind.name.lowercase())
            val edgeId = com.cyclone.mobile.brain.graphv2.AtlasGraphIds.wireEdgeId(com.cyclone.mobile.brain.graphv2.GraphEdgeKey(
                com.cyclone.mobile.brain.graphv2.GraphNodeId(structure.fromNodeKey),
                com.cyclone.mobile.brain.graphv2.GraphEdgeType.NAVIGATES_TO,
                com.cyclone.mobile.brain.graphv2.GraphNodeId(structure.toNodeKey),
            ))
            val app = context.applicationContext
            synchronized(lock) {
                save(app, ManualScreens.verified(load(app, pass.packageName), structure.fromNodeKey, structure.toNodeKey, edgeId, label, kind, System.currentTimeMillis()))
            }
        }
    }

    /** Downloaded names waiting for the owner, in memory only. */
    fun reviewItems(packageName: String): List<ReviewQueue.Item> = reviews[packageName]?.list().orEmpty()

    /** The owner's answer: "app word" admits the name as a confirmed set; "mine" keeps only its hash. */
    fun answerReview(context: Context, packageName: String, id: String, appWord: Boolean): AppDictionary = synchronized(lock) {
        val app = context.applicationContext
        val item = reviews[packageName]?.take(id) ?: throw Organizer.EditRefused("That question is gone (the phone restarted or it was answered).")
        val now = System.currentTimeMillis()
        val next = if (appWord) Organizer.ownerAdmit(load(app, packageName), item.proposal, now) else Organizer.decline(load(app, packageName), item.hash, now)
        save(app, next)
        next
    }

    /** The pass ended: the organizer runs in the background (gates, then at most one model question). */
    fun finish(context: Context, jobId: String) {
        val pass = passes.remove(jobId) ?: return
        choices.remove(jobId)
        focusWords.remove(jobId)
        val app = context.applicationContext
        worker.execute {
            val label = appLabel(app, pass.packageName)
            runCatching {
                val judge = judgeFactory(app, label, pass.modelChoice)
                val watched = if (judge == null) null else WatchedJudge(judge) { questions, decisions -> watchJev(app, pass.packageName, label, questions, decisions) }
                synchronized(lock) {
                    val result = Organizer.run(load(app, pass.packageName), watched, PassInfo(System.currentTimeMillis(), pass.versionName, pass.rooms.toSet()))
                    save(app, result.dictionary)
                }
            }
            // Then the describer (alpha.64): purposes, phrasings and the self-quiz, with the same model. Never blocks a pass.
            runCatching { describe(app, pass.packageName, label, pass.modelChoice) }
        }
    }

    // ---- the describer, abilities and walks (alpha.64) ----

    /** Seam for tests and the Lab: who writes the describer's answer ((system, user) → reply text). */
    internal var describerFactory: (Context, String) -> ((String, String) -> String)? = { context, model -> modelText(context, model, "manual-describer") }

    private fun describe(context: Context, packageName: String, appLabel: String, model: String) {
        val ask = describerFactory(context, model) ?: return
        val question = synchronized(lock) {
            val dict = load(context, packageName)
            ManualDescriber.question(dict, appLabel, Abilities.derive(dict))
        } ?: return
        val answer = ManualDescriber.parse(ask(question.system, question.user), question) ?: return
        // The dictionary may have moved on while the model answered: the answer goes onto the current one, by handle.
        synchronized(lock) { save(context, ManualDescriber.apply(load(context, packageName), answer, System.currentTimeMillis())) }
    }

    fun view(context: Context, packageName: String): ManualView? = runCatching {
        val dict = dictionary(context, packageName)
        if (dict.screens.isEmpty() && dict.entries.isEmpty()) return null
        ManualView(dict, appLabel(context, packageName), Abilities.derive(dict))
    }.getOrNull()

    /** The manual as the Mind reaches it. */
    fun mindPort(context: Context): MindManualPort {
        val app = context.applicationContext
        return object : MindManualPort {
            override fun view(packageName: String): ManualView? = this@ManualRuntime.view(app, packageName)
            override fun walked(packageName: String, abilityId: String, ok: Boolean) = recordWalk(app, packageName, abilityId, ok)
        }
    }

    /** A walk of an ability ended: runs teach the manual (plan 36 §5.6). Counts and a time only. */
    fun recordWalk(context: Context, packageName: String, abilityId: String, ok: Boolean) {
        runCatching {
            if (!com.cyclone.mobile.manual.dictionary.DictionaryJson.ABILITY.matches(abilityId)) return
            synchronized(lock) {
                val app = context.applicationContext
                val dict = load(app, packageName)
                val old = dict.abilityStats[abilityId] ?: com.cyclone.mobile.manual.dictionary.AbilityStat()
                val next = old.copy(walked = old.walked + if (ok) 1 else 0, failed = if (ok) 0 else old.failed + 1, lastAt = System.currentTimeMillis())
                save(app, dict.copy(abilityStats = dict.abilityStats + (abilityId to next)))
            }
        }
    }

    /** The manual as Markdown (plan 36 §3.1): the glossary first, then screens, abilities and the self-quiz. */
    fun markdown(context: Context, packageName: String): String? = view(context, packageName)?.let { v ->
        ManualRenderer.markdown(v.dictionary, v.appLabel, currentVersion(context, packageName), v.abilities)
    }

    // ---- reading and owner edits ----

    fun dictionary(context: Context, packageName: String): AppDictionary = synchronized(lock) { load(context.applicationContext, packageName) }

    fun edit(context: Context, packageName: String, edit: Organizer.OwnerEdit): AppDictionary = synchronized(lock) {
        val app = context.applicationContext
        val next = Organizer.edit(load(app, packageName), edit, System.currentTimeMillis())
        save(app, next)
        next
    }

    fun currentVersion(context: Context, packageName: String): String? =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

    fun appLabel(context: Context, packageName: String): String =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrNull() ?: packageName

    /** The glossary block for an agent in this app, or null when the app has no confirmed sets yet. */
    fun glossary(context: Context, packageName: String): String? = runCatching {
        Organizer.glossary(dictionary(context, packageName), appLabel(context, packageName)).takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---- storage ----

    private fun file(context: Context, packageName: String): File = File(File(context.filesDir, "manual/dictionaries"), "$packageName.json")

    private fun load(context: Context, packageName: String): AppDictionary = cache.getOrPut(packageName) {
        val file = file(context, packageName)
        runCatching { if (file.isFile) DictionaryJson.read(JSONObject(file.readText())).copy(packageName = packageName) else null }.getOrNull()
            ?: AppDictionary(packageName)
    }

    private fun save(context: Context, dictionary: AppDictionary) {
        cache[dictionary.packageName] = dictionary
        val file = file(context, dictionary.packageName)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(DictionaryJson.write(dictionary).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun lexicon(context: Context, packageName: String): AppLexicon {
        val code = runCatching { context.packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(-1L)
        lexicons[packageName]?.takeIf { it.first == code }?.let { return it.second }
        val built = AndroidLexicon.build(context, packageName)
        if (lexicons.size > 8) lexicons.clear()
        lexicons[packageName] = code to built
        return built
    }

    /** Raw accessibility nodes of a capture, as the reader needs them. */
    internal fun nodes(captured: com.cyclone.mobile.gateway.GatewayObservation): List<UiNode> =
        captured.elements.values.filter { it.source == "raw_accessibility" }.mapNotNull { element ->
            val e = element.evidence
            val bounds = e.optJSONObject("bounds") ?: return@mapNotNull null
            UiNode(
                id = e.optString("id").ifBlank { return@mapNotNull null },
                parentId = e.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
                text = e.optString("text"),
                description = e.optString("contentDescription"),
                resourceId = e.optString("resourceId"),
                className = e.optString("class"),
                role = e.optString("role"),
                left = bounds.optInt("left"), top = bounds.optInt("top"), right = bounds.optInt("right"), bottom = bounds.optInt("bottom"),
                clickable = e.optBoolean("clickable"),
                selected = e.optBoolean("selected"),
                scrollable = e.optBoolean("scrollable"),
                editable = e.optBoolean("editable"),
                password = e.optBoolean("password"),
                visible = e.optBoolean("visibleToUser", true),
            )
        }

    // ---- the judge and JEV ----

    /** The pass's model answering the organizer's one question. Null without a key or model: candidates then wait. */
    private fun modelJudge(context: Context, appLabel: String, modelId: String): OrganizerJudge? {
        val key = OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        val id = modelId.takeIf { it.isNotBlank() } ?: return null
        val preset = OpenRouterCatalogStore.preset(context, id)
        return object : OrganizerJudge {
            override val label: String = preset.label
            override fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision> {
                val model = OpenRouterMindModel(key, id, preset.label, preset.vision, null, "manual-organizer", ProviderCancellation(), { false })
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", OrganizerPrompt.SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", OrganizerPrompt.user(appLabel, questions)))
                val reply = model.complete(MindModelRequest(messages, emptyList(), nativeTools = false, budgetMs = 60_000))
                return OrganizerPrompt.parse(reply.text, questions)
            }
        }
    }

    /** The pass's model as plain text in and out, for the describer. Null without a key or a model. */
    private fun modelText(context: Context, modelId: String, purpose: String): ((String, String) -> String)? {
        val key = OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        val id = resolveModel(context, modelId).takeIf { it.isNotBlank() } ?: return null
        val preset = OpenRouterCatalogStore.preset(context, id)
        return { system, user ->
            val model = OpenRouterMindModel(key, id, preset.label, preset.vision, null, purpose, ProviderCancellation(), { false })
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            model.complete(MindModelRequest(messages, emptyList(), nativeTools = false, budgetMs = 90_000)).text
        }
    }

    /** Passes the model's answers through and lets JEV answer the same questions next to it, for the tally only. */
    private class WatchedJudge(private val inner: OrganizerJudge, private val watch: (List<OrganizerQuestion>, List<OrganizerDecision>) -> Unit) : OrganizerJudge {
        override val label: String get() = inner.label
        override fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision> {
            val decisions = inner.decide(questions)
            runCatching { watch(questions, decisions) }
            return decisions
        }
    }

    private fun jevWatching(context: Context): Boolean =
        context.getSharedPreferences("cyclone_drive", Context.MODE_PRIVATE).getBoolean("jev_watch", true)

    /** JEV answers each question as a typed choice; only agreement and time are kept. Its answer is never applied. */
    private fun watchJev(context: Context, packageName: String, appLabel: String, questions: List<OrganizerQuestion>, decisions: List<OrganizerDecision>) {
        if (!jevWatching(context)) return
        val key = OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return
        val voice = com.cyclone.mobile.voice.OpenRouterVoice(key)
        for (question in questions) {
            val started = System.currentTimeMillis()
            val outcome = runCatching { OrganizerPrompt.jevParse(voice.decide(OrganizerPrompt.jevRequest(appLabel, question)), question) }
            val ms = System.currentTimeMillis() - started
            synchronized(lock) {
                val dict = load(context, packageName)
                val next = outcome.fold(
                    onSuccess = { pick ->
                        if (pick == null) dict.copy(jev = dict.jev.copy(lastError = "no usable answer"))
                        else {
                            val model = OrganizerPrompt.asJevChoice(decisions.firstOrNull { it.key == question.key })
                            dict.copy(jev = dict.jev.add(pick.first == model, pick.second, ms))
                        }
                    },
                    onFailure = { error -> dict.copy(jev = dict.jev.copy(lastError = (error.message ?: error.javaClass.simpleName).take(120))) },
                )
                save(context, next)
            }
        }
    }

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}
