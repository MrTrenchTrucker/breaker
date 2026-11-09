package dev.breaker.dictation.updater

/** Pure version order: newer means strictly greater, no reinstall of the same build. */
internal object UpdateOrder {
    /**
     * True when [offeredCode] is strictly greater than [installedCode]. Equal or lower is false.
     */
    fun isNewer(installedCode: Long, offeredCode: Long): Boolean {
        return offeredCode > installedCode
    }
}
