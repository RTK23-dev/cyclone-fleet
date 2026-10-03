package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.agent.plan.DestinationAuthority
import com.cyclone.mobile.agent.plan.TaskDestination
import com.cyclone.mobile.agent.plan.TaskDifficulty
import com.cyclone.mobile.agent.plan.TaskTrajectory
import com.cyclone.mobile.agent.plan.WaypointKind
import com.cyclone.mobile.fastpath.FastPathLanding

enum class OutcomeStageStatus {
    QUEUED,
    BLOCKED,
    ACTIVE,
    NEEDS_INPUT,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED,
}

/**
 * One user-facing destination outcome. Waypoints stay diagnostic; this is the card the person sees.
 * Completion is a presentation of harness/trajectory evidence, never a second executor.
 */
data class OutcomeStage(
    val id: String,
    val order: Int,
    val destinationPackage: String?,
    val destinationLabel: String,
    val websiteOrigin: String?,
    val objective: String,
    val dependsOn: List<String> = emptyList(),
    val status: OutcomeStageStatus,
    val currentAction: String? = null,
    val verificationSummary: String? = null,
    val resultOrError: String? = null,
    val waypointStart: Int = 0,
    val waypointEndExclusive: Int = 0,
) {
    val expandedLine: String
        get() {
            val detail = when (status) {
                OutcomeStageStatus.COMPLETED -> resultOrError ?: OutcomeStageCopy.completedPhrase(objective)
                OutcomeStageStatus.NEEDS_INPUT -> currentAction ?: "Needs your input"
                OutcomeStageStatus.FAILED -> resultOrError ?: "Couldn't finish"
                OutcomeStageStatus.BLOCKED -> "Waiting for the previous stage"
                OutcomeStageStatus.QUEUED -> "Up next"
                OutcomeStageStatus.SKIPPED -> "Skipped"
                OutcomeStageStatus.CANCELLED -> "Cancelled"
                OutcomeStageStatus.ACTIVE -> currentAction ?: objective
            }
            return "$destinationLabel — $detail"
        }

    val collapsedLine: String
        get() = when (status) {
            OutcomeStageStatus.COMPLETED -> "$destinationLabel complete"
            OutcomeStageStatus.NEEDS_INPUT -> currentAction ?: "$destinationLabel needs your input"
            OutcomeStageStatus.FAILED -> resultOrError ?: "$destinationLabel couldn't finish"
            OutcomeStageStatus.ACTIVE -> currentAction ?: objective
            else -> destinationLabel
        }

    fun milestoneLabel(): String = when (status) {
        OutcomeStageStatus.COMPLETED -> "$destinationLabel — ${resultOrError ?: OutcomeStageCopy.completedPhrase(objective)}"
        OutcomeStageStatus.NEEDS_INPUT, OutcomeStageStatus.ACTIVE -> "$destinationLabel — ${currentAction ?: objective}"
        else -> "$destinationLabel — $objective"
    }
}

data class OutcomeStageProjection(
    val stages: List<OutcomeStage>,
    val activeIndex: Int,
    val destinationChain: String,
    val title: String,
)

data class TaskRunInformation(
    val elapsedMs: Long? = null,
    val waiting: Boolean = false,
    val modelRequests: Int? = null,
    val toolActions: Int? = null,
    val verifiedActions: Int? = null,
    val tokensInput: Long? = null,
    val tokensOutput: Long? = null,
    val tokensTotal: Long? = null,
    val tokensReported: Boolean = false,
    val modelName: String? = null,
    val verificationSummary: String? = null,
    val inProgress: Boolean = false,
) {
    val elapsedLabel: String? get() = elapsedMs?.let(TaskRunInformationProjector::formatElapsed)
}

object OutcomeStageCopy {
    private val SEARCH = Regex("\\b(search|find|look\\s*up|look\\s*for)\\b", RegexOption.IGNORE_CASE)

    fun completedPhrase(objective: String): String = when {
        objective.startsWith("Finding the signed-in email", ignoreCase = true) -> "Email address found"
        objective.startsWith("Finding", ignoreCase = true) ->
            objective.removePrefix("Finding ").removePrefix("finding ").replaceFirstChar { it.uppercase() }
        objective.startsWith("Checking", ignoreCase = true) ->
            objective.removePrefix("Checking ").removePrefix("checking ").replaceFirstChar { it.uppercase() } + " checked"
        objective.startsWith("Signing in", ignoreCase = true) -> "Signed in"
        objective.startsWith("Opening", ignoreCase = true) -> "Opened"
        objective.startsWith("Searching", ignoreCase = true) -> "Search complete"
        else -> "Completed"
    }

