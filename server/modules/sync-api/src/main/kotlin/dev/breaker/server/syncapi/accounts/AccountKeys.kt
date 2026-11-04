package dev.breaker.server.syncapi.accounts

/** The byte sizes of the stored key columns. They match the CHECK constraints of the accounts table. */
internal object KeySizes {
    const val WRAPPED_DEK: Int = 60
    const val WRAPPED_BOX_PRIVKEY: Int = 60
    const val BOX_PUBKEY: Int = 32
}

/**
 * The three key values an account stores once. The constructor refuses a wrong size, and the
 * message names the field and the expected size only, never a value.
 */
internal class KeyBundle(wrappedDek: ByteArray, wrappedBoxPrivkey: ByteArray, boxPubkey: ByteArray) {
    init {
        require(wrappedDek.size == KeySizes.WRAPPED_DEK) { "wrapped_dek must be ${KeySizes.WRAPPED_DEK} bytes" }
        require(wrappedBoxPrivkey.size == KeySizes.WRAPPED_BOX_PRIVKEY) {
            "wrapped_box_privkey must be ${KeySizes.WRAPPED_BOX_PRIVKEY} bytes"
        }
        require(boxPubkey.size == KeySizes.BOX_PUBKEY) { "box_pubkey must be ${KeySizes.BOX_PUBKEY} bytes" }
    }

    private val dekBytes: ByteArray = wrappedDek.copyOf()
    private val privkeyBytes: ByteArray = wrappedBoxPrivkey.copyOf()
    private val pubkeyBytes: ByteArray = boxPubkey.copyOf()

    // A copy in and a copy out, as in SaltLookup.Found: callers cannot change what the bundle holds.
    val wrappedDek: ByteArray
        get() = dekBytes.copyOf()

    val wrappedBoxPrivkey: ByteArray
        get() = privkeyBytes.copyOf()

    val boxPubkey: ByteArray
        get() = pubkeyBytes.copyOf()

    // A fixed text, so no key byte can reach a message by string conversion.
    override fun toString(): String = "KeyBundle"
}

/** What the accounts table holds for the key columns of one account. A null byte value means keyless. */
internal class StoredKeys(
    wrappedDek: ByteArray?,
    wrappedBoxPrivkey: ByteArray?,
    boxPubkey: ByteArray?,
    val keyVersion: Int,
    prevWrappedDek: ByteArray?,
    val rekeyedAtMs: Long?,
    val resetAtMs: Long?,
) {
    private val dekBytes: ByteArray? = wrappedDek?.copyOf()
    private val privkeyBytes: ByteArray? = wrappedBoxPrivkey?.copyOf()
    private val pubkeyBytes: ByteArray? = boxPubkey?.copyOf()
    private val prevDekBytes: ByteArray? = prevWrappedDek?.copyOf()

    val wrappedDek: ByteArray?
        get() = dekBytes?.copyOf()

    val wrappedBoxPrivkey: ByteArray?
        get() = privkeyBytes?.copyOf()

    val boxPubkey: ByteArray?
        get() = pubkeyBytes?.copyOf()

    val prevWrappedDek: ByteArray?
        get() = prevDekBytes?.copyOf()

    override fun toString(): String = "StoredKeys(keyVersion=$keyVersion)"
}

internal sealed class StoreKeysResult {
    /** The keys were written; [keyVersion] is the account's key version after the write. */
    class Stored(val keyVersion: Int) : StoreKeysResult()

    object AlreadyKeyed : StoreKeysResult()

    object NoSuchAccount : StoreKeysResult()

    /** The account is waiting for a reset to be completed, so no key is stored. */
    object ResetPending : StoreKeysResult()
}
