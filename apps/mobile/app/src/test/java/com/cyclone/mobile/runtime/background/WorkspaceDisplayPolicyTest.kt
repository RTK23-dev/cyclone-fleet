package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.runtime.session.ExecutionSessionStore
import com.cyclone.mobile.runtime.session.SessionKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDisplayPolicyTest {
    @Test
    fun preferredFlagsIncludeOwnDisplayGroupAndTrustedAndExcludePublic() {
        val flags = WorkspaceDisplayPolicy.isolationFlags()
        assertTrue(flags and WorkspaceDisplayPolicy.TRUSTED != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.OWN_DISPLAY_GROUP != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.OWN_FOCUS != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.STEAL_TOP_FOCUS_DISABLED != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.DESTROY_CONTENT_ON_REMOVAL != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.OWN_CONTENT_ONLY != 0)
        assertTrue(flags and WorkspaceDisplayPolicy.PRESENTATION != 0)
        assertEquals(0, flags and WorkspaceDisplayPolicy.PUBLIC)
        assertTrue("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" in WorkspaceDisplayPolicy.REQUIRED_VIRTUAL_DISPLAY_FLAG_FIELDS)
        assertTrue("VIRTUAL_DISPLAY_FLAG_PUBLIC" !in WorkspaceDisplayPolicy.REQUIRED_VIRTUAL_DISPLAY_FLAG_FIELDS)
    }

    @Test
    fun resolveFlagsFailsClosedWhenOwnDisplayGroupOrTrustedIsMissing() {
        val aosp = aospLookup()
        assertEquals(WorkspaceDisplayPolicy.PREFERRED_ISOLATION_FLAGS, WorkspaceDisplayPolicy.resolveFlags(aosp))
        assertEquals(0, WorkspaceDisplayPolicy.resolveFlags(aosp) and WorkspaceDisplayPolicy.PUBLIC)
        assertThrows(IllegalStateException::class.java) {
            WorkspaceDisplayPolicy.resolveFlags { name -> aosp(name).takeUnless { name == "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" } }
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceDisplayPolicy.resolveFlags { name -> aosp(name).takeUnless { name == "VIRTUAL_DISPLAY_FLAG_TRUSTED" } }
        }
    }

    @Test
    fun launchCommandNamesDisplayWithNonzeroId() {
        assertEquals(
            listOf("/system/bin/am", "start", "-W", "--display", "9", "-n", "com.example/.Main"),
            WorkspaceDisplayPolicy.launchCommand(9, "com.example/.Main"),
        )
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDisplayPolicy.launchCommand(0, "com.example/.Main") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDisplayPolicy.launchCommand(-1, "com.example/.Main") }
    }

    @Test
    fun inputNeverAcceptsDisplayZeroAndAlwaysEmitsOwnedDisplay() {
        val operations = listOf(
            Triple(WorkspaceCommands.TAP, floatArrayOf(20f, 30f), ""),
            Triple(WorkspaceCommands.SWIPE, floatArrayOf(20f, 30f, 20f, 90f, 300f), ""),
            Triple(WorkspaceCommands.BACK, floatArrayOf(), ""),
            Triple(WorkspaceCommands.TEXT, floatArrayOf(), "pizza near me"),
        )
        operations.forEach { (kind, coordinates, text) ->
            assertEquals(listOf("/system/bin/input", "-d", "9"), WorkspaceCommands.input(9, kind, coordinates, text).take(3))
            assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(0, kind, coordinates, text) }
            assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(-1, kind, coordinates, text) }
        }
    }

    @Test
    fun storeCanRegisterTwoOwnedSessionsWhileProductHotGateRemainsOne() {
        val store = ExecutionSessionStore()
        store.registerOwned("workspace-a", 8, "com.a")
        store.registerOwned("workspace-b", 9, "com.b")
        assertEquals(3, SessionKernel.snapshot(store).size)
        assertTrue(SessionKernel.API_SESSION_CAPACITY >= 2)
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(1, WorkspaceTasks.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertThrows(Exception::class.java) { store.registerOwned("workspace-c", 0, "com.c") }
        assertThrows(Exception::class.java) { store.registerOwned("workspace-d", 8, "com.d") }
    }

    private fun aospLookup(): (String) -> Int? = { name ->
        when (name) {
            "VIRTUAL_DISPLAY_FLAG_PUBLIC" -> WorkspaceDisplayPolicy.PUBLIC
            "VIRTUAL_DISPLAY_FLAG_PRESENTATION" -> WorkspaceDisplayPolicy.PRESENTATION
            "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY" -> WorkspaceDisplayPolicy.OWN_CONTENT_ONLY
            "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL" -> WorkspaceDisplayPolicy.DESTROY_CONTENT_ON_REMOVAL
            "VIRTUAL_DISPLAY_FLAG_TRUSTED" -> WorkspaceDisplayPolicy.TRUSTED
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" -> WorkspaceDisplayPolicy.OWN_DISPLAY_GROUP
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS" -> WorkspaceDisplayPolicy.OWN_FOCUS
            "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED" -> WorkspaceDisplayPolicy.STEAL_TOP_FOCUS_DISABLED
            else -> null
        }
    }
}
