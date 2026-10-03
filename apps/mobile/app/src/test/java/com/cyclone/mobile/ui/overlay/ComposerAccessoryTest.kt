package com.cyclone.mobile.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class ComposerAccessoryTest {
    @Test fun onlyOneAccessoryCanOpenAndSameButtonClosesIt() {
        assertEquals(ComposerAccessory.ATTACHMENTS, ComposerAccessory.NONE.toggle(ComposerAccessory.ATTACHMENTS))
        assertEquals(ComposerAccessory.MODEL, ComposerAccessory.ATTACHMENTS.toggle(ComposerAccessory.MODEL))
        assertEquals(ComposerAccessory.NONE, ComposerAccessory.MODEL.toggle(ComposerAccessory.MODEL))
    }
    @Test fun attachmentIndicatorTracksConsumptionAndRemoval() {
        PendingTaskAttachment.set(TaskAttachment(text = "reference"))
        assertTrue(PendingTaskAttachment.present.value)
        assertEquals("reference", PendingTaskAttachment.take()?.text)
        assertFalse(PendingTaskAttachment.present.value)
        assertNull(PendingTaskAttachment.take())
    }
}
