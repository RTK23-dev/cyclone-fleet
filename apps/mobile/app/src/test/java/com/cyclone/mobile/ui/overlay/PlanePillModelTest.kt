package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.plane.PlaneKind
import com.cyclone.mobile.runtime.plane.PlaneUi
import com.cyclone.mobile.task.TaskCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanePillModelTest {
    private val screen = PlaneUi("m1", PlaneKind.SCREEN, available = true)

    @Test fun tapMovesToTheOtherPlane() {
        assertEquals(PlanePillLook.SCREEN, PlanePillModel.look(screen))
        assertEquals(TaskCommand.MoveToBackground, PlanePillModel.tap(screen))
        val background = screen.copy(kind = PlaneKind.BACKGROUND, appLabel = "WhatsApp")
        assertEquals(PlanePillLook.BACKGROUND, PlanePillModel.look(background))
        assertEquals(TaskCommand.MoveToForeground, PlanePillModel.tap(background))
        assertTrue(PlanePillModel.describe(background).endsWith("in WhatsApp"))
    }

    @Test fun switchingAndUnavailableTapsExplainInsteadOfActing() {
        assertEquals(PlanePillLook.SWITCHING, PlanePillModel.look(screen.copy(switching = true)))
        assertNull(PlanePillModel.tap(screen.copy(switching = true)))
        val unavailable = screen.copy(available = false, note = "Shizuku: open it and tap Start.")
        assertEquals(PlanePillLook.UNAVAILABLE, PlanePillModel.look(unavailable))
        assertNull(PlanePillModel.tap(unavailable))
    }

    @Test fun aBackgroundTaskCanAlwaysComeBackEvenWhenBackgroundBroke() {
        val broken = screen.copy(kind = PlaneKind.BACKGROUND, available = false)
        assertEquals(TaskCommand.MoveToForeground, PlanePillModel.tap(broken))
    }

    @Test fun waitingForTheOwnersAppOffersStartNow() {
        val waiting = screen.copy(waitingFor = "WhatsApp")
        assertEquals(PlanePillLook.WAITING, PlanePillModel.look(waiting))
        assertEquals(TaskCommand.StartNow, PlanePillModel.tap(waiting))
        assertTrue(PlanePillModel.describe(waiting).contains("WhatsApp"))
    }
}
