package com.cyclone.mobile.mind.workspace

import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 37: the mission workspace. It keeps the bookkeeping of a run so the model does not have to dig for it: the
 * app stays (one per app the mission worked in, with a journal block when it ends), the collected facts, the plan and
 * its diversions, the definition of done, surprises from expectation checks, and where the mission is. It never
 * decides and never refuses (D8): the model reads it as help, and the screen stays the truth (D11).
 *
 * Pure and thread-confined to the mission's loop thread (the toolbox runs on the same thread), so it holds no locks.
 * Nothing here calls a model.
 */
class MissionWorkspace {
    class Stay(
        val index: Int,
        val packageName: String,
        val app: String,
        val startTurn: Int,
        var endTurn: Int? = null,
        /** Set when the mission came straight back to an app it had just left. */
        val returnOf: Int? = null,
        val did: MutableList<String> = mutableListOf(),
        val got: MutableList<String> = mutableListOf(),
        val surprises: MutableList<String> = mutableListOf(),
        var left: String? = null,
        var carry: String? = null,
        /** Turns up to here are already folded in the conversation. */
        var foldedThrough: Int = -1,
        var sectionShown: Boolean = false,
        var journaled: Boolean = false,
    ) {
        val closed: Boolean get() = endTurn != null

        fun toJson(): JSONObject = JSONObject().put("index", index).put("pkg", packageName).put("app", app).put("start", startTurn)
            .put("end", endTurn ?: JSONObject.NULL).put("returnOf", returnOf ?: JSONObject.NULL).put("did", JSONArray(did))
            .put("got", JSONArray(got)).put("surprises", JSONArray(surprises)).put("left", left ?: JSONObject.NULL)
            .put("carry", carry ?: JSONObject.NULL).put("folded", foldedThrough).put("section", sectionShown).put("journaled", journaled)

        companion object {
            fun fromJson(json: JSONObject): Stay = Stay(json.optInt("index"), json.optString("pkg"), json.optString("app"), json.optInt("start"),
                json.optInt("end").takeUnless { json.isNull("end") }, json.optInt("returnOf").takeUnless { json.isNull("returnOf") },
                strings(json.optJSONArray("did")), strings(json.optJSONArray("got")), strings(json.optJSONArray("surprises")),
                json.optString("left").takeUnless { json.isNull("left") }, json.optString("carry").takeUnless { json.isNull("carry") },
                json.optInt("folded", -1), json.optBoolean("section"), json.optBoolean("journaled"))
        }
    }

    data class Entry(val key: String, val value: String, val app: String, val turn: Int)
    data class Step(val text: String, val status: String, val app: String? = null, val why: String? = null)
    data class Diversion(val from: String, val to: String, val why: String, val turn: Int)
    data class Surprise(val id: Int, val turn: Int, val step: Int?, val text: String)

    private class Pending(val tool: String, val expect: String?, val step: Int?, val before: ScreenFacts?)

    private val stays = mutableListOf<Stay>()
    private var current: Stay? = null
    private var candidatePkg: String? = null
    private var candidateApp: String = ""
    private var candidateTurn = 0
    private var candidateCount = 0
    private var purposeful = false
    private var pendingCarry: String? = null
    private val ledger = LinkedHashMap<String, Entry>()
    private val diversions = mutableListOf<Diversion>()
    private val surprises = mutableListOf<Surprise>()
    private val sends = mutableListOf<DoneCheck.Sent>()
    private val turnStart = sortedMapOf<Int, Int>()
    private var pending: Pending? = null
    private var lastFacts: ScreenFacts? = null
    private var prevFacts: ScreenFacts? = null
    /** Lines of every screen seen in this mission, for the done checks. Memory only; never journaled. */
    private val seen = LinkedHashSet<String>()
    private var history: (() -> List<MindMessage>)? = null
    private var lastActionKey: String? = null
    private var repeats = 0

    var plan: List<Step> = emptyList()
        private set
    var done: List<DoneCheck.Item> = emptyList()
        private set
    var turn = 0
        private set
    private var workingMs = 0L
    private var budgetMs = 0L
    private var doneNoted = false

    // counters for the run record and the Lab
    private var held = 0
    private var missed = 0
    private var unchanged = 0
    private var folds = 0
    private var unverified: List<String> = emptyList()
    private var verified = 0

    val stayCount: Int get() = stays.size
    val currentStay: Stay? get() = current

