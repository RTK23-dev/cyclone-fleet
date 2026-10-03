package com.cyclone.mobile.gesture

import android.graphics.Path

/** Thin Android renderer. All motion planning remains inside [HumanGestureEngine]. */
object AndroidGestureRenderer {
    fun tapPath(plan: TapPlan): Path = Path().apply {
        moveTo(plan.point.x, plan.point.y)
    }

    fun strokePath(plan: StrokePlan): Path = Path().apply {
        moveTo(plan.start.x, plan.start.y)
        if (plan.profile == HumanizeProfile.OFF) {
            // Preserve the compatibility surface exactly: one straight start/end segment.
            lineTo(plan.end.x, plan.end.y)
        } else {
            cubicTo(
                plan.control1.x,
                plan.control1.y,
                plan.control2.x,
                plan.control2.y,
                plan.end.x,
                plan.end.y,
            )
        }
    }
}
