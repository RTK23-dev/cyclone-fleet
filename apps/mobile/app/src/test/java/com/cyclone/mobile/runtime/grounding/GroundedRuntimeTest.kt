package com.cyclone.mobile.runtime.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroundedRuntimeTest {
    @Test
    fun notificationBlockUsesTargetSemanticsBeforeContext() {
        val modal = DeviceSurface(
            kind = SurfaceKind.MODAL,
            packageName = "com.android.chrome",
            text = "www.ad.nl wants to send you notifications",
            controls = listOf(GroundedControl("Block"), GroundedControl("Allow")),
        )
        val type = ModalRecognitionEngine.classify(modal)
        assertEquals(ModalType.SITE_NOTIFICATION_PERMISSION, type)
        assertEquals(
            ActionIntent.DENY_SITE_NOTIFICATION,
            ActionIntentClassifier.classify("Block", modalType = type, context = listOf(modal.text)),
        )
        assertEquals(
            ActionIntent.ALLOW_SITE_NOTIFICATION,
            ActionIntentClassifier.classify("Allow", modalType = type, context = listOf(modal.text)),
        )
    }

    @Test
    fun cycloneOverlayCannotBecomeExternalTaskSurface() {
        val chrome = DeviceSurface(SurfaceKind.TASK, "com.android.chrome", text = "ad.nl")
        val modal = DeviceSurface(SurfaceKind.MODAL, "com.android.chrome", text = "notifications")
        val cyclone = DeviceSurface(SurfaceKind.AGENT, "com.cyclone.mobile", text = "Working")
        val reality = DeviceReality(chrome, modal, cyclone)
        assertEquals("com.android.chrome", reality.taskSurface?.packageName)
        assertEquals(SurfaceKind.MODAL, reality.modalSurface?.kind)
        assertEquals("com.cyclone.mobile", reality.agentSurface?.packageName)
    }

    @Test
    fun repeatedVisionIsDeniedUntilVerifiedProgressResetsSceneMemory() {
        val guard = VisionEscalationGuard()
        assertEquals(VisionEscalationGuard.Decision.ALLOW, guard.request("scene-adnl-notification"))
        assertEquals(VisionEscalationGuard.Decision.VISION_ALREADY_USED, guard.request("scene-adnl-notification"))
        guard.resetForVerifiedProgress()
        assertEquals(VisionEscalationGuard.Decision.ALLOW, guard.request("scene-adnl-notification"))
    }

    @Test
    fun visualSemanticLocatorReresolvesAgainstFreshControlsNotExpiredId() {
        val locator = VisualSemanticLocator(
            semanticTarget = "Block",
            modalType = ModalType.SITE_NOTIFICATION_PERMISSION,
            sourcePackage = "com.android.chrome",
            approximateBounds = "10,10,100,60",
            sceneIdentity = "scene-1",
            confidence = 0.98,
        )
        assertNotNull(locator.resolveFresh(listOf(GroundedControl("Allow"), GroundedControl("Block")), "com.android.chrome", "scene-1"))
        assertNull(locator.resolveFresh(listOf(GroundedControl("Block")), "com.android.chrome", "scene-2"))
    }

    @Test
    fun settlementWindowIsBoundedForDelayedChromeTransitions() {
        val policy = TransitionSettlementPolicy()
        assertEquals(1_800L, policy.maxWaitMs)
        assertEquals(120L, policy.observationIntervalMs)
    }
}
