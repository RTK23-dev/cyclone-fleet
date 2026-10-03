package com.cyclone.mobile.applearner

import android.content.Context
import com.cyclone.mobile.guided.RoutineTeachingOverlayRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import java.io.File

/**
 * Cancels the active Follow Me teaching session without opening a report, compiling a routine or
 * running model analysis. Observation-backed App Graph/Brain knowledge already learned while the
 * user was navigating remains valid, but temporary review artifacts are removed.
 */
fun discardFollowMeSession(context: Context) {
    val app = context.applicationContext
    val sessionId = RoutineTeachingRuntime.activeSessionId()

    RoutineTeachingOverlayRuntime.dismiss()

    if (sessionId != null) {
        RoutineTeachingRuntime.finish(
            appsSeen = 0,
            pagesSeen = 0,
            actionsSeen = 0,
            pathsLearned = 0,
        )
        File(app.filesDir, "cyclone-teaching-sessions/$sessionId").deleteRecursively()
        TeachingGestureEvidenceV292.clear(app, sessionId)
        File(app.filesDir, "cyclone-v292-teaching-corrections/$sessionId").deleteRecursively()
        val suffix = "-${sessionId.take(8)}"
        File(app.filesDir, "Cyclone Brain/Routine Teachings")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.endsWith(suffix) }
            .forEach(File::deleteRecursively)
    }

    // With the teaching session already cleared, stop() restores controller ownership but cannot
    // create a report or schedule RoutineTeachingAnalyzer.
    FollowMeLearnerRuntime.stop()
}
