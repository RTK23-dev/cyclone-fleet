package com.cyclone.mobile.runtime.workspaces

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cyclone.mobile.ai.OpenRouterSecretStore
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/** Non-exported, short-lived receiver started by the trusted root profile manager. */
class ProfileBootstrapService : Service() {
    private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repairJob: Job? = null
    private var carryJob: Job? = null
    override fun onDestroy() { repairScope.cancel(); super.onDestroy() }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("cyclone-profile-setup", "Profile setup", android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(this, "cyclone-profile-setup")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Preparing Cyclone profile")
            .setContentText("Transferring your settings securely").setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(903, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(903, notification)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.cyclone.PROFILE_REPAIR") {
            if (repairJob?.isActive != true) repairJob = repairScope.launch {
                try { ProfileBootstrapRuntime.repairFromOwner(this@ProfileBootstrapService, intent.getIntExtra("target", -1)) }
                catch (_: Exception) {
                    runCatching { ProfileBootstrapRuntime.reportRepairFailure(intent.getIntExtra("target", -1)) }
                }
                finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ProfileBootstrapRuntime.CARRY_ACTION) {
            if (carryJob?.isActive != true) carryJob = repairScope.launch {
                try { takeInCarry() } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        val folder = createDeviceProtectedStorageContext().filesDir
        val input = File(folder, "profile-bootstrap.json")
        val result = File(folder, "profile-bootstrap-result.json")
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
                }.generateKeyPair()
            }
            if (input.exists()) {
                val transfer = JSONObject(input.readText())
                ProfileBootstrapContract.validateTransfer(transfer.getInt("source"), transfer.getInt("target"), ProfileSetupRuntime.currentUserId())
                val ai = transfer.getJSONObject("ai")
                val edit = getSharedPreferences("cyclone_ai", MODE_PRIVATE).edit()
                ProfileBootstrapContract.aiKeys.forEach(edit::remove)
                ProfileBootstrapContract.aiKeys.forEach { key ->
                    if (ai.has(key)) when (val value = ai.get(key)) {
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                    }
                }
                check(edit.commit())
                check(getSharedPreferences("cyclone_profile_registry", MODE_PRIVATE).edit()
                    .putString("profiles", transfer.getString("profiles")).commit())
                if (transfer.has("key")) {
                    val bytes = ProfileTransferCipher.decrypt(store.getKey(ALIAS, null) as java.security.PrivateKey, transfer.getString("key"))
                    try { OpenRouterSecretStore.save(this, String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                    check(OpenRouterSecretStore.hasKey(this))
                } else OpenRouterSecretStore.clear(this)
                val permissions = transfer.getJSONArray("permissions")
                for (index in 0 until permissions.length()) {
                    val permission = permissions.getString(index)
                    check(permission in ProfileBootstrapContract.permissions || permission == "moe.shizuku.manager.permission.API_V23")
                    check(checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
                if (transfer.optBoolean("overlay")) check(android.provider.Settings.canDrawOverlays(this))
                if (transfer.optBoolean("accessibility")) check(android.provider.Settings.Secure.getString(contentResolver,
                    "enabled_accessibility_services").orEmpty().split(':').contains(ProfileBootstrapContract.ACCESSIBILITY))
                if (transfer.optBoolean("listener")) {
                    val components = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().split(':')
                    check(components.any { it == ProfileBootstrapContract.LISTENER || it == "com.cyclone.mobile/com.cyclone.mobile.CycloneNotificationListener" })
                }
                check(createDeviceProtectedStorageContext().getSharedPreferences("cyclone_profile_origin", MODE_PRIVATE).edit()
                    .putInt("source", transfer.getInt("source")).putString("profile", transfer.getString("profile")).commit())
                result.writeText(JSONObject().put("nonce", transfer.getString("nonce")).put("user", ProfileSetupRuntime.currentUserId()).put("ok", true).toString())
            }
            input.delete()
            File(folder, "profile-bootstrap-public.txt").writeText(Base64.encodeToString(store.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP))
        } catch (_: Exception) {
            input.delete()
            // Never log preference contents, encrypted payloads, or credential failures with values.
            result.writeText("{\"ok\":false}")
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    /**
     * Plan 40 P2: takes in what another profile's Cyclone carried here. The bundle opens only with this profile's
     * Keystore key and only for this switch's nonce; memory is sealed again with this profile's own memory key.
     * The answer holds counts, never content.
     */
    private fun takeInCarry() {
        val folder = createDeviceProtectedStorageContext().filesDir
        val input = File(folder, "carry-inbox.json")
        val result = File(folder, "carry-result.json")
        var nonce = ""
        try {
            val envelope = JSONObject(input.readText())
            input.delete()
            nonce = envelope.getString("nonce")
            val me = ProfileSetupRuntime.currentUserId()
            check(envelope.getInt("target") == me && envelope.getInt("source") != me) { "Carry identity mismatch" }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val plain = ProfileTransferCipher.open(store.getKey(ALIAS, null) as java.security.PrivateKey, envelope.getJSONObject("bundle"),
                CarryRules.cipherContext(me, nonce))
            val report = try { ProfileCarry.absorb(this, JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
            result.writeText(JSONObject().put("nonce", nonce).put("user", me).put("ok", true).put("report", report.toJson()).toString())
        } catch (_: Exception) {
            input.delete()
            // Never log the bundle or what failed inside it.
            result.writeText(JSONObject().put("nonce", nonce).put("user", ProfileSetupRuntime.currentUserId()).put("ok", false).toString())
        }
    }

    companion object {
        private const val ALIAS = "cyclone.profile.bootstrap.v1"
    }
}
