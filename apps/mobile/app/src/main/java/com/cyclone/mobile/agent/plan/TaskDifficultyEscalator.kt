package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.PageContext

/**
 * Evidence-only promotion. The ask starts the tier; the screen can only raise it.
 * Chrome custom tabs, launchers and IME never count as a second app.
 */
object TaskDifficultyEscalator {
    fun next(
        current: TaskDifficultyTier,
        goal: String,
        page: PageContext,
        packagesSeen: Set<String>,
        consecutiveNoProgress: Int,
    ): TaskDifficultyTier {
        if (current == TaskDifficultyTier.HARD) return current
        val families = TaskDifficulty.evidenceFamilies(packagesSeen, goal)
        if (families.size >= 2) return TaskDifficultyTier.HARD
        if (current == TaskDifficultyTier.MEDIUM &&
            consecutiveNoProgress >= 2 &&
            TaskDifficulty.assess(goal).destinationCount >= 2
        ) {
            return TaskDifficultyTier.HARD
        }
        if (current == TaskDifficultyTier.EASY && leftoverNeedsScene(goal, page)) {
            return TaskDifficultyTier.MEDIUM
        }
        return current
    }

    fun leftoverNeedsScene(goal: String, page: PageContext): Boolean {
        if (TaskDifficulty.classify(goal) != TaskDifficultyTier.EASY) return true
        return TaskTrajectory.looksLikeLoginWall(page) && TaskDifficulty.hasAuthenticatedSession(goal)
    }

    fun userApps(packagesSeen: Set<String>): Set<String> = TaskDifficulty.nativeFamilies(packagesSeen)
}