    fun objective(
        goal: String,
        destination: TaskDestination,
        label: String,
        last: Boolean,
    ): String = DestinationAuthority.objective(goal, destination, last)

    fun title(goal: String, stages: List<OutcomeStage>): String {
        val gmail = stages.any { it.destinationPackage == "com.google.android.gm" }
        val facebook = stages.any { stage ->
            stage.websiteOrigin.orEmpty().contains("facebook") ||
                stage.destinationLabel.contains("Facebook", ignoreCase = true)
        }
        // Only an explicit sign-in ask becomes a sign-in title; "my logged in email" is not one.
        val login = com.cyclone.mobile.agent.plan.DestinationAuthority.asksAboutLogin(goal)
        if (gmail && facebook && login) return "Sign in to Facebook using your Gmail address"
        if (stages.size >= 2) return stages.joinToString(" → ") { it.destinationLabel }
        return stages.singleOrNull()?.let { stage ->
            when {
                login -> "Checking ${stage.destinationLabel} login status"
                SEARCH.containsMatchIn(goal) -> "Searching ${stage.destinationLabel}"
                else -> stage.objective.replaceFirstChar { it.uppercase() }
            }
        } ?: "Phone task"
    }

    fun attention(stage: OutcomeStage?, interruption: TaskInterruption?): String {
        interruption?.prompt?.takeIf { it.isNotBlank() }?.let { return it }
        val dest = stage?.destinationLabel ?: "this step"
        return "Complete the required step in $dest, then continue."
    }

    fun terminalFailure(
        stages: List<OutcomeStage>,
        recordedReason: String? = null,
        resumable: Boolean = false,
    ): String {
        val completed = stages.filter { it.status == OutcomeStageStatus.COMPLETED }
        val failed = stages.firstOrNull { it.status == OutcomeStageStatus.FAILED }
            ?: stages.lastOrNull { it.status == OutcomeStageStatus.ACTIVE || it.status == OutcomeStageStatus.NEEDS_INPUT }
        val reason = recordedReason?.takeIf { it.isNotBlank() && !looksLikePlaceholder(it) }
        val body = when {
            completed.isNotEmpty() && failed != null && reason != null ->
                "${completed.joinToString(" and ") { it.destinationLabel }} completed. ${failed.destinationLabel} could not be finished."
            completed.isNotEmpty() && failed != null ->
                "Stopped before ${failed.destinationLabel} could be verified. The reason was not recorded."
            failed != null && reason != null ->
                "${failed.destinationLabel} could not be finished."
            failed != null ->
                "Stopped before ${failed.destinationLabel} could be verified. The reason was not recorded."
            else ->
                "I couldn't finish this task. The reason was not recorded."
        }
        val history = if (resumable) " Your place is saved." else " Run history saved."
        return body + history
    }

    private fun looksLikePlaceholder(value: String): Boolean {
        val lower = value.lowercase()
        return "couldn't finish" in lower || "could not finish" in lower || "your place is saved" in lower
    }
}

