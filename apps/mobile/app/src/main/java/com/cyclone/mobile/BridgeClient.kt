package com.cyclone.mobile

import android.content.Context

/**
 * Retired Core/Hermes transport. Kept only while old stored routines and UI source are migrated.
 * Saved Core URLs/tokens are never read and no socket, notification forwarding or command receiver
 * is started. The optional PC companion uses gateway/GatewayRuntime and its pairing policy.
 */
@Suppress("UNUSED_PARAMETER")
object BridgeClient {
    fun start(context: Context) { DeviceState.bridgeConnected = false }
    fun stop() { DeviceState.bridgeConnected = false }
    fun sendNotificationEvent(packageName: String, title: String, text: String, key: String? = null) = Unit
    fun sendAutomationEvent(type: String, payload: Map<String, String>): Boolean = false
}
