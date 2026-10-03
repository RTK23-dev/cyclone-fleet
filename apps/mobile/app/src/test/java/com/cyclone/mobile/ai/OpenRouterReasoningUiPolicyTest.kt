package com.cyclone.mobile.ai

import com.cyclone.mobile.ui.v32.ReasoningSelectorMode
import com.cyclone.mobile.ui.v32.reasoningSelectorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterReasoningUiPolicyTest {
    @Test fun zeroEffortsIsModelControlled() {
        assertEquals(ReasoningSelectorMode.MODEL_CONTROLLED, reasoningSelectorMode(emptyList()))
    }

    @Test fun upToThreeExactEffortsUsePills() {
        assertEquals(ReasoningSelectorMode.PILLS, reasoningSelectorMode(listOf("max")))
        assertEquals(ReasoningSelectorMode.PILLS, reasoningSelectorMode(listOf("max", "high", "low")))
    }

    @Test fun moreThanThreeExactEffortsUsesExpandableMenu() {
        assertEquals(ReasoningSelectorMode.MENU, reasoningSelectorMode(listOf("xhigh", "high", "medium", "low")))
        assertEquals(ReasoningSelectorMode.MENU, reasoningSelectorMode(listOf("max", "xhigh", "high", "medium", "low", "minimal", "none")))
    }
}
