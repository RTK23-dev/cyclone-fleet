package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.runtime.background.WorkspaceConfirmation
import com.cyclone.mobile.runtime.background.WorkspaceConsent
import org.junit.Assert.*
import org.junit.Test

class WorkspaceConsentTest {
    private fun challenge() = WorkspaceConfirmation(action = "phone.click", nodeId = "send", fingerprint = "page-a", kind = "send")
    @Test fun grantIsExactAndOneShot() {
        val consent = WorkspaceConsent(); val c = challenge(); consent.request(c)
        assertFalse(consent.approve("wrong", 0))
        assertTrue(consent.approve(c.token, 0))
        assertTrue(consent.consume(c.action, c.nodeId, c.fingerprint, c.kind, 1))
        assertFalse(consent.consume(c.action, c.nodeId, c.fingerprint, c.kind, 2))
    }
    @Test fun changedPageExpiryAndHandoffInvalidateConsent() {
        val c = challenge()
        val consent = WorkspaceConsent()
        consent.request(c); consent.approve(c.token, 0)
        assertFalse(consent.consume(c.action, c.nodeId, "page-b", c.kind, 1))
        consent.request(c); consent.approve(c.token, 0)
        assertFalse(consent.consume(c.action, c.nodeId, c.fingerprint, c.kind, 60_000))
        consent.request(c); consent.approve(c.token, 0); consent.clear()
        assertFalse(consent.consume(c.action, c.nodeId, c.fingerprint, c.kind, 1))
    }
}
