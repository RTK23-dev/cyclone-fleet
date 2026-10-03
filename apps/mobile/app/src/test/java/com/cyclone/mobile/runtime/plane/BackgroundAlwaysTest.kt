package com.cyclone.mobile.runtime.plane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAlwaysTest {
    private val ready = BackgroundFacts(ownerOn = true, android15 = true, helperInstalled = true, helperRunning = true,
        helperAuthorized = true, accessibility = true, notifications = true)

    @Test fun oneAnswerWithOneNextStep() {
        assertEquals(CapabilityLevel.READY, BackgroundCapabilities.judge(ready).level)
        assertEquals(CapabilityLevel.UNSUPPORTED, BackgroundCapabilities.judge(ready.copy(android15 = false)).level)
        assertEquals(CapabilityAction.TURN_ON, BackgroundCapabilities.judge(ready.copy(ownerOn = false)).action)
        val stopped = BackgroundCapabilities.judge(ready.copy(helperRunning = false))
        assertEquals(CapabilityLevel.NEEDS_START, stopped.level)
        assertEquals(CapabilityAction.START_HELPER, stopped.action)
        assertEquals(CapabilityAction.OPEN_SETUP, BackgroundCapabilities.judge(ready.copy(helperInstalled = false)).action)
        assertEquals(CapabilityLevel.NEEDS_SETUP, BackgroundCapabilities.judge(ready.copy(accessibility = false)).level)
    }

    @Test fun theOwnerIsToldOnceAndOnlyAboutWhatTheyCanFix() {
        val paused = BackgroundCapabilities.judge(ready.copy(helperRunning = false))
        assertTrue(BackgroundCapabilities.shouldNotify(paused, lastToldAtMs = 0, nowMs = BackgroundCapabilities.QUIET_MS))
        assertFalse(BackgroundCapabilities.shouldNotify(paused, lastToldAtMs = 1_000, nowMs = 2_000))
        assertFalse(BackgroundCapabilities.shouldNotify(BackgroundCapabilities.judge(ready.copy(ownerOn = false)), 0, Long.MAX_VALUE))
        assertFalse(BackgroundCapabilities.shouldNotify(BackgroundCapabilities.judge(ready), 0, Long.MAX_VALUE))
    }

    private val busy = StartFacts(mode = PlaneMode.AUTOMATIC, fallback = PlaneFallback.SCREEN, backgroundReady = true,
        ownerBusyElsewhere = true, appLabel = "WhatsApp")

    @Test fun theStartTable() {
        // The owner's choices come first.
        assertTrue(StartPolicy.begin(busy.copy(override = PlaneOverride.SCREEN)) is StartPlan.Screen)
        assertTrue(StartPolicy.begin(busy.copy(mode = PlaneMode.SCREEN)) is StartPlan.Screen)
        assertTrue(StartPolicy.begin(busy.copy(mode = PlaneMode.SCREEN, override = PlaneOverride.BACKGROUND)) is StartPlan.Background)
        assertTrue(StartPolicy.begin(busy.copy(needsHands = true)) is StartPlan.Screen)
        assertTrue(StartPolicy.begin(busy.copy(targetCompat = BackgroundCompat.SECURE)) is StartPlan.Screen)
        // Automatic and idle: the screen, where the owner can watch.
        assertTrue(StartPolicy.begin(busy.copy(ownerBusyElsewhere = false)) is StartPlan.Screen)
        // Where the app is decides how it gets to the background.
        assertEquals(BackgroundEntry.LAUNCH, (StartPolicy.begin(busy) as StartPlan.Background).entry)
        assertEquals(BackgroundEntry.ADOPT_FROM_RECENTS, (StartPolicy.begin(busy.copy(holder = TargetHolder.RECENTS)) as StartPlan.Background).entry)
        assertEquals(BackgroundEntry.SECOND_WINDOW,
            (StartPolicy.begin(busy.copy(holder = TargetHolder.OWNER, secondWindow = true)) as StartPlan.Background).entry)
        // The owner holds the app: the fallback decides.
        assertTrue(StartPolicy.begin(busy.copy(holder = TargetHolder.OWNER)) is StartPlan.Screen)
        assertTrue(StartPolicy.begin(busy.copy(holder = TargetHolder.OWNER, fallback = PlaneFallback.WAIT)) is StartPlan.Wait)
        assertTrue(StartPolicy.begin(busy.copy(holder = TargetHolder.OWNER, fallback = PlaneFallback.ASK)) is StartPlan.Ask)
        // Background not possible: waiting cannot fix a stopped helper.
        assertTrue(StartPolicy.begin(busy.copy(backgroundReady = false, fallback = PlaneFallback.WAIT)) is StartPlan.Screen)
        assertTrue(StartPolicy.begin(busy.copy(backgroundReady = false, fallback = PlaneFallback.ASK)) is StartPlan.Ask)
    }

    @Test fun defaultsAndAnswers() {
        assertEquals(PlaneFallback.WAIT, PlaneFallback.defaultFor(PlaneMode.BACKGROUND))
        assertEquals(PlaneFallback.SCREEN, PlaneFallback.defaultFor(PlaneMode.AUTOMATIC))
        assertEquals(StartChoice.WHEN_DONE, StartPolicy.answer("When I'm done"))
        assertEquals(StartChoice.TAKE_TO_BACKGROUND, StartPolicy.answer("Take it to the background"))
        assertEquals(StartChoice.NOW_ON_SCREEN, StartPolicy.answer("Now on my screen"))
        assertEquals(PlaneOverride.SCREEN, AppPlaneCompat.seedOverride("com.bunq.android"))
        assertEquals(PlaneOverride.SCREEN, AppPlaneCompat.seedOverride("com.google.android.GoogleCamera"))
        assertEquals(PlaneOverride.AUTO, AppPlaneCompat.seedOverride("com.whatsapp"))
    }
}
