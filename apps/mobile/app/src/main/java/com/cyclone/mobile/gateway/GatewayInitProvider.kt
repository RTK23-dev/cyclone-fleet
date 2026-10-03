package com.cyclone.mobile.gateway

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Process bootstrap for the USB-only localabstract gateway.
 *
 * Desktop V1 keeps the listener alive in a zero-authority pairing mode even when full PC control
 * is disabled. In that state only pair.begin/pair.complete/pair.qr.complete are accepted; every observation,
 * control, clipboard, teaching and debug operation still requires the strong session credential.
 *
 * A ContentProvider runs during process creation, before normal app UI. Any exception escaping
 * onCreate can therefore look like an instant Cyclone crash. Bootstrap is deliberately best-effort:
 * the app must stay usable even if the USB socket cannot start and can retry when the Gateway UI opens.
 */
class GatewayInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return true
        // First, so a freeze during startup is caught too.
        runCatching { com.cyclone.mobile.runtime.health.MainThreadWatchdog.start(app) }
        runCatching { com.cyclone.mobile.ai.OpenRouterCatalogStore.initialize(app) }
        runCatching { com.cyclone.mobile.secrets.VaultGatewayV5Integration.install(app) }
            .onFailure {
                GatewayRuntime.reportSafeError(
                    "VAULT_SOURCE_UNAVAILABLE",
                    "Phone Vault metadata source could not initialize safely.",
                )
            }
        runCatching { GatewayRuntime.startPairingBootstrap(app) }
            .onFailure {
                GatewayRuntime.reportSafeError(
                    "USB pairing bootstrap could not start safely. Reopen Cyclone or reconnect USB.",
                )
            }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
