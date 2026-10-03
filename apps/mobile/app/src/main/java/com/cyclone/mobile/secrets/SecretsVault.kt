package com.cyclone.mobile.secrets

internal data class SealedSecret(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun toString(): String =
        "SealedSecret(ivBytes=" + iv.size + ", ciphertextBytes=" + ciphertext.size + ")"
}

internal data class SecretRecord(
    val metadata: SecretSlotMetadata,
    val sealed: SealedSecret?,
)

internal interface SecretRecordStore {
    fun load(key: SecretSlotKey): SecretRecord?
    fun save(record: SecretRecord)
    fun list(placeId: String, persona: SecretPersona): List<SecretRecord>
    fun listAll(): List<SecretRecord>
}

internal interface SecretSealer {
    fun seal(key: SecretSlotKey, secret: CharArray): SealedSecret
    fun open(key: SecretSlotKey, sealed: SealedSecret): CharArray
}

internal class VaultStrongBoxUnavailable(cause: Throwable? = null) : RuntimeException(cause)

internal data class SelectedVaultKey<T>(val key: T, val strongBoxBacked: Boolean)

internal class StrongBoxKeySelector<T>(
    private val strongBox: () -> T,
    private val fallback: () -> T,
) {
    fun select(): SelectedVaultKey<T> = try {
        SelectedVaultKey(strongBox(), true)
    } catch (_: VaultStrongBoxUnavailable) {
        SelectedVaultKey(fallback(), false)
    }
}

internal fun interface SecretFillExecutor {
    fun fill(target: SecretFillTarget, secret: CharArray): SecretFillExecution
}

internal class OneShotSecretLease(secret: CharArray) {
    private var value: CharArray? = secret
    private var consuming = false
    private var revoked = false

    @Synchronized
    fun consume(block: (CharArray) -> SecretFillExecution): SecretFillExecution? {
        if (revoked || consuming) return null
        consuming = true
        val current = value ?: return null
        return try {
            block(current)
        } finally {
            current.fill('\u0000')
            value = null
            consuming = false
            revoked = true
        }
    }

    @Synchronized
    fun revoke() {
        value?.fill('\u0000')
        value = null
        consuming = false
        revoked = true
    }

    @Synchronized
    internal fun revokedForTest(): Boolean = revoked
}

class SecretsVault internal constructor(
    private val store: SecretRecordStore,
    private val sealer: SecretSealer,
    private val fillExecutor: SecretFillExecutor,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SecretSlotCatalog {
    private val lock = Any()

    override fun metadata(key: SecretSlotKey): SecretSlotMetadata? =
        synchronized(lock) { store.load(key)?.metadata }

    override fun slots(placeId: String, persona: SecretPersona): List<SecretSlotMetadata> =
        synchronized(lock) {
            store.list(placeId, persona)
                .map { it.metadata }
                .sortedBy { it.key.slotName.lowercase() }
        }

    override fun allSlots(): List<SecretSlotMetadata> = synchronized(lock) {
        store.listAll()
            .map { it.metadata }
            .sortedWith(
                compareBy<SecretSlotMetadata> { it.key.placeId }
                    .thenBy { it.key.persona.wireValue }
                    .thenBy { it.key.slotName.lowercase() },
            )
    }

    fun declare(key: SecretSlotKey): SecretSlotMetadata = synchronized(lock) {
        store.load(key)?.metadata ?: run {
            val now = clockMs()
            SecretSlotMetadata(key, present = false, createdAtMs = now, updatedAtMs = now)
                .also { store.save(SecretRecord(it, null)) }
        }
    }

    /**
     * Consumes [secret]. Plaintext is sealed synchronously and the supplied array is zeroed on
     * every path, including storage failure.
     */
    fun replace(key: SecretSlotKey, secret: CharArray): SecretSlotMetadata {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        require(secret.size <= 4_096) { "secret exceeds bounded length" }
        return try {
            synchronized(lock) {
                val previous = store.load(key)
                val now = clockMs()
                val sealed = sealer.seal(key, secret)
                val metadata = SecretSlotMetadata(
                    key = key,
                    present = true,
                    createdAtMs = previous?.metadata?.createdAtMs ?: now,
                    updatedAtMs = now,
                )
                store.save(SecretRecord(metadata, sealed))
                metadata
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    fun delete(key: SecretSlotKey): Boolean = synchronized(lock) {
        val previous = store.load(key) ?: return@synchronized false
        if (!previous.metadata.present) return@synchronized false
        store.save(
            SecretRecord(
                previous.metadata.copy(present = false, updatedAtMs = clockMs()),
                sealed = null,
            ),
        )
        true
    }

    /**
     * Opens one encrypted slot into a single-use in-memory lease and never returns plaintext.
     * The lease is revoked after success, failure, or an exception.
     */
    fun useOnce(key: SecretSlotKey, target: SecretFillTarget): SecretUseResult {
        val sealed = synchronized(lock) {
            store.load(key)?.takeIf { it.metadata.present }?.sealed
        } ?: return SecretUseResult(SecretUseStatus.MISSING, errorCode = "SLOT_MISSING")

        val opened = try {
            sealer.open(key, sealed)
        } catch (_: Exception) {
            return SecretUseResult(SecretUseStatus.FAILED, errorCode = "VAULT_OPEN_FAILED")
        }
        val lease = OneShotSecretLease(opened)
        return try {
            val execution = lease.consume { fillExecutor.fill(target, it) }
                ?: return SecretUseResult(SecretUseStatus.ALREADY_USED, errorCode = "LEASE_ALREADY_USED")
            if (execution.performed && execution.verified) {
                SecretUseResult(SecretUseStatus.FILLED, verified = true)
            } else {
                SecretUseResult(
                    SecretUseStatus.FAILED,
                    verified = false,
                    errorCode = execution.errorCode ?: "FILL_NOT_VERIFIED",
                )
            }
        } catch (_: Exception) {
            SecretUseResult(SecretUseStatus.FAILED, errorCode = "FILL_EXCEPTION")
        } finally {
            lease.revoke()
        }
    }
}
