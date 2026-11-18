package dev.breaker.dictation.updater

/**
 * The reason sentences of the held rollback. They are fixed: none holds an address, a version
 * or a digit, so a caller can show one as it is.
 */
internal object RollbackReasons {
    const val NONE_HELD: String = "no earlier version is on offer"
    const val NOT_ALLOWED: String = "only an admin can roll back"
}
