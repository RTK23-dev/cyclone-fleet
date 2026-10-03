package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayReadyNextActionTest {
    @Test
    fun allGoodReturnsNull() {
        assertNull(
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                hasListenerError = false,
                phoneControlReady = true,
                phoneControlNeedsRepair = false,
                pendingTrust = false,
                trustedPcCount = 0,
                pcSessionKnown = true,
            ),
        )
    }

    @Test
    fun connectedWithAccessibilityEnabledAndListeningIsReadyEvenWithTrustedPcs() {
        assertNull(
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                hasListenerError = false,
                phoneControlReady = true,
                phoneControlNeedsRepair = false,
                pendingTrust = false,
                trustedPcCount = 2,
                pcSessionKnown = true,
            ),
        )
    }

    @Test
    fun gatewayOffWinsOverAccessibilityOff() {
        val action = nextAction(
            gatewayEnabled = false,
            socketListening = false,
            hasListenerError = false,
            phoneControlReady = false,
            phoneControlNeedsRepair = false,
            pendingTrust = false,
            trustedPcCount = 0,
            pcSessionKnown = false,
        )
        assertEquals(GatewayReadyDoctor.TURN_ON_GATEWAY, action?.code)
        assertEquals("Turn on PC Gateway", action?.title)
        assertEquals(
            "Enable Full PC + Codex Gateway in Cyclone AI so this phone can accept a trusted PC over USB.",
            action?.body,
        )
        assertEquals("Turn on PC Gateway", action?.actionLabel)
    }

    @Test
    fun turnOnGatewayWhenDisabledEvenIfSocketLooksReady() {
        val action = nextAction(
            gatewayEnabled = false,
            socketListening = true,
            hasListenerError = true,
            phoneControlReady = true,
            pendingTrust = true,
            trustedPcCount = 1,
            pcSessionKnown = true,
        )
        assertEquals(GatewayReadyDoctor.TURN_ON_GATEWAY, action?.code)
    }

    @Test
    fun fixUsbBridgeWhenListenerError() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            hasListenerError = true,
            phoneControlReady = true,
        )
        assertEquals(GatewayReadyDoctor.FIX_USB_BRIDGE, action?.code)
        assertEquals("Fix the USB bridge", action?.title)
        assertEquals(
            "Start USB debugging on this phone and open Cyclone One on your PC. The USB bridge is not ready for PC control.",
            action?.body,
        )
        assertEquals("Fix the USB bridge", action?.actionLabel)
    }

    @Test
    fun fixUsbBridgeWhenEnabledButNotListening() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = false,
            hasListenerError = false,
            phoneControlReady = false,
            phoneControlNeedsRepair = true,
            pendingTrust = true,
        )
        assertEquals(GatewayReadyDoctor.FIX_USB_BRIDGE, action?.code)
    }

    @Test
    fun repairPhoneControlWinsOverEnable() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = false,
            phoneControlNeedsRepair = true,
        )
        assertEquals(GatewayReadyDoctor.REPAIR_PHONE_CONTROL, action?.code)
        assertEquals("Repair phone control", action?.title)
        assertEquals(
            "Re-enable Cyclone in Accessibility after a force-stop so phone control is ready for your PC.",
            action?.body,
        )
        assertEquals("Open Accessibility settings", action?.actionLabel)
    }

    @Test
    fun enablePhoneControlWhenAccessibilityOff() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = false,
            phoneControlNeedsRepair = false,
        )
        assertEquals(GatewayReadyDoctor.ENABLE_PHONE_CONTROL, action?.code)
        assertEquals("Enable phone control", action?.title)
        assertEquals(
            "Enable Cyclone Accessibility in Android Settings so a trusted PC can observe and control this phone.",
            action?.body,
        )
        assertEquals("Open Accessibility settings", action?.actionLabel)
    }

    @Test
    fun pendingTrustWinsOverWaitForPc() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = true,
            pendingTrust = true,
            trustedPcCount = 1,
            pcSessionKnown = false,
        )
        assertEquals(GatewayReadyDoctor.CONFIRM_AI_TRUST, action?.code)
        assertEquals("Allow this PC", action?.title)
        assertEquals(
            "Confirm the visible Cyclone AI trust request on this phone. Tap Allow this PC to continue.",
            action?.body,
        )
        assertEquals("Allow this PC", action?.actionLabel)
    }

    @Test
    fun pendingTrustWinsOverPairAiTrust() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = true,
            pendingTrust = true,
            trustedPcCount = 0,
            pcSessionKnown = false,
        )
        assertEquals(GatewayReadyDoctor.CONFIRM_AI_TRUST, action?.code)
    }

    @Test
    fun pairAiTrustWhenNoTrustedPcAndNoSession() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = true,
            pendingTrust = false,
            trustedPcCount = 0,
            pcSessionKnown = false,
        )
        assertEquals(GatewayReadyDoctor.PAIR_AI_TRUST, action?.code)
        assertEquals("Connect USB and allow this PC", action?.title)
        assertEquals(
            "Plug in USB and open Cyclone One on the PC. Cyclone One will request AI trust — confirm Allow this PC on this phone.",
            action?.body,
        )
        assertEquals("Connect USB and allow this PC", action?.actionLabel)
    }

    @Test
    fun waitForPcWhenTrustedButNoSession() {
        val action = nextAction(
            gatewayEnabled = true,
            socketListening = true,
            phoneControlReady = true,
            pendingTrust = false,
            trustedPcCount = 1,
            pcSessionKnown = false,
        )
        assertEquals(GatewayReadyDoctor.WAIT_FOR_PC, action?.code)
        assertEquals("Start Cyclone One on the PC", action?.title)
        assertEquals(
            "Plug in USB, allow debugging if asked, and open Cyclone One on your PC.",
            action?.body,
        )
        assertEquals("Start Cyclone One on the PC", action?.actionLabel)
    }

    @Test
    fun everyPriorityCodeIsCovered() {
        val codes = listOf(
            nextAction(gatewayEnabled = false)?.code,
            nextAction(gatewayEnabled = true, socketListening = false)?.code,
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                phoneControlReady = false,
                phoneControlNeedsRepair = true,
            )?.code,
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                phoneControlReady = false,
            )?.code,
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                phoneControlReady = true,
                pendingTrust = true,
            )?.code,
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                phoneControlReady = true,
                trustedPcCount = 0,
                pcSessionKnown = false,
            )?.code,
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                phoneControlReady = true,
                trustedPcCount = 1,
                pcSessionKnown = false,
            )?.code,
        )
        assertEquals(
            listOf(
                GatewayReadyDoctor.TURN_ON_GATEWAY,
                GatewayReadyDoctor.FIX_USB_BRIDGE,
                GatewayReadyDoctor.REPAIR_PHONE_CONTROL,
                GatewayReadyDoctor.ENABLE_PHONE_CONTROL,
                GatewayReadyDoctor.CONFIRM_AI_TRUST,
                GatewayReadyDoctor.PAIR_AI_TRUST,
                GatewayReadyDoctor.WAIT_FOR_PC,
            ),
            codes,
        )
        assertNotNull(codes[0])
    }

    @Test
    fun readyDoesNotRequireZeroTrustedPcs() {
        assertNull(
            nextAction(
                gatewayEnabled = true,
                socketListening = true,
                hasListenerError = false,
                phoneControlReady = true,
                phoneControlNeedsRepair = false,
                pendingTrust = false,
                trustedPcCount = 3,
                pcSessionKnown = true,
            ),
        )
    }

    private fun nextAction(
        gatewayEnabled: Boolean = true,
        socketListening: Boolean = true,
        hasListenerError: Boolean = false,
        phoneControlReady: Boolean = true,
        phoneControlNeedsRepair: Boolean = false,
        pendingTrust: Boolean = false,
        trustedPcCount: Int = 1,
        pcSessionKnown: Boolean = true,
    ): GatewayReadyNextAction? = GatewayReadyDoctor.nextAction(
        gatewayEnabled = gatewayEnabled,
        socketListening = socketListening,
        hasListenerError = hasListenerError,
        phoneControlReady = phoneControlReady,
        phoneControlNeedsRepair = phoneControlNeedsRepair,
        pendingTrust = pendingTrust,
        trustedPcCount = trustedPcCount,
        pcSessionKnown = pcSessionKnown,
    )
}
