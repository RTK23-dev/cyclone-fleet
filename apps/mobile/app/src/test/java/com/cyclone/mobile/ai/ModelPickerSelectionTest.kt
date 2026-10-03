package com.cyclone.mobile.ai

import org.junit.Assert.*
import org.junit.Test

class ModelPickerSelectionTest {
    @Test fun arbitraryCatalogSelectionsHaveNoPresetOrCountLimit() {
        var state = ModelPickerSelection(emptySet(), "")
        repeat(25) { state = state.toggle("provider/model-$it:free", true) }
        assertEquals(25, state.ids.size)
        assertEquals("provider/model-0:free", state.activeId)
        val restored = ModelPickerSelection(state.ids.toSet(), state.activeId)
        assertEquals(state, restored)
        state.ids.toList().forEach { state = state.toggle(it, false) }
        assertTrue(state.ids.isEmpty())
        assertEquals("", state.activeId)
    }

    @Test fun deselectingActiveModelKeepsOnlyExplicitRemainingChoices() {
        val state = ModelPickerSelection(setOf("a/one", "b/two"), "b/two")
        assertEquals("b/two", state.toggle("c/three", true).activeId)
        assertEquals(ModelPickerSelection(setOf("a/one"), "a/one"), state.toggle("b/two", false))
        assertEquals(state, state.toggle("b/two", true))
    }
}
