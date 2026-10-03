package com.cyclone.mobile.gateway

/** One doctor-style next action when PC Gateway is not Ready for PC control. */
data class GatewayReadyNextAction(
    val code: String,
    val title: String,
    val body: String,
    val actionLabel: String,
)

/**
 * Pure priority helper for in-app Gateway + AI-trust readiness.
 * Transport-on is not Ready. First unmet doctor row wins.
 */
object GatewayReadyDoctor {
    const val TURN_ON_GATEWAY = "TURN_ON_GATEWAY"
    const val FIX_USB_BRIDGE = "FIX_USB_BRIDGE"
    const val REPAIR_PHONE_CONTROL = "REPAIR_PHONE_CONTROL"
    const val ENABLE_PHONE_CONTROL = "ENABLE_PHONE_CONTROL"
    const val CONFIRM_AI_TRUST = "CONFIRM_AI_TRUST"
    const val PAIR_AI_TRUST = "PAIR_AI_TRUST"
    const val WAIT_FOR_PC = "WAIT_FOR_PC"

    fun nextAction(
        gatewayEnabled: Boolean,
        socketListening: Boolean,
        hasListenerError: Boolean,
        phoneControlReady: Boolean,
        phoneControlNeedsRepair: Boolean,
        pendingTrust: Boolean,
        trustedPcCount: Int,
        pcSessionKnown: Boolean,
    ): GatewayReadyNextAction? = when {
        !gatewayEnabled -> action(
            code = TURN_ON_GATEWAY,
            title = "Turn on PC Gateway",
            body = "Enable Full PC + Codex Gateway in Cyclone AI so this phone can accept a trusted PC over USB.",
            actionLabel = "Turn on PC Gateway",
        )
        hasListenerError || (gatewayEnabled && !socketListening) -> action(
            code = FIX_USB_BRIDGE,
            title = "Fix the USB bridge",
            body = "Start USB debugging on this phone and open Cyclone One on your PC. The USB bridge is not ready for PC control.",
            actionLabel = "Fix the USB bridge",
        )
        !phoneControlReady && phoneControlNeedsRepair -> action(
            code = REPAIR_PHONE_CONTROL,
            title = "Repair phone control",
            body = "Re-enable Cyclone in Accessibility after a force-stop so phone control is ready for your PC.",
            actionLabel = "Open Accessibility settings",
        )
        !phoneControlReady -> action(
            code = ENABLE_PHONE_CONTROL,
            title = "Enable phone control",
            body = "Enable Cyclone Accessibility in Android Settings so a trusted PC can observe and control this phone.",
            actionLabel = "Open Accessibility settings",
        )
        pendingTrust -> action(
            code = CONFIRM_AI_TRUST,
            title = "Allow this PC",
            body = "Confirm the visible Cyclone AI trust request on this phone. Tap Allow this PC to continue.",
            actionLabel = "Allow this PC",
        )
        trustedPcCount == 0 && !pcSessionKnown -> action(
            code = PAIR_AI_TRUST,
            title = "Connect USB and allow this PC",
            body = "Plug in USB and open Cyclone One on the PC. Cyclone One will request AI trust — confirm Allow this PC on this phone.",
            actionLabel = "Connect USB and allow this PC",
        )
        !pcSessionKnown -> action(
            code = WAIT_FOR_PC,
            title = "Start Cyclone One on the PC",
            body = "Plug in USB, allow debugging if asked, and open Cyclone One on your PC.",
            actionLabel = "Start Cyclone One on the PC",
        )
        else -> null
    }

    private fun action(
        code: String,
        title: String,
        body: String,
        actionLabel: String,
    ) = GatewayReadyNextAction(
        code = code,
        title = title,
        body = body,
        actionLabel = actionLabel,
    )
}
