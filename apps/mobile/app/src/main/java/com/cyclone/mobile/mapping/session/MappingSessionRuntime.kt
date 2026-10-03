package com.cyclone.mobile.mapping.session

import android.content.Context
import java.io.File

object MappingSessionRuntime {
    @Volatile private var initialized = false
    private lateinit var controllerValue: MappingSessionController

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val journal = AtlasDiffJournal(
            File(appContext.filesDir, "mapping/atlas-diff-v1.json"),
        )
        controllerValue = MappingSessionController(
            authority = AndroidMappingAuthority(appContext),
            journal = journal,
        )
        initialized = true
    }

    fun controller(context: Context): MappingSessionController {
        initialize(context)
        return controllerValue
    }

    @Synchronized
    internal fun installForTests(controller: MappingSessionController) {
        controllerValue = controller
        initialized = true
    }

    @Synchronized
    internal fun resetForTests() {
        initialized = false
    }
}