    /** The loop gives the workspace read access to the conversation, for recall of folded turns. */
    fun attach(history: () -> List<MindMessage>) { this.history = history }

    /** Called by the loop at the start of each turn, before the model's reply is added at [messageIndex]. */
    fun beginTurn(turn: Int, messageIndex: Int, workingMs: Long, budgetMs: Long) {
        this.turn = turn
        this.workingMs = workingMs
        this.budgetMs = budgetMs
        turnStart[turn] = messageIndex
    }

    // ---- stays ----------------------------------------------------------------------------------------------------

    /** The mission is about to move on purpose (open_app, home, a link): the next app in front opens a stay at once. */
    fun movingOnPurpose(carry: String? = null) {
        purposeful = true
        carry?.trim()?.takeIf { it.isNotBlank() }?.let { pendingCarry = it.take(200) }
    }

    /**
     * Every observation passes here. Returns true when it opened a new stay. Surfaces (share sheets, pickers, the
     * keyboard) never open one; another app must stay in front for two observations unless the move was on purpose;
     * coming straight back within [RETURN_TURNS] turns reuses the app's place in the journal.
     */
    fun observe(facts: ScreenFacts): Boolean {
        facts.texts.forEach { if (seen.size < MAX_SEEN) seen += it.lowercase() }
        facts.title?.let { if (seen.size < MAX_SEEN) seen += it.lowercase() }
        if (lastFacts?.signature != facts.signature) {
            prevFacts = lastFacts
            lastFacts = facts
        }
        val pkg = facts.packageName
        if (Surfaces.transient(pkg)) return false
        val here = current
        val app = if (Surfaces.launcher(pkg)) "Home" else facts.app.ifBlank { pkg }
        if (here == null) {
            open(pkg, app, turn)
            current?.left = facts.sketch()
            return true
        }
        if (pkg == here.packageName) {
            candidatePkg = null
            candidateCount = 0
            here.left = facts.sketch()
            purposeful = false
            return false
        }
        // A tap inside an app that opens a browser tab stays nested in the app, unless the mission went there itself.
        if (!purposeful && Surfaces.browser(pkg) && !Surfaces.browser(here.packageName)) return false
        if (candidatePkg == pkg) candidateCount++ else {
            candidatePkg = pkg
            candidateApp = app
            candidateTurn = turn
            candidateCount = 1
        }
        if (!purposeful && candidateCount < 2) return false
        val startTurn = if (purposeful) turn else candidateTurn
        close(here, (startTurn - 1).coerceAtLeast(here.startTurn))
        open(pkg, app, startTurn)
        current?.left = facts.sketch()
        return true
    }

    private fun open(pkg: String, app: String, startTurn: Int) {
        val back = stays.lastOrNull { it.closed && it.packageName == pkg }?.takeIf { (turn - (it.endTurn ?: 0)) <= RETURN_TURNS }
        val stay = Stay(stays.size + 1, pkg, app, startTurn, returnOf = back?.index)
        stays += stay
        current = stay
        candidatePkg = null
        candidateCount = 0
        purposeful = false
    }

    private fun close(stay: Stay, endTurn: Int) {
        stay.endTurn = endTurn
        stay.carry = pendingCarry ?: stay.carry ?: currentStep()?.text
        pendingCarry = null
    }

    /** True once per stay when the app's section (map, manual, notes) should be shown: on arrival, also on every return. */
    fun takeSection(packageName: String): Boolean {
        val stay = current ?: return false
        if (stay.packageName != packageName || stay.sectionShown) return false
        stay.sectionShown = true
        return true
    }

    /** Where the mission left an app last time, for `open_app(resume=true)`. */
    fun leftOf(packageName: String): Pair<Int, String>? = stays.lastOrNull { it.closed && it.packageName == packageName && it.left != null }
        ?.let { it.index to it.left!! }

    // ---- actions and checks -----------------------------------------------------------------------------------------

    /** A screen-changing action is about to run with what the model expects of it. */
    fun beginAction(tool: String, expect: String?, step: Int?) {
        pending = Pending(tool, expect?.trim()?.takeIf { it.isNotBlank() }?.take(200), step, lastFacts)
    }

