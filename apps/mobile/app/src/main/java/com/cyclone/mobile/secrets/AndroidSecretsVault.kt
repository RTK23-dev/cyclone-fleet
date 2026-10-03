package com.cyclone.mobile.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val VAULT_PREFS = "cyclone_vault_secrets_v1"
private const val INDEX_KEY = "slot_index"
private const val RECORD_PREFIX = "slot:"
private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "cyclone.vault.secrets.aes.v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

internal class AndroidSecretRecordStore(context: Context) : SecretRecordStore {
    private val prefs = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)

    override fun load(key: SecretSlotKey): SecretRecord? {
        val encoded = prefs.getString(RECORD_PREFIX + key.storageId(), null) ?: return null
        return decode(encoded)?.takeIf { it.metadata.key == key }
    }

    override fun save(record: SecretRecord) {
        val id = record.metadata.key.storageId()
        val index = prefs.getStringSet(INDEX_KEY, emptySet()).orEmpty().toMutableSet().apply { add(id) }
        check(
            prefs.edit()
                .putString(RECORD_PREFIX + id, encode(record))
                .putStringSet(INDEX_KEY, index)
                .commit(),
        ) { "Could not persist encrypted vault record" }
    }

    override fun list(placeId: String, persona: SecretPersona): List<SecretRecord> =
        listAll().filter { it.metadata.key.placeId == placeId && it.metadata.key.persona == persona }

    override fun listAll(): List<SecretRecord> =
        prefs.getStringSet(INDEX_KEY, emptySet()).orEmpty()
            .mapNotNull { id -> prefs.getString(RECORD_PREFIX + id, null)?.let(::decode) }

    private fun encode(record: SecretRecord): String {
        val metadata = record.metadata
        return JSONObject()
            .put("placeId", metadata.key.placeId)
            .put("persona", metadata.key.persona.wireValue)
            .put("slot", metadata.key.slotName)
            .put("present", metadata.present)
            .put("createdAtMs", metadata.createdAtMs)
            .put("updatedAtMs", metadata.updatedAtMs)
            .apply {
                record.sealed?.let {
                    put("iv", Base64.encodeToString(it.iv, Base64.NO_WRAP))
                    put("ciphertext", Base64.encodeToString(it.ciphertext, Base64.NO_WRAP))
                }
            }
            .toString()
    }

    private fun decode(encoded: String): SecretRecord? = runCatching {
        val json = JSONObject(encoded)
        val key = SecretSlotKey.of(
            json.getString("placeId"),
            SecretPersona.parse(json.getString("persona")),
            json.getString("slot"),
        )
        val present = json.optBoolean("present", false)
        val sealed = if (present) {
            SealedSecret(
                iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP),
                ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP),
            )
        } else null
        SecretRecord(
            SecretSlotMetadata(
                key = key,
                present = present && sealed != null,
                createdAtMs = json.getLong("createdAtMs"),
                updatedAtMs = json.getLong("updatedAtMs"),
            ),
            sealed = sealed,
        )
    }.getOrNull()
}

internal class AndroidVaultKeyProvider {
    @Volatile private var cached: SecretKey? = null

    fun getOrCreate(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
                cached = it
                return it
            }
            val selected = StrongBoxKeySelector(
                strongBox = {
                    try {
                        generate(strongBox = true)
                    } catch (error: StrongBoxUnavailableException) {
                        throw VaultStrongBoxUnavailable(error)
                    }
                },
                fallback = { generate(strongBox = false) },
            ).select()
            cached = selected.key
            return selected.key
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        generator.init(builder.build())
        return generator.generateKey()
    }
}

internal class AndroidKeystoreSecretSealer(
    private val keyProvider: AndroidVaultKeyProvider = AndroidVaultKeyProvider(),
) : SecretSealer {
    override fun seal(key: SecretSlotKey, secret: CharArray): SealedSecret {
        val plain = encodeUtf8(secret)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
            cipher.updateAAD(key.canonicalMetadata().toByteArray(Charsets.UTF_8))
            SealedSecret(cipher.iv.copyOf(), cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    override fun open(key: SecretSlotKey, sealed: SealedSecret): CharArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.getOrCreate(),
            GCMParameterSpec(128, sealed.iv),
        )
        cipher.updateAAD(key.canonicalMetadata().toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(sealed.ciphertext)
        return try {
            decodeUtf8(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        return CharArray(buffer.remaining()).also(buffer::get)
    }
}

object SecretsVaultRuntime {
    @Volatile private var instance: SecretsVault? = null

    fun slotCatalog(context: Context): SecretSlotCatalog = vault(context)

    fun allSlots(context: Context): List<SecretSlotMetadata> = vault(context).allSlots()

    fun delete(context: Context, key: SecretSlotKey): Boolean = vault(context).delete(key)

    internal fun vault(context: Context): SecretsVault {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return SecretsVault(
                store = AndroidSecretRecordStore(context.applicationContext),
                sealer = AndroidKeystoreSecretSealer(),
                fillExecutor = PhoneToolSecretFillExecutor(context.applicationContext),
            ).also { instance = it }
        }
    }

    internal fun resetForTests() {
        instance = null
    }
}
