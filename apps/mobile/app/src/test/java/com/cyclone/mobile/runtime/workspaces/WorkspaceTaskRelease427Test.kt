package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTaskRelease427Test {
    @Test fun closingOneTaskReleasesItsAuthorityAndPreservesOtherJobs() {
        val engine = WorkspaceEngine()
        engine.register(Workspace("a", "Chrome", "com.android.chrome"))
        engine.register(Workspace("b", "Mail", "com.google.gmail"))
        engine.arm("a"); engine.arm("b")
        val lease = engine.switch("a", { false }, {}, { WorkspaceTarget(it.appPackage, it.androidUserId) })
        engine.closeTask("a", lease.generation)
        assertNull(engine.holder())
        assertNull(engine.selectedId())
        assertEquals(listOf("b"), engine.queue())
    }
    @Test fun staleCloseCannotRevokeNewLease() {
        val engine = WorkspaceEngine()
        engine.register(Workspace("a", "Chrome", "com.android.chrome"))
        val old = engine.switch("a", { false }, {}, { WorkspaceTarget(it.appPackage, it.androidUserId) })
        val current = engine.switch("a", { false }, {}, { WorkspaceTarget(it.appPackage, it.androidUserId) })
        assertTrue(runCatching { engine.closeTask("a", old.generation) }.isFailure)
        assertEquals(current, engine.holder())
    }
}