    /**
     * The lines added under a new screen: what changed and whether the expectation held. Called once per rendered
     * screen; an expectation is checked against the screen its own action produced.
     */
    fun screenLines(facts: ScreenFacts, apps: List<Pair<String, String>>): List<String> {
        val action = pending
        pending = null
        val before = action?.before ?: prevFacts.takeIf { it?.signature != facts.signature }
        val out = mutableListOf<String>()
        val delta = ScreenDelta.lines(before, facts)
        if (delta.isNotEmpty() && !(delta.size == 1 && action?.expect != null && delta[0].startsWith("nothing"))) {
            out += "Changed: " + delta.joinToString("; ")
        }
        val expect = action?.expect ?: return out
        val result = ExpectCheck.check(expect, action.before, facts, apps)
        when (result.verdict) {
            ExpectCheck.Verdict.HELD -> held++
            ExpectCheck.Verdict.MISSED -> { missed++; surprise(action.step, "${action.tool}: ${result.line.orEmpty().removePrefix("✗ ")}") }
            ExpectCheck.Verdict.UNCHANGED -> { unchanged++; surprise(action.step, "${action.tool}: nothing changed") }
            ExpectCheck.Verdict.UNKNOWN -> Unit
        }
        result.line?.let { out += "Check: $it" }
        return out
    }

    /** What an action did, for the stay's journal block, and the loop line when the same thing repeats on one screen. */
    fun acted(tool: String, target: String?, ok: Boolean) {
        val label = listOfNotNull(tool, target?.takeIf { it.isNotBlank() }?.let { "\"${it.take(40)}\"" }).joinToString(" ")
        if (ok) current?.did?.let { did -> did += label; while (did.size > MAX_DID) did.removeAt(0) }
        val key = label + "|" + (lastFacts?.signature ?: "")
        if (key == lastActionKey) repeats++ else { lastActionKey = key; repeats = 1 }
        if (repeats == REPEAT_AT) surprise(currentStepNumber(), "repeating $label on the same screen")
    }

    private fun surprise(step: Int?, text: String) {
        val item = Surprise(surprises.size + 1, turn, step, text.take(160))
        surprises += item
        current?.surprises?.add("t${item.turn} ${item.text}")
    }

    /** Two surprises on one step: advice, never a rule (plan 37 §4). */
    fun stepAdvice(): String? {
        val step = surprises.lastOrNull()?.step ?: return null
        val count = surprises.count { it.step == step }
        if (count != 2 || surprises.last().turn != turn) return null
        return "Harness note: step $step has surprised you twice. If this route keeps failing, consider another " +
            "(a different route, abilities_find, or ask the owner)."
    }

    // ---- ledger, plan, done -------------------------------------------------------------------------------------------

    /** A collected fact. The caller has already refused secrets. */
    fun collect(key: String?, value: String, app: String): Entry {
        val cleanKey = key?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: "note ${ledger.size + 1}"
        val entry = Entry(cleanKey, value.trim().take(300), app, turn)
        ledger.remove(cleanKey)
        ledger[cleanKey] = entry
        while (ledger.size > MAX_LEDGER) ledger.remove(ledger.keys.first())
        current?.got?.add("$cleanKey = ${entry.value.take(80)}")
        return entry
    }

    val collected: List<Entry> get() = ledger.values.toList()

    fun setPlan(steps: List<Step>) { plan = steps.take(20) }

    fun setDone(items: List<DoneCheck.Item>) { if (items.isNotEmpty()) done = items }

    fun divert(from: String, to: String, why: String): Diversion =
        Diversion(from.trim().take(120), to.trim().take(120), why.trim().take(200), turn).also { diversions += it }

    val diverted: List<Diversion> get() = diversions.toList()

    fun currentStepNumber(): Int? = plan.indexOfFirst { it.status == "doing" }.takeIf { it >= 0 }?.plus(1)
        ?: plan.indexOfFirst { it.status == "todo" }.takeIf { it >= 0 }?.plus(1)

    fun currentStep(): Step? = currentStepNumber()?.let { plan.getOrNull(it - 1) }

    /** One line for the owner: the step the mission is on, then what just happened. */
    fun narrate(done: String): String {
        val number = currentStepNumber() ?: return done
        val step = plan.getOrNull(number - 1) ?: return done
        return "Step $number of ${plan.size} · ${step.text.take(60)} — $done"
    }

    fun sent(app: String, chat: String) {
        sends += DoneCheck.Sent(app.take(60), chat.take(80))
    }

