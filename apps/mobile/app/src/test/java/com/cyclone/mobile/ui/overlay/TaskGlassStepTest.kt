package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGlassStepTest {
    @Test
    fun blankAndBrainBookkeepingAreNotGlassSubtitles() {
        assertNull(TaskGlassStep.fromProgress(""))
        assertNull(TaskGlassStep.fromProgress("   "))
        assertNull(TaskGlassStep.fromProgress("Cyclone Brain updated"))
        assertNull(TaskGlassStep.fromProgress("Writing verified results to Second Brain…"))
    }

    @Test
    fun compiledSkillReplayUsesSkillSubtitle() {
        val step = TaskGlassStep.fromProgress("Replaying compiled skill · Open search")
        assertNotNull(step)
        assertEquals(GlassStepKind.SKILL, step!!.kind)
        assertTrue(step.label.startsWith("Skill ·"))
        assertTrue(step.label.contains("Open search"))
        assertNoForbiddenPhrases(step.label)
    }

    @Test
    fun verifiedAppMapIsSkill() {
        val step = TaskGlassStep.fromProgress("Using verified app map: Checkout")
        assertNotNull(step)
        assertEquals(GlassStepKind.SKILL, step!!.kind)
        assertTrue(step.label.startsWith("Skill ·"))
        assertNoForbiddenPhrases(step.label)
    }

    @Test
    fun layer2ProgressUsesLayer2SliceSubtitle() {
        for (raw in listOf("Layer 2 slice · Shop", "workspace.next", "Time-sliced Shop")) {
            val step = TaskGlassStep.fromProgress(raw)
            assertNotNull(raw, step)
            assertEquals(raw, GlassStepKind.LAYER2_SLICE, step!!.kind)
            assertTrue(raw, step.label.startsWith("Layer 2 slice"))
            assertNoForbiddenPhrases(step.label)
        }
    }

    @Test
    fun fastPathActionSummariesUseFastPathSubtitle() {
        for (raw in listOf(
            "tap Search",
            "Checking the page…",
            "Understanding Chrome · AI request 1",
            "Checking the result…",
        )) {
            val step = TaskGlassStep.fromProgress(raw)
            assertNotNull(raw, step)
            assertEquals(raw, GlassStepKind.FAST_PATH, step!!.kind)
            assertTrue(raw, step.label.startsWith("Fast Path ·"))
            assertNoForbiddenPhrases(step.label)
        }
    }

    @Test
    fun layer2SliceFactoryUsesWorkspaceAndOptionalStep() {
        val instagram = TaskGlassStep.layer2Slice("Instagram")
        assertEquals(GlassStepKind.LAYER2_SLICE, instagram.kind)
        assertEquals("Layer 2 slice · Instagram", instagram.label)

        val shop = TaskGlassStep.layer2Slice("Shop", "claim next")
        assertEquals(GlassStepKind.LAYER2_SLICE, shop.kind)
        assertTrue(shop.label.contains("Shop"))
        assertTrue(shop.label.contains("claim next"))
        assertNoForbiddenPhrases(instagram.label)
        assertNoForbiddenPhrases(shop.label)
    }

    @Test
    fun subtitlePrefixesMatchKind() {
        assertTrue(
            TaskGlassStep.subtitle(GlassStepKind.SKILL, "Replaying compiled skill · Open search")
                .startsWith("Skill ·"),
        )
        assertTrue(
            TaskGlassStep.subtitle(GlassStepKind.LAYER2_SLICE, "Layer 2 slice · Shop")
                .startsWith("Layer 2 slice"),
        )
        assertTrue(TaskGlassStep.subtitle(GlassStepKind.FAST_PATH, "tap Search").startsWith("Fast Path ·"))
        assertNoForbiddenPhrases(TaskGlassStep.subtitle(GlassStepKind.SKILL, "Replaying compiled skill · Open search"))
        assertNoForbiddenPhrases(TaskGlassStep.subtitle(GlassStepKind.LAYER2_SLICE, "Layer 2 slice · Shop"))
        assertNoForbiddenPhrases(TaskGlassStep.subtitle(GlassStepKind.FAST_PATH, "tap Search"))
    }

    @Test
    fun fromProgressTruncatesLongLabels() {
        val long = "tap " + "Search".repeat(40)
        val step = TaskGlassStep.fromProgress(long)
        assertNotNull(step)
        assertEquals(GlassStepKind.FAST_PATH, step!!.kind)
        assertTrue(step.label.length <= 120)
        assertTrue(step.label.startsWith("Fast Path ·"))
        assertNoForbiddenPhrases(step.label)
    }

    private fun assertNoForbiddenPhrases(label: String) {
        OverlayCopy.NEVER_SAY.forEach { forbidden ->
            assertFalse("glass subtitle must not contain: $forbidden", label.contains(forbidden))
        }
    }
}
