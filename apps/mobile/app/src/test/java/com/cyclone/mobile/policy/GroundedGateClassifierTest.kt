package com.cyclone.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroundedGateClassifierTest {
    @Test
    fun blockNotificationIsNotMisclassifiedAsSend() {
        assertNull(
            GateClassifier.classify(
                "phone.click",
                listOf("Block", "www.ad.nl wants to send you notifications"),
            ),
        )
    }

    @Test
    fun allowNotificationRemainsGrantBoundary() {
        assertEquals(
            GateClass.GRANT,
            GateClassifier.classify(
                "phone.click",
                listOf("Allow", "www.ad.nl wants to send you notifications"),
            ),
        )
    }

    @Test
    fun sendButtonRemainsSendBoundary() {
        assertEquals(GateClass.SEND, GateClassifier.classify("phone.click", listOf("Send")))
    }

    @Test
    fun paymentDeleteAndGrantRemainGated() {
        assertEquals(GateClass.PAY, GateClassifier.classify("phone.click", listOf("Pay now")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Delete")))
        assertEquals(GateClass.GRANT, GateClassifier.classify("phone.click", listOf("Grant access")))
    }

    @Test
    fun forceStopClearStorageAndUninstallNeedTheOwner() {
        for (label in listOf("Force stop", "Clear storage", "Clear data", "Uninstall", "Geforceerd stoppen")) {
            assertEquals(label, GateClass.DELETE, GateClassifier.classify("phone.click", listOf(label)))
        }
        assertNull(GateClassifier.classify("phone.click", listOf("Storage & cache")))
        assertNull(GateClassifier.classify("phone.click", listOf("Open")))
    }
}
