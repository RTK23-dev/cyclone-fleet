package com.cyclone.mobile.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenRouter API key encrypted with an Android Keystore AES key. */
object OpenRouterSecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cyclone.openrouter.api_key.v1"
    private const val PREFS = "cyclone_ai_secrets"
    private const val PREF_BLOB = "openrouter_key_blob"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun hasKey(context: Context): Boolean = read(context).isNotBlank()

    fun save(context: Context, apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            clear(context)
            return
        }
        require(clean.startsWith("sk-or-")) { "This does not look like an OpenRouter API key" }
        val previous = read(context)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_BLOB, blob).commit()) { "Could not save encrypted API settings." }
        if (previous != clean) {
            OpenRouterCatalogStore.invalidateAvailability(context)
            ProviderRequests.invalidateAccount(OpenRouterCatalogStore.fingerprint(previous))
            ProviderRequests.activateAccount(OpenRouterCatalogStore.fingerprint(clean))
        }
    }

    fun read(context: Context): String {
        val blob = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_BLOB, null) ?: return ""
        return runCatching {
            val parts = blob.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    fun clear(context: Context) {
        val previous = read(context)
        val hadKey = previous.isNotBlank()
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_BLOB).commit()) { "Could not clear encrypted API settings." }
        if (hadKey) {
            OpenRouterCatalogStore.invalidateAvailability(context)
            ProviderRequests.invalidateAccount(OpenRouterCatalogStore.fingerprint(previous))
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
