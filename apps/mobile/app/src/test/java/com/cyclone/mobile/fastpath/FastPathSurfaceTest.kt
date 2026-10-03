package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathSurfaceTest {
    @Test
    fun plannerAndUiToolsAreDisjointOnThePhoneSurface() {
        val overlap = FastPathSurface.PLANNER_PHONE_TOOLS.intersect(FastPathSurface.UI_PHONE_TOOLS)
        assertTrue(overlap.isEmpty())
        assertEquals(FastPathToolRole.PLANNER, FastPathSurface.roleForPhoneTool("phone.open_app"))
        assertEquals(FastPathToolRole.PLANNER, FastPathSurface.roleForPhoneTool("phone.launch_intent"))
        assertEquals(FastPathToolRole.UI, FastPathSurface.roleForPhoneTool("phone.click"))
        assertEquals(FastPathToolRole.UI, FastPathSurface.roleForPhoneTool("phone.type"))
        assertEquals(FastPathToolRole.SHARED, FastPathSurface.roleForPhoneTool("phone.screenshot"))
    }

    @Test
    fun mcpObserveIsUiGetTreeAndOpenAppStaysPlanner() {
        assertEquals(FastPathToolRole.UI, FastPathSurface.roleForMcpTool("phone_observe"))
        assertEquals(FastPathToolRole.UI, FastPathSurface.roleForMcpTool("phone_act"))
        assertEquals(FastPathToolRole.PLANNER, FastPathSurface.roleForMcpTool("phone_status"))
        assertEquals(FastPathToolRole.PLANNER, FastPathSurface.roleForMcpTool("phone_skill_run"))
        assertTrue(FastPathSurface.CLOSEPAW_ALIASES["get_tree"]!!.contains("phone_observe"))
        assertTrue(FastPathSurface.CLOSEPAW_ALIASES["click"]!!.contains("elementIndex"))
        assertTrue(FastPathSurface.toJson().optString("rule").contains("soft-success"))
    }
}
