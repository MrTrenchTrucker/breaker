package dev.breaker.dictation.updater

/**
 * A pinned manifest public key: the id that a manifest may select, and the key as base64 SPKI DER text.
 *
 * The key text is never printed: [toString] shows the id only.
 */
internal class PinnedKey(val id: String, val spkiBase64: String) {
    override fun toString(): String = "PinnedKey(id=$id)"
}

/** True when [id] is 1 to 64 characters, each one an ASCII letter, a digit, a dot, an underscore or a hyphen. */
internal fun isWellFormedKeyId(id: String): Boolean {
    return id.length in 1..64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
}

/** True when the list holds at least one key, every id is well formed, and no two ids are equal. */
internal fun pinnedKeysAreUsable(keys: List<PinnedKey>): Boolean {
    if (keys.isEmpty()) return false
    if (!keys.all { isWellFormedKeyId(it.id) }) return false
    return keys.map { it.id }.toSet().size == keys.size
}