object OutcomeStageProjector {
    fun project(
        goal: String,
        trajectory: TaskTrajectory?,
        app: String,
        packageName: String,
        phase: TaskPhase,
        interruption: TaskInterruption? = null,
        outcome: String? = null,
    ): OutcomeStageProjection {
        val drafts = drafts(goal, trajectory, app, packageName)
        val consumer = consumerState(phase)
        val index = trajectory?.index ?: 0
        val stages = drafts.mapIndexed { order, draft ->
            val previousIds = drafts.take(order).map { it.id }
            val status = statusFor(draft, index, consumer, drafts, order)
            val currentAction = when (status) {
                OutcomeStageStatus.NEEDS_INPUT -> interruption?.prompt?.takeIf { it.isNotBlank() }
                    ?: "Needs your input"
                OutcomeStageStatus.ACTIVE -> draft.objective
                OutcomeStageStatus.FAILED -> OutcomeStageCopy.terminalFailure(
                    emptyList(),
                    outcome,
                    resumable = false,
                ).substringBefore(". Run history").substringBefore(". Your place")
                else -> null
            }
            OutcomeStage(
                id = draft.id,
                order = order,
                destinationPackage = draft.destinationPackage,
                destinationLabel = draft.destinationLabel,
                websiteOrigin = draft.websiteOrigin,
                objective = draft.objective,
                dependsOn = previousIds,
                status = status,
                currentAction = currentAction,
                verificationSummary = if (status == OutcomeStageStatus.COMPLETED) {
                    OutcomeStageCopy.completedPhrase(draft.objective)
                } else null,
                resultOrError = when (status) {
                    OutcomeStageStatus.COMPLETED -> OutcomeStageCopy.completedPhrase(draft.objective)
                    OutcomeStageStatus.FAILED -> "Couldn't finish"
                    else -> null
                },
                waypointStart = draft.waypointStart,
                waypointEndExclusive = draft.waypointEndExclusive,
            )
        }
        val resolved = when (consumer) {
            TaskConsumerState.FAILED -> markFailedCurrent(stages)
            TaskConsumerState.DONE -> stages.map { it.copy(status = OutcomeStageStatus.COMPLETED, resultOrError = OutcomeStageCopy.completedPhrase(it.objective), verificationSummary = OutcomeStageCopy.completedPhrase(it.objective)) }
            else -> stages
        }
        val activeIndex = resolved.indexOfFirst {
            it.status in setOf(OutcomeStageStatus.ACTIVE, OutcomeStageStatus.NEEDS_INPUT, OutcomeStageStatus.FAILED)
        }.let { if (it < 0) resolved.indexOfLast { stage -> stage.status == OutcomeStageStatus.COMPLETED }.coerceAtLeast(0) else it }
        val chain = resolved.joinToString(" → ") { it.destinationLabel }
        return OutcomeStageProjection(
            stages = resolved,
            activeIndex = activeIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
            destinationChain = chain,
            title = OutcomeStageCopy.title(goal, resolved),
        )
    }

    fun destinationLabel(destination: TaskDestination, goal: String): String {
        return when (destination.kind) {
            "host" -> {
                val site = hostLabel(destination.value)
                if (mentionsBrowser(goal)) "Chrome · $site" else site
            }
            else -> packageLabel(destination.value)
        }
    }

