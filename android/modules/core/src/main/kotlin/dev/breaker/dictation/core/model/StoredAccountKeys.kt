package dev.breaker.dictation.core.model

/**
 * The account keys as the key store holds them, with the version of the key set they belong to.
 *
 * The keys live only inside this object. The constructor checks the version first, then builds
 * its own copy of both keys, so the arrays you pass in are never held and never changed by this
 * class. [wipe] zeroes the keys this object holds and never touches your arrays. Whoever gets a
 * holder from the store owns it and should wipe it when done.
 *
 * This is a plain class, not a data class, so there is no generated `toString` or `copy` that
 * could print or duplicate the keys, and no value equality: two holders are equal only if they
 * are the same object. To compare the contents, compare the bytes of [keys].
 */
class StoredAccountKeys(keys: AccountKeys, val keyVersion: Int) {
    /** The keys this object holds: its own copy, not the one passed in. */
    val keys: AccountKeys

    init {
        require(keyVersion >= 0) { "A key version cannot be negative" }
        this.keys = AccountKeys(
            DataEncryptionKey(keys.dek.bytes.copyOf()),
            BoxPrivateKey(keys.boxPrivateKey.copyBytes()),
        )
    }

    /** Zero the keys this object holds. The arrays the caller passed in are not touched. */
    fun wipe() = keys.wipe()

    /** Never prints the keys or the version. */
    override fun toString(): String = "StoredAccountKeys(redacted)"
}