    /**
     * When a send is approved in a chat that is not the one the owner named, the approval card says so. Only when the
     * owner named a recipient; a choice the owner left open is never flagged.
     */
    fun approvalNote(chat: String?, app: String?): String? {
        val wanted = done.firstOrNull { it.kind == "sent_to" } ?: return null
        val shown = listOfNotNull(chat, app).joinToString(" ")
        if (shown.isBlank() || DoneCheck.names(chat.orEmpty().ifBlank { shown }, DoneCheck.recipient(wanted.value))) return null
        return "You asked for ${wanted.value}; this is ${chat?.takeIf { it.isNotBlank() }?.let { "\"${it.take(60)}\"" } ?: "another chat"}" +
            (app?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") + "."
    }

    /**
     * The finish nudge (plan 37 §5): the first finish with a checkable item that is nowhere in the mission gets one
     * note; any later finish is accepted and the item is recorded as unverified.
     */
    fun finishNote(summary: String): String? {
        if (done.isEmpty()) return null
        val results = DoneCheck.verify(done, DoneCheck.Evidence(seen.joinToString("\n"), ledger.values.map { "${it.key} ${it.value}" }, sends, summary))
        val missing = results.filter { it.second == false }.map { it.first.label }
        verified = results.count { it.second == true }
        unverified = missing
        if (missing.isEmpty() || doneNoted) return null
        doneNoted = true
        return "Before finishing: I could not find ${missing.joinToString("; ") { "\"$it\"" }} anywhere in this mission. " +
            "If it is done, call task_finish again with the evidence (it will be recorded as unverified); otherwise carry on."
    }

    // ---- recall -------------------------------------------------------------------------------------------------------

    /** The full text of every tool result of [number], also when it is folded in the prompt. */
    fun recallTurn(number: Int): String? {
        val messages = history?.invoke() ?: return null
        val from = turnStart[number] ?: return null
        val to = turnStart.tailMap(number + 1).values.firstOrNull() ?: messages.size
        val results = messages.subList(from.coerceAtMost(messages.size), to.coerceAtMost(messages.size))
            .filterIsInstance<MindMessage.Tool>().map { "RESULT of ${it.name}:\n${it.full}" }
        if (results.isEmpty()) return null
        return "Turn $number, as it was:\n" + results.joinToString("\n\n").take(MAX_RECALL)
    }

    /** A stay's journal block and its last full screen. */
    fun recallStay(index: Int): String? {
        val stay = stays.firstOrNull { it.index == index } ?: return null
        val last = (stay.endTurn ?: turn).let { end -> (end downTo stay.startTurn).firstNotNullOfOrNull { t -> recallTurn(t)?.takeIf { "Screen:" in it } } }
        return listOfNotNull(journalBlock(stay), last).joinToString("\n\n").take(MAX_RECALL)
    }

    // ---- folding and the journal --------------------------------------------------------------------------------------

    /**
     * Called by the loop after each turn. Closed stays are folded in the conversation (their tool results to one line,
     * screenshots and provider reasoning dropped; the model's own words stay) and get a journal block. A long stay is
     * folded in batches, keeping its last turns in full, so the cached prefix changes rarely. Returns how many tool
     * results were folded.
     */
    fun endTurn(conversation: MindConversation): Int {
        var folded = 0
        stays.filter { it.closed && !it.journaled }.forEach { stay ->
            val from = indexOfTurn(maxOf(stay.startTurn, stay.foldedThrough + 1))
            val to = indexOfTurn(stay.endTurn!! + 1) ?: conversation.size()
            if (from != null && from < to) folded += conversation.fold(from, to, KEEP_MODEL_TEXT)
            stay.foldedThrough = stay.endTurn!!
            stay.journaled = true
            conversation.add(MindMessage.User(journalBlock(stay), origin = MindMessage.User.Origin.HARNESS))
        }
        val here = current
        if (here != null && turn - KEEP_TURNS > here.foldedThrough) {
            val from = indexOfTurn(maxOf(here.startTurn, here.foldedThrough + 1))
            if (from != null && conversation.unfoldedToolChars(from) > STAY_FOLD_CHARS) {
                val through = turn - KEEP_TURNS
                val to = indexOfTurn(through + 1)
                if (to != null && from < to) {
                    folded += conversation.fold(from, to, KEEP_MODEL_TEXT)
                    here.foldedThrough = through
                }
            }
        }
        folds += folded
        return folded
    }

    private fun indexOfTurn(turn: Int): Int? = turnStart.tailMap(turn).values.firstOrNull()

    fun journalBlock(stay: Stay): String = buildString {
        append("JOURNAL · stay ${stay.index} · ${stay.app}")
        append(" (turns ${stay.startTurn}–${stay.endTurn ?: turn})")
        stay.returnOf?.let { append(" · back from stay $it") }
        appendLine()
        if (stay.did.isNotEmpty()) appendLine("Did: " + stay.did.takeLast(MAX_DID).joinToString(", "))
        if (stay.got.isNotEmpty()) appendLine("Got: " + stay.got.joinToString(" · ") + " (collected)")
        stay.left?.let { appendLine("Left: ${it.take(200)}. recall(stay=${stay.index}) shows it again.") }
        if (stay.surprises.isNotEmpty()) appendLine("Surprise: " + stay.surprises.takeLast(3).joinToString(" · "))
        stay.carry?.let { appendLine("Carry: ${it.take(160)}") }
    }.trimEnd()

    // ---- the live state -----------------------------------------------------------------------------------------------

    /**
     * The block sent after the conversation every turn (never stored in it). Null while the mission is light: a short
     * single-app mission looks like a classic run (plan 37 §2).
     */
    fun liveState(): String? {
        val light = plan.size < 2 && stays.size < 2 && turn < LIGHT_TURNS && ledger.isEmpty() && done.isEmpty() &&
            surprises.isEmpty() && diversions.isEmpty()
        if (light) return null
        val text = buildString {
            append("LIVE STATE · turn $turn")
            if (budgetMs > 0) append(" · ${workingMs / 60_000} of ${budgetMs / 60_000} min used")
            appendLine(" (Cyclone's bookkeeping; the screen is the truth when they disagree)")
            if (done.isNotEmpty()) appendLine("Done when: " + done.joinToString(" · ") { it.label })
            if (plan.isNotEmpty()) {
                val now = currentStepNumber()
                appendLine("Plan: " + plan.mapIndexed { i, step ->
                    val mark = when {
                        step.status == "done" -> "✓"
                        step.status == "skipped" -> "–"
                        i + 1 == now -> "→"
                        else -> "☐"
                    }
                    "$mark${i + 1} ${step.text.take(70)}" + (step.app?.let { " ($it)" } ?: "")
                }.joinToString("  "))
            }
            if (ledger.isNotEmpty()) appendLine("Collected: " + ledger.values.toList().takeLast(MAX_LEDGER_SHOWN)
                .joinToString(" · ") { "${it.key} = ${it.value.take(80)} (${it.app}, t${it.turn})" })
            if (surprises.isNotEmpty()) appendLine("Surprises: " + surprises.takeLast(4).joinToString(" · ") { "S${it.id} t${it.turn} ${it.text}" })
            diversions.lastOrNull()?.let { appendLine("Diverted: ${it.from} → ${it.to} (${it.why})") }
            current?.let { stay ->
                append("Where: ${stay.app}")
                lastFacts?.title?.takeIf { it.isNotBlank() && it != stay.app && lastFacts?.packageName == stay.packageName }
                    ?.let { append(" › \"${it.take(50)}\"") }
                stays.getOrNull(stays.size - 2)?.let { prev ->
                    append(" · came from ${prev.app} (stay ${prev.index}); back: open_app with resume=true, or recall(stay=${prev.index})")
                }
                appendLine()
            }
        }.trimEnd()
        return text.take(MAX_LIVE)
    }

    // ---- record and resume --------------------------------------------------------------------------------------------

    fun metrics(): JSONObject = JSONObject()
        .put("stays", stays.size)
        .put("apps", JSONArray(stays.map { it.app }.distinct()))
        .put("checks", JSONObject().put("held", held).put("missed", missed).put("unchanged", unchanged))
        .put("surprises", surprises.size)
        .put("diversions", diversions.size)
        .put("collected", ledger.size)
        .put("folded", folds)
        .put("done", JSONObject().put("items", done.size).put("verified", verified).put("unverified", unverified.size))

    /** The owner- and Glass-facing record: stays, plan, diversions and done checks (no screen content beyond titles). */
    fun record(): JSONObject = JSONObject()
        .put("stays", JSONArray(stays.map { JSONObject().put("index", it.index).put("app", it.app).put("start", it.startTurn)
            .put("end", it.endTurn ?: turn).put("did", it.did.size).put("surprises", it.surprises.size) }))
        .put("done", JSONArray(done.map { item -> item.toJson().put("verified", item.label !in unverified) }))
        .put("diversions", JSONArray(diversions.map { JSONObject().put("from", it.from).put("to", it.to).put("why", it.why).put("turn", it.turn) }))
        .put("metrics", metrics())

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("stays", JSONArray(stays.map { it.toJson() }))
        .put("current", current?.index ?: JSONObject.NULL)
        .put("ledger", JSONArray(ledger.values.map { JSONObject().put("key", it.key).put("value", it.value).put("app", it.app).put("turn", it.turn) }))
        .put("plan", JSONArray(plan.map { JSONObject().put("text", it.text).put("status", it.status).put("app", it.app ?: JSONObject.NULL).put("why", it.why ?: JSONObject.NULL) }))
        .put("done", JSONArray(done.map { it.toJson() }))
        .put("diversions", JSONArray(diversions.map { JSONObject().put("from", it.from).put("to", it.to).put("why", it.why).put("turn", it.turn) }))
        .put("surprises", JSONArray(surprises.map { JSONObject().put("id", it.id).put("turn", it.turn).put("step", it.step ?: JSONObject.NULL).put("text", it.text) }))
        .put("sends", JSONArray(sends.map { JSONObject().put("app", it.app).put("chat", it.chat) }))
        .put("turnStart", JSONObject(turnStart.mapKeys { it.key.toString() } as Map<*, *>))
        .put("counters", JSONObject().put("held", held).put("missed", missed).put("unchanged", unchanged).put("folds", folds).put("doneNoted", doneNoted))
        .put("last", lastFacts?.toJson() ?: JSONObject.NULL)

    companion object {
        const val SCHEMA = "cyclone-mission-workspace-v1"
        const val RETURN_TURNS = 3
        const val KEEP_TURNS = 3
        const val STAY_FOLD_CHARS = 32_000
        const val KEEP_MODEL_TEXT = 300
        const val LIGHT_TURNS = 6
        const val MAX_LEDGER = 30
        const val MAX_LEDGER_SHOWN = 20
        const val MAX_DID = 6
        const val MAX_SEEN = 4_000
        const val MAX_LIVE = 6_000
        const val MAX_RECALL = 12_000
        const val REPEAT_AT = 3

        fun fromJson(json: JSONObject?): MissionWorkspace? {
            if (json == null || json.optString("schema") != SCHEMA) return null
            return runCatching {
                MissionWorkspace().apply {
                    json.optJSONArray("stays")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let { stays += Stay.fromJson(it) } } }
                    current = json.optInt("current").takeUnless { json.isNull("current") }?.let { index -> stays.firstOrNull { it.index == index } }
                    json.optJSONArray("ledger")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let {
                        ledger[it.optString("key")] = Entry(it.optString("key"), it.optString("value"), it.optString("app"), it.optInt("turn")) } } }
                    plan = json.optJSONArray("plan")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
                        Step(it.optString("text"), it.optString("status"), it.optString("app").takeUnless { _ -> it.isNull("app") },
                            it.optString("why").takeUnless { _ -> it.isNull("why") }) } } }.orEmpty()
                    done = DoneCheck.fromJson(json.optJSONArray("done"))
                    json.optJSONArray("diversions")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let {
                        diversions += Diversion(it.optString("from"), it.optString("to"), it.optString("why"), it.optInt("turn")) } } }
                    json.optJSONArray("surprises")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let {
                        surprises += Surprise(it.optInt("id"), it.optInt("turn"), it.optInt("step").takeUnless { _ -> it.isNull("step") }, it.optString("text")) } } }
                    json.optJSONArray("sends")?.let { a -> (0 until a.length()).forEach { i -> a.optJSONObject(i)?.let {
                        sends += DoneCheck.Sent(it.optString("app"), it.optString("chat")) } } }
                    json.optJSONObject("turnStart")?.let { t -> t.keys().forEach { k -> k.toIntOrNull()?.let { turnStart[it] = t.optInt(k) } } }
                    json.optJSONObject("counters")?.let { c ->
                        held = c.optInt("held"); missed = c.optInt("missed"); unchanged = c.optInt("unchanged"); folds = c.optInt("folds")
                        doneNoted = c.optBoolean("doneNoted")
                    }
                    lastFacts = ScreenFacts.fromJson(json.optJSONObject("last"))
                    turn = if (turnStart.isEmpty()) 0 else turnStart.lastKey()
                }
            }.getOrNull()
        }

        private fun strings(array: JSONArray?): MutableList<String> =
            array?.let { a -> (0 until a.length()).map(a::optString).toMutableList() } ?: mutableListOf()
    }
}
