package com.cyclone.mobile.runtime.workspaces

/** Time-sliced display-0 profiles, separate from owned Shizuku display sessions. */
enum class WorkspaceState { idle, running, paused, gated }
data class Workspace(val id: String, val label: String, val appPackage: String,
    val androidUserId: Int = 0, val displayId: Int = 0, val state: WorkspaceState = WorkspaceState.idle) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid workspace id" }
        require(label.isNotBlank() && label.length <= 80) { "Label required (80 characters maximum)" }
        require(appPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid app package" }
        require(androidUserId >= 0 && displayId == 0) { "Layer 2 supports display 0 only" }
    }
}
data class WorkspaceLease(val workspaceId: String, val generation: Long)
data class WorkspaceTarget(val appPackage: String, val androidUserId: Int, val displayId: Int = 0)

/** All callers, including PhoneToolExecutor, use this SAME monitor for the entire mutation. */
class WorkspaceEngine(private val persist: (List<Workspace>) -> Unit = {}) {
    private val mutableRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = mutableRevision
    private fun changed() { mutableRevision.value += 1 }
    val mutationLock = Any()
    private val registry = linkedMapOf<String, Workspace>()
    private val armed = ArrayDeque<String>()
    private var selected: String? = null
    private var generation = 0L
    private var lease: WorkspaceLease? = null
    fun snapshot(): List<Workspace> = synchronized(mutationLock) { registry.values.toList() }
    fun holder(): WorkspaceLease? = synchronized(mutationLock) { lease }
    fun selectedId(): String? = synchronized(mutationLock) { selected }
    fun queue(): List<String> = synchronized(mutationLock) { armed.toList() }
    fun restore(items: List<Workspace>) = synchronized(mutationLock) {
        check(registry.isEmpty())
        require(items.map { it.id }.distinct().size == items.size)
        items.forEach { registry[it.id] = it.copy(state = WorkspaceState.paused) }
        // No lease, armed work, or execution identity survives a process restart.
    }
    fun register(workspace: Workspace) = synchronized(mutationLock) {
        check(workspace.id != selected) { "Pause and clear selection before editing this workspace" }
        val updated = registry.toMutableMap().apply { put(workspace.id, workspace.copy(state = WorkspaceState.idle)) }
        persist(updated.values.toList())
        registry.clear(); registry.putAll(updated); changed()
    }
    /** Plan 40 P1: forgets every workspace of a profile Android removed for good. */
    fun forgetUser(androidUserId: Int) = synchronized(mutationLock) {
        val kept = registry.values.filter { it.androidUserId != androidUserId }
        if (kept.size == registry.size) return@synchronized
        if (selected?.let { registry[it]?.androidUserId } == androidUserId) { revoke(); selected = null }
        armed.removeAll { id -> registry[id]?.androidUserId == androidUserId }
        persist(kept)
        registry.clear(); kept.forEach { registry[it.id] = it }; changed()
    }
    private fun state(id: String, value: WorkspaceState) { registry[id]?.let { registry[id] = it.copy(state = value) } }
    private fun revoke() { lease = null; generation++; changed() }
    fun pause() = synchronized(mutationLock) { revoke(); selected?.let { state(it, WorkspaceState.paused) } }
    fun clearSelection() = synchronized(mutationLock) { pause(); selected = null; armed.clear() }
    /**
     * Ask Cyclone / default-foreground owns the human display. A leftover Layer-2
     * selection must not convert phone.open_app into a capability failure.
     * Armed jobs stay queued; only the live lease is released.
     */
    fun releaseForForegroundTask() = synchronized(mutationLock) {
        if (selected == null) return@synchronized
        pause()
        selected = null
    }
    /** Close exactly one task and invalidate its lease without dropping other armed jobs. */
    fun closeTask(id: String, expectedGeneration: Long) = synchronized(mutationLock) {
        if (selected == id) {
            check(lease == null || lease?.generation == expectedGeneration) { "STALE_WORKSPACE: task identity changed" }
            revoke()
            selected = null
            state(id, WorkspaceState.idle)
        }
        armed.remove(id)
        changed()
    }
    fun arm(id: String) = synchronized(mutationLock) {
        check(registry.containsKey(id)) { "Unknown workspace" }
        if (id !in armed) { armed.addLast(id); changed() }
    }
    fun disarm(id: String) = synchronized(mutationLock) { armed.remove(id); changed(); if (selected == id) pause() }
    fun switch(id: String, gated: () -> Boolean, launch: (Workspace) -> Unit,
        observe: (Workspace) -> WorkspaceTarget): WorkspaceLease = synchronized(mutationLock) {
        revoke()
        selected?.let { state(it, WorkspaceState.paused) }
        val target = registry[id] ?: error("Unknown workspace; mutation lease released")
        selected = id
        try {
            check(!gated()) { "GATE: resolve the pending human review before switching" }
            launch(target)
            check(!gated()) { "GATE: human review became pending during launch" }
            val seen = observe(target)
            check(seen == WorkspaceTarget(target.appPackage, target.androidUserId, target.displayId)) {
                "TARGET_MISMATCH: package/user/display could not be verified; input remains paused"
            }
            check(!gated()) { "GATE: human review became pending during observation" }
            WorkspaceLease(id, generation).also { lease = it; state(id, WorkspaceState.running) }
        } catch (error: Exception) {
            revoke(); state(id, if (gated()) WorkspaceState.gated else WorkspaceState.paused)
            throw error
        }
    }
    fun requireMutation(id: String?, expectedGeneration: Long, gated: () -> Boolean, observe: (Workspace) -> WorkspaceTarget) = synchronized(mutationLock) {
        check(!gated()) { selected?.let { state(it, WorkspaceState.gated) }; revoke(); "GATE: human review required" }
        if (selected == null) { check(id == null) { "Workspace has no mutation lease" }; return@synchronized }
        val current = lease
        check(current != null && current.workspaceId == id && current.generation == expectedGeneration) { "MUTATE_LOCK: switch and use the current workspaceId/workspaceGeneration" }
        val target = registry.getValue(current.workspaceId)
        try {
            check(observe(target) == WorkspaceTarget(target.appPackage, target.androidUserId, target.displayId)) { "TARGET_MISMATCH: input paused" }
        } catch (error: Exception) { pause(); throw error }
    }
    /** One bounded job slice. Failure disarms that job; GATE stops the whole queue. */
    fun next(gated: () -> Boolean, launch: (Workspace) -> Unit, observe: (Workspace) -> WorkspaceTarget,
        slice: (WorkspaceLease) -> Unit): WorkspaceLease? = synchronized(mutationLock) {
        check(!gated()) { pause(); "GATE: queue paused" }
        val id = armed.removeFirstOrNull() ?: return@synchronized null
        try {
            val acquired = switch(id, gated, launch, observe)
            slice(acquired)
            check(!gated()) { state(id, WorkspaceState.gated); "GATE: queue paused" }
            armed.addLast(id)
            acquired
        } finally { revoke(); if (registry[id]?.state == WorkspaceState.running) state(id, WorkspaceState.paused) }
    }
}

enum class RootStatus(val label: String) { ROOTED("Rooted"), NOT_ROOTED("Not rooted"), UNKNOWN("Unknown") }
object RootStatusMapping {
    fun from(exitCode: Int?, output: String, unavailable: Boolean = false): RootStatus = when {
        unavailable -> RootStatus.NOT_ROOTED
        exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(output) -> RootStatus.ROOTED
        else -> RootStatus.UNKNOWN // Permission refusal/timeouts do not prove that a phone is unrooted.
    }
}

/** Parse only a positively identified display-0 activity. OEM ambiguity fails closed. */
object RootTargetParser {
    fun parse(output: String): WorkspaceTarget? {
        var display: Int? = null
        val targets = mutableListOf<WorkspaceTarget>()
        output.lineSequence().forEach { line ->
            Regex("Display #([0-9]+)").find(line)?.let { display = it.groupValues[1].toInt() }
            if (line.contains("mResumedActivity:")) {
                val match = Regex("\\bu([0-9]+) ([A-Za-z0-9_.]+)/").find(line)
                if (display == null) return null
                if (match != null && display == 0) targets += WorkspaceTarget(match.groupValues[2], match.groupValues[1].toInt(), 0)
            }
        }
        return targets.singleOrNull()
    }
}
