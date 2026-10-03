package com.cyclone.mobile.capture

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class LiveCaptureSessionManagerTest {
    @After fun reset() {
        val id = LiveCaptureSessionManager.state.value.generation
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.STOPPING)
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.OFF)
        LiveCaptureSessionManager.releaseService(id)
    }
    @Test fun consentIsNotLiveAndStaleCallbacksCannotRestartSharing() {
        val id = LiveCaptureSessionManager.request(CaptureScope.USER_CHOICE)!!
        assertFalse(LiveCaptureSessionManager.transition(id, ScreenSharePhase.LIVE))
        assertNull(LiveCaptureSessionManager.request(CaptureScope.WHOLE_DISPLAY))
        assertTrue(LiveCaptureSessionManager.transition(id, ScreenSharePhase.STARTING))
        assertTrue(LiveCaptureSessionManager.claimService(id))
        assertTrue(LiveCaptureSessionManager.transition(id, ScreenSharePhase.LIVE))
        assertTrue(LiveCaptureSessionManager.transition(id, ScreenSharePhase.STOPPING))
        assertFalse(LiveCaptureSessionManager.transition(id, ScreenSharePhase.LIVE))
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.OFF)
        assertNull(LiveCaptureSessionManager.request(CaptureScope.USER_CHOICE))
        LiveCaptureSessionManager.releaseService(id)
        val next = LiveCaptureSessionManager.request(CaptureScope.WHOLE_DISPLAY)!!
        assertTrue(next > id)
        assertFalse(LiveCaptureSessionManager.transition(id, ScreenSharePhase.STARTING))
        assertEquals(CaptureScope.WHOLE_DISPLAY, LiveCaptureSessionManager.state.value.scope)
    }
    @Test fun revocationSurvivesServiceCleanupAndResizeRequiresNewFrame() {
        val id = LiveCaptureSessionManager.request(CaptureScope.USER_CHOICE)!!
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.STARTING)
        LiveCaptureSessionManager.claimService(id)
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.LIVE)
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.STARTING)
        assertEquals(ScreenSharePhase.STARTING, LiveCaptureSessionManager.state.value.phase)
        LiveCaptureSessionManager.transition(id, ScreenSharePhase.REVOKED)
        assertFalse(LiveCaptureSessionManager.transition(id, ScreenSharePhase.LIVE))
        assertFalse(LiveCaptureSessionManager.transition(id, ScreenSharePhase.OFF))
        assertNull(LiveCaptureSessionManager.request(CaptureScope.USER_CHOICE))
        LiveCaptureSessionManager.releaseService(id)
        assertNotNull(LiveCaptureSessionManager.request(CaptureScope.USER_CHOICE))
    }
    @Test fun samplingBoundsCopiesButRefreshesAfterAnActionAndOnStableScreens() {
        val sampler = CaptureFrameSampler()
        assertTrue(sampler.admit(0))
        assertFalse(sampler.admit(16))
        assertFalse(sampler.admit(299))
        assertTrue(sampler.admit(300))
        sampler.contentChanged(false)
        assertFalse(sampler.admit(600))
        assertTrue(sampler.admit(800))
        sampler.requestBurst(810)
        assertTrue(sampler.admit(900))
        assertFalse(sampler.admit(916))
        assertTrue(sampler.admit(1000))
        assertTrue(sampler.admit(1811))
        assertFalse(sampler.admit(2000))
        assertTrue(sampler.admit(2311))
    }
}
