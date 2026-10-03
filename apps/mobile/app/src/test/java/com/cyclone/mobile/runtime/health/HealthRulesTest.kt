package com.cyclone.mobile.runtime.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRulesTest {
    private val idle = listOf("android.os.MessageQueue.nativePollOnce", "android.os.MessageQueue.next:335", "android.os.Looper.loop:189")

    @Test
    fun `a freeze names the Cyclone code seen in most samples`() {
        val walk = listOf(
            "android.view.accessibility.AccessibilityInteractionClient.getWindowsOnAllDisplays:420",
            "com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490",
            "com.cyclone.mobile.CycloneAccessibilityService.onAccessibilityEvent:181",
            "android.os.Looper.loop:189",
        )
        val draw = listOf("android.graphics.RenderNode.draw", "com.cyclone.mobile.ui.rain.RainView.onDraw:77", "android.os.Looper.loop:189")
        val stall = Stalls.of(1_000, 6_104, listOf(walk, walk, draw, idle))
        assertEquals("com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490", stall.suspect)
        assertEquals(walk, stall.frames)
        assertEquals(4, stall.samples)
    }

    @Test
    fun `a freeze outside Cyclone code keeps Android's top frame, and idle samples are ignored`() {
        val binder = listOf("android.os.BinderProxy.transactNative", "android.os.BinderProxy.transact:584")
        val stall = Stalls.of(0, 900, listOf(idle, binder))
        assertEquals("android.os.BinderProxy.transactNative", stall.suspect)
        assertEquals(binder, stall.frames)
    }

    @Test
    fun `the phone keeps the newest twenty freezes and survives a bad file`() {
        var kept = emptyList<Stall>()
        repeat(25) { kept = Stalls.append(kept, Stall(it.toLong(), 600, "a.B.c", listOf("a.B.c"), 3)) }
        assertEquals(Stalls.MAX_KEPT, kept.size)
        assertEquals(5L, kept.first().startedAtMs)
        val back = Stalls.decode(Stalls.encode(kept))
        assertEquals(kept, back)
        assertTrue(Stalls.decode("not json").isEmpty())
        assertTrue(Stalls.decode(null).isEmpty())
    }

    @Test
    fun `exit reasons use Android's numbers and flag the unexpected ones`() {
        assertEquals("anr", ExitReasons.kind(6))
        assertEquals("crash", ExitReasons.kind(4))
        assertEquals("low_memory", ExitReasons.kind(3))
        assertEquals("package_updated", ExitReasons.kind(16))
        assertEquals("unknown", ExitReasons.kind(99))
        assertTrue(ExitReasons.unexpected(6))
        assertTrue(ExitReasons.unexpected(3))
        assertFalse(ExitReasons.unexpected(10))
        assertFalse(ExitReasons.unexpected(16))
        assertEquals("Input dispatching timed out", ExitReasons.description("  Input dispatching\n timed out "))
        assertNull(ExitReasons.description("   "))
    }

    @Test
    fun `an ANR trace gives only the main thread's frames`() {
        val trace = """
            ----- pid 5262 at 2026-09-30 15:40:35 -----
            Cmd line: com.cyclone.mobile

            "main" prio=5 tid=1 Native
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a6e8b8 self=0xb400
              native: #00 pc 000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (__ioctl+12)
              at android.os.BinderProxy.transactNative(Native method)
              at android.os.BinderProxy.transact(BinderProxy.java:584)
              at com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot(CycloneAccessibilityService.kt:490)
              - waiting to lock <0x0a1b> (a java.lang.Object)
              at android.os.Looper.loop(Looper.java:189)

            "Signal Catcher" daemon prio=10 tid=6 Runnable
              at java.lang.Thread.run(Thread.java:1012)
        """.trimIndent()
        assertEquals(
            listOf(
                "android.os.BinderProxy.transactNative",
                "android.os.BinderProxy.transact:584",
                "com.cyclone.mobile.CycloneAccessibilityService.preferredForegroundRoot:490",
                "android.os.Looper.loop:189",
            ),
            ExitReasons.mainThread(trace),
        )
        assertTrue(ExitReasons.mainThread("no threads here").isEmpty())
        assertTrue(ExitReasons.mainThread(null).isEmpty())
    }
}