    fun packageLabel(packageName: String): String {
        val aliases = FastPathLanding.APP_PACKAGE_ALIASES.filterValues { it == packageName }.keys
        val best = aliases.maxByOrNull { it.length }
        if (best != null) {
            return best.split(Regex("\\s+")).joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }
        }
        return packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            .takeIf { it.isNotBlank() && it.length <= 24 } ?: "App"
    }

    fun hostLabel(host: String): String {
        val root = host.removePrefix("www.")
        FastPathLanding.APP_PACKAGE_ALIASES.values.distinct().forEach { pkg ->
            val fallback = FastPathLanding.webFallback(pkg) ?: return@forEach
            val fallbackHost = fallback.substringAfter("://").substringBefore('/').removePrefix("www.")
            if (fallbackHost == root || fallbackHost.substringBefore('.') == root.substringBefore('.')) {
                return packageLabel(pkg)
            }
        }
        return root.substringBefore('.').replaceFirstChar { it.uppercase() }
    }

    private fun mentionsBrowser(goal: String): Boolean =
        Regex("(?i)\\b(chrome|browser|firefox)\\b").containsMatchIn(goal)

    private fun consumerState(phase: TaskPhase): TaskConsumerState = when (phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> TaskConsumerState.WORKING
        TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN -> TaskConsumerState.ACTION_NEEDED
        TaskPhase.DONE -> TaskConsumerState.DONE
        TaskPhase.FAILED, TaskPhase.STOPPED -> TaskConsumerState.FAILED
    }

    private data class Draft(
        val id: String,
        val destinationPackage: String?,
        val destinationLabel: String,
        val websiteOrigin: String?,
        val objective: String,
        val waypointStart: Int,
        val waypointEndExclusive: Int,
    )

    private fun drafts(
        goal: String,
        trajectory: TaskTrajectory?,
        app: String,
        packageName: String,
    ): List<Draft> {
        val assessment = TaskDifficulty.assess(goal)
        if (assessment.destinations.isNotEmpty()) {
            return assessment.destinations.mapIndexed { index, destination ->
                val range = waypointRange(trajectory, destination, index, assessment.destinations.size)
                val label = destinationLabel(destination, goal)
                Draft(
                    id = "stage-$index",
                    destinationPackage = if (destination.kind == "app") destination.value else browserPackage(goal),
                    destinationLabel = label,
                    websiteOrigin = if (destination.kind == "host") destination.value else null,
                    objective = OutcomeStageCopy.objective(goal, destination, label, index == assessment.destinations.lastIndex),
                    waypointStart = range.first,
                    waypointEndExclusive = range.second,
                )
            }
        }
        val waypoints = trajectory?.waypoints.orEmpty()
        val groups = groupWaypoints(waypoints)
        if (groups.isNotEmpty()) {
            return groups.mapIndexed { index, group ->
                val host = group.uri?.substringAfter("://")?.substringBefore('/')?.removePrefix("www.")
                val dest = if (host != null) {
                    TaskDestination("host", host, index)
                } else {
                    TaskDestination("app", group.packageName ?: packageName, index)
                }
                val label = destinationLabel(dest, goal).ifBlank {
                    app.takeIf { it.isNotBlank() && !it.equals("your app", true) } ?: "App"
                }
                Draft(
                    id = "stage-$index",
                    destinationPackage = group.packageName ?: packageName.takeIf { it.isNotBlank() },
                    destinationLabel = label,
                    websiteOrigin = host,
                    objective = OutcomeStageCopy.objective(goal, dest, label, index == groups.lastIndex),
                    waypointStart = group.start,
                    waypointEndExclusive = group.endExclusive,
                )
            }
        }
        val label = app.takeIf { it.isNotBlank() && !it.equals("your app", true) && !it.equals("Other", true) }
            ?: packageName.takeIf { it.isNotBlank() }?.let(::packageLabel)
            ?: "App"
        return listOf(
            Draft(
                id = "stage-0",
                destinationPackage = packageName.takeIf { it.isNotBlank() },
                destinationLabel = label,
                websiteOrigin = null,
                objective = OutcomeStageCopy.objective(
                    goal,
                    TaskDestination("app", packageName.ifBlank { "app" }, 0),
                    label,
                    last = true,
                ),
                waypointStart = 0,
                waypointEndExclusive = (trajectory?.waypoints?.size ?: 1).coerceAtLeast(1),
            ),
        )
    }

    private data class WaypointGroup(
        val packageName: String?,
        val uri: String?,
        val start: Int,
        val endExclusive: Int,
    )

    private fun groupWaypoints(waypoints: List<com.cyclone.mobile.agent.plan.TaskWaypoint>): List<WaypointGroup> {
        val groups = mutableListOf<WaypointGroup>()
        var current: WaypointGroup? = null
        waypoints.forEachIndexed { index, waypoint ->
            when (waypoint.kind) {
                WaypointKind.OPEN_APP, WaypointKind.LAUNCH_INTENT -> {
                    current?.let { groups[groups.lastIndex] = it.copy(endExclusive = index) }
                    current = WaypointGroup(waypoint.packageName, waypoint.uri, index, index + 1)
                    groups += current!!
                }
                WaypointKind.DONE -> current?.let { groups[groups.lastIndex] = it.copy(endExclusive = index) }
                else -> current?.let { groups[groups.lastIndex] = it.copy(endExclusive = index + 1) }
            }
        }
        return groups.filter { it.endExclusive > it.start }
    }

    private fun waypointRange(
        trajectory: TaskTrajectory?,
        destination: TaskDestination,
        destIndex: Int,
        destCount: Int,
    ): Pair<Int, Int> {
        val waypoints = trajectory?.waypoints.orEmpty()
        if (waypoints.isEmpty()) return destIndex to destIndex + 1
        val groups = groupWaypoints(waypoints)
        val match = groups.getOrNull(destIndex)
            ?: groups.lastOrNull()
            ?: return destIndex to destIndex + 1
        return match.start to match.endExclusive
    }

    private fun browserPackage(goal: String): String? =
        if (mentionsBrowser(goal)) "com.android.chrome" else null

    private fun statusFor(
        draft: Draft,
        trajectoryIndex: Int,
        consumer: TaskConsumerState,
        drafts: List<Draft>,
        order: Int,
    ): OutcomeStageStatus {
        if (consumer == TaskConsumerState.DONE) return OutcomeStageStatus.COMPLETED
        val previousComplete = order == 0 || trajectoryIndex >= draft.waypointStart
        if (consumer == TaskConsumerState.FAILED) {
            return when {
                trajectoryIndex >= draft.waypointEndExclusive -> OutcomeStageStatus.COMPLETED
                trajectoryIndex >= draft.waypointStart -> OutcomeStageStatus.FAILED
                else -> OutcomeStageStatus.QUEUED
            }
        }
        return when {
            trajectoryIndex >= draft.waypointEndExclusive && draft.waypointEndExclusive > draft.waypointStart ->
                OutcomeStageStatus.COMPLETED
            !previousComplete && order > 0 && trajectoryIndex < draft.waypointStart ->
                if (drafts.take(order).any { trajectoryIndex < it.waypointEndExclusive }) OutcomeStageStatus.BLOCKED
                else OutcomeStageStatus.QUEUED
            trajectoryIndex in draft.waypointStart until draft.waypointEndExclusive ||
                (order == drafts.lastIndex && trajectoryIndex >= draft.waypointStart) ->
                if (consumer == TaskConsumerState.ACTION_NEEDED) OutcomeStageStatus.NEEDS_INPUT
                else OutcomeStageStatus.ACTIVE
            else -> OutcomeStageStatus.QUEUED
        }
    }

    private fun markFailedCurrent(stages: List<OutcomeStage>): List<OutcomeStage> {
        val current = stages.indexOfLast {
            it.status == OutcomeStageStatus.FAILED || it.status == OutcomeStageStatus.ACTIVE ||
                it.status == OutcomeStageStatus.NEEDS_INPUT
        }.let { if (it < 0) stages.indexOfFirst { stage -> stage.status != OutcomeStageStatus.COMPLETED } else it }
        if (current < 0) return stages
        return stages.mapIndexed { index, stage ->
            when {
                index < current -> stage.copy(
                    status = if (stage.status == OutcomeStageStatus.COMPLETED) stage.status else OutcomeStageStatus.COMPLETED,
                    resultOrError = stage.resultOrError ?: OutcomeStageCopy.completedPhrase(stage.objective),
                )
                index == current -> stage.copy(status = OutcomeStageStatus.FAILED, resultOrError = "Couldn't finish")
                else -> stage.copy(status = OutcomeStageStatus.QUEUED)
            }
        }
    }
}

object TaskRunInformationProjector {
    fun fromTask(task: WorkspaceTaskUi, nowMs: Long = System.currentTimeMillis()): TaskRunInformation {
        val elapsed = task.startedAtMs.takeIf { it > 0L }?.let { start -> (nowMs - start).coerceAtLeast(0L) }
        return TaskRunInformation(
            elapsedMs = elapsed,
            waiting = task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW),
            inProgress = task.working || task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW),
        )
    }

    fun combine(
        task: WorkspaceTaskUi,
        modelName: String?,
        startedAtMs: Long?,
        endedAtMs: Long?,
        modelRequests: Int?,
        toolActions: Int?,
        verifiedActions: Int?,
        tokensInput: Long?,
        tokensOutput: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): TaskRunInformation {
        val start = startedAtMs?.takeIf { it > 0L } ?: task.startedAtMs.takeIf { it > 0L }
        val end = endedAtMs ?: nowMs
        val tokensReported = tokensInput != null || tokensOutput != null
        return TaskRunInformation(
            elapsedMs = start?.let { (end - it).coerceAtLeast(0L) },
            waiting = task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW),
            modelRequests = modelRequests,
            toolActions = toolActions,
            verifiedActions = verifiedActions,
            tokensInput = tokensInput,
            tokensOutput = tokensOutput,
            tokensTotal = if (tokensReported) (tokensInput ?: 0L) + (tokensOutput ?: 0L) else null,
            tokensReported = tokensReported,
            modelName = modelName?.takeIf { it.isNotBlank() && it != "local-launch" },
            verificationSummary = task.plannedStages.firstOrNull {
                it.status == OutcomeStageStatus.ACTIVE || it.status == OutcomeStageStatus.NEEDS_INPUT
            }?.verificationSummary,
            inProgress = task.working || task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW),
        )
    }

    fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            minutes <= 0L -> "${seconds}s"
            else -> "${minutes}m ${seconds}s"
        }
    }
}
